package app.wireroute;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.crypto.KeyPair;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Lifecycle regression against a dummy loopback peer on an isolated emulator only. */
public final class VpnLifecycleSmokeTest {
    @Test public void foregroundTunnelSurvivesBackgroundAndManualStopStaysDown() throws Exception {
        assertTrue("Run only on a disposable emulator; never replace a phone profile",
                (Build.FINGERPRINT.startsWith("google/sdk_gphone")
                        || Build.FINGERPRINT.contains("generic"))
                        && ("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE)));
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertTrue("Build with -PuiTest; never exercise the user's installed VPN",
                context.getPackageName().endsWith(".uitest"));
        WireRouteApplication app = (WireRouteApplication) context.getApplicationContext();
        TunnelController controller = app.getTunnelController();
        assertFalse("Emulator must have no real profile", app.getConfigStore().hasConfig());
        assertNull("Authorize only this emulator with appops ACTIVATE_VPN before running",
                VpnService.prepare(context));

        // The peer is unreachable loopback, and only benchmarking addresses are routed.
        // No user's key, endpoint, server, or profile is used by this test.
        String config = "[Interface]\nPrivateKey = " + new KeyPair().getPrivateKey().toBase64()
                + "\nAddress = 198.18.0.2/32\n[Peer]\nPublicKey = "
                + new KeyPair().getPublicKey().toBase64()
                + "\nEndpoint = 127.0.0.1:9\nAllowedIPs = 198.18.0.0/15\n";
        app.getConfigStore().save(config, "emulator-lifecycle-test.conf");
        app.getRoutingPreferences().setMode(RoutingMode.ONLY_SELECTED);
        app.getRoutingPreferences().setSelectedPackages(Collections.singleton(
                instrumentation.getContext().getPackageName()));

        AtomicReference<Activity> activity = new AtomicReference<>(instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        try {
            instrumentation.runOnMainSync(controller::connect);
            awaitState(controller, TunnelController.State.UP, 15_000L);
            assertTrue("Actual VPN service must be foreground after UP", isVpnForeground(context));
            long serviceStarted = vpnServiceStarted(context);
            assertTrue("The VPN must have an actual running service", serviceStarted > 0L);
            int nativeHandle = backendHandleWhileUp(controller);
            AtomicInteger unexpectedTransitions = new AtomicInteger();
            TunnelController.Listener lifecycleTransitions = snapshot -> {
                if (snapshot.state != TunnelController.State.UP) unexpectedTransitions.incrementAndGet();
            };
            controller.addListener(lifecycleTransitions);
            try {
                // Cover immediate returns and the reported 5–7 second delay window.
                // These cycles change visibility only; no route/profile is saved.
                for (long backgroundMillis : new long[]{500L, 1_500L, 6_500L}) {
                    instrumentation.runOnMainSync(() -> assertTrue("The activity must move behind other apps",
                            activity.get().moveTaskToBack(true)));
                    assertSameSessionFor(context, controller, serviceStarted, nativeHandle,
                            unexpectedTransitions, backgroundMillis);
                    instrumentation.runOnMainSync(() -> context.startActivity(
                            new Intent(context, MainActivity.class).addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
                    awaitForeground(instrumentation, activity.get());
                    assertSameSessionFor(context, controller, serviceStarted, nativeHandle,
                            unexpectedTransitions, 500L);
                }

                // Activity destruction/recreation must not own or destroy the VPN.
                Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                        MainActivity.class.getName(), null, false);
                try {
                    Activity original = activity.get();
                    instrumentation.runOnMainSync(original::recreate);
                    Activity recreated = instrumentation.waitForMonitorWithTimeout(monitor, 10_000L);
                    assertNotNull("Recreation must create a new MainActivity", recreated);
                    assertNotSame("The test must observe an actual replacement activity", original, recreated);
                    activity.set(recreated);
                    awaitForeground(instrumentation, recreated);
                    assertSameSessionFor(context, controller, serviceStarted, nativeHandle,
                            unexpectedTransitions, 1_000L);
                } finally {
                    instrumentation.removeMonitor(monitor);
                }
            } finally {
                controller.removeListener(lifecycleTransitions);
            }

            assertReadOnlyServiceDump(instrumentation, context, app, controller,
                    serviceStarted, nativeHandle, config);

            try {
                shell(instrumentation, "svc wifi disable");
                shell(instrumentation, "svc data disable");
                SystemClock.sleep(1_500L);
                assertEquals("Lost uplink must not tear down the VPN", TunnelController.State.UP,
                        controller.getSnapshot().state);
            } finally {
                shell(instrumentation, "svc wifi enable");
                shell(instrumentation, "svc data enable");
            }
            SystemClock.sleep(1_500L);
            assertEquals("Uplink changes must not restart the service", serviceStarted,
                    vpnServiceStarted(context));
            assertTrue(isVpnForeground(context));

            AtomicBoolean sawDown = new AtomicBoolean();
            TunnelController.Listener transitions = snapshot -> {
                if (snapshot.state == TunnelController.State.DOWN) sawDown.set(true);
            };
            controller.addListener(transitions);
            try {
                instrumentation.runOnMainSync(() -> {
                    HashSet<String> updated = new HashSet<>(app.getRoutingPreferences().getSelectedPackages());
                    updated.add("com.android.settings");
                    app.getRoutingPreferences().setSelectedPackages(updated);
                });
                SystemClock.sleep(1_000L);
                assertEquals("Editing a saved selection must not interrupt the live tunnel",
                        serviceStarted, vpnServiceStarted(context));
                assertEquals(TunnelController.State.UP, controller.getSnapshot().state);
                assertFalse(sawDown.get());
                instrumentation.runOnMainSync(() -> {
                    assertTrue("Changing selected apps must refresh an active session",
                            controller.applyRoutingChanges());
                });
                awaitState(controller, TunnelController.State.UP, 15_000L);
                instrumentation.waitForIdleSync();
                assertTrue("Intentional route refresh must publish DOWN before UP", sawDown.get());
                assertTrue("Refreshed VPN must have a foreground service", isVpnForeground(context));
                assertTrue("Route refresh must create a new service after the previous one is destroyed",
                        vpnServiceStarted(context) > serviceStarted);
            } finally {
                controller.removeListener(transitions);
            }

            instrumentation.runOnMainSync(() -> {
                assertTrue("Queue another refresh to exercise manual Stop cancellation",
                        controller.applyRoutingChanges());
                controller.disconnect();
            });
            awaitState(controller, TunnelController.State.DOWN, 10_000L);
            SystemClock.sleep(2_000L);
            assertEquals("Manual Stop must not autoreconnect", TunnelController.State.DOWN,
                    controller.getSnapshot().state);
            assertFalse("Manual Stop removes the VPN foreground service", isVpnForeground(context));
            assertFalse("Route refresh after Stop must not reconnect", controller.applyRoutingChanges());
            assertFalse(controller.isConnectionDesired());
        } finally {
            instrumentation.runOnMainSync(() -> {
                controller.disconnect();
                activity.get().finish();
            });
            awaitState(controller, TunnelController.State.DOWN, 10_000L);
            // Only this guarded emulator test created the synthetic profile.
            // Leave the isolated package rerunnable without retaining test keys.
            app.getConfigStore().clear();
        }
    }

    private static void assertReadOnlyServiceDump(Instrumentation instrumentation, Context context,
            WireRouteApplication app, TunnelController controller, long serviceStarted,
            int nativeHandle, String profile) throws Exception {
        File profileFile = new File(context.getNoBackupFilesDir(), "profile.wrc");
        byte[] encryptedProfile = readFile(profileFile);
        Map<String, ?> routing = new HashMap<>(context.getSharedPreferences("routing",
                Context.MODE_PRIVATE).getAll());
        Map<String, ?> metadata = new HashMap<>(context.getSharedPreferences("profile_metadata",
                Context.MODE_PRIVATE).getAll());
        long routingRevision = app.getRoutingPreferences().getRevision();
        long userGeneration = controller.getUserActionGeneration();
        Field generationField = TunnelController.class.getDeclaredField("operationGeneration");
        generationField.setAccessible(true);
        long operationGeneration = generationField.getLong(controller);
        AtomicInteger unexpectedTransitions = new AtomicInteger();
        TunnelController.Listener transitions = snapshot -> {
            if (snapshot.state != TunnelController.State.UP) unexpectedTransitions.incrementAndGet();
        };
        controller.addListener(transitions);
        try {
            for (int i = 0; i < 3; i++) {
                assertSafeServiceDump(serviceDump(instrumentation, context), profile);
                assertSameSessionFor(context, controller, serviceStarted, nativeHandle,
                        unexpectedTransitions, 100L);
            }

            // A system dump runs on Android's main thread. Holding the native backend
            // monitor must not delay it: only the existing worker touches that monitor.
            Field backendField = TunnelController.class.getDeclaredField("backend");
            backendField.setAccessible(true);
            GoBackend backend = (GoBackend) backendField.get(controller);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                synchronized (backend) {
                    locked.countDown();
                    try { release.await(8L, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            }, "lifecycle-dump-native-lock");
            holder.start();
            try {
                assertTrue("The test must actually hold the native backend lock",
                        locked.await(2L, TimeUnit.SECONDS));
                long started = SystemClock.elapsedRealtime();
                assertSafeServiceDump(serviceDump(instrumentation, context), profile);
                assertTrue("Cached DUMP must return promptly while the backend is locked",
                        SystemClock.elapsedRealtime() - started < 3_000L);
            } finally {
                release.countDown();
                holder.join(2_000L);
                assertFalse("The test must release the native backend monitor", holder.isAlive());
            }
            assertSameSessionFor(context, controller, serviceStarted, nativeHandle,
                    unexpectedTransitions, 100L);
            assertTrue("DUMP must preserve the exact encrypted profile bytes",
                    Arrays.equals(encryptedProfile, readFile(profileFile)));
            assertTrue("DUMP must preserve routing preferences", routing.equals(
                    context.getSharedPreferences("routing", Context.MODE_PRIVATE).getAll()));
            assertTrue("DUMP must preserve profile metadata", metadata.equals(
                    context.getSharedPreferences("profile_metadata", Context.MODE_PRIVATE).getAll()));
            assertEquals("DUMP must not stage routing edits", routingRevision,
                    app.getRoutingPreferences().getRevision());
            assertEquals("DUMP must not create user actions", userGeneration,
                    controller.getUserActionGeneration());
            assertEquals("DUMP must not enqueue reconnects", operationGeneration,
                    generationField.getLong(controller));
        } finally {
            controller.removeListener(transitions);
        }
    }

    private static String serviceDump(Instrumentation instrumentation, Context context) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(
                "dumpsys activity service " + context.getPackageName() + "/app.wireroute.StableVpnService");
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            return readBounded(input);
        }
    }

    private static void assertSafeServiceDump(String dump, String profile) {
        int safeLines = 0;
        for (String line : dump.split("\\r?\\n")) {
            String text = line.trim();
            if (text.startsWith("WireRouteVpn ")) {
                assertTrue("DUMP identity must contain only the fixed non-secret fields",
                        text.matches("WireRouteVpn pid=[0-9]+ state=UP service_started_elapsed_ms=[0-9]+ underlay_mode=SYSTEM"));
                safeLines++;
            } else if (text.startsWith("sample_state=")) {
                assertTrue("The existing observer must supply counters for the same UP session",
                        text.matches("sample_state=UP available=true stale=(?:true|false) observation_age_ms=[0-9]+"));
                safeLines++;
            } else if (text.startsWith("rx_bytes=")) {
                assertTrue("DUMP counters must contain only numbers and an explicit unknown handshake",
                        text.matches("rx_bytes=[0-9]+ tx_bytes=[0-9]+ handshake_age_s=(?:[0-9]+|unknown)"));
                safeLines++;
            }
        }
        assertEquals("The real system DUMP must invoke the three-line service hook", 3, safeLines);
        for (String secretMarker : new String[]{"PrivateKey", "PublicKey", "PresharedKey", "[Interface]",
                "[Peer]", "Endpoint", "127.0.0.1", "198.18.0.", "emulator-lifecycle-test.conf"}) {
            assertFalse("DUMP must never expose profile fields or addresses", dump.contains(secretMarker));
        }
        for (String line : profile.split("\\n")) {
            if (line.startsWith("PrivateKey = ") || line.startsWith("PublicKey = ")) {
                assertFalse("DUMP must never expose raw key bytes", dump.contains(line.substring(line.indexOf(" = ") + 3)));
            }
        }
    }

    private static byte[] readFile(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1_024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String readBounded(FileInputStream input) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1_024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                assertTrue("The exact-component dump must remain bounded", output.size() + count <= 65_536);
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void assertSameSessionFor(Context context, TunnelController controller,
            long serviceStarted, int nativeHandle, AtomicInteger unexpectedTransitions,
            long durationMillis) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + durationMillis;
        do {
            assertEquals("Visibility changes must never publish a VPN transition", 0,
                    unexpectedTransitions.get());
            assertEquals("The same VPN foreground service must remain running", serviceStarted,
                    vpnServiceStarted(context));
            assertTrue("The VPN service must stay foreground behind other apps", isVpnForeground(context));
            assertEquals("Visibility changes must preserve the live native tunnel handle", nativeHandle,
                    backendHandleWhileUp(controller));
            SystemClock.sleep(100L);
        } while (SystemClock.elapsedRealtime() < deadline);
    }

    private static void awaitForeground(Instrumentation instrumentation, Activity activity) {
        long deadline = SystemClock.elapsedRealtime() + 10_000L;
        AtomicBoolean focused = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> focused.set(activity.hasWindowFocus()));
            if (focused.get()) {
                instrumentation.waitForIdleSync();
                return;
            }
            SystemClock.sleep(50L);
        }
        fail("The activity did not return to the foreground");
    }

    /** Test-only inspection: a UI UP label alone cannot prove that the backend survived. */
    private static int backendHandleWhileUp(TunnelController controller) throws Exception {
        Field backendField = TunnelController.class.getDeclaredField("backend");
        Field tunnelField = TunnelController.class.getDeclaredField("tunnel");
        Field handleField = GoBackend.class.getDeclaredField("currentTunnelHandle");
        backendField.setAccessible(true);
        tunnelField.setAccessible(true);
        handleField.setAccessible(true);
        GoBackend backend = (GoBackend) backendField.get(controller);
        Tunnel tunnel = (Tunnel) tunnelField.get(controller);
        synchronized (backend) {
            assertEquals("The controller must keep the active state", TunnelController.State.UP,
                    controller.getSnapshot().state);
            assertEquals("The real backend must remain UP", Tunnel.State.UP, backend.getState(tunnel));
            int handle = handleField.getInt(backend);
            assertTrue("The backend must retain a live native handle", handle >= 0);
            return handle;
        }
    }

    private static void awaitState(TunnelController controller, TunnelController.State expected,
            long timeoutMillis) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        while (SystemClock.elapsedRealtime() < deadline) {
            TunnelController.Snapshot current = controller.getSnapshot();
            if (current.state == expected) return;
            if (current.state == TunnelController.State.ERROR) {
                fail("Unexpected controller failure: " + current.failure);
            }
            SystemClock.sleep(50L);
        }
        fail("Expected " + expected + ", got " + controller.getSnapshot().state);
    }

    @SuppressWarnings("deprecation")
    private static boolean isVpnForeground(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(100)) {
            if (isWireGuardService(service.service.getClassName())) {
                return service.foreground;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static long vpnServiceStarted(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(100)) {
            if (isWireGuardService(service.service.getClassName())) {
                return service.activeSince;
            }
        }
        return -1L;
    }

    private static boolean isWireGuardService(String className) {
        return "app.wireroute.StableVpnService".equals(className)
                || "com.wireguard.android.backend.GoBackend$VpnService".equals(className);
    }

    private static void shell(Instrumentation instrumentation, String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[512];
            while (input.read(buffer) != -1) { /* Drain bounded command output. */ }
        }
    }
}
