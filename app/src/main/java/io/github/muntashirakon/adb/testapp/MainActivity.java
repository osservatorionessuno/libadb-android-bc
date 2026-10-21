// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb.testapp;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.muntashirakon.adb.android.AdbUsb;

public class MainActivity extends AppCompatActivity {
    private static final int DEFAULT_PORT_ADDRESS = 5555;
    private static final String ACTION_USB_PERMISSION = "io.github.muntashirakon.adb.testapp.USB_PERMISSION";

    private MenuItem connectAdbMenu;
    private MenuItem disconnectAdbMenu;
    private MenuItem pairAdbMenu;
    private MenuItem usbAdbMenu;
    private ScrollView scrollView;

    private AppCompatEditText commandInput;
    private AppCompatTextView commandOutput;
    private MainViewModel viewModel;
    private boolean connected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setSupportActionBar(findViewById(R.id.toolbar));
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        scrollView = findViewById(R.id.scrollView);
        commandInput = findViewById(R.id.command_input);
        commandOutput = findViewById(R.id.command_output);
        init();
        handleUsbAttach(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleUsbAttach(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (usbPermissionReceiver != null) {
            unregisterReceiver(usbPermissionReceiver);
            usbPermissionReceiver = null;
        }
    }

    private void init() {
        commandInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && connected) {
                String command = Objects.requireNonNull(commandInput.getText()).toString();
                viewModel.execute(command);
                return true;
            }
            return false;
        });

        // Observers
        viewModel.watchConnectAdb().observe(this, isConnected -> {
            connected = isConnected;
            checkMenus();
            if (isConnected) {
                Toast.makeText(this, getString(R.string.connected_to_adb), Toast.LENGTH_SHORT).show();
                openIme();
            } else {
                Toast.makeText(this, getString(R.string.disconnected_from_adb), Toast.LENGTH_SHORT).show();
            }
        });
        viewModel.watchPairAdb().observe(this, isPaired -> {
            if (isPaired) {
                Toast.makeText(this, getString(R.string.pairing_successful), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.pairing_failed), Toast.LENGTH_SHORT).show();
            }
        });
        viewModel.watchAskPairAdb().observe(this, displayDialog -> {
            if (displayDialog) {
                pairAdb();
            }
        });
        viewModel.watchCommandOutput().observe(this, output -> {
            commandOutput.setText(output == null ? "" : output);
            commandOutput.post(() -> scrollView.scrollTo(0, commandOutput.getHeight()));
        });

        // Try auto-connecting
        viewModel.autoConnect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        openIme();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (connected && commandInput != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(commandInput.getWindowToken(), 0);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.actions_main, menu);
        connectAdbMenu = menu.findItem(R.id.action_connect);
        disconnectAdbMenu = menu.findItem(R.id.action_disconnect);
        pairAdbMenu = menu.findItem(R.id.action_pair);
        usbAdbMenu = menu.findItem(R.id.action_usb);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (pairAdbMenu != null) {
            pairAdbMenu.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
            pairAdbMenu.setVisible(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        }
        checkMenus();
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_connect) {
            connectAdb();
            return true;
        }
        if (id == R.id.action_disconnect) {
            connectAdb();
            return true;
        }
        if (id == R.id.action_pair) {
            pairAdb();
            return true;
        }
        if (id == R.id.action_usb) {
            connectUsb();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openIme() {
        if (connected && commandInput != null && !commandInput.isFocused()) {
            commandInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(commandInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void checkMenus() {
        if (connectAdbMenu != null) {
            connectAdbMenu.setEnabled(!connected);
            connectAdbMenu.setVisible(!connected);
        }
        if (disconnectAdbMenu != null) {
            disconnectAdbMenu.setEnabled(connected);
            disconnectAdbMenu.setVisible(connected);
        }
        if (usbAdbMenu != null) {
            boolean usbHost = getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);
            usbAdbMenu.setEnabled(!connected && usbHost);
            usbAdbMenu.setVisible(!connected && usbHost);
        }
    }

    // --- ADB over USB (OTG) ---

    @Nullable
    private BroadcastReceiver usbPermissionReceiver;

    /** Launched (or re-delivered) by the system's "open with" prompt on attach: permission is already granted. */
    private void handleUsbAttach(@Nullable Intent intent) {
        if (intent == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            return;
        }
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null && !connected) {
            onUsbDeviceChosen(device);
        }
    }

    private void connectUsb() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            Toast.makeText(this, R.string.usb_no_host, Toast.LENGTH_LONG).show();
            return;
        }
        List<UsbDevice> adbDevices = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (AdbUsb.isAdbDevice(device)) {
                adbDevices.add(device);
            }
        }
        if (adbDevices.isEmpty()) {
            Toast.makeText(this, R.string.usb_no_adb_device, Toast.LENGTH_LONG).show();
            return;
        }
        if (adbDevices.size() == 1) {
            onUsbDeviceChosen(adbDevices.get(0));
            return;
        }
        String[] labels = new String[adbDevices.size()];
        for (int i = 0; i < labels.length; ++i) {
            UsbDevice d = adbDevices.get(i);
            labels[i] = d.getProductName() + " (" + d.getDeviceName() + ")";
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.usb_pick_device)
                .setItems(labels, (dialog, which) -> onUsbDeviceChosen(adbDevices.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onUsbDeviceChosen(@NonNull UsbDevice device) {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager.hasPermission(device)) {
            viewModel.connectUsb(device);
            return;
        }
        if (usbPermissionReceiver == null) {
            usbPermissionReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                        return;
                    }
                    UsbDevice granted = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && granted != null) {
                        viewModel.connectUsb(granted);
                    } else {
                        Toast.makeText(MainActivity.this, R.string.usb_permission_denied, Toast.LENGTH_LONG).show();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(usbPermissionReceiver, filter);
            }
        }
        // Explicit (package-targeted) and mutable: the system fills in the device and grant result extras.
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, flags | PendingIntent.FLAG_UPDATE_CURRENT);
        usbManager.requestPermission(device, pi);
    }

    private void connectAdb() {
        if (connected) {
            viewModel.disconnect();
            return;
        }
        AppCompatEditText editText = new AppCompatEditText(this);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.setText(String.valueOf(DEFAULT_PORT_ADDRESS));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.connect_adb)
                .setView(editText)
                .setPositiveButton(R.string.connect_adb, (dialog, which) -> {
                    CharSequence portString = editText.getText();
                    if (portString != null && TextUtils.isDigitsOnly(portString)) {
                        int port = Integer.parseInt(portString.toString());
                        viewModel.connect(port);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pairAdb() {
        View view = getLayoutInflater().inflate(R.layout.dialog_input, null);
        TextInputEditText pairingCodeEditText = view.findViewById(R.id.pairing_code);
        TextInputEditText portNumberEditText = view.findViewById(R.id.port_number);
        viewModel.watchPairingPort().observe(this, port -> {
            if (port != -1) {
                portNumberEditText.setText(String.valueOf(port));
            } else {
                portNumberEditText.setText(null);
            }
        });
        viewModel.getPairingPort();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pair_adb)
                .setView(view)
                .setPositiveButton(R.string.pair_adb, (dialog, which) -> {
                    CharSequence pairingCode = pairingCodeEditText.getText();
                    CharSequence portNumberString = portNumberEditText.getText();
                    if (pairingCode != null && pairingCode.length() == 6 && portNumberString != null
                            && TextUtils.isDigitsOnly(portNumberString)) {
                        int port = Integer.parseInt(portNumberString.toString());
                        viewModel.pair(port, pairingCode.toString());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(dialog -> viewModel.watchPairingPort().removeObservers(this))
                .show();
    }
}
