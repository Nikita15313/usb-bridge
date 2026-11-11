package app.wireroute.addon;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Looper;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/* JADX INFO: loaded from: classes4.dex */
public final class NetworkDiagnostics {
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final String DNS_NAME = "cloudflare.com";
    private static final String[] HTTPS_TARGETS = {"https://www.cloudflare.com/cdn-cgi/trace", "https://www.gstatic.com/generate_204", "https://example.com/"};
    public static final String LIMITATION = "Это вероятностная оценка по небольшой выборке. Похожие симптомы дают DNS, MTU, маршрутизация, captive portal, сбой VPN-сервера и потеря пакетов. Тест не доказывает наличие белого списка и не определяет его источник.";
    private static final int MAX_RESPONSE_BYTES = 8192;
    private static final int READ_TIMEOUT_MILLIS = 6000;
    private final ConnectivityManager connectivity;

    public enum Signal {
        LOW,
        MEDIUM,
        INDETERMINATE
    }

    public NetworkDiagnostics(Context context) {
        Context context2 = (Context) Objects.requireNonNull(context, "context");
        Context applicationContext = context2.getApplicationContext();
        this.connectivity = (ConnectivityManager) (applicationContext != null ? applicationContext : context2)
                .getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public Result run() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Network diagnostics must run on a worker thread");
        }
        long jCurrentTimeMillis = System.currentTimeMillis();
        if (this.connectivity == null) {
            PathResult pathResultUnavailable = PathResult.unavailable("Сеть", "Служба Android недоступна");
            return new Result(jCurrentTimeMillis, System.currentTimeMillis(), pathResultUnavailable, pathResultUnavailable, Signal.INDETERMINATE, "Недостаточно данных", "Android не предоставил сведения о сетях.", defaultActions(), LIMITATION);
        }
        return combine(jCurrentTimeMillis, runPath(false), runPath(true));
    }

    public PathResult runPath(boolean z) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Network diagnostics must run on a worker thread");
        }
        if (this.connectivity == null) {
            return PathResult.unavailable(z ? "Через VPN" : "Без VPN", "Служба Android недоступна");
        }
        Network networkChooseNetwork = chooseNetwork(z);
        if (networkChooseNetwork == null) {
            return PathResult.unavailable(z ? "Через VPN" : "Без VPN", z ? "Активная VPN-сеть не найдена" : "Обычная сеть не найдена");
        }
        return probe(z ? "Через VPN" : "Без VPN", networkChooseNetwork);
    }

    public Result combine(long j, PathResult pathResult, PathResult pathResult2) {
        Assessment assessmentAssess = assess(pathResult, pathResult2);
        return new Result(j, System.currentTimeMillis(), pathResult, pathResult2, assessmentAssess.signal, assessmentAssess.title, assessmentAssess.explanation, assessmentAssess.actions, LIMITATION);
    }

    private Network chooseNetwork(boolean z) {
        Network network = null;
        try {
            Network[] allNetworks = this.connectivity.getAllNetworks();
            if (allNetworks == null) {
                return null;
            }
            int i = Integer.MIN_VALUE;
            for (Network network2 : allNetworks) {
                try {
                    NetworkCapabilities networkCapabilities = this.connectivity.getNetworkCapabilities(network2);
                    if (networkCapabilities != null) {
                        int i2 = 4;
                        if (z == networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                                && (z || networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))) {
                            if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                                i2 = 0;
                            }
                            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                                i2 += 8;
                            }
                            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                i2 += 2;
                            } else if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                i2++;
                            }
                            if (i2 > i) {
                                network = network2;
                                i = i2;
                            }
                        }
                    }
                } catch (RuntimeException e) {
                }
            }
            return network;
        } catch (RuntimeException e2) {
            return null;
        }
    }

    private PathResult probe(String label, Network network) {
        List<String> observations = new ArrayList<>();
        int attempted = 1;
        int succeeded = 0;

        long dnsStarted = System.nanoTime();
        try {
            InetAddress[] addresses = network.getAllByName(DNS_NAME);
            long duration = elapsedMillis(dnsStarted);
            if (addresses == null || addresses.length == 0) {
                observations.add("DNS: нет ответа · " + duration + " мс");
            } else {
                succeeded++;
                observations.add("DNS: OK · " + duration + " мс · адресов " + addresses.length);
            }
        } catch (Exception error) {
            observations.add("DNS: ошибка · " + shortError(error));
        }

        for (String target : HTTPS_TARGETS) {
            attempted++;
            long requestStarted = System.nanoTime();
            HttpURLConnection connection = null;
            try {
                String separator = target.contains("?") ? "&" : "?";
                URLConnection opened = network.openConnection(
                        new URL(target + separator + "wr=" + System.nanoTime()));
                if (!(opened instanceof HttpURLConnection)) {
                    observations.add(host(target) + ": неподдерживаемое соединение");
                    continue;
                }
                connection = (HttpURLConnection) opened;
                connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
                connection.setReadTimeout(READ_TIMEOUT_MILLIS);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Connection", "close");
                connection.setRequestProperty("User-Agent", "WireRoute connectivity diagnostics");

                int responseCode = connection.getResponseCode();
                drain(responseStream(connection, responseCode));
                long duration = elapsedMillis(requestStarted);
                if (responseCode >= 200 && responseCode < 400) {
                    succeeded++;
                }
                observations.add(host(target) + ": HTTP " + responseCode + " · " + duration + " мс");
            } catch (Exception error) {
                observations.add(host(target) + ": ошибка · " + shortError(error));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        NetworkCapabilities capabilities = null;
        try {
            capabilities = this.connectivity.getNetworkCapabilities(network);
        } catch (RuntimeException ignored) {
            // The network may disappear while the bounded probes are running.
        }
        return new PathResult(label, true, transportLabel(capabilities),
                capabilities != null && capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED), attempted, succeeded,
                Collections.unmodifiableList(observations), null);
    }

    private static Assessment assess(PathResult pathResult, PathResult pathResult2) {
        String str;
        if (!pathResult.available || !pathResult2.available) {
            if (!pathResult2.available) {
                str = "Сравнение невозможно: Android не видит VPN-сеть.";
            } else {
                str = "Сравнение невозможно: Android не видит прямую сеть.";
            }
            return new Assessment(Signal.INDETERMINATE, "Недостаточно данных", str, defaultActions());
        }
        int i = pathResult.attempted - pathResult.succeeded;
        int i2 = pathResult2.attempted - pathResult2.succeeded;
        if (i >= 2 && pathResult2.succeeded >= 3 && pathResult2.succeeded > pathResult.succeeded) {
            return new Assessment(Signal.MEDIUM, "Есть признаки различия сетевых путей", "Через VPN прошла большая часть проверок, а напрямую несколько независимых проверок не прошли. Это совместимо с избирательным ограничением, но не доказывает белый список.", actions("Повторить тест через 5–10 минут и на другой сети.", "Вручную переподключить VPN и сравнить результат.", "При повторении проверить другой доверенный сервер/профиль и DNS/MTU."));
        }
        if (i2 >= 2 && pathResult.succeeded >= 3 && pathResult.succeeded > pathResult2.succeeded) {
            return new Assessment(Signal.LOW, "Вероятнее проблема VPN-пути", "Прямой путь отвечает заметно лучше VPN. Сначала проверьте профиль, сервер, DNS, MTU и handshake; это не похоже на подтверждение белого списка.", actions("Вручную переподключить VPN.", "Проверить актуальность endpoint и ключей профиля.", "Сравнить с другим доверенным VPN-сервером."));
        }
        if (pathResult.succeeded >= 3 && pathResult2.succeeded >= 3) {
            return new Assessment(Signal.LOW, "Явных признаков не найдено", "В этой короткой проверке оба пути достигли большинства целей. Это не исключает ограничения для других адресов или в другое время.", actions("Сохранить отчёт как базовый результат.", "Повторить тест при появлении симптомов."));
        }
        return new Assessment(Signal.INDETERMINATE, "Результат неоднозначен", "Сбои есть на обоих путях или выборка слишком мала. Причиной могут быть общая связь, DNS, captive portal, перегрузка или временный сбой целей.", defaultActions());
    }

    private static List<String> defaultActions() {
        return actions("Проверить, открывается ли интернет без VPN.", "Повторить тест позже или в другой сети.", "Менять профиль только вручную и только на доверенный.");
    }

    private static List<String> actions(String... strArr) {
        ArrayList arrayList = new ArrayList();
        Collections.addAll(arrayList, strArr);
        return Collections.unmodifiableList(arrayList);
    }

    private static InputStream responseStream(HttpURLConnection httpURLConnection, int i) throws IOException {
        InputStream errorStream = i >= 400 ? httpURLConnection.getErrorStream() : httpURLConnection.getInputStream();
        return errorStream == null ? httpURLConnection.getInputStream() : errorStream;
    }

    private static void drain(InputStream inputStream) throws IOException {
        int i;
        if (inputStream == null) {
            return;
        }
        try {
            byte[] bArr = new byte[1024];
            int i2 = 0;
            while (i2 < 8192 && (i = inputStream.read(bArr, 0, Math.min(1024, 8192 - i2))) >= 0) {
                i2 += i;
            }
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (Throwable th) {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private static long elapsedMillis(long j) {
        return Math.max(0L, (System.nanoTime() - j) / 1000000);
    }

    private static String shortError(Throwable th) {
        String simpleName = th.getClass().getSimpleName();
        String message = th.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return simpleName;
        }
        String strTrim = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (strTrim.length() > 96) {
            strTrim = strTrim.substring(0, 95) + "…";
        }
        return simpleName + ": " + strTrim;
    }

    private static String host(String str) {
        try {
            return new URL(str).getHost();
        } catch (Exception e) {
            return str;
        }
    }

    private static String transportLabel(NetworkCapabilities networkCapabilities) {
        if (networkCapabilities == null) {
            return "неизвестно";
        }
        ArrayList arrayList = new ArrayList();
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            arrayList.add("VPN");
        }
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            arrayList.add("Wi-Fi");
        }
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            arrayList.add("мобильная");
        }
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            arrayList.add("Ethernet");
        }
        return arrayList.isEmpty() ? "прочее" : join(arrayList, " + ");
    }

    private static String join(List<String> list, String str) {
        StringBuilder sb = new StringBuilder();
        for (String str2 : list) {
            if (sb.length() > 0) {
                sb.append(str);
            }
            sb.append(str2);
        }
        return sb.toString();
    }

    public static final class PathResult {
        public final int attempted;
        public final boolean available;
        public final String error;
        public final String label;
        public final List<String> observations;
        public final int succeeded;
        public final String transport;
        public final boolean validated;

        PathResult(String str, boolean z, String str2, boolean z2, int i, int i2, List<String> list, String str3) {
            this.label = str;
            this.available = z;
            this.transport = str2;
            this.validated = z2;
            this.attempted = i;
            this.succeeded = i2;
            this.observations = list;
            this.error = str3;
        }

        static PathResult unavailable(String str, String str2) {
            return new PathResult(str, false, "недоступно", false, 0, 0, Collections.emptyList(), str2);
        }

        public String summary() {
            if (!this.available) {
                return this.label + ": недоступно · " + this.error;
            }
            return String.format(Locale.ROOT, "%s: %d/%d успешно · %s · Android validated: %s", this.label, Integer.valueOf(this.succeeded), Integer.valueOf(this.attempted), this.transport, this.validated ? "да" : "нет");
        }
    }

    public static final class Result {
        public final PathResult direct;
        public final String explanation;
        public final long finishedAtEpochMillis;
        public final String limitation;
        public final List<String> recommendedActions;
        public final Signal signal;
        public final long startedAtEpochMillis;
        public final String title;
        public final PathResult vpn;

        Result(long j, long j2, PathResult pathResult, PathResult pathResult2, Signal signal, String str, String str2, List<String> list, String str3) {
            this.startedAtEpochMillis = j;
            this.finishedAtEpochMillis = j2;
            this.direct = pathResult;
            this.vpn = pathResult2;
            this.signal = signal;
            this.title = str;
            this.explanation = str2;
            this.recommendedActions = list;
            this.limitation = str3;
        }

        public String compactReport() {
            StringBuilder sb = new StringBuilder();
            sb.append(this.title).append("\n").append(this.explanation).append("\n").append(this.direct.summary()).append("\n").append(this.vpn.summary());
            return sb.toString();
        }
    }

    private static final class Assessment {
        final List<String> actions;
        final String explanation;
        final Signal signal;
        final String title;

        Assessment(Signal signal, String str, String str2, List<String> list) {
            this.signal = signal;
            this.title = str;
            this.explanation = str2;
            this.actions = list;
        }
    }
}
