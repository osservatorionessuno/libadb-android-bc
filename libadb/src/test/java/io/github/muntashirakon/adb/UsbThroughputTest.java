// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;

import io.github.muntashirakon.adb.desktop.DesktopUsb;

/**
 * Throughput / stability benchmark for the USB transport against a real attached phone. Measures read
 * (device -> host, the acquisition-relevant direction) throughput, checks large transfers complete with an
 * exact byte count (no truncation, no timeout), and characterises small-command round-trip latency.
 * <p>
 * Opt-in and device-gated so it never runs in a normal suite. Run with:
 * <pre>
 *   adb kill-server
 *   ./gradlew :libadb:testReleaseUnitTest --tests '*UsbThroughputTest' -Dlibadb.usb.benchmark=true
 * </pre>
 */
public class UsbThroughputTest {
    private static final long MiB = 1024 * 1024;

    @BeforeClass
    public static void setUp() {
        if (System.getProperty("jna.library.path") == null
                && System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            System.setProperty("jna.library.path", "/opt/homebrew/lib:/usr/local/lib");
        }
        Assume.assumeTrue("set -Dlibadb.usb.benchmark=true to run the USB benchmark",
                Boolean.getBoolean("libadb.usb.benchmark"));
    }

    @Test(timeout = 300_000)
    public void benchmark() throws Exception {
        DesktopUsb.Session opened = DesktopUsb.open();
        Assume.assumeTrue("No USB ADB connection (see console for why); skipping", opened != null);
        try (DesktopUsb.Session session = opened) {
            AdbConnection connection = session.connection;

            // Prefer the raw exec pipe (no PTY throttling); fall back to a shell PTY if the device's
            // adbd doesn't serve exec: over this transport.
            boolean useExec;
            try {
                useExec = drain(connection, "exec:dd if=/dev/zero bs=1024 count=1 2>/dev/null") > 0;
            } catch (Exception e) {
                useExec = false;
            }
            System.out.println("transfer path: " + (useExec ? "exec: (raw pipe)" : "shell: (PTY)"));

            // Warm up (spin up dd, prime the pipe) — not measured.
            readZeros(connection, useExec, 50);

            System.out.println("\n=== USB read throughput (device -> host) ===");
            double best = 0;
            for (long sizeMiB : new long[]{100, 200, 400}) {
                long start = System.nanoTime();
                long bytes = readZeros(connection, useExec, sizeMiB);
                double seconds = (System.nanoTime() - start) / 1e9;
                double mbps = (bytes / (double) MiB) / seconds;
                best = Math.max(best, mbps);
                System.out.printf("  %4d MiB in %6.2f s = %6.1f MiB/s%n", sizeMiB, seconds, mbps);
                assertEquals("transfer must be complete (no truncation/timeout)", sizeMiB * MiB, bytes);
            }

            System.out.println("\n=== Large sustained transfer (no-timeout check) ===");
            long big = 1024; // 1 GiB
            long start = System.nanoTime();
            long bytes = readZeros(connection, useExec, big);
            double seconds = (System.nanoTime() - start) / 1e9;
            System.out.printf("  %4d MiB in %6.2f s = %6.1f MiB/s%n", big, seconds,
                    (bytes / (double) MiB) / seconds);
            assertEquals("1 GiB transfer must complete exactly", big * MiB, bytes);

            System.out.println("\n=== Small-command round-trip latency ===");
            int iterations = 20;
            long rtStart = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                drain(connection, "shell:getprop ro.product.model");
            }
            double avgMs = (System.nanoTime() - rtStart) / 1e6 / iterations;
            System.out.printf("  %d x getprop round-trip: %.1f ms average%n", iterations, avgMs);

            System.out.println();
            assertTrue("throughput implausibly low (something is stalling)", best > 1.0);
        }
    }

    /** Streams {@code sizeMiB} of zeros from the device and returns the bytes read (stderr dropped so the
     *  count is exactly the payload). */
    private static long readZeros(AdbConnection connection, boolean useExec, long sizeMiB) throws Exception {
        String prefix = useExec ? "exec:" : "shell:";
        return drain(connection, prefix + "dd if=/dev/zero bs=1048576 count=" + sizeMiB + " 2>/dev/null");
    }

    private static long drain(AdbConnection connection, String destination) throws Exception {
        try (AdbStream stream = connection.open(destination)) {
            InputStream in = stream.openInputStream();
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
            }
            return total;
        }
    }
}
