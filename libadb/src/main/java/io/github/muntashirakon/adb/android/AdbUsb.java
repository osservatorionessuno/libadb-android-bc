// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

import io.github.muntashirakon.adb.AdbChannel;
import io.github.muntashirakon.adb.UsbBulkIo;
import io.github.muntashirakon.adb.UsbChannel;

/**
 * ADB over USB in host (OTG) mode: this device is the USB host and the device to talk to is attached over an
 * OTG cable with USB debugging enabled. The caller is responsible for USB permission
 * ({@link UsbManager#requestPermission}) before opening a channel, and for the target's "Allow USB debugging?"
 * confirmation, which shows up during the connect (legacy RSA) handshake.
 */
public final class AdbUsb {
    /**
     * The ADB interface: vendor-specific class, subclass 0x42, protocol 1.
     */
    public static final int ADB_INTERFACE_CLASS = 0xFF;
    public static final int ADB_INTERFACE_SUBCLASS = 0x42;
    public static final int ADB_INTERFACE_PROTOCOL = 1;

    private AdbUsb() {
    }

    /**
     * Find the ADB interface of a USB device, if it exposes one.
     */
    @Nullable
    public static UsbInterface findAdbInterface(@NonNull UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); ++i) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == ADB_INTERFACE_CLASS
                    && usbInterface.getInterfaceSubclass() == ADB_INTERFACE_SUBCLASS
                    && usbInterface.getInterfaceProtocol() == ADB_INTERFACE_PROTOCOL) {
                return usbInterface;
            }
        }
        return null;
    }

    /**
     * Whether a USB device exposes an ADB interface (i.e. it is an Android device with USB debugging enabled).
     */
    public static boolean isAdbDevice(@NonNull UsbDevice device) {
        return findAdbInterface(device) != null;
    }

    /**
     * Open an {@link AdbChannel} to the ADB interface of an attached USB device. USB permission for the device
     * must already be granted.
     *
     * @throws IOException If the device cannot be opened (missing permission or detached), has no ADB interface,
     *                     or its interface cannot be claimed.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    @NonNull
    public static AdbChannel openChannel(@NonNull Context context, @NonNull UsbDevice device) throws IOException {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            throw new IOException("USB host service is unavailable");
        }
        UsbInterface usbInterface = findAdbInterface(device);
        if (usbInterface == null) {
            throw new IOException("Device has no ADB interface. Is USB debugging enabled on it?");
        }
        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < usbInterface.getEndpointCount(); ++i) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(i);
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                continue;
            }
            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                in = endpoint;
            } else {
                out = endpoint;
            }
        }
        if (in == null || out == null) {
            throw new IOException("ADB interface lacks bulk endpoints");
        }
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            throw new IOException("Could not open USB device. Has USB permission been granted?");
        }
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close();
            throw new IOException("Could not claim the ADB interface");
        }
        // Discard anything already queued on the IN endpoint before the handshake. A cleanly reset
        // interface has nothing pending (this returns immediately), but if a previous session was torn
        // down mid-transfer the daemon may still have bytes buffered; reading them now keeps the first
        // header read from starting mid-stream. Best-effort and time-bounded.
        drainStaleInput(connection, in);
        return new UsbChannel(new DeviceConnectionBulkIo(context, device, connection, usbInterface, in, out));
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private static void drainStaleInput(@NonNull UsbDeviceConnection connection, @NonNull UsbEndpoint in) {
        // Packet-aligned buffer and a short timeout, so a full IN packet can never overflow the request.
        byte[] scratch = new byte[16 * 1024];
        long deadline = System.currentTimeMillis() + 250;
        while (System.currentTimeMillis() < deadline) {
            int n = connection.bulkTransfer(in, scratch, scratch.length, 50);
            if (n <= 0) {
                return;   // timed out (pipe empty) or errored: nothing more to drain
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private static final class DeviceConnectionBulkIo implements UsbBulkIo {
        // One URB per ADB payload from API 28, where usbfs no longer caps a request at 16 KiB.
        private static final int MAX_BULK_SIZE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? 1 << 20 : 16 * 1024;
        // One wait on a queued request; it stays queued across timeouts, this only re-checks close().
        private static final long WAIT_SLICE_MS = 1_000L;
        // The daemon drains a write at once; one it does not accept within this is a dead link.
        private static final long WRITE_TIMEOUT_MS = 10_000L;

        @NonNull
        private final Context mContext;
        @NonNull
        private final UsbDeviceConnection mConnection;
        @NonNull
        private final UsbInterface mInterface;
        // Queued requests on direct buffers: nothing on the Java heap is pinned while a transfer
        // waits, so a quiet daemon never blocks garbage collection (bulkTransfer pins its array
        // in a JNI critical section for the whole transfer on Android 13/14).
        @NonNull
        private final UsbRequest mInRequest = new UsbRequest();
        @NonNull
        private final UsbRequest mOutRequest = new UsbRequest();
        @NonNull
        private final ByteBuffer mInBuffer = ByteBuffer.allocateDirect(MAX_BULK_SIZE);
        @NonNull
        private final ByteBuffer mOutBuffer = ByteBuffer.allocateDirect(MAX_BULK_SIZE);
        // requestWait() hands back whichever request finished, so the reader may reap the
        // writer's completion and vice versa; they pass them to each other here.
        private final Object mReaped = new Object();
        private boolean mInDone;
        private boolean mOutDone;
        private boolean mReaping;
        private boolean mFailed;
        private volatile boolean mClosed = false;
        @Nullable
        private BroadcastReceiver mDetachReceiver;

        private DeviceConnectionBulkIo(@NonNull Context context, @NonNull UsbDevice device,
                                       @NonNull UsbDeviceConnection connection, @NonNull UsbInterface usbInterface,
                                       @NonNull UsbEndpoint in, @NonNull UsbEndpoint out) throws IOException {
            mContext = context.getApplicationContext();
            mConnection = connection;
            mInterface = usbInterface;
            if (!mInRequest.initialize(connection, in) || !mOutRequest.initialize(connection, out)) {
                throw new IOException("Could not initialize the USB requests");
            }
            // A pulled cable fails the next transfer anyway; this fails it now.
            final String name = device.getDeviceName();
            mDetachReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    UsbDevice gone = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (gone != null && name.equals(gone.getDeviceName())) {
                        close();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mContext.registerReceiver(mDetachReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                mContext.registerReceiver(mDetachReceiver, filter);
            }
        }

        @Override
        public int bulkRead(@NonNull byte[] buffer, int offset, int length) throws IOException {
            int len = Math.min(length, MAX_BULK_SIZE);
            while (true) {
                mInBuffer.clear();
                mInBuffer.limit(len);
                if (!mInRequest.queue(mInBuffer)) {
                    throw new IOException(mClosed ? "USB channel closed" : "USB bulk read failed to queue");
                }
                await(mInRequest, 0);
                int n = mInBuffer.position();
                if (n > 0) {
                    mInBuffer.flip();
                    mInBuffer.get(buffer, offset, n);
                    return n;
                }
                // A zero-length packet terminates a transfer; read on.
            }
        }

        @Override
        public void bulkWrite(@NonNull byte[] buffer, int offset, int length) throws IOException {
            // Callers hand over at most getMaxBulkSize() bytes (UsbChannel.writeChunked).
            mOutBuffer.clear();
            mOutBuffer.put(buffer, offset, length);
            mOutBuffer.flip();
            if (!mOutRequest.queue(mOutBuffer)) {
                throw new IOException(mClosed ? "USB channel closed" : "USB bulk write failed to queue");
            }
            await(mOutRequest, System.currentTimeMillis() + WRITE_TIMEOUT_MS);
            int n = mOutBuffer.position();
            if (n != length) {
                throw new IOException(mClosed ? "USB channel closed" : "USB bulk write failed (" + n + "/" + length + ")");
            }
        }

        /**
         * Block until [request] has completed. One thread at a time reaps completions for both
         * requests (requestWait hands back whichever finished); the other waits on the monitor and
         * is woken the moment its request is reaped, instead of noticing at the end of its own
         * wait slice. [deadline] 0 waits forever.
         */
        private void await(@NonNull UsbRequest request, long deadline) throws IOException {
            boolean in = request == mInRequest;
            while (true) {
                synchronized (mReaped) {
                    if (in ? mInDone : mOutDone) {
                        if (in) mInDone = false; else mOutDone = false;
                        return;
                    }
                    if (mClosed) throw new IOException("USB channel closed");
                    if (mFailed) throw new IOException("USB transfer failed");
                    if (deadline != 0 && System.currentTimeMillis() >= deadline) {
                        request.cancel();
                        throw new IOException("USB bulk write timed out after " + WRITE_TIMEOUT_MS + " ms");
                    }
                    if (mReaping) {
                        long wait = deadline == 0 ? WAIT_SLICE_MS : Math.max(1, Math.min(WAIT_SLICE_MS, deadline - System.currentTimeMillis()));
                        try {
                            mReaped.wait(wait);
                        } catch (InterruptedException e) {
                            throw new InterruptedIOException("interrupted waiting for a USB transfer");
                        }
                        continue;
                    }
                    mReaping = true;
                }
                UsbRequest done = null;
                boolean timedOut = false;
                try {
                    done = mConnection.requestWait(WAIT_SLICE_MS);
                } catch (TimeoutException e) {
                    timedOut = true;
                } finally {
                    synchronized (mReaped) {
                        mReaping = false;
                        if (done == mInRequest) mInDone = true;
                        else if (done == mOutRequest) mOutDone = true;
                        else if (done == null && !timedOut && !mClosed) mFailed = true;
                        mReaped.notifyAll();
                    }
                }
            }
        }

        @Override
        public int getMaxBulkSize() {
            return MAX_BULK_SIZE;
        }

        @Override
        public boolean isConnected() {
            return !mClosed;
        }

        @Override
        public void close() {
            if (mClosed) return;
            // Cancelling the requests and closing the connection unblock a waiting read or write.
            mClosed = true;
            synchronized (mReaped) {
                mReaped.notifyAll();
            }
            BroadcastReceiver receiver = mDetachReceiver;
            mDetachReceiver = null;
            if (receiver != null) {
                try {
                    mContext.unregisterReceiver(receiver);
                } catch (IllegalArgumentException ignored) {
                }
            }
            mInRequest.cancel();
            mOutRequest.cancel();
            try {
                mConnection.releaseInterface(mInterface);
            } finally {
                mConnection.close();
                mInRequest.close();
                mOutRequest.close();
            }
        }
    }
}
