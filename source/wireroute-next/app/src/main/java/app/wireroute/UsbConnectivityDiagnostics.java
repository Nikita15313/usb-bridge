package app.wireroute;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Bundle;
import java.util.Set;

/** Explicit local report only: no probe, app restart, route change or saved configuration edit. */
public final class UsbConnectivityDiagnostics {
    private UsbConnectivityDiagnostics() { }
    private static final String[] TELEGRAM_PACKAGES = {
            "org.telegram.messenger", "org.telegram.messenger.web",
            "org.telegram.messenger.beta", "org.thunderdog.challegram"
    };

    public static String collect(Context context) {
        Bundle usb = UsbBridgeUnderlay.query(context);
        boolean selected = usb != null && usb.getBoolean("selected");
        boolean connected = selected && usb.getBoolean("connected");
        Boolean helper = value(usb, "helperReady"), prepared = value(usb, "appNetworkReady");
        Boolean defaults = value(usb, "vpnDefaultReady");
        Boolean legacy = null, modern = null;
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm != null) {
                // This is the same public legacy predicate used by Telegram,
                // observed for this reporting UID. It is not a query of Telegram's UID.
                NetworkInfo active = cm.getActiveNetworkInfo();
                NetworkInfo mobile = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
                NetworkInfo wifi = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                legacy = legacyOnline(active != null && active.isConnectedOrConnecting(),
                        active != null && active.isAvailable(),
                        mobile != null && mobile.isConnectedOrConnecting(),
                        wifi != null && wifi.isConnectedOrConnecting());
                Network activeNetwork = cm.getActiveNetwork();
                NetworkCapabilities caps = activeNetwork == null ? null : cm.getNetworkCapabilities(activeNetwork);
                modern = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
        } catch (RuntimeException ignored) { /* Unknown, never manufacture online status. */ }

        StringBuilder report = new StringBuilder("usb_status=LOCAL_PASSIVE_SNAPSHOT\n");
        report.append("usb_selected=").append(selected).append(" usb_connected=").append(connected)
                .append(" helper_ready=").append(tri(helper))
                .append(" app_network_ready=").append(tri(prepared))
                .append(" vpn_uid_default_ready=").append(tri(defaults));
        report.append("\nobserver_legacy_online=").append(tri(legacy))
                .append(" observer_modern_internet=").append(tri(modern))
                .append(" observer_scope=WireRoute_UID_not_Telegram_UID");
        report.append("\nusb_observation=").append(classify(selected, connected, prepared, legacy, modern));
        try {
            WireRouteApplication app = (WireRouteApplication) context.getApplicationContext();
            RoutingPreferences routing = app.getRoutingPreferences();
            Set<String> chosen = routing.getSelectedPackages();
            int installed = 0, encrypted = 0;
            for (String name : TELEGRAM_PACKAGES) {
                try { context.getPackageManager().getApplicationInfo(name, 0); }
                catch (android.content.pm.PackageManager.NameNotFoundException absent) { continue; }
                installed++;
                if (configuredVpn(routing.getMode(), chosen.contains(name))) encrypted++;
            }
            boolean vpnOn = app.getTunnelController().getSnapshot().state == TunnelController.State.UP;
            report.append("\nphone_vpn_on=").append(vpnOn)
                    .append(" telegram_known_installed=").append(installed)
                    .append(" telegram_configured_vpn=").append(encrypted)
                    .append(" telegram_configured_plain=").append(installed - encrypted);
            report.append("\ntelegram_check=").append(telegramCheck(vpnOn, installed, encrypted));
        } catch (RuntimeException unavailable) { report.append("\ntelegram_check=UNKNOWN"); }
        report.append("\ntelegram_remote_connection=NOT_TESTED");
        return report.toString();
    }

    private static Boolean value(Bundle bundle, String key) {
        return bundle != null && bundle.containsKey(key) ? bundle.getBoolean(key) : null;
    }
    private static String tri(Boolean value) { return value == null ? "UNKNOWN" : value.toString(); }

    static boolean legacyOnline(boolean activeConnected, boolean activeAvailable,
                                boolean mobileConnected, boolean wifiConnected) {
        return activeConnected || activeAvailable || mobileConnected || wifiConnected;
    }
    static boolean configuredVpn(RoutingMode mode, boolean selected) {
        return mode == RoutingMode.ONLY_SELECTED ? selected : !selected;
    }
    static String classify(boolean selected, boolean connected, Boolean prepared,
                           Boolean legacy, Boolean modern) {
        if (!selected) return "USB_NOT_SELECTED";
        if (!connected) return "USB_DISCONNECTED";
        if (Boolean.FALSE.equals(prepared)) return "ANDROID_NETWORK_PREPARATION_INCOMPLETE";
        if (Boolean.FALSE.equals(legacy) && Boolean.TRUE.equals(modern))
            return "LEGACY_STATUS_DIFFERS_FOR_OBSERVER";
        if (Boolean.TRUE.equals(legacy)) return "LEGACY_STATUS_AVAILABLE_FOR_OBSERVER";
        return "UNKNOWN";
    }
    static String telegramCheck(boolean vpnOn, int installed, int encrypted) {
        if (installed <= 0) return "KNOWN_VARIANT_NOT_FOUND";
        if (!vpnOn) return "PHONE_VPN_OFF";
        if (encrypted == 0) return "KNOWN_VARIANTS_OUTSIDE_CONFIGURED_VPN";
        if (encrypted < installed) return "SOME_KNOWN_VARIANTS_OUTSIDE_CONFIGURED_VPN";
        return "KNOWN_VARIANTS_IN_CONFIGURED_VPN_REMOTE_NOT_TESTED";
    }
}
