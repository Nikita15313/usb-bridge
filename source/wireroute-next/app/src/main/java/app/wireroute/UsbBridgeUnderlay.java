package app.wireroute;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import com.wireguard.config.Config;
import com.wireguard.config.Peer;
import com.wireguard.config.InetEndpoint;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Opt-in USB transport. Runtime endpoints change; saved profiles never do. */
final class UsbBridgeUnderlay {
    static final Uri ENDPOINT = Uri.parse("content://local.usbbridge.underlay");
    private static volatile Network network;
    private static volatile long session;
    private static volatile UsbWireGuardRelay relay;
    private static volatile Set<Long> previousVpnNetworks = Collections.emptySet();
    private static volatile UsbSplitPolicy splitPolicy;

    static void configureRouting(Context context, RoutingMode mode, Set<String> packages) throws Exception {
        Set<Integer> uids = new HashSet<>();
        for (String name : packages) uids.add(context.getPackageManager().getApplicationInfo(name, 0).uid);
        splitPolicy = new UsbSplitPolicy(mode, uids);
    }
    static UsbSplitPolicy splitPolicy() { return splitPolicy; }

    static Bundle query(Context context) {
        try { return context.getContentResolver().call(ENDPOINT, "network", null, null); }
        catch (RuntimeException absent) { return null; }
    }

    static boolean selected(Context context) {
        Bundle result = query(context);
        return result != null && result.getBoolean("selected");
    }

    static long offeredRelaySession(Context context) { return relaySession(query(context)); }

    static long relaySession(Bundle result) {
        return result != null && result.getBoolean("selected") && result.getBoolean("connected")
                && result.getInt("relayVersion") >= 1 ? result.getLong("session") : 0;
    }

    static String preflightIssue(Context context) {
        Bundle result = query(context);
        if (result == null || !result.getBoolean("selected")) return null;
        if (!result.getBoolean("connected")) return "Сначала подключите компьютер в USB Bridge";
        if (relaySession(result) != 0) return null;
        if (result.getLong("network") == 0) return "Обновите USB Bridge на телефоне и компьютере, затем подключите кабель заново";
        return null;
    }

    static synchronized boolean begin(Context context, long requiredUsbSession) throws IOException {
        Bundle result = query(context);
        long offered = relaySession(result);
        // Check the same snapshot used below, before an absent bridge can select
        // the phone's network or a new cable session can replace the old one.
        UsbConnectivityRefresh.requireSameSession(requiredUsbSession, offered);
        if (result == null || !result.getBoolean("selected")) return false;
        if (!result.getBoolean("connected")) throw new IOException("USB Bridge is disconnected");
        if (offered != 0) {
            // Android may briefly list the old plain TUN after its service has
            // closed. It must never be advertised as the encrypted hotspot path.
            previousVpnNetworks = snapshotOwnedVpnNetworks(context);
            session = offered;
            return true;
        }
        long handle = result.getLong("network");
        if (handle == 0 || android.os.Build.VERSION.SDK_INT < 28) throw new IOException("USB Bridge needs an update");
        Network chosen = Network.fromNetworkHandle(handle);
        if (!context.getSystemService(ConnectivityManager.class).bindProcessToNetwork(chosen))
            throw new IOException("Android refused USB network");
        network = chosen;
        return true;
    }

    static ParcelFileDescriptor openRelay(Context context, long expectedSession) throws IOException {
        if (expectedSession == 0 || offeredRelaySession(context) != expectedSession)
            throw new IOException("USB session ended");
        Uri uri = ENDPOINT.buildUpon().appendPath("relay")
                .appendQueryParameter("session", Long.toString(expectedSession)).build();
        ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "rw");
        if (fd == null) throw new IOException("USB relay unavailable");
        return fd;
    }

    static Config adaptRuntime(Context context, Config config, Runnable lost) throws Exception {
        if (session == 0) return config;
        if (config.getPeers().size() != 1) throw new IOException("USB requires one peer");
        Peer peer = config.getPeers().iterator().next();
        InetEndpoint endpoint = peer.getEndpoint().orElseThrow(() -> new IOException("Peer endpoint missing"));
        ParcelFileDescriptor fd = openRelay(context, session);
        UsbWireGuardRelay created;
        try { created = new UsbWireGuardRelay(fd, endpoint, lost); }
        catch (Exception error) { fd.close(); throw error; }
        relay = created;
        Config adapted = ConfigUtils.withUsbRelayEndpoint(config, created.port());
        created.start();
        return adapted;
    }

    static synchronized void beginVpnIntent(Context context) throws IOException {
        if (session == 0) return; // Legacy integration has no direct relay capability.
        Bundle args = new Bundle();
        args.putLong("session", session);
        args.putString("mode", "vpn");
        args.putLong("vpnNetwork", 0);
        final Bundle ack;
        try { ack = context.getContentResolver().call(ENDPOINT, "starting", null, args); }
        catch (RuntimeException failure) { throw new IOException("USB sharing transition was not acknowledged", failure); }
        if (ack == null || !acceptsStartingAck(session, ack.getLong("session"),
                ack.getBoolean("selected"), ack.getBoolean("connected"), ack.getBoolean("wifiBarrierReady")))
            throw new IOException("USB sharing transition was not acknowledged");
    }

    static boolean acceptsStartingAck(long expectedSession, long receivedSession,
            boolean selected, boolean connected, boolean barrierReady) {
        return expectedSession > 0 && expectedSession == receivedSession
                && selected && connected && barrierReady;
    }

    static synchronized void report(Context context, String state) {
        if (session != 0) { reportSession(context, state, session, "vpn"); return; }
        Network current = network;
        if (current == null) return;
        Bundle args = new Bundle(); args.putLong("network", current.getNetworkHandle());
        try { context.getContentResolver().call(ENDPOINT, state, null, args); } catch (RuntimeException ignored) { }
    }

    static void reportSession(Context context, String state, long selectedSession, String mode) {
        Bundle args = new Bundle(); args.putLong("session", selectedSession); args.putString("mode", mode);
        // The hotspot gateway must bind to this exact encrypted VPN. Reporting
        // zero while stopped/plain prevents a stale or unrelated VPN fallback.
        Network vpn = "vpn".equals(mode) && ("started".equals(state) || "alive".equals(state))
                ? ownVpnNetwork(context) : null;
        args.putLong("vpnNetwork", vpn == null ? 0 : vpn.getNetworkHandle());
        try { context.getContentResolver().call(ENDPOINT, state, null, args); } catch (RuntimeException ignored) { }
    }

    static Network ownVpnNetwork(Context context) {
        if (Build.VERSION.SDK_INT < 30) return null; // Older APIs cannot verify network ownership.
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm == null) return null;
            for (Network candidate : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(candidate);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        && caps.getOwnerUid() == android.os.Process.myUid()
                        && !previousVpnNetworks.contains(candidate.getNetworkHandle())) return candidate;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    static Network currentOwnedVpnNetwork(Context context) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm == null) return null;
            for (Network candidate : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(candidate);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        && caps.getOwnerUid() == android.os.Process.myUid()) return candidate;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    static boolean awaitVpnNetworkGone(Context context, long handle) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null || handle == 0) return false;
        Network previous = Network.fromNetworkHandle(handle);
        long until = android.os.SystemClock.elapsedRealtime() + 3000;
        do {
            if (cm.getNetworkCapabilities(previous) == null) return true;
            try { Thread.sleep(50); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        } while (android.os.SystemClock.elapsedRealtime() < until);
        return cm.getNetworkCapabilities(previous) == null;
    }

    private static Set<Long> snapshotOwnedVpnNetworks(Context context) {
        Set<Long> handles = new HashSet<>();
        if (Build.VERSION.SDK_INT < 30) return handles;
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return handles;
        for (Network candidate : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(candidate);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && caps.getOwnerUid() == android.os.Process.myUid()) handles.add(candidate.getNetworkHandle());
        }
        return handles;
    }

    /**
     * Optional, real USB TestNetwork supplied by an already running system
     * helper. Android's legacy getActiveNetworkInfo() looks at a VPN's declared
     * underlying network, even when its packets travel through a file descriptor.
     * This declaration never binds the process or WireGuard transport sockets.
     */
    static Network connectivityNetwork(Context context, long expectedSession) {
        if (expectedSession == 0 || Build.VERSION.SDK_INT < 28) return null;
        Bundle offered = query(context);
        if (relaySession(offered) != expectedSession) return null;
        long handle = offered.getLong("connectivityNetwork");
        if (handle == 0) return null;
        try {
            Network candidate = Network.fromNetworkHandle(handle);
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(candidate);
            // Never declare a VPN (including ourselves) as our own underlay.
            return caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? candidate : null;
        } catch (RuntimeException ignored) { return null; }
    }

    static Network relayConnectivityNetwork(Context context) { return connectivityNetwork(context, session); }

    static Network current() { return network; }
    static boolean active() { return network != null || session != 0; }
    static boolean relayActive() { return session != 0; }
    static boolean relayStillConnected(Context context) { return session != 0 && offeredRelaySession(context) == session; }

    static void bindProtectedSocket(int descriptor) {
        try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.fromFd(descriptor)) {
            UsbWireGuardRelay currentRelay = relay;
            if (currentRelay != null) {
                java.net.SocketAddress address = Os.getsockname(duplicate.getFileDescriptor());
                if (address instanceof InetSocketAddress
                        && ((InetSocketAddress)address).getAddress() instanceof java.net.Inet4Address)
                    currentRelay.acceptBackendPort(((InetSocketAddress)address).getPort());
            } else if (network != null) network.bindSocket(duplicate.getFileDescriptor());
        } catch (Exception failure) { throw new IllegalStateException("USB Bridge socket binding failed", failure); }
    }

    static synchronized void end(Context context) {
        if (active()) report(context, "stopped");
        UsbWireGuardRelay old = relay; relay = null;
        if (old != null) old.close();
        if (network != null) context.getSystemService(ConnectivityManager.class).bindProcessToNetwork(null);
        network = null;
        session = 0;
        previousVpnNetworks = Collections.emptySet();
        splitPolicy = null;
    }
}
