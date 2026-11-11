package app.wireroute.diagnostics;

import android.content.Context;
import android.util.Log;

import app.wireroute.ConfigUtils;
import app.wireroute.RoutingMode;
import app.wireroute.RoutingPreferences;
import app.wireroute.SecureConfigStore;
import app.wireroute.SessionDiagnostics;
import app.wireroute.UsbConnectivityDiagnostics;
import app.wireroute.WireRouteApplication;

import com.wireguard.config.Config;
import com.wireguard.config.InetEndpoint;
import com.wireguard.config.InetNetwork;
import com.wireguard.config.Peer;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

/** Explicit local inspection only. No keys, endpoint, address, DNS name or package list leaves here. */
public final class ProfileDiagnostics {
    private ProfileDiagnostics() { }

    /** Reads the currently saved encrypted profile, not a possibly older native tunnel config. */
    public static String collect(Context context) {
        String connectivity = UsbConnectivityDiagnostics.collect(context) + "\n";
        try {
            WireRouteApplication app = (WireRouteApplication) context.getApplicationContext();
            SecureConfigStore store = app.getConfigStore();
            if (!store.hasConfig()) return connectivity + "profile=NOT_CONFIGURED";
            String raw = store.load();
            if (raw == null) return connectivity + "profile=NOT_CONFIGURED";
            RoutingPreferences routing = app.getRoutingPreferences();
            android.content.pm.PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long versionCode = android.os.Build.VERSION.SDK_INT >= 28
                    ? packageInfo.getLongVersionCode() : packageInfo.versionCode;
            return connectivity + "app_version_code=" + versionCode + "\n" + inspect(ConfigUtils.parse(raw), routing.getMode(),
                    routing.getSelectedPackages(), context.getPackageName());
        } catch (Exception ignored) {
            // Parser and keystore exceptions can contain secret source lines: never print them.
            return connectivity + "profile=UNAVAILABLE";
        }
    }

    static String inspect(Config config, RoutingMode mode, Set<String> packages, String ownPackage) {
        StringBuilder text = new StringBuilder("config_source=stored_encrypted\n");
        text.append("client_public_key_sha256_12=").append(SessionDiagnostics.publicKeyFingerprint(
                config.getInterface().getKeyPair().getPublicKey().toBase64()));
        text.append("\nprofile_mtu=").append(config.getInterface().getMtu()
                .map(String::valueOf).orElse("AUTO"));
        int listenPort = config.getInterface().getListenPort().orElse(0);
        text.append("\ninterface_listen_port=").append(listenPort == 0 ? "AUTO" : String.valueOf(listenPort));
        int dns4 = 0, dns6 = 0, address4 = 0, address6 = 0;
        for (InetAddress address : config.getInterface().getDnsServers()) {
            if (address instanceof Inet4Address) dns4++;
            if (address instanceof Inet6Address) dns6++;
        }
        for (InetNetwork address : config.getInterface().getAddresses()) {
            if (address.getAddress() instanceof Inet4Address) address4++;
            if (address.getAddress() instanceof Inet6Address) address6++;
        }
        text.append("\ninterface_addresses_v4=").append(address4).append(" v6=").append(address6);
        text.append("\ndns_v4=").append(dns4).append(" dns_v6=").append(dns6)
                .append(" search_domains=").append(config.getInterface().getDnsSearchDomains().size());
        ConfigUtils.Info info = ConfigUtils.inspect(config);
        int routes4 = 0, routes6 = 0;
        for (Peer peer : config.getPeers()) for (InetNetwork route : peer.getAllowedIps()) {
            if (route.getAddress() instanceof Inet4Address) routes4++;
            if (route.getAddress() instanceof Inet6Address) routes6++;
        }
        text.append("\nallowed_routes_v4=").append(routes4).append(" v6=").append(routes6)
                .append(" full_v4=").append(info.fullIpv4).append(" full_v6=").append(info.fullIpv6);
        text.append("\nrouting=").append(mode.name()).append(" selected_count=").append(packages.size())
                .append(" diagnostic_app_selected=").append(packages.contains(ownPackage));
        text.append("\npeer_count=").append(config.getPeers().size());
        int index = 0;
        for (Peer peer : config.getPeers()) {
            if (index >= 16) {
                text.append("\nadditional_peer_metadata=OMITTED");
                break;
            }
            text.append("\npeer_").append(++index).append("_public_key_sha256_12=")
                    .append(SessionDiagnostics.publicKeyFingerprint(peer.getPublicKey().toBase64()))
                    .append(" keepalive_s=").append(peer.getPersistentKeepalive().orElse(0));
            if (peer.getEndpoint().isPresent()) {
                InetEndpoint endpoint = peer.getEndpoint().get();
                text.append(" endpoint_port=").append(endpoint.getPort())
                        .append(" endpoint_text_sha256_12=").append(endpointFingerprint(endpoint));
            } else {
                text.append(" endpoint=NOT_CONFIGURED");
            }
        }
        return text.toString();
    }

    /** Hash normalized configured text only, with no DNS lookup or raw host output. */
    static String endpointFingerprint(InetEndpoint endpoint) {
        String host = endpoint.getHost().trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        String normalized = (host.contains(":") ? "[" + host + "]" : host) + ":" + endpoint.getPort();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(12);
            for (int index = 0; index < 6; index++) value.append(String.format(Locale.ROOT, "%02x", digest[index] & 0xff));
            return value.toString();
        } catch (Exception ignored) { return "UNAVAILABLE"; }
    }

    /** Only call with a report returned by collect/inspect, never with configuration text. */
    static void logGeneratedReport(String report) {
        for (String line : report.split("\n")) Log.i("WireRouteProfile", line);
    }
}
