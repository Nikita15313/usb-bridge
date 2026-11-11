package app.wireroute.diagnostics;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One explicit diagnostic run; never changes the VPN, its app list, or process network binding. */
public final class TunnelProbe {
    static final int RUN_TIMEOUT_MS = 8_000;
    private static final int TCP_TIMEOUT_MS = 2_500;
    private static final int DNS_TIMEOUT_MS = 3_500;
    private static final String DNS_HOST = "example.com";
    // Raw bytes ensure the TCP control checks never perform a DNS lookup.
    private static final byte[][] TCP_ADDRESSES = {
            {1, 1, 1, 1}, {8, 8, 8, 8},
            {0x26, 0x06, 0x47, 0x00, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11}
    };
    private static final String[] TCP_LABELS = {"TCP_CF_v4", "TCP_GOOGLE_v4", "TCP_CF_v6"};
    private final ConnectivityManager connectivity;
    private final AtomicReference<Run> active = new AtomicReference<>();

    public TunnelProbe(Context context) {
        connectivity = context.getApplicationContext().getSystemService(ConnectivityManager.class);
    }

    /** Must be called on a worker. All network operations share an eight-second deadline. */
    public Result run() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("A worker is required");
        }
        Run run = new Run();
        if (!active.compareAndSet(null, run)) {
            run.close();
            throw new IllegalStateException("A probe is already running");
        }
        long start = SystemClock.elapsedRealtime();
        long deadline = start + RUN_TIMEOUT_MS;
        List<String> lines = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        try {
            lines.add("probe_start_elapsed_ms=" + start + " max_ms=" + RUN_TIMEOUT_MS);
            for (boolean vpn : new boolean[] {false, true}) {
                String path = vpn ? "VPN" : "DIRECT";
                Network network = chooseNetwork(vpn);
                if (network == null) {
                    lines.add(path + " network=NOT_VISIBLE_OR_UNAVAILABLE");
                    continue;
                }
                lines.add(describe(path, network));
                for (int index = 0; index < TCP_ADDRESSES.length; index++) {
                    final int target = index;
                    String name = path + " " + TCP_LABELS[index];
                    pending.add(new Pending(name, run.pool.submit(
                            () -> tcp(run, network, TCP_ADDRESSES[target]))));
                }
                for (boolean ipv6 : new boolean[] {false, true}) {
                    String name = path + (ipv6 ? " DNS_AAAA" : " DNS_A");
                    pending.add(new Pending(name, run.pool.submit(() -> dns(run, network, ipv6))));
                }
            }
            for (Pending check : pending) {
                Observation observation;
                try {
                    long remaining = Math.max(0L, deadline - SystemClock.elapsedRealtime());
                    observation = check.future.get(remaining, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    observation = new Observation("DEADLINE", RUN_TIMEOUT_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    observation = new Observation("CANCELLED", 0);
                } catch (CancellationException ignored) {
                    observation = new Observation("CANCELLED", 0);
                } catch (ExecutionException ignored) {
                    observation = new Observation("PLATFORM_ERROR", 0);
                }
                lines.add(check.name + " status=" + observation.status + " ms=" + observation.millis);
            }
            lines.add("probe_duration_ms=" + Math.max(0L, SystemClock.elapsedRealtime() - start));
            return new Result(lines);
        } finally {
            run.close();
            active.compareAndSet(run, null);
        }
    }

    public void cancel() {
        Run run = active.get();
        if (run != null) run.close();
    }

    @SuppressWarnings("deprecation")
    private Network chooseNetwork(boolean vpn) {
        if (connectivity == null) return null;
        Network best = null;
        int score = Integer.MIN_VALUE;
        try {
            Network preferred = connectivity.getActiveNetwork();
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != vpn) continue;
                if (!vpn && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue;
                int value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 8 : 0;
                if (network.equals(preferred)) value += 16;
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) value += 4;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) value += 2;
                if (value > score) { best = network; score = value; }
            }
        } catch (RuntimeException ignored) { return null; }
        return best;
    }

    private String describe(String label, Network network) {
        try {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            LinkProperties link = connectivity.getLinkProperties(network);
            String transport = caps == null ? "UNKNOWN"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "WIFI"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "CELLULAR"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ETHERNET" : "OTHER";
            return label + " network=VISIBLE transport=" + transport
                    + " validated=" + (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    + " mtu=" + (Build.VERSION.SDK_INT >= 29 && link != null ? String.valueOf(link.getMtu()) : "UNKNOWN")
                    + " private_dns=" + (Build.VERSION.SDK_INT >= 28 && link != null && link.isPrivateDnsActive());
        } catch (RuntimeException ignored) { return label + " metadata=UNAVAILABLE"; }
    }

    private static Observation tcp(Run run, Network network, byte[] address) {
        long start = SystemClock.elapsedRealtime();
        Socket socket = null;
        try {
            if (run.closed.get()) return observed("CANCELLED", start);
            socket = network.getSocketFactory().createSocket();
            run.sockets.add(socket);
            if (run.closed.get()) return observed("CANCELLED", start);
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(address), 443), TCP_TIMEOUT_MS);
            return observed("OK", start);
        } catch (Exception error) {
            return observed(run.closed.get() ? "CANCELLED" : errorStatus(error), start);
        } finally {
            if (socket != null) {
                run.sockets.remove(socket);
                try { socket.close(); } catch (IOException ignored) { }
            }
        }
    }

    private static Observation dns(Run run, Network network, boolean ipv6) {
        long start = SystemClock.elapsedRealtime();
        if (Build.VERSION.SDK_INT < 29) return observed("UNSUPPORTED_ANDROID", start);
        if (run.closed.get()) return observed("CANCELLED", start);
        return DnsApi29.query(run, network, ipv6, start);
    }

    /** Isolated so Android 7–9 never loads APIs it does not provide. */
    @RequiresApi(29)
    private static final class DnsApi29 {
        static Observation query(Run run, Network network, boolean ipv6, long start) {
            CancellationSignal signal = new CancellationSignal();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>("TIMEOUT");
            run.signals.add(signal);
            try {
                if (run.closed.get()) return observed("CANCELLED", start);
                DnsResolver.getInstance().query(network, DNS_HOST,
                        ipv6 ? DnsResolver.TYPE_AAAA : DnsResolver.TYPE_A,
                        // Bypass a cached success; CancellationSignal bounds any resolver retries.
                        DnsResolver.FLAG_NO_CACHE_LOOKUP,
                        Runnable::run, signal, new DnsResolver.Callback<List<InetAddress>>() {
                            @Override public void onAnswer(List<InetAddress> answer, int rcode) {
                                result.set(rcode == 0 && !answer.isEmpty() ? "OK" : "NO_ANSWER");
                                done.countDown();
                            }
                            @Override public void onError(DnsResolver.DnsException error) {
                                result.set(errorStatus(error));
                                done.countDown();
                            }
                        });
                if (!done.await(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return observed("TIMEOUT", start);
                return observed(result.get(), start);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return observed("CANCELLED", start);
            } catch (Exception error) {
                return observed(run.closed.get() ? "CANCELLED" : errorStatus(error), start);
            } finally {
                signal.cancel();
                run.signals.remove(signal);
            }
        }
    }

    static String errorStatus(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof SecurityException) return "NOT_PERMITTED";
            if (current instanceof SocketTimeoutException) return "TIMEOUT";
            if (current instanceof ErrnoException) {
                int errno = ((ErrnoException) current).errno;
                if (errno == OsConstants.EACCES || errno == OsConstants.EPERM) return "NOT_PERMITTED";
                if (errno == OsConstants.ENETUNREACH || errno == OsConstants.EHOSTUNREACH) return "NO_ROUTE";
            }
        }
        return "CHECK_FAILED";
    }

    private static Observation observed(String status, long start) {
        return new Observation(status, Math.max(0L, SystemClock.elapsedRealtime() - start));
    }

    private static final class Observation {
        final String status;
        final long millis;
        Observation(String status, long millis) { this.status = status; this.millis = millis; }
    }

    private static final class Pending {
        final String name;
        final Future<Observation> future;
        Pending(String name, Future<Observation> future) { this.name = name; this.future = future; }
    }

    private static final class Run {
        final ExecutorService pool = Executors.newFixedThreadPool(10);
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final Set<CancellationSignal> signals = ConcurrentHashMap.newKeySet();
        final AtomicBoolean closed = new AtomicBoolean();
        void close() {
            closed.set(true);
            for (CancellationSignal signal : signals) signal.cancel();
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            pool.shutdownNow();
        }
    }

    public static final class Result {
        private final List<String> safeLines;
        Result(List<String> lines) { safeLines = Collections.unmodifiableList(new ArrayList<>(lines)); }
        public String format() {
            return "Проверка TCP и DNS\n\n" + String.join("\n", safeLines)
                    + "\n\nOK — эта проверка прошла. TCP проверяет только установление соединения, не видео."
                    + "\nNOT_VISIBLE_OR_UNAVAILABLE / NOT_PERMITTED — приложение не получило доступ к сети; это не доказательство обрыва VPN."
                    + "\nTIMEOUT / CHECK_FAILED / NO_ROUTE — не прошла конкретная проверка. Отсутствие IPv6 не означает отказ IPv4."
                    + "\nЕсли числовой TCP работает, а DNS — нет, проверяйте DNS отдельно. Успех короткого теста не исключает периодические паузы."
                    + "\nТест не меняет список приложений VPN. Прямой путь может быть ограничен настройкой Android «Блокировать без VPN».";
        }
        public void logToUsb() {
            for (String line : safeLines) Log.i("WireRouteProbe", line);
        }
    }
}
