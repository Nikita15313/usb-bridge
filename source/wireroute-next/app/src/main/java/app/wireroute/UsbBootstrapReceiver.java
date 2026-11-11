package app.wireroute;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** Restores the already-authorized plain USB path without opening an Activity. */
public final class UsbBootstrapReceiver extends BroadcastReceiver {
    public static final String ACTION = "local.usbbridge.START_PLAIN";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        long requested = intent.getLongExtra("session", 0);
        // The provider checks this package against the user's selected VPN.
        // An exported broadcast alone never authorizes a stale or arbitrary link.
        long offered = UsbBridgeUnderlay.offeredRelaySession(context);
        if (!matchesSession(requested, offered)) return;
        TunnelController controller = ((WireRouteApplication) context.getApplicationContext())
                .getTunnelController();
        if (!controller.canStartPlainUsb()) return;
        if (VpnService.prepare(context) != null) {
            UsbBridgeUnderlay.reportSession(context, "plain_permission", offered, "plain");
            return;
        }
        UsbPlainVpnService.startIfAvailable(context);
    }

    static boolean matchesSession(long requested, long offered) {
        return requested > 0 && requested == offered;
    }
}
