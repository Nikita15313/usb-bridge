package app.wireroute.diagnostics;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/** Small, bounded network test that can address Android's direct and VPN networks separately. */
public final class NetworkTestEngine {
    private static final String LOG_TAG = "WireRouteSpeed";
    private static final String LATENCY_URL = "https://speed.cloudflare.com/__down?bytes=0";
    private static final String DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=";
    private static final String UPLOAD_URL = "https://speed.cloudflare.com/__up";
    private static final int DOWNLOAD_BYTES = 2_000_000;
    private static final int UPLOAD_BYTES = 512_000;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int LATENCY_SAMPLES = 4;

    public enum Path { DIRECT, VPN }

    public interface ProgressListener {
        void onProgress(String message);
    }

    private final ConnectivityManager connectivityManager;
    private volatile boolean cancelled;
    private volatile HttpURLConnection activeConnection;

    public NetworkTestEngine(Context context) {
        connectivityManager = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    public PathResult measure(Path path, ProgressListener listener) {
        cancelled = false;
        Network network = findNetwork(path);
        if (network == null) {
            return PathResult.unavailable(path, path == Path.VPN
                    ? "WireRoute сейчас не включён"
                    : "Нет прямого подключения к интернету");
        }

        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        String transport = transportName(capabilities);
        List<Double> latencySamples = new ArrayList<>();
        TraceIdentity identity = TraceIdentity.empty();
        try {
            notify(listener, path, "Проверяю отклик…");
            // The first request includes DNS/TLS setup and is intentionally a warm-up,
            // otherwise it inflates jitter on a healthy connection.
            identity = latencyProbe(network);
            for (int index = 0; index < LATENCY_SAMPLES; index++) {
                ensureRunning();
                long started = System.nanoTime();
                TraceIdentity sampleIdentity = latencyProbe(network);
                latencySamples.add((System.nanoTime() - started) / 1_000_000.0);
                if (identity.ip.isEmpty()) identity = sampleIdentity;
            }

            notify(listener, path, "Проверяю загрузку…");
            double downloadMbps = measureDownload(network);
            notify(listener, path, "Проверяю отдачу…");
            double uploadMbps = measureUpload(network);

            double latency = median(latencySamples);
            double jitter = averageDifference(latencySamples);
            return PathResult.available(path, transport, latency, jitter,
                    downloadMbps, uploadMbps, identity.ip, identity.country, identity.colo);
        } catch (Exception error) {
            Log.w(LOG_TAG, "Speed check failed for " + path + ": "
                    + error.getClass().getSimpleName() + ": " + error.getMessage(), error);
            if (cancelled || Thread.currentThread().isInterrupted()) {
                return PathResult.unavailable(path, "Проверка остановлена");
            }
            return PathResult.unavailable(path, friendlyError(error));
        } finally {
            activeConnection = null;
        }
    }

    public NetworkSnapshot inspect() {
        if (connectivityManager == null) {
            return new NetworkSnapshot(false, false, false, "Нет подключения",
                    "Не удалось получить состояние сети", false, "");
        }
        Network active = connectivityManager.getActiveNetwork();
        NetworkCapabilities activeCapabilities = active == null
                ? null : connectivityManager.getNetworkCapabilities(active);
        boolean internet = activeCapabilities != null
                && activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean validated = activeCapabilities != null
                && activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean vpn = findNetwork(Path.VPN) != null;
        String transport = transportName(activeCapabilities);
        LinkProperties properties = active == null ? null : connectivityManager.getLinkProperties(active);
        boolean privateDns = Build.VERSION.SDK_INT >= 28
                && properties != null && properties.isPrivateDnsActive();
        String privateDnsName = Build.VERSION.SDK_INT < 28 || properties == null
                || properties.getPrivateDnsServerName() == null
                ? "" : properties.getPrivateDnsServerName();
        String state;
        if (!internet) state = "Интернет не найден";
        else if (!validated) state = "Сеть есть, но доступ не подтверждён";
        else state = vpn ? "VPN-интерфейс доступен" : "Обычная сеть доступна";
        return new NetworkSnapshot(internet, validated, vpn, state, transport,
                privateDns, privateDnsName);
    }

    private Network findNetwork(Path path) {
        if (connectivityManager == null) return null;
        Network active = connectivityManager.getActiveNetwork();
        NetworkCapabilities activeCapabilities = active == null
                ? null : connectivityManager.getNetworkCapabilities(active);
        if (matchesPath(path, activeCapabilities)) return active;
        Network best = null;
        for (Network network : connectivityManager.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (!matchesPath(path, capabilities)) continue;
            if (best == null) best = network;
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return network;
            }
        }
        return best;
    }

    private static boolean matchesPath(Path path, NetworkCapabilities capabilities) {
        if (capabilities == null
                || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false;
        }
        boolean isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        return (path == Path.VPN) == isVpn;
    }

    private TraceIdentity latencyProbe(Network network) throws IOException {
        HttpsURLConnection connection = open(network,
                LATENCY_URL + "&wr=" + System.nanoTime());
        try {
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) throw new IOException("HTTP " + response);
            ensureRunning();
            try (InputStream ignored = connection.getInputStream()) {
                // A zero-byte response measures time to first byte without spending traffic.
            }
            return new TraceIdentity(safeHeader(connection, "cf-meta-ip"),
                    firstHeader(connection, "cf-meta-country", "country"),
                    firstHeader(connection, "cf-meta-colo", "colo"));
        } finally {
            connection.disconnect();
        }
    }

    private static String safeHeader(HttpURLConnection connection, String name) {
        String value = connection.getHeaderField(name);
        return value == null ? "" : value.trim();
    }

    private static String firstHeader(HttpURLConnection connection, String first, String second) {
        String value = safeHeader(connection, first);
        return value.isEmpty() ? safeHeader(connection, second) : value;
    }

    private double measureDownload(Network network) throws IOException {
        HttpsURLConnection connection = open(network,
                DOWNLOAD_URL + DOWNLOAD_BYTES + "&wr=" + System.nanoTime());
        connection.setRequestProperty("Accept-Encoding", "identity");
        long started = System.nanoTime();
        long bytes = 0;
        try {
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) throw new IOException("HTTP " + response);
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    ensureRunning();
                    if (read > 0) bytes += read;
                }
            }
        } finally {
            connection.disconnect();
        }
        return mbps(bytes, System.nanoTime() - started);
    }

    private double measureUpload(Network network) throws IOException {
        HttpsURLConnection connection = open(network, UPLOAD_URL + "?wr=" + System.nanoTime());
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(UPLOAD_BYTES);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        byte[] buffer = new byte[32 * 1024];
        int remaining = UPLOAD_BYTES;
        long started = System.nanoTime();
        try {
            try (OutputStream output = connection.getOutputStream()) {
                while (remaining > 0) {
                    ensureRunning();
                    int count = Math.min(remaining, buffer.length);
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) throw new IOException("HTTP " + response);
            try (InputStream ignored = connection.getInputStream()) {
                // Opening and closing the response completes the request without retaining data.
            }
        } finally {
            connection.disconnect();
        }
        return mbps(UPLOAD_BYTES, System.nanoTime() - started);
    }

    private HttpsURLConnection open(Network network, String address) throws IOException {
        URLConnection raw = network.openConnection(new URL(address));
        if (!(raw instanceof HttpsURLConnection)) throw new IOException("Небезопасное соединение");
        HttpsURLConnection connection = (HttpsURLConnection) raw;
        activeConnection = connection;
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", "WireRoute/2.7 speed-check");
        return connection;
    }

    private void ensureRunning() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
    }

    private static void notify(ProgressListener listener, Path path, String message) {
        if (listener != null) listener.onProgress((path == Path.VPN ? "WireRoute: " : "Напрямую: ") + message);
    }

    static TraceIdentity parseTrace(String text) {
        String ip = "";
        String country = "";
        String colo = "";
        if (text != null) {
            for (String line : text.split("\\n")) {
                int separator = line.indexOf('=');
                if (separator <= 0) continue;
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if ("ip".equals(key)) ip = value;
                else if ("loc".equals(key)) country = value;
                else if ("colo".equals(key)) colo = value;
            }
        }
        return new TraceIdentity(ip, country, colo);
    }

    static double median(List<Double> values) {
        if (values == null || values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0
                : sorted.get(middle);
    }

    private static double averageDifference(List<Double> values) {
        if (values.size() < 2) return 0.0;
        double total = 0.0;
        for (int i = 1; i < values.size(); i++) total += Math.abs(values.get(i) - values.get(i - 1));
        return total / (values.size() - 1);
    }

    private static double mbps(long bytes, long nanos) throws IOException {
        if (bytes <= 0 || nanos <= 0) throw new IOException("Нет данных для замера");
        return (bytes * 8.0) / (nanos / 1_000_000_000.0) / 1_000_000.0;
    }

    private static String friendlyError(Exception error) {
        String message = error.getMessage();
        if (message != null && message.startsWith("HTTP ")) return "Сервис проверки ответил с ошибкой";
        return "Не удалось проверить этот путь";
    }

    private static String transportName(NetworkCapabilities capabilities) {
        if (capabilities == null) return "Подключение не определено";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "WireRoute";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi‑Fi";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Мобильная сеть";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Кабельная сеть";
        return "Сеть Android";
    }

    private static final class TraceIdentity {
        final String ip;
        final String country;
        final String colo;

        TraceIdentity(String ip, String country, String colo) {
            this.ip = ip;
            this.country = country;
            this.colo = colo;
        }

        static TraceIdentity empty() { return new TraceIdentity("", "", ""); }
    }

    public static final class PathResult {
        public final Path path;
        public final boolean available;
        public final String error;
        public final String transport;
        public final double latencyMs;
        public final double jitterMs;
        public final double downloadMbps;
        public final double uploadMbps;
        public final String ip;
        public final String country;
        public final String point;

        private PathResult(Path path, boolean available, String error, String transport,
                           double latencyMs, double jitterMs, double downloadMbps,
                           double uploadMbps, String ip, String country, String point) {
            this.path = path;
            this.available = available;
            this.error = error;
            this.transport = transport;
            this.latencyMs = latencyMs;
            this.jitterMs = jitterMs;
            this.downloadMbps = downloadMbps;
            this.uploadMbps = uploadMbps;
            this.ip = ip;
            this.country = country;
            this.point = point;
        }

        static PathResult available(Path path, String transport, double latencyMs,
                                    double jitterMs, double downloadMbps, double uploadMbps,
                                    String ip, String country, String point) {
            return new PathResult(path, true, "", transport, latencyMs, jitterMs,
                    downloadMbps, uploadMbps, ip, country, point);
        }

        static PathResult unavailable(Path path, String error) {
            return new PathResult(path, false, error, "", Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, "", "", "");
        }

        public String format() {
            if (!available) return error;
            String route = country.isEmpty() ? transport : transport + " · " + country;
            if (!point.isEmpty()) route += " / " + point;
            return String.format(Locale.US,
                    "%s\nОтклик %.0f мс · разброс %.0f мс\nЗагрузка %.1f Мбит/с · отдача %.1f Мбит/с%s",
                    route, latencyMs, jitterMs, downloadMbps, uploadMbps,
                    ip.isEmpty() ? "" : "\nВнешний IP: " + ip);
        }
    }

    public static final class NetworkSnapshot {
        public final boolean internet;
        public final boolean validated;
        public final boolean vpn;
        public final String state;
        public final String transport;
        public final boolean privateDns;
        public final String privateDnsName;

        NetworkSnapshot(boolean internet, boolean validated, boolean vpn, String state,
                        String transport, boolean privateDns, String privateDnsName) {
            this.internet = internet;
            this.validated = validated;
            this.vpn = vpn;
            this.state = state;
            this.transport = transport;
            this.privateDns = privateDns;
            this.privateDnsName = privateDnsName;
        }

        public String format() {
            String dns = privateDns ? "Защищённый DNS включён" : "Защищённый DNS не используется";
            if (!privateDnsName.isEmpty()) dns += " · " + privateDnsName;
            return state + "\n" + transport + "\n" + dns;
        }
    }
}
