// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import io.github.muntashirakon.adb.desktop.LibUsb;
import io.github.muntashirakon.adb.desktop.LibUsbBulkIo;

/**
 * Runs the USB transport on the host JVM against a real phone attached over USB. This is how the
 * Android-targeted library is exercised on a non-Android platform: the AGP already runs {@code src/test}
 * on the desktop JVM (with the stub android.jar, {@code returnDefaultValues=true}), and libusb — reached
 * through JNA — fills the {@link UsbBulkIo} seam that {@code UsbDeviceConnection} fills on-device.
 * <p>
 * The test is <b>self-skipping</b>: if libusb is unavailable or no ADB device is attached (the CI case),
 * it is assumed away rather than failed. To run it for real:
 * <pre>
 *   brew install libusb                 # provides the native libusb
 *   adb kill-server                     # release the interface so libusb can claim it
 *   ./gradlew :libadb:testReleaseUnitTest --tests '*UsbDeviceIntegrationTest'
 * </pre>
 * On first connect, approve "Allow USB debugging?" on the phone; the key is persisted so later runs
 * don't re-prompt. Override the libusb location with {@code -Djna.library.path=...} if not on Homebrew.
 */
public class UsbDeviceIntegrationTest {
    private static final int ADB_CLASS = 0xFF;
    private static final int ADB_SUBCLASS = 0x42;
    private static final int ADB_PROTOCOL = 1;
    private static final File KEY_DIR = new File(System.getProperty("user.home"), ".libadb-usb-desktop");

    @BeforeClass
    public static void pointJnaAtLibusb() {
        if (System.getProperty("jna.library.path") == null) {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac")) {
                System.setProperty("jna.library.path", "/opt/homebrew/lib:/usr/local/lib");
            }
        }
    }

    @Test(timeout = 90_000)
    public void connectsAndReadsPropsOverUsb() throws Exception {
        LibUsb lib;
        try {
            lib = LibUsb.INSTANCE;   // triggers Native.load
        } catch (Throwable t) {
            Assume.assumeNoException("libusb not available on this host; skipping USB device test", t);
            return;
        }

        int r = lib.libusb_init(null);
        Assume.assumeTrue("libusb_init failed; skipping", r == LibUsb.LIBUSB_SUCCESS);
        try {
            PointerByReference listRef = new PointerByReference();
            long count = lib.libusb_get_device_list(null, listRef);
            Assume.assumeTrue("could not list USB devices; skipping", count >= 0);
            Pointer listHead = listRef.getValue();
            Pointer[] devices = count == 0 ? new Pointer[0] : listHead.getPointerArray(0, (int) count);

            Pointer adbDevice = null;
            LibUsb.InterfaceDescriptor adbInterface = null;
            for (Pointer device : devices) {
                LibUsb.InterfaceDescriptor found = findAdbInterface(lib, device);
                if (found != null) {
                    adbDevice = device;
                    adbInterface = found;
                    break;
                }
            }
            Assume.assumeTrue("No ADB USB device attached (enable USB debugging + `adb kill-server`); skipping",
                    adbDevice != null);

            byte inEndpoint = 0, outEndpoint = 0;
            for (LibUsb.EndpointDescriptor ep : adbInterface.endpoints()) {
                if ((ep.bmAttributes & LibUsb.LIBUSB_TRANSFER_TYPE_MASK) != LibUsb.LIBUSB_TRANSFER_TYPE_BULK) continue;
                if ((ep.bEndpointAddress & LibUsb.LIBUSB_ENDPOINT_DIR_MASK) == LibUsb.LIBUSB_ENDPOINT_IN) {
                    inEndpoint = ep.bEndpointAddress;
                } else {
                    outEndpoint = ep.bEndpointAddress;
                }
            }
            assertTrue("ADB interface must expose bulk endpoints", inEndpoint != 0 && outEndpoint != 0);

            PointerByReference handleRef = new PointerByReference();
            int open = lib.libusb_open(adbDevice, handleRef);
            Assume.assumeTrue("could not open the device (permission?); skipping", open == LibUsb.LIBUSB_SUCCESS);
            Pointer handle = handleRef.getValue();
            lib.libusb_set_auto_detach_kernel_driver(handle, 1);
            int ifaceNum = adbInterface.bInterfaceNumber & 0xFF;
            int claim = lib.libusb_claim_interface(handle, ifaceNum);
            if (claim != LibUsb.LIBUSB_SUCCESS) {
                lib.libusb_close(handle);
                // A device is present but the OS (adb server) holds the interface: setup issue, not a defect.
                Assume.assumeTrue("could not claim the ADB interface (run `adb kill-server`); skipping", false);
            }

            LibUsbBulkIo io = new LibUsbBulkIo(handle, inEndpoint, outEndpoint, ifaceNum);
            PrivateKey privateKey = loadOrCreateKey();
            Certificate certificate = certForPublicKey(derivePublicKey(privateKey));

            try (AdbConnection connection = new AdbConnection.Builder()
                    .setChannel(new UsbChannel(io))
                    .setApi(34)
                    .setPrivateKey(privateKey)
                    .setCertificate(certificate)
                    .setDeviceName("libadb-desktop-test")
                    .build()) {
                assertTrue("USB connect (approve the phone's Allow-USB-debugging prompt if shown)",
                        connection.connect(60, TimeUnit.SECONDS, false));
                assertTrue(connection.isConnectionEstablished());

                String model = runShell(connection, "getprop ro.product.model").trim();
                System.out.println("Connected over USB. ro.product.model = " + model);
                assertFalse("getprop over USB must return a model", model.isEmpty());
            }
        } finally {
            lib.libusb_exit(null);
        }
    }

    private static LibUsb.InterfaceDescriptor findAdbInterface(LibUsb lib, Pointer device) {
        PointerByReference configRef = new PointerByReference();
        if (lib.libusb_get_active_config_descriptor(device, configRef) != LibUsb.LIBUSB_SUCCESS) return null;
        Pointer configPtr = configRef.getValue();
        try {
            LibUsb.ConfigDescriptor config = new LibUsb.ConfigDescriptor(configPtr);
            for (LibUsb.Interface iface : config.interfaces()) {
                for (LibUsb.InterfaceDescriptor d : iface.altsettings()) {
                    if ((d.bInterfaceClass & 0xFF) == ADB_CLASS
                            && (d.bInterfaceSubClass & 0xFF) == ADB_SUBCLASS
                            && (d.bInterfaceProtocol & 0xFF) == ADB_PROTOCOL) {
                        return d;
                    }
                }
            }
        } finally {
            lib.libusb_free_config_descriptor(configPtr);
        }
        return null;
    }

    private static String runShell(AdbConnection connection, String command) throws Exception {
        try (AdbStream stream = connection.open("shell:" + command)) {
            InputStream in = stream.openInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private static PrivateKey loadOrCreateKey() throws Exception {
        File keyFile = new File(KEY_DIR, "adbkey.pk8");
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        if (keyFile.isFile()) {
            return rsa.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair pair = generator.generateKeyPair();
        KEY_DIR.mkdirs();
        Files.write(keyFile.toPath(), pair.getPrivate().getEncoded());
        return pair.getPrivate();
    }

    private static PublicKey derivePublicKey(PrivateKey privateKey) throws Exception {
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
        return KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
    }

    private static Certificate certForPublicKey(PublicKey publicKey) {
        return new Certificate("X.509") {
            @Override public byte[] getEncoded() { return new byte[0]; }
            @Override public void verify(PublicKey key) { }
            @Override public void verify(PublicKey key, String sigProvider) { }
            @Override public String toString() { return "desktop test certificate"; }
            @Override public PublicKey getPublicKey() { return publicKey; }
        };
    }
}
