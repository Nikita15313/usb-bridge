package app.wireroute.addon;

import android.content.ContentResolver;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Looper;
import android.provider.Settings;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/* JADX INFO: loaded from: classes4.dex */
public final class PrivateDnsDiagnostics {
    private static final int CONNECT_TIMEOUT_MILLIS = 3500;
    private static final int DNS_TRANSACTION_ID = 22354;
    private static final int DOT_PORT = 853;
    private static final String GENERIC_LOOKUP_HOST = "example.com";
    private static final int MAX_DOT_RESPONSE_BYTES = 4096;
    private static final int MAX_PROVIDER_ADDRESSES = 2;
    private static final String MODE_AUTOMATIC = "opportunistic";
    private static final String MODE_OFF = "off";
    private static final String MODE_STRICT = "hostname";
    private static final String PRIVATE_DNS_MODE_KEY = "private_dns_mode";
    private static final String PRIVATE_DNS_SPECIFIER_KEY = "private_dns_specifier";
    private static final int TLS_TIMEOUT_MILLIS = 4500;
    private final ConnectivityManager connectivity;
    private final ContentResolver resolver;

    public enum Classification {
        HEALTHY,
        VPN_BLOCKS_PRIVATE_DNS,
        PRIVATE_DNS_UNREACHABLE,
        VPN_DNS_FAILURE,
        DIRECT_DNS_FAILURE,
        PROFILE_DNS_MISMATCH,
        INDETERMINATE
    }

    public enum DotStatus {
        NOT_CONFIGURED,
        OK,
        HOST_LOOKUP_FAILED,
        TCP_FAILED,
        TLS_FAILED,
        CERTIFICATE_FAILED,
        QUERY_FAILED,
        SECURITY,
        PLATFORM
    }

    public enum GlobalMode {
        OFF,
        AUTOMATIC,
        STRICT,
        UNKNOWN
    }

    public enum LookupFailure {
        NONE,
        EMPTY_ANSWER,
        UNKNOWN_HOST,
        SECURITY,
        PLATFORM
    }

    public enum ProfileDnsMatch {
        MATCH,
        MISMATCH,
        NOT_DECLARED,
        NOT_APPLICABLE,
        UNKNOWN
    }

    public enum UnavailableReason {
        ANDROID_SERVICE,
        DIRECT_NETWORK,
        VPN_NETWORK
    }

    public PrivateDnsDiagnostics(Context context) {
        Context context2 = (Context) Objects.requireNonNull(context, "context");
        Context applicationContext = context2.getApplicationContext();
        context2 = applicationContext != null ? applicationContext : context2;
        this.connectivity = (ConnectivityManager) context2.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        this.resolver = context2.getContentResolver();
    }

    public ProbeContext prepare(String config) {
        ProfileDnsSummary profile = inspectProfile(config);
        String mode = null;
        String specifier = null;
        boolean settingsReadable = true;
        try {
            mode = Settings.Global.getString(this.resolver, PRIVATE_DNS_MODE_KEY);
        } catch (RuntimeException ignored) {
            settingsReadable = false;
        }
        try {
            specifier = Settings.Global.getString(this.resolver, PRIVATE_DNS_SPECIFIER_KEY);
        } catch (RuntimeException ignored) {
            settingsReadable = false;
        }
        GlobalMode globalMode = globalMode(mode, specifier);
        return new ProbeContext(globalMode, settingsReadable,
                globalMode == GlobalMode.STRICT ? normalizeHost(specifier) : null, profile);
    }

    public PathResult runPath(boolean z, ProbeContext probeContext) {
        ensureWorkerThread();
        ProbeContext probeContext2 = (ProbeContext) Objects.requireNonNull(probeContext, "context");
        String str = z ? "Через VPN" : "Без VPN";
        if (this.connectivity == null) {
            return PathResult.unavailable(str, UnavailableReason.ANDROID_SERVICE);
        }
        Network networkChooseNetwork = chooseNetwork(z);
        if (networkChooseNetwork == null) {
            return PathResult.unavailable(str, z ? UnavailableReason.VPN_NETWORK : UnavailableReason.DIRECT_NETWORK);
        }
        return probe(str, z, networkChooseNetwork, probeContext2);
    }

    public Result combine(long j, ProbeContext probeContext, PathResult pathResult, PathResult pathResult2) {
        ProbeContext probeContext2 = (ProbeContext) Objects.requireNonNull(probeContext, "context");
        PathResult pathResult3 = (PathResult) Objects.requireNonNull(pathResult, "direct");
        PathResult pathResult4 = (PathResult) Objects.requireNonNull(pathResult2, "vpn");
        Assessment assessmentAssess = assess(probeContext2, pathResult3, pathResult4);
        return new Result(j, System.currentTimeMillis(), probeContext2.globalMode, probeContext2.settingsReadable, probeContext2.profile.publicCopy(), pathResult3, pathResult4, assessmentAssess.classification, assessmentAssess.title, assessmentAssess.explanation, assessmentAssess.actions);
    }

    public static ProfileDnsSummary inspectProfile(String str) {
        int iIndexOf;
        if (str == null || str.trim().isEmpty()) {
            return ProfileDnsSummary.empty();
        }
        LinkedHashSet linkedHashSet = new LinkedHashSet();
        String[] strArrSplit = str.split("\\r?\\n", -1);
        int length = strArrSplit.length;
        String lowerCase = "";
        boolean z = false;
        boolean z2 = false;
        int i = 0;
        int i2 = 0;
        int i3 = 0;
        for (int i4 = 0; i4 < length; i4++) {
            String str2 = strArrSplit[i4];
            String strTrim = str2 == null ? lowerCase : str2.trim();
            if (strTrim.startsWith("[") && strTrim.endsWith("]") && strTrim.length() > 2) {
                lowerCase = strTrim.substring(1, strTrim.length() - 1).trim().toLowerCase(Locale.ROOT);
                if ("interface".equals(lowerCase)) {
                    z = true;
                }
            } else if ("interface".equals(lowerCase) && !strTrim.isEmpty()) {
                if (!strTrim.startsWith("#") && !strTrim.startsWith(";") && (iIndexOf = strTrim.indexOf(61)) > 0 && "dns".equals(strTrim.substring(0, iIndexOf).trim().toLowerCase(Locale.ROOT))) {
                    for (String str3 : stripInlineComment(strTrim.substring(iIndexOf + 1)).split(",", -1)) {
                        String strTrim2 = str3.trim();
                        if (!strTrim2.isEmpty()) {
                            AddressToken addressTokenNumericAddress = numericAddress(strTrim2);
                            if (addressTokenNumericAddress == null) {
                                i2++;
                            } else if (linkedHashSet.add(addressTokenNumericAddress)) {
                                if (addressTokenNumericAddress.bytes.length == 4) {
                                    i++;
                                } else {
                                    i3++;
                                }
                            }
                        }
                    }
                    z2 = true;
                }
            }
        }
        return new ProfileDnsSummary(z, z2, linkedHashSet.size() + i2, i, i3, i2, linkedHashSet);
    }

    private PathResult probe(String label, boolean vpn, Network network, ProbeContext probeContext) {
        NetworkCapabilities capabilities = null;
        LinkProperties linkProperties = null;
        try {
            capabilities = this.connectivity.getNetworkCapabilities(network);
        } catch (RuntimeException ignored) {
            // The selected network may disappear between discovery and inspection.
        }
        try {
            linkProperties = this.connectivity.getLinkProperties(network);
        } catch (RuntimeException ignored) {
            // Continue with the bounded resolver probe even without link metadata.
        }

        boolean validated = capabilities != null && capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean internetCapability = capabilities != null && capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET);
        LinkPrivateDns linkPrivateDns = inspectLinkPrivateDns(linkProperties);
        String strictHost = probeContext.strictHost != null
                ? probeContext.strictHost : linkPrivateDns.strictHost;
        boolean strictConfigured = strictHost != null;
        ProfileDnsMatch profileMatch = vpn
                ? compareProfileDns(probeContext.profile, linkProperties)
                : ProfileDnsMatch.NOT_APPLICABLE;

        long lookupStarted = System.nanoTime();
        boolean lookupSucceeded = false;
        LookupFailure lookupFailure = LookupFailure.NONE;
        try {
            InetAddress[] addresses = network.getAllByName(GENERIC_LOOKUP_HOST);
            lookupSucceeded = addresses != null && addresses.length > 0;
            if (!lookupSucceeded) {
                lookupFailure = LookupFailure.EMPTY_ANSWER;
            }
        } catch (SecurityException error) {
            lookupFailure = LookupFailure.SECURITY;
        } catch (UnknownHostException error) {
            lookupFailure = LookupFailure.UNKNOWN_HOST;
        } catch (RuntimeException error) {
            lookupFailure = LookupFailure.PLATFORM;
        }
        long lookupMillis = elapsedMillis(lookupStarted);

        DotProbe dotProbe = strictConfigured
                ? probeDot(network, strictHost)
                : new DotProbe(DotStatus.NOT_CONFIGURED, 0L, 0);
        int dnsServerCount = linkProperties == null ? 0 : linkProperties.getDnsServers().size();
        return new PathResult(label, true, transportLabel(capabilities), internetCapability,
                validated, strictConfigured, linkPrivateDns.active, dnsServerCount, profileMatch,
                lookupSucceeded, lookupMillis, lookupFailure, dotProbe.status,
                dotProbe.durationMillis, dotProbe.addressesTried, null);
    }

    private static DotProbe probeDot(Network network, String host) {
        long started = System.nanoTime();
        final InetAddress[] addresses;
        try {
            addresses = network.getAllByName(host);
        } catch (SecurityException error) {
            return new DotProbe(DotStatus.SECURITY, elapsedMillis(started), 0);
        } catch (UnknownHostException error) {
            return new DotProbe(DotStatus.HOST_LOOKUP_FAILED, elapsedMillis(started), 0);
        } catch (RuntimeException error) {
            return new DotProbe(DotStatus.PLATFORM, elapsedMillis(started), 0);
        }
        if (addresses == null || addresses.length == 0) {
            return new DotProbe(DotStatus.HOST_LOOKUP_FAILED, elapsedMillis(started), 0);
        }

        DotStatus lastStatus = DotStatus.TCP_FAILED;
        int tried = 0;
        for (InetAddress address : addresses) {
            if (tried >= MAX_PROVIDER_ADDRESSES) {
                break;
            }
            tried++;
            Socket tcpSocket = null;
            SSLSocket tlsSocket = null;
            boolean tcpConnected = false;
            try {
                tcpSocket = network.getSocketFactory().createSocket();
                tcpSocket.connect(new InetSocketAddress(address, DOT_PORT),
                        CONNECT_TIMEOUT_MILLIS);
                tcpConnected = true;
                tcpSocket.setSoTimeout(TLS_TIMEOUT_MILLIS);

                tlsSocket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(tcpSocket, host, DOT_PORT, true);
                SSLParameters parameters = tlsSocket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
                tlsSocket.setSSLParameters(parameters);
                tlsSocket.setSoTimeout(TLS_TIMEOUT_MILLIS);
                tlsSocket.startHandshake();

                if (queryDot(tlsSocket)) {
                    return new DotProbe(DotStatus.OK, elapsedMillis(started), tried);
                }
                lastStatus = DotStatus.QUERY_FAILED;
            } catch (SSLHandshakeException error) {
                lastStatus = containsCertificateFailure(error)
                        ? DotStatus.CERTIFICATE_FAILED : DotStatus.TLS_FAILED;
            } catch (SecurityException error) {
                lastStatus = DotStatus.SECURITY;
            } catch (IOException error) {
                lastStatus = tcpConnected ? DotStatus.TLS_FAILED : DotStatus.TCP_FAILED;
            } catch (RuntimeException error) {
                lastStatus = DotStatus.PLATFORM;
            } finally {
                if (tlsSocket != null) {
                    closeQuietly(tlsSocket);
                } else if (tcpSocket != null) {
                    closeQuietly(tcpSocket);
                }
            }
        }
        return new DotProbe(lastStatus, elapsedMillis(started), tried);
    }

    private static boolean queryDot(SSLSocket sSLSocket) {
        byte[] bArr = {87, 82, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1};
        try {
            OutputStream outputStream = sSLSocket.getOutputStream();
            outputStream.write(0);
            outputStream.write(29);
            outputStream.write(bArr);
            outputStream.flush();
            DataInputStream dataInputStream = new DataInputStream(sSLSocket.getInputStream());
            int unsignedShort = dataInputStream.readUnsignedShort();
            if (unsignedShort >= 12 && unsignedShort <= 4096) {
                byte[] bArr2 = new byte[unsignedShort];
                dataInputStream.readFully(bArr2);
                return (((bArr2[0] & 0xFF) << 8) | (bArr2[1] & 0xFF)) == DNS_TRANSACTION_ID && ((bArr2[2] & 0x80) != 0) && (bArr2[3] & 15) == 0 && ((bArr2[5] & 0xFF) | ((bArr2[4] & 0xFF) << 8)) == 1;
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Assessment assess(ProbeContext probeContext, PathResult pathResult, PathResult pathResult2) {
        String str;
        String str2;
        String str3;
        if (!pathResult.available || !pathResult2.available) {
            Classification classification = Classification.INDETERMINATE;
            if (!pathResult2.available) {
                str = "Android не показал отдельный VPN-путь.";
            } else {
                str = "Android не показал обычный сетевой путь.";
            }
            return new Assessment(classification, "Недостаточно данных", str, actions("Один раз включить VPN на основном экране.", "Повторить DNS-проверку."));
        }
        boolean z = probeContext.globalMode == GlobalMode.STRICT || pathResult.strictConfigured || pathResult2.strictConfigured;
        boolean z2 = pathResult.dotStatus == DotStatus.OK;
        boolean z3 = pathResult2.dotStatus == DotStatus.OK;
        if (z && z2 && !z3) {
            return new Assessment(Classification.VPN_BLOCKS_PRIVATE_DNS, "Private DNS теряется через VPN", "DNS-over-TLS отвечает напрямую, но не проходит через VPN. Android требует, чтобы ручной Private DNS был доступен с обоих путей.", actions("Переподключить VPN и проверить ещё раз.", "Если повторяется — вручную выбрать «Частный DNS → Автоматически» на время VPN.", "После выключения VPN вернуть ваш DNS-провайдер, если он нужен."));
        }
        if (z && !z2 && !z3) {
            return new Assessment(Classification.PRIVATE_DNS_UNREACHABLE, "Private DNS сейчас недоступен", "Проверка TLS на порту 853 не прошла ни напрямую, ни через VPN. Настройка могла сохраниться; Android не может подтвердить сам сервер.", actions("Открыть системные настройки Private DNS и заново сохранить провайдера.", "Для проверки временно выбрать «Автоматически».", "Сравнить мобильную сеть и Wi‑Fi."));
        }
        if (pathResult.genericLookupSucceeded && !pathResult2.genericLookupSucceeded) {
            Classification classification2 = Classification.VPN_DNS_FAILURE;
            if (pathResult2.profileDnsMatch == ProfileDnsMatch.MISMATCH) {
                str3 = "Обычный DNS-запрос работает напрямую, а через VPN нет; DNS профиля не совпал с DNS, который Android видит на VPN-интерфейсе.";
            } else {
                str3 = "Обычный DNS-запрос работает напрямую, а через VPN не прошёл.";
            }
            return new Assessment(classification2, "DNS не отвечает через VPN", str3, actions("Переподключить VPN.", "Проверить DNS в профиле, не меняя ключи и endpoint.", "Повторить проверку."));
        }
        if (!pathResult.genericLookupSucceeded && pathResult2.genericLookupSucceeded) {
            return new Assessment(Classification.DIRECT_DNS_FAILURE, "DNS без VPN не отвечает", "Через VPN имя разрешается, а напрямую — нет. Вероятнее проблема ручного Private DNS или сети оператора, а не WireGuard-профиля.", actions("Проверить Private DNS в настройках Android.", "Сравнить результат на Wi‑Fi и мобильной сети."));
        }
        if (pathResult2.profileDnsMatch == ProfileDnsMatch.MISMATCH) {
            return new Assessment(Classification.PROFILE_DNS_MISMATCH, "DNS профиля применился не полностью", "Адреса DNS в профиле не совпали с тем, что Android показывает на VPN-интерфейсе. Значения скрыты из отчёта.", actions("Переподключить VPN и повторить проверку.", "Если не изменится — заново сохранить профиль без изменения ключей."));
        }
        if (pathResult.genericLookupSucceeded && pathResult2.genericLookupSucceeded && (!z || (z2 && z3))) {
            Classification classification3 = Classification.HEALTHY;
            if (z) {
                str2 = "Обычные DNS-запросы и TLS к Private DNS прошли по обоим путям.";
            } else {
                str2 = "Обычные DNS-запросы прошли напрямую и через VPN.";
            }
            return new Assessment(classification3, "DNS сейчас работает", str2, actions("Повторить тест сразу после следующего системного уведомления.", "Сохранить результат как рабочий для сравнения."));
        }
        return new Assessment(Classification.INDETERMINATE, "DNS работает нестабильно", "Результаты двух путей не дают одного уверенного вывода.", actions("Переподключить сеть и VPN.", "Повторить проверку при появлении уведомления Android."));
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
                        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == z
                                && (z || networkCapabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_NOT_VPN))) {
                            if (!networkCapabilities.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                                i2 = 0;
                            }
                            if (networkCapabilities.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                                i2 += 8;
                            }
                            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                i2 += 2;
                            } else if (networkCapabilities.hasTransport(
                                    NetworkCapabilities.TRANSPORT_CELLULAR)) {
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

    private static LinkPrivateDns inspectLinkPrivateDns(LinkProperties linkProperties) {
        if (Build.VERSION.SDK_INT < 28 || linkProperties == null) {
            return new LinkPrivateDns(false, null);
        }
        try {
            return new LinkPrivateDns(linkProperties.isPrivateDnsActive(), normalizeHost(linkProperties.getPrivateDnsServerName()));
        } catch (RuntimeException e) {
            return new LinkPrivateDns(false, null);
        }
    }

    private static ProfileDnsMatch compareProfileDns(ProfileDnsSummary profileDnsSummary, LinkProperties linkProperties) {
        if (!profileDnsSummary.hasDnsLine || profileDnsSummary.addresses.isEmpty()) {
            return ProfileDnsMatch.NOT_DECLARED;
        }
        if (linkProperties == null) {
            return ProfileDnsMatch.UNKNOWN;
        }
        LinkedHashSet linkedHashSet = new LinkedHashSet();
        try {
            for (InetAddress inetAddress : linkProperties.getDnsServers()) {
                if (inetAddress != null) {
                    linkedHashSet.add(new AddressToken(inetAddress.getAddress()));
                }
            }
            return profileDnsSummary.addresses.equals(linkedHashSet) ? ProfileDnsMatch.MATCH : ProfileDnsMatch.MISMATCH;
        } catch (RuntimeException e) {
            return ProfileDnsMatch.UNKNOWN;
        }
    }

    private static GlobalMode globalMode(String str, String str2) {
        String lowerCase = str == null ? "" : str.trim().toLowerCase(Locale.ROOT);
        if (MODE_OFF.equals(lowerCase)) {
            return GlobalMode.OFF;
        }
        if (MODE_AUTOMATIC.equals(lowerCase)) {
            return GlobalMode.AUTOMATIC;
        }
        if (MODE_STRICT.equals(lowerCase) || (lowerCase.isEmpty() && normalizeHost(str2) != null)) {
            return GlobalMode.STRICT;
        }
        return GlobalMode.UNKNOWN;
    }

    private static String normalizeHost(String str) {
        if (str == null) {
            return null;
        }
        String strTrim = str.trim();
        while (strTrim.endsWith(".")) {
            strTrim = strTrim.substring(0, strTrim.length() - 1);
        }
        if (strTrim.isEmpty() || strTrim.length() > 253 || strTrim.indexOf(47) >= 0 || strTrim.indexOf(58) >= 0 || strTrim.indexOf(32) >= 0) {
            return null;
        }
        try {
            String lowerCase = IDN.toASCII(strTrim, 2).toLowerCase(Locale.ROOT);
            if (lowerCase.isEmpty() || lowerCase.length() > 253) {
                return null;
            }
            return lowerCase;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static AddressToken numericAddress(String str) {
        if (str.indexOf(58) >= 0) {
            if (!str.matches("[0-9A-Fa-f:.]+")) {
                return null;
            }
            try {
                byte[] address = InetAddress.getByName(str).getAddress();
                if (address.length == 16 || address.length == 4) {
                    return new AddressToken(address);
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        }
        String[] strArrSplit = str.split("\\.", -1);
        if (strArrSplit.length != 4) {
            return null;
        }
        byte[] bArr = new byte[4];
        for (int i = 0; i < strArrSplit.length; i++) {
            String str2 = strArrSplit[i];
            if (str2.isEmpty() || str2.length() > 3) {
                return null;
            }
            int i2 = 0;
            for (int i3 = 0; i3 < str2.length(); i3++) {
                char cCharAt = str2.charAt(i3);
                if (cCharAt < '0' || cCharAt > '9') {
                    return null;
                }
                i2 = ((i2 * 10) + cCharAt) - 48;
            }
            if (i2 > 255) {
                return null;
            }
            bArr[i] = (byte) i2;
        }
        return new AddressToken(bArr);
    }

    private static String stripInlineComment(String str) {
        int length = str.length();
        int iIndexOf = str.indexOf(35);
        int iIndexOf2 = str.indexOf(59);
        if (iIndexOf >= 0) {
            length = Math.min(length, iIndexOf);
        }
        if (iIndexOf2 >= 0) {
            length = Math.min(length, iIndexOf2);
        }
        return str.substring(0, length);
    }

    private static boolean containsCertificateFailure(Throwable th) {
        int i = 0;
        while (th != null) {
            int i2 = i + 1;
            if (i >= 8) {
                break;
            }
            if (th instanceof CertificateException) {
                return true;
            }
            th = th.getCause();
            i = i2;
        }
        return false;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (Exception e) {
        }
    }

    private static long elapsedMillis(long j) {
        return Math.max(0L, (System.nanoTime() - j) / 1000000);
    }

    private static List<String> actions(String... strArr) {
        ArrayList arrayList = new ArrayList();
        Collections.addAll(arrayList, strArr);
        return Collections.unmodifiableList(arrayList);
    }

    private static String transportLabel(NetworkCapabilities networkCapabilities) {
        if (networkCapabilities == null) {
            return "неизвестно";
        }
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "VPN";
        }
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "Wi‑Fi";
        }
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "мобильная";
        }
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "Ethernet";
        }
        return "прочее";
    }

    private static void ensureWorkerThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Private DNS diagnostics must run on a worker thread");
        }
    }

    public static final class ProbeContext {
        public final GlobalMode globalMode;
        public final ProfileDnsSummary profile;
        public final boolean settingsReadable;
        private final String strictHost;

        ProbeContext(GlobalMode globalMode, boolean z, String str, ProfileDnsSummary profileDnsSummary) {
            this.globalMode = globalMode;
            this.settingsReadable = z;
            this.strictHost = str;
            this.profile = profileDnsSummary;
        }

        public boolean hasStrictHostname() {
            return this.strictHost != null;
        }
    }

    public static final class ProfileDnsSummary {
        private final Set<AddressToken> addresses;
        public final int entryCount;
        public final boolean hasDnsLine;
        public final boolean hasInterface;
        public final int ipv4Count;
        public final int ipv6Count;
        public final int otherCount;

        ProfileDnsSummary(boolean z, boolean z2, int i, int i2, int i3, int i4, Set<AddressToken> set) {
            this.hasInterface = z;
            this.hasDnsLine = z2;
            this.entryCount = Math.max(0, i);
            this.ipv4Count = Math.max(0, i2);
            this.ipv6Count = Math.max(0, i3);
            this.otherCount = Math.max(0, i4);
            this.addresses = Collections.unmodifiableSet(new LinkedHashSet(set));
        }

        static ProfileDnsSummary empty() {
            return new ProfileDnsSummary(false, false, 0, 0, 0, 0, Collections.emptySet());
        }

        ProfileDnsSummary publicCopy() {
            return new ProfileDnsSummary(this.hasInterface, this.hasDnsLine, this.entryCount, this.ipv4Count, this.ipv6Count, this.otherCount, Collections.emptySet());
        }

        public String safeSummary() {
            if (!this.hasDnsLine || this.entryCount == 0) {
                return "DNS профиля: не указан";
            }
            return String.format(Locale.ROOT, "DNS профиля: %d · IPv4=%d · IPv6=%d · другое=%d", Integer.valueOf(this.entryCount), Integer.valueOf(this.ipv4Count), Integer.valueOf(this.ipv6Count), Integer.valueOf(this.otherCount));
        }
    }

    public static final class PathResult {
        public final int advertisedDnsCount;
        public final boolean androidValidated;
        public final boolean available;
        public final int dotAddressesTried;
        public final long dotMillis;
        public final DotStatus dotStatus;
        public final long genericLookupMillis;
        public final boolean genericLookupSucceeded;
        public final boolean internetCapability;
        public final String label;
        public final LookupFailure lookupFailure;
        public final boolean privateDnsActive;
        public final ProfileDnsMatch profileDnsMatch;
        public final boolean strictConfigured;
        public final String transport;
        public final UnavailableReason unavailableReason;

        PathResult(String str, boolean z, String str2, boolean z2, boolean z3, boolean z4, boolean z5, int i, ProfileDnsMatch profileDnsMatch, boolean z6, long j, LookupFailure lookupFailure, DotStatus dotStatus, long j2, int i2, UnavailableReason unavailableReason) {
            this.label = str;
            this.available = z;
            this.transport = str2;
            this.internetCapability = z2;
            this.androidValidated = z3;
            this.strictConfigured = z4;
            this.privateDnsActive = z5;
            this.advertisedDnsCount = Math.max(0, i);
            this.profileDnsMatch = profileDnsMatch;
            this.genericLookupSucceeded = z6;
            this.genericLookupMillis = Math.max(0L, j);
            this.lookupFailure = lookupFailure;
            this.dotStatus = dotStatus;
            this.dotMillis = Math.max(0L, j2);
            this.dotAddressesTried = Math.max(0, i2);
            this.unavailableReason = unavailableReason;
        }

        static PathResult unavailable(String str, UnavailableReason unavailableReason) {
            return new PathResult(str, false, "недоступно", false, false, false, false, 0, ProfileDnsMatch.UNKNOWN, false, 0L, LookupFailure.PLATFORM, DotStatus.PLATFORM, 0L, 0, unavailableReason);
        }

        public String safeSummary() {
            if (!this.available) {
                return this.label + ": сеть недоступна · " + String.valueOf(this.unavailableReason);
            }
            return String.format(Locale.ROOT, "%s: DNS=%s (%d мс) · DoT=%s (%d мс) · profile=%s · validated=%s", this.label, this.genericLookupSucceeded ? "OK" : this.lookupFailure, Long.valueOf(this.genericLookupMillis), this.dotStatus, Long.valueOf(this.dotMillis), this.profileDnsMatch, this.androidValidated ? "да" : "нет");
        }
    }

    public static final class Result {
        public final Classification classification;
        public final PathResult direct;
        public final String explanation;
        public final long finishedAtEpochMillis;
        public final GlobalMode globalMode;
        public final ProfileDnsSummary profile;
        public final List<String> recommendedActions;
        public final boolean settingsReadable;
        public final long startedAtEpochMillis;
        public final String title;
        public final PathResult vpn;

        Result(long j, long j2, GlobalMode globalMode, boolean z, ProfileDnsSummary profileDnsSummary, PathResult pathResult, PathResult pathResult2, Classification classification, String str, String str2, List<String> list) {
            this.startedAtEpochMillis = j;
            this.finishedAtEpochMillis = j2;
            this.globalMode = globalMode;
            this.settingsReadable = z;
            this.profile = profileDnsSummary;
            this.direct = pathResult;
            this.vpn = pathResult2;
            this.classification = classification;
            this.title = str;
            this.explanation = str2;
            this.recommendedActions = list;
        }

        public String safeReport() {
            return this.title + '\n' + this.explanation + "\nРежим Android: " + this.globalMode + '\n' + this.profile.safeSummary() + '\n' + this.direct.safeSummary() + '\n' + this.vpn.safeSummary();
        }
    }

    private static final class DotProbe {
        final int addressesTried;
        final long durationMillis;
        final DotStatus status;

        DotProbe(DotStatus dotStatus, long j, int i) {
            this.status = dotStatus;
            this.durationMillis = j;
            this.addressesTried = i;
        }
    }

    private static final class LinkPrivateDns {
        final boolean active;
        final String strictHost;

        LinkPrivateDns(boolean z, String str) {
            this.active = z;
            this.strictHost = str;
        }
    }

    private static final class AddressToken {
        final byte[] bytes;

        AddressToken(byte[] bArr) {
            this.bytes = bArr == null ? new byte[0] : (byte[]) bArr.clone();
        }

        public boolean equals(Object obj) {
            return (obj instanceof AddressToken) && Arrays.equals(this.bytes, ((AddressToken) obj).bytes);
        }

        public int hashCode() {
            return Arrays.hashCode(this.bytes);
        }
    }

    private static final class Assessment {
        final List<String> actions;
        final Classification classification;
        final String explanation;
        final String title;

        Assessment(Classification classification, String str, String str2, List<String> list) {
            this.classification = classification;
            this.title = str;
            this.explanation = str2;
            this.actions = list;
        }
    }
}
