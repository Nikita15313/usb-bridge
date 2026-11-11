package app.wireroute;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.config.Interface;
import com.wireguard.config.Peer;

import org.junit.Test;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicit live comparison using temporary own-UID tunnels, never saved user routing.
 * Running instrumentation stops the old app process; the caller must restore the
 * user's normal VPN afterward. This class must never run as part of a general suite.
 */
public final class InstagramWifiNetworkAudit {
    private static final long AUDIT_BUDGET_MS = 45_000L;
    private static final String HOST = "www.instagram.com";
    private static final String HTTPS_TARGET = "https://www.instagram.com/";

    @Test public void compareInheritedWifiDnsWithRuntimeVpnDns() throws Exception {
        assertEquals("Live network audit must be requested explicitly", "true",
                InstrumentationRegistry.getArguments().getString("verifyInstagramNetwork312"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Live audit must target the actual installation",
                "app.wireroute.release263", context.getPackageName());
        WireRouteApplication app = (WireRouteApplication) context.getApplicationContext();
        assertEquals("Instrumentation must have stopped the old VPN before this audit",
                TunnelController.State.DOWN, app.getTunnelController().getSnapshot().state);
        assertFalse("Normal connection must not be queued", app.getTunnelController().isConnectionDesired());
        assertNull("VPN permission must already be granted", VpnService.prepare(context));
        assertFalse("Audit requires ordinary network mode", UsbBridgeUnderlay.selected(context));

        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        assertNotNull("Android network service is required", connectivity);
        Network wifi = physicalWifi(connectivity);
        assertNotNull("Validated physical Wi-Fi must be connected", wifi);

        File profileFile = new File(context.getNoBackupFilesDir(), "profile.wrc");
        byte[] encryptedProfile = Files.readAllBytes(profileFile.toPath());
        Map<String, ?> routing = new HashMap<>(context.getSharedPreferences("routing", 0).getAll());
        Map<String, ?> profileMetadata = new HashMap<>(context.getSharedPreferences("profile_metadata", 0).getAll());
        Map<String, ?> sites = new HashMap<>(context.getSharedPreferences("site_library_v1", 0).getAll());
        Map<String, ?> legacySites = new HashMap<>(context.getSharedPreferences("wireroute_site_routes_v1", 0).getAll());
        long routingRevision = app.getRoutingPreferences().getRevision();
        long userGeneration = app.getTunnelController().getUserActionGeneration();

        Config base;
        try {
            String stored = app.getConfigStore().load();
            assertNotNull("An existing saved profile is required", stored);
            base = ConfigUtils.parse(stored);
        } catch (Exception ignored) {
            fail("Saved profile could not be read safely");
            return;
        }
        assertTrue("Comparison requires an originally empty DNS server list",
                base.getInterface().getDnsServers().isEmpty());
        assertTrue("Comparison requires full IPv4 VPN routes", ConfigUtils.inspect(base).fullIpv4);

        long deadline = SystemClock.elapsedRealtime() + AUDIT_BUDGET_MS;
        ExecutorService queries = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "instagram-wifi-audit-query");
            thread.setDaemon(true);
            return thread;
        });
        GoBackend backend = new GoBackend(new StableBackendContext(context));
        Tunnel tunnel = new AuditTunnel();
        try {
            // Populate only the endpoint object's in-memory cache before native UP.
            // No configuration text or endpoint address is ever reported.
            Future<Boolean> endpoints = queries.submit(() -> {
                for (Peer peer : base.getPeers()) {
                    if (peer.getEndpoint().isPresent()
                            && !peer.getEndpoint().get().getResolved().isPresent()) return false;
                }
                return true;
            });
            assertTrue("The existing peer endpoint must resolve before the comparison",
                    getBounded(endpoints, deadline, 5_000L, false));

            Observation legacy = runPath(context, connectivity, wifi, backend, tunnel,
                    legacyRuntime(base, context.getPackageName()), queries, deadline);
            report("LEGACY", legacy);
            Observation fixed = runPath(context, connectivity, wifi, backend, tunnel,
                    ConfigUtils.withRouting(base, RoutingMode.ONLY_SELECTED,
                            Set.of(context.getPackageName())), queries, deadline);
            report("FIXED", fixed);
            assertTrue("Runtime VPN DNS must resolve Instagram on Wi-Fi", fixed.dnsCount > 0);
            assertTrue("Instagram HTTPS must return an HTTP response on Wi-Fi",
                    fixed.httpStatus >= 100 && fixed.httpStatus <= 599);
        } finally {
            try { stopTemporaryTunnel(context, backend, tunnel); }
            finally {
                queries.shutdownNow();
                assertTrue("Encrypted saved profile must remain byte-for-byte unchanged",
                        Arrays.equals(encryptedProfile, Files.readAllBytes(profileFile.toPath())));
                assertTrue("Saved routing must remain unchanged", routing.equals(
                        context.getSharedPreferences("routing", 0).getAll()));
                assertTrue("Profile metadata must remain unchanged", profileMetadata.equals(
                        context.getSharedPreferences("profile_metadata", 0).getAll()));
                assertTrue("Saved sites must remain unchanged", sites.equals(
                        context.getSharedPreferences("site_library_v1", 0).getAll()));
                assertTrue("Legacy sites must remain unchanged", legacySites.equals(
                        context.getSharedPreferences("wireroute_site_routes_v1", 0).getAll()));
                assertEquals("Audit must not stage user routing edits", routingRevision,
                        app.getRoutingPreferences().getRevision());
                assertEquals("Audit must not issue normal controller actions", userGeneration,
                        app.getTunnelController().getUserActionGeneration());
            }
        }
    }

    /** Reproduces the pre-3.1.2 ordinary-network runtime configuration without saving it. */
    private static Config legacyRuntime(Config base, String ownPackage) throws Exception {
        Interface old = base.getInterface();
        Interface.Builder builder = new Interface.Builder().addAddresses(old.getAddresses())
                .addDnsServers(old.getDnsServers()).addDnsSearchDomains(old.getDnsSearchDomains())
                .setKeyPair(old.getKeyPair()).includeApplications(Set.of(ownPackage));
        if (old.getListenPort().isPresent()) builder.setListenPort(old.getListenPort().get());
        if (old.getMtu().isPresent()) builder.setMtu(old.getMtu().get());
        return new Config.Builder().setInterface(builder.build()).addPeers(base.getPeers()).build();
    }

    private static Observation runPath(Context context, ConnectivityManager connectivity,
            Network expectedWifi, GoBackend backend, Tunnel tunnel, Config config,
            ExecutorService queries, long deadline) throws Exception {
        Observation result = new Observation();
        long started = SystemClock.elapsedRealtime();
        try {
            assertTrue("Audit time budget must remain", remaining(deadline) >= 12_000L);
            assertEquals("Physical Wi-Fi must remain the same network",
                    expectedWifi, physicalWifi(connectivity));
            try {
                synchronized (backend) { backend.setState(tunnel, Tunnel.State.UP, config); }
            } catch (Exception ignored) {
                fail("Temporary test tunnel could not start");
            }
            // First real request starts the handshake; allow the service to publish its network.
            new CountDownLatch(1).await(Math.min(1_000L, remaining(deadline)), TimeUnit.MILLISECONDS);
            Network vpn = vpnNetwork(connectivity);
            assertNotNull("Temporary VPN must be visible to this target UID", vpn);
            NetworkCapabilities vpnCapabilities = connectivity.getNetworkCapabilities(vpn);
            assertTrue("The temporary VPN must report Wi-Fi as an underlying transport",
                    vpnCapabilities != null
                            && vpnCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));

            Future<DnsObservation> dns = queries.submit(() -> {
                try {
                    InetAddress[] addresses = vpn.getAllByName(HOST);
                    return new DnsObservation(addresses == null ? 0 : addresses.length, "NONE");
                } catch (Exception error) {
                    return new DnsObservation(0, safeType(error));
                }
            });
            DnsObservation resolved = getBounded(dns, deadline, 5_000L,
                    new DnsObservation(0, "TIMEOUT"));
            result.dnsCount = resolved.count;
            result.dnsError = resolved.error;

            AtomicReference<HttpURLConnection> openConnection = new AtomicReference<>();
            Future<HttpObservation> https = queries.submit(() -> {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) vpn.openConnection(new URL(HTTPS_TARGET));
                    openConnection.set(connection);
                    connection.setRequestMethod("HEAD");
                    connection.setInstanceFollowRedirects(false);
                    connection.setUseCaches(false);
                    connection.setConnectTimeout(5_000);
                    connection.setReadTimeout(5_000);
                    return new HttpObservation(connection.getResponseCode(), "NONE");
                } catch (Exception error) {
                    return new HttpObservation(0, safeType(error));
                } finally {
                    if (connection != null) connection.disconnect();
                }
            });
            try {
                HttpObservation reached = getBounded(https, deadline, 8_000L,
                        new HttpObservation(0, "TIMEOUT"));
                result.httpStatus = reached.status;
                result.httpError = reached.error;
            } finally {
                HttpURLConnection connection = openConnection.get();
                if (connection != null) connection.disconnect();
            }
            assertEquals("Both observations must use the same Wi-Fi network",
                    expectedWifi, physicalWifi(connectivity));
            return result;
        } finally {
            result.millis = Math.max(0L, SystemClock.elapsedRealtime() - started);
            stopTemporaryTunnel(context, backend, tunnel);
        }
    }

    private static void stopTemporaryTunnel(Context context, GoBackend backend, Tunnel tunnel) throws Exception {
        CountDownLatch lifetime = StableVpnService.currentLifetime();
        boolean stopped = true;
        try {
            synchronized (backend) { backend.setState(tunnel, Tunnel.State.DOWN, null); }
        } catch (Exception ignored) { stopped = false; }
        context.stopService(new Intent(context, StableVpnService.class));
        assertTrue("Temporary VPN service must finish before the next configuration",
                lifetime.await(3_000L, TimeUnit.MILLISECONDS));
        assertTrue("Temporary backend must stop successfully", stopped);
    }

    @SuppressWarnings("deprecation")
    private static Network physicalWifi(ConnectivityManager connectivity) {
        try {
            Network active = connectivity.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : connectivity.getNetworkCapabilities(active);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return active;
            for (Network network : connectivity.getAllNetworks()) {
                caps = connectivity.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return network;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Network vpnNetwork(ConnectivityManager connectivity) {
        try {
            Network active = connectivity.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : connectivity.getNetworkCapabilities(active);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return active;
            for (Network network : connectivity.getAllNetworks()) {
                caps = connectivity.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return network;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static <T> T getBounded(Future<T> query, long deadline, long limit, T unavailable) {
        try { return query.get(Math.min(limit, remaining(deadline)), TimeUnit.MILLISECONDS); }
        catch (Exception ignored) { query.cancel(true); return unavailable; }
    }

    private static long remaining(long deadline) {
        return Math.max(0L, deadline - SystemClock.elapsedRealtime());
    }

    private static String safeType(Throwable error) {
        String type = error.getClass().getSimpleName();
        return type.matches("[A-Za-z][A-Za-z0-9_]{0,63}") ? type : "OTHER";
    }

    private static void report(String label, Observation observation) {
        Bundle report = new Bundle();
        report.putString("instagramWifi312", label + " dns_count=" + observation.dnsCount
                + " dns_error_type=" + observation.dnsError + " http_status=" + observation.httpStatus
                + " http_error_type=" + observation.httpError + " duration_ms=" + observation.millis);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
    }

    private static final class AuditTunnel implements Tunnel {
        @Override public String getName() { return "WireRouteAudit"; }
        @Override public void onStateChange(State state) { }
    }

    private static final class Observation {
        int dnsCount;
        String dnsError = "NOT_RUN";
        int httpStatus;
        String httpError = "NOT_RUN";
        long millis;
    }

    private static final class DnsObservation {
        final int count;
        final String error;
        DnsObservation(int count, String error) { this.count = count; this.error = error; }
    }

    private static final class HttpObservation {
        final int status;
        final String error;
        HttpObservation(int status, String error) { this.status = status; this.error = error; }
    }
}
