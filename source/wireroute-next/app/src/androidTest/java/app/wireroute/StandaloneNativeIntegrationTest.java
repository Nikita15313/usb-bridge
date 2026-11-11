package app.wireroute;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Real TUN regression for the custom native wrapper, using an ephemeral peer. */
public final class StandaloneNativeIntegrationTest {
    @Test public void originalAndroidTunPathStillHandshakesAndTransfers() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Never replace a user's profile", context.getPackageName().endsWith(".usbtest"));
        assertTrue("Disposable emulator only", Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"));
        WireRouteApplication app = (WireRouteApplication) context.getApplicationContext();
        assertTrue(!app.getConfigStore().hasConfig()
                || "usb-emulator-fixture.conf".equals(app.getConfigStore().getDisplayName()));
        assertEquals("Stop Bridge before this test; ordinary Wi-Fi must be the uplink",
                0, UsbBridgeUnderlay.offeredRelaySession(context));
        assertFalse("No previous USB service may remain", UsbPlainVpnService.running);
        assertNull("Authorize only the isolated emulator package", VpnService.prepare(context));
        Bundle args = InstrumentationRegistry.getArguments();
        String config = "[Interface]\nPrivateKey = " + args.getString("clientKey")
                + "\nAddress = 10.88.0.2/32\nDNS = 10.88.0.1\n[Peer]\nPublicKey = "
                + args.getString("serverKey") + "\nEndpoint = " + args.getString("host")
                + ":" + args.getString("port") + "\nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 5\n";
        app.getConfigStore().save(config, "usb-emulator-fixture.conf");
        Set<String> selected = new HashSet<>(Arrays.asList("local.usbbridge.probe"));
        app.getRoutingPreferences().setMode(RoutingMode.ONLY_SELECTED);
        app.getRoutingPreferences().setSelectedPackages(selected);
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        TunnelController controller = app.getTunnelController();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(controller::connect);
            long end = SystemClock.elapsedRealtime() + 25000;
            while (controller.getSnapshot().state != TunnelController.State.UP && SystemClock.elapsedRealtime() < end) {
                assertNotEquals(controller.getSnapshot().failure.toString(), TunnelController.State.ERROR,
                        controller.getSnapshot().state);
                SystemClock.sleep(100);
            }
            assertEquals(TunnelController.State.UP, controller.getSnapshot().state);
            assertFalse("The upstream native TUN path must not use the USB packet adapter", UsbBridgeUnderlay.active());
            end = SystemClock.elapsedRealtime() + 25000;
            while (controller.getTransferSnapshot().latestHandshakeEpochMillis == 0 && SystemClock.elapsedRealtime() < end)
                SystemClock.sleep(250);
            assertTrue("Actual WireGuard handshake over ordinary uplink",
                    controller.getTransferSnapshot().latestHandshakeEpochMillis > 0);
            // The controller deliberately excludes its own package from the
            // user-app selection. Bind this fixture request to the owned VPN
            // explicitly; an ordinary URL.openConnection here would test the
            // physical uplink instead of the native TUN. Separate probe UIDs
            // remain necessary to test application-selection policy itself.
            Network owned = UsbBridgeUnderlay.ownVpnNetwork(context);
            assertNotNull("The native service must publish its owned VPN", owned);
            HttpURLConnection request = (HttpURLConnection) owned.openConnection(new URL(
                    args.getString("verifyUrl", "http://10.88.0.1/standalone-native")));
            request.setConnectTimeout(15000); request.setReadTimeout(15000); request.setUseCaches(false);
            try { assertEquals("Actual payload request through the normal Android TUN", 204, request.getResponseCode()); }
            finally { request.disconnect(); }
            assertEquals(config, app.getConfigStore().load());
            assertEquals(selected, app.getRoutingPreferences().getSelectedPackages());
            android.util.Log.i("USBBridgeProbe", "PASS standalone native handshake and HTTP204 through real Android TUN");
            long hold = Long.parseLong(args.getString("holdMillis", "0"));
            if (hold < 0 || hold > 300000) throw new IllegalArgumentException("holdMillis must be 0..300000");
            SystemClock.sleep(hold);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(controller::disconnect);
            long end = SystemClock.elapsedRealtime() + 10000;
            while (controller.getSnapshot().state != TunnelController.State.DOWN && SystemClock.elapsedRealtime() < end)
                SystemClock.sleep(100);
            assertEquals(TunnelController.State.DOWN, controller.getSnapshot().state);
            assertFalse(UsbBridgeUnderlay.active());
            assertFalse("Standalone OFF must not start USB", UsbPlainVpnService.running);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
        }
    }
}
