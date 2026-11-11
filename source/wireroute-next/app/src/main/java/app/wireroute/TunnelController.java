package app.wireroute;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.wireguard.android.backend.BackendException;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.BadConfigException;
import com.wireguard.config.Config;

import app.wireroute.sites.LegacyProfileMigration;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class TunnelController {
    private static final long STATISTICS_TIMEOUT_MILLIS = 3_000L;

    public enum State {
        DOWN,
        CONNECTING,
        UP,
        DISCONNECTING,
        ERROR
    }

    public enum Failure {
        NONE,
        NO_CONFIG,
        EMPTY_SELECTION,
        PROFILE,
        DNS,
        VPN_INTERFACE,
        BACKEND,
        USB_SOURCE,
        USB_SWITCH,
        USB_PROFILE,
        UNKNOWN
    }

    public interface Listener {
        void onTunnelStateChanged(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final State state;
        public final Failure failure;

        Snapshot(State state, Failure failure) {
            this.state = state;
            this.failure = failure;
        }
    }

    /** Immutable WireGuard counters for the currently running tunnel instance. */
    public static final class TransferSnapshot {
        public final boolean available;
        public final State state;
        public final long rxBytes;
        public final long txBytes;
        public final boolean stale;
        public final long latestHandshakeEpochMillis;

        private TransferSnapshot(boolean available, State state, long rxBytes,
                                 long txBytes, boolean stale, long latestHandshakeEpochMillis) {
            this.available = available;
            this.state = state;
            this.rxBytes = Math.max(0L, rxBytes);
            this.txBytes = Math.max(0L, txBytes);
            this.stale = stale;
            this.latestHandshakeEpochMillis = Math.max(0L, latestHandshakeEpochMillis);
        }

        static TransferSnapshot unavailable(State state) {
            return new TransferSnapshot(false, state, 0L, 0L, false, 0L);
        }
    }

    private final Context context;
    private final SecureConfigStore configStore;
    private final RoutingPreferences routingPreferences;
    private final GoBackend backend;
    private final ManagedTunnel tunnel = new ManagedTunnel();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile Snapshot snapshot = new Snapshot(State.DOWN, Failure.NONE);
    private volatile String runtimeEndpointClass = "UNKNOWN";
    private boolean connectionDesired;
    private long operationGeneration;
    private long userActionGeneration;
    private long profileRevision;
    private long appliedProfileRevision;
    private RoutingMode appliedMode;
    private Set<String> appliedPackages = new HashSet<>();
    private final UsbConnectivityRefresh connectivityRefresh = new UsbConnectivityRefresh();
    private boolean plainConnectivityRefresh;

    public TunnelController(
            Context context,
            SecureConfigStore configStore,
            RoutingPreferences routingPreferences
    ) {
        this.context = context.getApplicationContext();
        this.configStore = configStore;
        this.routingPreferences = routingPreferences;
        backend = new GoBackend(new StableBackendContext(this.context));
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public String getRuntimeEndpointClass() { return runtimeEndpointClass; }

    public synchronized boolean isConnectionDesired() { return connectionDesired; }

    synchronized boolean canStartPlainUsb() { return !plainConnectivityRefresh && canStartPlainUsb(snapshot.state, connectionDesired); }

    /** Keep the user's enabled VPN while changing only its transport to this cable session. */
    boolean handoverSystemToUsb(long requestedSession) {
        // Provider and ConnectivityManager calls stay outside the controller monitor.
        long offeredSession = UsbBridgeUnderlay.offeredRelaySession(context);
        android.net.Network oldNetwork = UsbBridgeUnderlay.currentOwnedVpnNetwork(context);
        synchronized (this) {
            if (!canHandoverSystemToUsb(snapshot.state, connectionDesired,
                    UsbBridgeUnderlay.active(), requestedSession, offeredSession,
                    hasPendingConfigurationChanges())) return false;
            android.util.Log.i("WireRouteUsb", "Switch active SYSTEM VPN to USB; session=" + requestedSession);
            // Reuse the serialized stop/restart path. Its session and user-action
            // checks prevent an unplug or a newer Off action from restarting VPN.
            stopConnection(true, requestedSession, userActionGeneration,
                    oldNetwork == null ? 0 : oldNetwork.getNetworkHandle());
            return true;
        }
    }

    static boolean canHandoverSystemToUsb(State state, boolean desired, boolean usbActive,
                                         long requestedSession, long offeredSession,
                                         boolean pendingConfiguration) {
        return desired && !usbActive && !pendingConfiguration
                && (state == State.UP || state == State.CONNECTING)
                && requestedSession > 0 && requestedSession == offeredSession;
    }

    void refreshUsbConnectivity(boolean encrypted) {
        android.os.Bundle offered = UsbBridgeUnderlay.query(context);
        long usbSession = UsbBridgeUnderlay.relaySession(offered);
        long prepared = offered == null ? 0 : offered.getLong("legacyRefreshNetwork");
        if (usbSession == 0 || prepared == 0) return;
        android.net.Network current = UsbBridgeUnderlay.currentOwnedVpnNetwork(context);
        if (current == null) return;
        final long network = current.getNetworkHandle(), action, operation;
        synchronized (this) {
            boolean eligible = encrypted ? snapshot.state == State.UP && connectionDesired
                    && !hasPendingConfigurationChanges() : canStartPlainUsb() && UsbPlainVpnService.running;
            if (!connectivityRefresh.claim(usbSession, prepared, network, eligible)) return;
            action = userActionGeneration; operation = operationGeneration;
        }
        executor.execute(() -> {
            long stillOffered = UsbBridgeUnderlay.offeredRelaySession(context);
            synchronized (TunnelController.this) {
                if (!UsbConnectivityRefresh.current(usbSession, stillOffered, action, userActionGeneration)
                        || operation != operationGeneration) return;
                if (encrypted) {
                    if (snapshot.state != State.UP || !connectionDesired || hasPendingConfigurationChanges()) return;
                    android.util.Log.i("WireRouteUsb", "Renew Android VPN network once; session=" + usbSession);
                    stopConnection(true, usbSession, action, network);
                    return;
                }
                if (!canStartPlainUsb() || !UsbPlainVpnService.running) return;
                plainConnectivityRefresh = true;
            }
            boolean renewed = false;
            try {
                android.util.Log.i("WireRouteUsb", "Renew Android plain network once; session=" + usbSession);
                UsbPlainVpnService.stopForConnectivityRefresh(usbSession);
                renewed = UsbBridgeUnderlay.awaitVpnNetworkGone(context, network);
            } catch (Exception failure) {
                android.util.Log.w("WireRouteUsb", "Android network renewal failed", failure);
            } finally {
                stillOffered = UsbBridgeUnderlay.offeredRelaySession(context);
                synchronized (TunnelController.this) {
                    plainConnectivityRefresh = false;
                    if (renewed && UsbConnectivityRefresh.current(usbSession, stillOffered, action, userActionGeneration)
                            && operation == operationGeneration && canStartPlainUsb())
                        UsbPlainVpnService.startIfAvailable(context);
                }
            }
        });
    }

    static boolean canStartPlainUsb(State state, boolean desired) {
        // OFF intent precedes native teardown. A delayed bootstrap must not
        // replace the encrypted TUN until backend/service DOWN is complete.
        return state == State.DOWN && !desired;
    }

    /** Actual active-session routing, for opening a saved site in a matching browser. */
    public synchronized boolean isPackageUsingVpn(String packageName) {
        if (snapshot.state != State.UP) return false;
        return appliedMode == RoutingMode.ONLY_SELECTED
                ? appliedPackages.contains(packageName) : !appliedPackages.contains(packageName);
    }

    public synchronized long getUserActionGeneration() { return userActionGeneration; }

    void notifyUsbModeChanged() {
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onTunnelStateChanged(snapshot);
        });
    }

    public void noteProfileChanged() {
        synchronized (this) { profileRevision++; }
        // An import can finish after its Activity was replaced. Notify whichever
        // screen is currently attached, without changing state or restarting VPN.
        mainHandler.post(() -> {
            Snapshot current = snapshot;
            for (Listener listener : listeners) listener.onTunnelStateChanged(current);
        });
    }

    public synchronized boolean hasPendingConfigurationChanges() {
        return snapshot.state == State.UP && (profileRevision != appliedProfileRevision
                || appliedMode != routingPreferences.getMode()
                || !appliedPackages.equals(routingPreferences.getSelectedPackages()));
    }

    private String preResolveEndpoints(Config config) throws ControllerFailure {
        // GoBackend resolves again during UP while holding our native-handle monitor.
        // Warm its endpoint cache before starting the service: a DNS failure must not
        // make Android's main-thread onDestroy wait through the backend's retry loop.
        String classification = null;
        for (com.wireguard.config.Peer peer : config.getPeers()) {
            com.wireguard.config.InetEndpoint endpoint = peer.getEndpoint().orElse(null);
            if (endpoint == null) {
                classification = RuntimeEndpointClass.merge(classification, "UNKNOWN");
            } else {
                // Reuse the existing single resolution call; classification itself
                // is numeric-only and never performs a second DNS lookup.
                java.util.Optional<com.wireguard.config.InetEndpoint> resolved = endpoint.getResolved();
                if (!resolved.isPresent()) throw new ControllerFailure(Failure.DNS);
                classification = RuntimeEndpointClass.merge(classification,
                        RuntimeEndpointClass.classify(resolved.get().getHost()));
            }
        }
        return classification == null ? "UNKNOWN" : classification;
    }

    private void setBackendState(Tunnel.State state, Config config) throws Exception {
        // Service destruction runs on Android's main thread, outside our executor.
        // StableVpnService takes this same monitor before native handle teardown.
        java.util.concurrent.CountDownLatch lifetime = StableVpnService.currentLifetime();
        if (state == Tunnel.State.DOWN) StableVpnService.prepareForBackendStop();
        synchronized (backend) {
            backend.setState(tunnel, state, config);
            if (state == Tunnel.State.DOWN && (backend.getState(tunnel) != Tunnel.State.DOWN
                    || !backend.getRunningTunnelNames().isEmpty()))
                throw new ControllerFailure(Failure.VPN_INTERFACE);
            if (state == Tunnel.State.DOWN) runtimeEndpointClass = "UNKNOWN";
        }
        if (state == Tunnel.State.DOWN) {
            // Also cleans up a foreground service when activation failed before a TUN existed.
            context.stopService(new android.content.Intent(context, StableVpnService.class));
            // stopSelf is asynchronous. Never reconnect through a service being destroyed.
            // The main-thread onDestroy needs the backend monitor, so wait outside that lock.
            if (!lifetime.await(3_000L, TimeUnit.MILLISECONDS)) {
                throw new ControllerFailure(Failure.VPN_INTERFACE);
            }
            UsbBridgeUnderlay.end(context);
            android.util.Log.i("WireRouteUsb", "Encrypted backend DOWN; native handle released");
        }
    }

    /**
     * Reads exact counters exposed by the WireGuard backend. Call this from a worker thread;
     * the values reset when the tunnel is recreated.
     */
    public TransferSnapshot getTransferSnapshot() {
        Snapshot current = snapshot;
        if (current.state != State.UP) {
            return TransferSnapshot.unavailable(current.state);
        }
        Future<TransferSnapshot> query = executor.submit(() -> {
            Snapshot before = snapshot;
            if (before.state != State.UP) {
                return TransferSnapshot.unavailable(before.state);
            }
            try {
                // GoBackend statistics and setState share native tunnel handles.
                // Keeping both on the controller executor prevents a close/read race.
                Statistics statistics;
                synchronized (backend) {
                    statistics = backend.getStatistics(tunnel);
                }
                Snapshot after = snapshot;
                if (after.state != State.UP) {
                    return TransferSnapshot.unavailable(after.state);
                }
                long latestHandshake = 0L;
                for (com.wireguard.crypto.Key peer : statistics.peers()) {
                    Statistics.PeerStats peerStats = statistics.peer(peer);
                    if (peerStats != null) latestHandshake = Math.max(latestHandshake, peerStats.latestHandshakeEpochMillis());
                }
                return new TransferSnapshot(true, after.state, statistics.totalRx(),
                        statistics.totalTx(), statistics.isStale(), latestHandshake);
            } catch (Throwable ignored) {
                return TransferSnapshot.unavailable(snapshot.state);
            }
        });
        try {
            return query.get(STATISTICS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            query.cancel(true);
            return TransferSnapshot.unavailable(snapshot.state);
        } catch (Throwable ignored) {
            query.cancel(true);
            return TransferSnapshot.unavailable(snapshot.state);
        }
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onTunnelStateChanged(snapshot);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized void connect() {
        if (snapshot.state == State.CONNECTING || snapshot.state == State.UP) {
            return;
        }
        connectionDesired = true;
        userActionGeneration++;
        if (snapshot.state == State.DISCONNECTING) return;
        startConnection();
    }

    private synchronized void startConnection() {
        startConnection(0);
    }

    private synchronized void startConnection(long requiredUsbSession) {
        if (!connectionDesired) return;
        // The old encrypted overlay is the only proof of the original profile.
        // Resolve it before permitting any new connection or profile replacement.
        if (LegacyProfileMigration.hasPendingRecovery(context)) {
            update(State.ERROR, Failure.PROFILE);
            return;
        }
        if (!configStore.hasConfig()) {
            update(State.ERROR, Failure.NO_CONFIG);
            return;
        }
        update(State.CONNECTING, Failure.NONE);
        final long generation = ++operationGeneration;
        executor.execute(() -> {
            try {
                if (!operationCurrent(generation)) return;
                final long sourceRevision;
                synchronized (TunnelController.this) { sourceRevision = profileRevision; }
                String rawConfig = configStore.load();
                if (rawConfig == null) {
                    throw new ControllerFailure(Failure.NO_CONFIG);
                }

                final boolean usb;
                try { usb = UsbBridgeUnderlay.begin(context, requiredUsbSession); }
                catch (Exception failure) { throw new ControllerFailure(Failure.USB_SOURCE); }
                if (usb) {
                    // This may stop an active hotspot datapath. Wait on the
                    // worker, before replacing the still-working plain TUN.
                    try { UsbBridgeUnderlay.beginVpnIntent(context); }
                    catch (Exception failure) { throw new ControllerFailure(Failure.USB_SWITCH); }
                    UsbPlainVpnService.stopForVpn();
                }
                RoutingMode mode = routingPreferences.getMode();
                Set<String> selectedPackages = routingPreferences.getSelectedPackages();
                Set<String> installedPackages = filterInstalled(selectedPackages);
                if (mode == RoutingMode.ONLY_SELECTED
                        && installedPackages.isEmpty()) {
                    throw new ControllerFailure(Failure.EMPTY_SELECTION);
                }

                // Transport belongs to the imported profile. The official backend
                // supplies its own defaults for absent MTU/keepalive/listen port.
                Config base = ConfigUtils.parse(rawConfig);
                SessionDiagnostics.get(context).notePublicKey(base.getInterface().getKeyPair().getPublicKey().toBase64());
                if (usb && !ConfigUtils.supportsUsb(base))
                    throw new ControllerFailure(Failure.USB_PROFILE);
                Config runtimeConfig = ConfigUtils.withRouting(base,
                        usb ? RoutingMode.BYPASS_SELECTED : mode,
                        usb ? java.util.Set.of("local.usbbridge", "com.android.shell", context.getPackageName()) : installedPackages,
                        usb);
                if (usb) {
                    if (android.os.Build.VERSION.SDK_INT < 29) throw new ControllerFailure(Failure.USB_PROFILE);
                    UsbBridgeUnderlay.configureRouting(context, mode, installedPackages);
                    try { runtimeConfig = UsbBridgeUnderlay.adaptRuntime(context, runtimeConfig, this::usbConnectionLost); }
                    catch (Exception failure) { throw new ControllerFailure(Failure.USB_SOURCE); }
                }
                String endpointClass = preResolveEndpoints(runtimeConfig);
                if (!operationCurrent(generation)) return;
                runtimeEndpointClass = endpointClass;
                setBackendState(Tunnel.State.UP, runtimeConfig);
                if(usb)UsbBridgeUnderlay.report(context,"started");
                if (!publishUpIfCurrent(generation, mode, selectedPackages, sourceRevision)) {
                    setBackendState(Tunnel.State.DOWN, null);
                    return;
                }
            } catch (Throwable error) {
                // Publish the real failure before cleanup. A service-destroyed
                // callback caused by that cleanup must not replace it with DOWN.
                Failure failure = classify(error);
                publishErrorIfCurrent(generation, failure);
                boolean backendStopped = false;
                try {
                    setBackendState(Tunnel.State.DOWN, null);
                    backendStopped = true;
                } catch (Throwable ignored) {
                    // Best effort cleanup. The original, safely classified error is more useful.
                }
                if (requiredUsbSession > 0 && backendStopped
                        && (failure == Failure.USB_SOURCE || failure == Failure.USB_SWITCH)) {
                    // A cable can disappear after the stop worker checked its session
                    // but before begin() attaches the new USB relay. No relay observer
                    // exists then. Release the stale enabled intent so the Bridge's
                    // next plain bootstrap can recover, without selecting mobile data.
                    long offeredSession = UsbBridgeUnderlay.offeredRelaySession(context);
                    synchronized (TunnelController.this) {
                        if (shouldCancelPinnedUsbStart(failure, requiredUsbSession, offeredSession,
                                generation, operationGeneration, backendStopped)) {
                            connectionDesired = false;
                            update(State.DOWN, Failure.NONE);
                            android.util.Log.i("WireRouteUsb", "Cancel ended USB handover; session="
                                    + requiredUsbSession);
                        }
                    }
                }
            }
        });
    }

    static boolean shouldCancelPinnedUsbStart(Failure failure, long requiredSession,
                                              long offeredSession, long expectedGeneration,
                                              long currentGeneration, boolean backendStopped) {
        return backendStopped && (failure == Failure.USB_SOURCE || failure == Failure.USB_SWITCH)
                && requiredSession > 0
                && requiredSession != offeredSession && expectedGeneration == currentGeneration;
    }

    public synchronized void disconnect() {
        connectionDesired = false;
        userActionGeneration++;
        if (snapshot.state == State.DOWN || snapshot.state == State.DISCONNECTING) {
            return;
        }
        stopConnection();
    }

    private synchronized void stopConnection() {
        stopConnection(true);
    }

    private synchronized void stopConnection(boolean restorePlainUsb) {
        stopConnection(restorePlainUsb, 0, 0, 0);
    }

    private synchronized void stopConnection(boolean restorePlainUsb, long refreshSession,
                                            long refreshAction, long oldNetwork) {
        final long generation = ++operationGeneration;
        update(State.DISCONNECTING, Failure.NONE);
        executor.execute(() -> {
            boolean stopped = true;
            try {
                setBackendState(Tunnel.State.DOWN, null);
                if (oldNetwork != 0 && !UsbBridgeUnderlay.awaitVpnNetworkGone(context, oldNetwork))
                    throw new ControllerFailure(Failure.VPN_INTERFACE);
            } catch (Throwable ignored) {
                stopped = false;
            }
            long offeredSession = refreshSession == 0 ? 0 : UsbBridgeUnderlay.offeredRelaySession(context);
            synchronized (TunnelController.this) {
                if (generation != operationGeneration) return;
                // A refresh cannot fall back to the phone after its USB session ended.
                // A newer explicit user action retains the normal controller behavior.
                if (refreshSession != 0 && refreshAction == userActionGeneration
                        && refreshSession != offeredSession) connectionDesired = false;
                update(stopped ? State.DOWN : State.ERROR,
                        stopped ? Failure.NONE : Failure.VPN_INTERFACE);
                if (stopped && connectionDesired) {
                    // Keep an automatic renewal on its original cable session.
                    // An explicit newer Connect action retains normal source selection.
                    startConnection(refreshAction == userActionGeneration ? refreshSession : 0);
                }
                else if (stopped && restorePlainUsb) UsbPlainVpnService.startIfAvailable(context);
            }
        });
    }

    /** A lost USB transport must not silently retry via the phone's mobile data. */
    void usbConnectionLost() {
        synchronized (this) {
            if (!UsbBridgeUnderlay.active() || snapshot.state == State.DISCONNECTING || snapshot.state == State.DOWN) return;
            connectionDesired = false;
            stopConnection(false);
        }
    }

    /** Called only by the explicit Apply action; editing never interrupts a session. */
    public synchronized boolean applyRoutingChanges() {
        if (!connectionDesired || snapshot.state != State.UP) return false;
        userActionGeneration++;
        stopConnection();
        return true;
    }

    private synchronized boolean operationCurrent(long generation) {
        return generation == operationGeneration;
    }

    private synchronized boolean publishUpIfCurrent(long generation, RoutingMode mode,
            Set<String> packages, long sourceRevision) {
        if (!canPublishUp(generation, operationGeneration, connectionDesired)) {
            return false;
        }
        // Generation check and visible UP publication are one critical section
        // with onStateChange(DOWN) and a manual disconnect.
        appliedMode = mode;
        appliedPackages = new HashSet<>(packages);
        appliedProfileRevision = sourceRevision;
        update(State.UP, Failure.NONE);
        return true;
    }

    private synchronized void publishErrorIfCurrent(long generation, Failure failure) {
        if (generation == operationGeneration) {
            update(State.ERROR, failure);
        }
    }

    static boolean canPublishUp(long expectedGeneration, long currentGeneration,
                                boolean mayStart) {
        return mayStart && expectedGeneration == currentGeneration;
    }

    static boolean unexpectedBackendDownNeedsInvalidation(State state) {
        return state == State.UP || state == State.CONNECTING;
    }

    private Set<String> filterInstalled(Set<String> selected) {
        PackageManager packageManager = context.getPackageManager();
        Set<String> installed = new HashSet<>();
        for (String packageName : selected) {
            if (packageName == null || packageName.equals(context.getPackageName())) {
                continue;
            }
            try {
                packageManager.getApplicationInfo(packageName, 0);
                installed.add(packageName);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Removed applications must not prevent Android from creating the VPN interface.
            }
        }
        return installed;
    }

    private Failure classify(Throwable error) {
        if (error instanceof ControllerFailure) {
            return ((ControllerFailure) error).failure;
        }
        if (error instanceof ConfigUtils.EmptySelectionException) {
            return Failure.EMPTY_SELECTION;
        }
        if (error instanceof BadConfigException) {
            return Failure.PROFILE;
        }
        if (error instanceof BackendException) {
            String reason = ((BackendException) error).getReason().name();
            if (reason.contains("DNS")) {
                return Failure.DNS;
            }
            if (reason.contains("TUN_CREATION")
                    || reason.contains("VPN_NOT_AUTHORIZED")
                    || reason.contains("UNABLE_TO_START_VPN")) {
                return Failure.VPN_INTERFACE;
            }
            return Failure.BACKEND;
        }
        if (error instanceof java.security.GeneralSecurityException
                || error instanceof java.io.IOException) {
            return Failure.PROFILE;
        }
        return Failure.UNKNOWN;
    }

    private void update(State state, Failure failure) {
        Snapshot next = new Snapshot(state, failure);
        snapshot = next;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onTunnelStateChanged(next);
            }
        });
    }

    private final class ManagedTunnel implements Tunnel {
        @Override
        public String getName() {
            return "WireRoute";
        }

        @Override
        public void onStateChange(Tunnel.State newState) {
            if (newState != Tunnel.State.DOWN) return;
            synchronized (TunnelController.this) {
                if (unexpectedBackendDownNeedsInvalidation(snapshot.state)) {
                    // Invalidate an in-flight connect so it cannot publish UP
                    // after Android has already destroyed the VpnService.
                    operationGeneration++;
                    connectionDesired = false;
                    update(TunnelController.State.DOWN, Failure.NONE);
                }
            }
        }
    }

    private static final class ControllerFailure extends Exception {
        final Failure failure;

        ControllerFailure(Failure failure) {
            this.failure = failure;
        }
    }
}
