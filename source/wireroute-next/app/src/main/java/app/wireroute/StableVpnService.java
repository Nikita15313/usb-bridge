package app.wireroute;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import com.wireguard.android.backend.GoBackend;
import app.wireroute.release263.R;
import app.wireroute.release263.BuildConfig;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** A visible, long-running VPN service. Observations never trigger a tunnel restart. */
public final class StableVpnService extends GoBackend.VpnService {
    private static final String CHANNEL = "wireguard_connection";
    private static final int NOTIFICATION_ID = 294;
    private static final String ACTION_STOP = "app.wireroute.STOP_WIREGUARD";
    private static volatile CountDownLatch currentLifetime = new CountDownLatch(0);
    private static volatile StableVpnService currentService;
    private final CountDownLatch lifetime = new CountDownLatch(1);
    private final ScheduledExecutorService observer = Executors.newSingleThreadScheduledExecutor();
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ConnectivityManager.NetworkCallback vpnNetworkCallback;
    private final AtomicBoolean usbReportQueued = new AtomicBoolean();
    private TunnelController controller;
    private TunnelController.Listener stateListener;
    private volatile GoBackend backendOwner;
    private volatile long reconnectingToHandle;
    private volatile long declaredConnectivityHandle = Long.MIN_VALUE;
    private long serviceStartedElapsedMillis;
    private volatile DumpObservation dumpObservation;
    private volatile UsbSplitTunnel usbSplit;
    // Counts describe calls on the latest normal Builder; no route/IP values.
    private volatile int stationNormalRouteAdds;
    private volatile int stationNormalRouteSubstitutions;
    private volatile boolean stationNormalRouteExcluded;
    private volatile String stationNormalRoutePolicy = "OFF";

    @Override public Builder getBuilder() {
        if (!UsbBridgeUnderlay.relayActive()) {
            stationNormalRouteAdds = 0;
            stationNormalRouteSubstitutions = 0;
            stationNormalRouteExcluded = false;
            stationNormalRoutePolicy = BuildConfig.STATION_ROUTE_SUBTRACTION_ENABLED ? "CIDR_COMPLEMENT" : "OFF";
            if (!BuildConfig.STATION_ROUTE_SUBTRACTION_ENABLED) return super.getBuilder();
            return new Builder() {
                @Override public Builder addRoute(java.net.InetAddress address, int prefixLength) {
                    StationRoutePolicy.Plan plan = StationRoutePolicy.subtract(address, prefixLength);
                    // addRoute implicitly enables the address family. Preserve
                    // that side effect even when the sole Station/32 vanishes.
                    if (plan.preserveIpv4Family) super.allowFamily(android.system.OsConstants.AF_INET);
                    for (StationRoutePolicy.Route route : plan.routes) {
                        super.addRoute(route.address, route.prefix);
                        stationNormalRouteAdds++;
                    }
                    stationNormalRouteExcluded |= plan.stationExcluded;
                    if (plan.stationExcluded) stationNormalRouteSubstitutions++;
                    return this;
                }
            };
        }
        return new Builder() {
            private int packetMtu = 1280;
            @Override public Builder setMtu(int mtu) {
                super.setMtu(mtu);
                packetMtu = mtu;
                return this;
            }
            @Override public android.os.ParcelFileDescriptor establish() {
                android.os.ParcelFileDescriptor tun = super.establish();
                if (tun == null) return null;
                try {
                    if (Build.VERSION.SDK_INT < 29)
                        throw new java.io.IOException("USB app routing requires Android 10");
                    UsbSplitPolicy policy = UsbBridgeUnderlay.splitPolicy();
                    if (policy == null) throw new java.io.IOException("USB app routing missing");
                    usbSplit = new UsbSplitTunnel(StableVpnService.this, tun, policy, packetMtu,
                            () -> ((WireRouteApplication)getApplication()).getTunnelController().usbConnectionLost());
                    return usbSplit.backendDescriptor();
                } catch (Exception failure) {
                    try { tun.close(); } catch (java.io.IOException ignored) { }
                    throw new IllegalStateException("USB app routing failed", failure);
                }
            }
        };
    }

    private static final class DumpObservation {
        final TunnelController.TransferSnapshot transfer;
        final long sampledElapsedMillis;

        DumpObservation(TunnelController.TransferSnapshot transfer) {
            this.transfer = transfer;
            sampledElapsedMillis = SystemClock.elapsedRealtime();
        }
    }

    static CountDownLatch currentLifetime() { return currentLifetime; }

    static void prepareForBackendStop() {
        StableVpnService service = currentService;
        if (service != null) service.closeSplitRoute();
    }

    private synchronized void closeSplitRoute() {
        UsbSplitTunnel split = usbSplit; usbSplit = null;
        if (split != null) split.close();
    }

    @Override public void setOwner(GoBackend owner) {
        backendOwner = owner;
        super.setOwner(owner);
    }

    @Override public boolean protect(int socket) {
        if (socket < 0) return false;
        boolean protectedSocket = super.protect(socket);
        if (UsbBridgeUnderlay.active()) {
            if (!protectedSocket) throw new IllegalStateException("Android refused VPN socket protection");
            UsbBridgeUnderlay.bindProtectedSocket(socket);
            if (UsbBridgeUnderlay.current() != null)
                super.setUnderlyingNetworks(new Network[]{UsbBridgeUnderlay.current()});
        }
        return protectedSocket;
    }

    @Override public boolean setUnderlyingNetworks(Network[] networks) {
        Network usb = UsbBridgeUnderlay.current();
        if (usb != null) return super.setUnderlyingNetworks(new Network[]{usb});
        if (UsbBridgeUnderlay.relayActive()) {
            // Declaring the helper TestNetwork makes Android copy TRANSPORT_TEST
            // onto this VPN. Tethering then mistakes the VPN for its test upstream.
            // Null preserves the UID default installed by the Bridge helper;
            // an empty array would explicitly report no legacy connectivity.
            boolean applied = super.setUnderlyingNetworks(null);
            if (applied) declaredConnectivityHandle = 0;
            return applied;
        }
        return super.setUnderlyingNetworks(networks);
    }

    private void refreshConnectivityDeclaration() {
        if (!UsbBridgeUnderlay.relayActive()) return;
        if (declaredConnectivityHandle != 0 && super.setUnderlyingNetworks(null))
            declaredConnectivityHandle = 0;
    }

    @Override public void onCreate() {
        currentService = this;
        serviceStartedElapsedMillis = SystemClock.elapsedRealtime();
        // Complete GoBackend's service future only after foreground promotion succeeds.
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Подключение VPN",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Постоянная работа WireRoute и быстрое отключение VPN");
            channel.setShowBadge(false);
            notifications.createNotificationChannel(channel);
        }
        Notification notification = notification("VPN подключается");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        currentLifetime = lifetime;
        super.onCreate();
        controller = ((WireRouteApplication) getApplication()).getTunnelController();
        stateListener = ignored -> {
            TunnelController.State state = controller.getSnapshot().state;
            String text = state == TunnelController.State.UP ? (UsbBridgeUnderlay.active() ? "VPN через USB · выбранные приложения" : "VPN включён · выбранные маршруты активны")
                    : state == TunnelController.State.DISCONNECTING ? "Отключение VPN"
                    : "Подключение VPN";
            notifications.notify(NOTIFICATION_ID, notification(text));
            if (state == TunnelController.State.UP) reportUsbNetworkSoon();
        };
        controller.addListener(stateListener);
        SessionDiagnostics.get(this).noteEvent("SERVICE_STARTED");
        observeUnderlyingNetwork();
        observeVpnNetwork();
        observer.scheduleWithFixedDelay(this::observeTunnel, 0, 5, TimeUnit.SECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            controller.disconnect();
        }
        // Android must not recreate an empty VPN service with a misleading persistent icon.
        return START_NOT_STICKY;
    }

    private void observeTunnel() {
        try {
            if (UsbBridgeUnderlay.relayActive() && !UsbBridgeUnderlay.relayStillConnected(this)) {
                controller.usbConnectionLost();
                return;
            }
            refreshConnectivityDeclaration();
            controller.refreshUsbConnectivity(true);
            Network bound = UsbBridgeUnderlay.current();
            if (bound != null && controller.getSnapshot().state == TunnelController.State.UP) {
                Bundle offered = UsbBridgeUnderlay.query(this);
                long replacement = offered == null ? 0 : offered.getLong("network");
                if (offered != null && offered.getBoolean("selected")
                        && offered.getBoolean("connected") && replacement != 0
                        && replacement != bound.getNetworkHandle()
                        && reconnectingToHandle != replacement) {
                    reconnectingToHandle = replacement;
                    // The old test network is gone after USB Bridge recreates
                    // its channel. Keep the explicit USB choice and rebuild
                    // the VPN against the newly offered network.
                    controller.applyRoutingChanges();
                    return;
                }
            }
            TunnelController.TransferSnapshot transfer = controller.getTransferSnapshot();
            dumpObservation = new DumpObservation(transfer);
            if(transfer.state==TunnelController.State.UP)UsbBridgeUnderlay.report(this,"alive");
            SessionDiagnostics.get(this).sample(transfer.state.name(), transfer.available,
                    transfer.stale, transfer.rxBytes, transfer.txBytes, transfer.latestHandshakeEpochMillis);
        } catch (RuntimeException ignored) {
            // A temporary status failure must not affect the tunnel.
        }
    }

    /** System DUMP hook: reads cached counters only, so the main thread never waits for native locks. */
    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        TunnelController current = controller;
        DumpObservation observation = dumpObservation;
        TunnelController.TransferSnapshot transfer = observation == null ? null : observation.transfer;
        writer.println("WireRouteVpn pid=" + Process.myPid()
                + " state=" + (current == null ? "UNKNOWN" : current.getSnapshot().state.name())
                + " service_started_elapsed_ms=" + serviceStartedElapsedMillis
                + " underlay_mode=" + (UsbBridgeUnderlay.active() ? "USB" : "SYSTEM")
                + " runtime_endpoint_class=" + (current == null ? "UNKNOWN" : current.getRuntimeEndpointClass())
                + " normal_route_add_calls=" + stationNormalRouteAdds
                + " normal_route_policy=" + stationNormalRoutePolicy
                + " normal_route_substitutions=" + stationNormalRouteSubstitutions
                + " station_route_excluded=" + stationNormalRouteExcluded);
        Network ownedVpn = UsbBridgeUnderlay.ownVpnNetwork(this);
        writer.println("vpn_network=" + (ownedVpn == null ? 0 : ownedVpn.getNetworkHandle())
                + " connectivity_network=" + declaredConnectivityHandle);
        UsbSplitTunnel split = usbSplit;
        if (split != null) writer.println(split.diagnostic());
        writer.println("sample_state=" + (transfer == null ? "UNKNOWN" : transfer.state.name())
                + " available=" + (transfer != null && transfer.available)
                + " stale=" + (transfer != null && transfer.stale)
                + " observation_age_ms=" + (observation == null ? -1L
                    : Math.max(0L, SystemClock.elapsedRealtime() - observation.sampledElapsedMillis)));
        boolean available = transfer != null && transfer.available;
        String handshakeAge = available && transfer.latestHandshakeEpochMillis > 0L
                ? Long.toString(Math.max(0L, System.currentTimeMillis()
                    - transfer.latestHandshakeEpochMillis) / 1_000L) : "unknown";
        writer.println("rx_bytes=" + (available ? Long.toString(transfer.rxBytes) : "unknown")
                + " tx_bytes=" + (available ? Long.toString(transfer.txBytes) : "unknown")
                + " handshake_age_s=" + handshakeAge);
    }

    private void observeUnderlyingNetwork() {
        connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                // Observe physical uplinks independently of the selected app-routing mode.
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return;
                String transport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "WIFI"
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "CELLULAR"
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ETHERNET" : "OTHER";
                SessionDiagnostics.get(StableVpnService.this).noteUnderlay("CAPABILITIES", transport,
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            }
            @Override public void onLost(Network network) {
                SessionDiagnostics.get(StableVpnService.this).noteUnderlay("LOST", "NONE", false, false);
            }
        };
        try { connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback); }
        catch (RuntimeException ignored) { networkCallback = null; }
    }

    /** Publish the actual VPN handle as soon as Android creates it, rather than
     * leaving hotspot startup waiting for the five-second diagnostic poll. */
    private void observeVpnNetwork() {
        if (connectivity == null || Build.VERSION.SDK_INT < 30) return;
        vpnNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps.getOwnerUid() == Process.myUid()) reportUsbNetworkSoon();
            }
            @Override public void onLost(Network network) { reportUsbNetworkSoon(); }
        };
        try {
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), vpnNetworkCallback);
        } catch (RuntimeException ignored) {
            // The existing observer remains a fallback on restricted firmware.
            vpnNetworkCallback = null;
        }
    }

    private void reportUsbNetworkSoon() {
        if (!usbReportQueued.compareAndSet(false, true)) return;
        try {
            observer.execute(() -> {
                // Release before reading: a network event racing this report
                // must be able to queue the newer handle behind it.
                usbReportQueued.set(false);
                if (currentService != this || controller == null
                        || controller.getSnapshot().state != TunnelController.State.UP
                        || !UsbBridgeUnderlay.relayActive()) return;
                // Binder calls and network queries stay off Android's callback
                // and UI threads. report() rechecks the session under its lock.
                UsbBridgeUnderlay.report(this, "alive");
            });
        } catch (RejectedExecutionException stopped) { usbReportQueued.set(false); }
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, StableVpnService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_vpn_notification)
                .setContentTitle("WireRoute — VPN")
                .setContentText(text).setContentIntent(open).setOngoing(true)
                .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "Отключить", stop).build()).build();
    }

    @Override public void onDestroy() {
        closeSplitRoute();
        observer.shutdownNow();
        if (controller != null && stateListener != null) controller.removeListener(stateListener);
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) { }
        }
        if (connectivity != null && vpnNetworkCallback != null) {
            try { connectivity.unregisterNetworkCallback(vpnNetworkCallback); }
            catch (RuntimeException ignored) { }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        try {
            GoBackend owner = backendOwner;
            if (owner == null) super.onDestroy();
            else synchronized (owner) { super.onDestroy(); }
            SessionDiagnostics.get(this).noteEvent("SERVICE_STOPPED");
        } finally {
            UsbBridgeUnderlay.end(this);
            if (currentService == this) currentService = null;
            lifetime.countDown();
        }
    }
}
