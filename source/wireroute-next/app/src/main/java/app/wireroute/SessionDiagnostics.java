package app.wireroute;

import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Passive session observations. This class never probes the network or changes a tunnel.
 * WireGuard counters include protocol traffic: neither a reply nor a missing reply
 * establishes whether an application can access the Internet.
 */
public final class SessionDiagnostics {
    static final int MAX_ENTRIES = 240;
    private static final long SUMMARY_INTERVAL_MS = 60_000L;
    private static final long RECENT_REPLY_MS = 20_000L;
    private static final long QUIET_RX_MS = 30_000L;
    private static final long MEANINGFUL_OUTBOUND_BYTES = 1_024L;
    private static final String STATES = "(?:DOWN|ERROR|CONNECTING|DISCONNECTING|UP|UNKNOWN)";
    private static final Pattern SAFE_JOURNAL_LINE = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z elapsed_ms=[0-9]+ "
            + "(?:observer_started|counters_reset|state=" + STATES
            + "|event=(?:SERVICE_STARTED|SERVICE_STOPPED|CONFIG_CHANGED)"
            + "|client_public_key_sha256_12=[a-f0-9]{12}"
            + "|underlay event=(?:AVAILABLE|LOST|CAPABILITIES|LINK_PROPERTIES|CHANGED|UNKNOWN)"
            + " transport=(?:WIFI|CELLULAR|ETHERNET|OTHER|NONE|UNKNOWN) validated=(?:true|false) metered=(?:true|false)"
            + "|sample state=" + STATES
            + " observation=(?:STOPPED|TRANSITIONING|UNKNOWN|OBSERVING|RECEIVING|QUIET_RX)"
            + " counters=(?:available|unknown) rx=[0-9]+ tx=[0-9]+ handshake_age_s=-?[0-9]+ last_rx_age_s=-?[0-9]+)");
    private static volatile SessionDiagnostics instance;

    public enum Health { STOPPED, TRANSITIONING, UNKNOWN, OBSERVING, RECEIVING, QUIET_RX }

    public static final class Report {
        public final Health health;
        public final String summary;
        public final String details;
        /** -1 means not observed, not a failed handshake. */
        public final long handshakeAgeSeconds;
        public final long lastRxAgeSeconds;
        public final long rxBytes;
        public final long txBytes;

        private Report(Health health, String summary, String details, long handshakeAgeSeconds,
                       long lastRxAgeSeconds, long rxBytes, long txBytes) {
            this.health = health;
            this.summary = summary;
            this.details = details;
            this.handshakeAgeSeconds = handshakeAgeSeconds;
            this.lastRxAgeSeconds = lastRxAgeSeconds;
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
        }
    }

    interface Clock {
        long elapsedMillis();
        long wallMillis();
    }

    interface Store {
        String read();
        void write(String text);
    }

    private final Clock clock;
    private final Store store;
    private final Deque<String> entries = new ArrayDeque<>();
    private String lastState = "";
    private String lastUnderlay = "";
    private boolean baseline;
    private long previousRx;
    private long previousTx;
    private long lastRxAt = -1L;
    private long outgoingSinceReply;
    private long outgoingWindowAt = -1L;
    private long lastSummaryAt = -1L;
    private Report report = new Report(Health.STOPPED, "VPN выключен", "", -1L, -1L, 0L, 0L);

    public static SessionDiagnostics get(Context context) {
        SessionDiagnostics current = instance;
        if (current != null) return current;
        synchronized (SessionDiagnostics.class) {
            if (instance == null) {
                Context app = context.getApplicationContext();
                instance = new SessionDiagnostics(new Clock() {
                    @Override public long elapsedMillis() { return SystemClock.elapsedRealtime(); }
                    @Override public long wallMillis() { return System.currentTimeMillis(); }
                }, new FileStore(new File((app == null ? context : app).getFilesDir(),
                        "vpn-session-diagnostics.log")));
            }
            return instance;
        }
    }

    SessionDiagnostics(Clock clock, Store store) {
        this.clock = clock;
        this.store = store;
        String previous = store.read();
        if (previous != null) {
            for (String line : previous.split("\\n")) {
                // Journal lines are generated here and never contain config or network addresses.
                if (isSafeJournalLine(line)) addLine(line);
            }
        }
        append("observer_started");
    }

    /** Call every 5-10 seconds on a worker while the session is active. */
    public synchronized Report sample(String state, boolean available, boolean stale,
                                      long rxBytes, long txBytes, long latestHandshakeEpochMillis) {
        long now = clock.elapsedMillis();
        String normalizedState = normalizeState(state);
        boolean stateChanged = !normalizedState.equals(lastState);
        if (stateChanged) {
            resetCounters();
            lastState = normalizedState;
            append("state=" + normalizedState);
        }
        long handshakeAge = latestHandshakeEpochMillis > 0L
                ? Math.max(0L, clock.wallMillis() - latestHandshakeEpochMillis) / 1_000L : -1L;
        Health health;
        String summary;
        String details;
        if ("DOWN".equals(normalizedState) || "ERROR".equals(normalizedState)) {
            health = Health.STOPPED;
            summary = "ERROR".equals(normalizedState) ? "VPN не подключён" : "VPN выключен";
            details = "";
        } else if (!"UP".equals(normalizedState)) {
            health = Health.TRANSITIONING;
            summary = "DISCONNECTING".equals(normalizedState)
                    ? "VPN отключается" : "VPN подключается";
            details = "";
        } else if (!available || stale || rxBytes < 0L || txBytes < 0L) {
            baseline = false;
            outgoingSinceReply = 0L;
            outgoingWindowAt = -1L;
            health = Health.UNKNOWN;
            summary = "VPN включён; счётчики пока недоступны";
            details = "Это само по себе не означает потерю подключения.";
        } else {
            if (baseline && (rxBytes < previousRx || txBytes < previousTx)) {
                resetCounters();
                append("counters_reset");
            }
            if (baseline) {
                long received = rxBytes - previousRx;
                long sent = txBytes - previousTx;
                if (received > 0L) {
                    lastRxAt = now;
                    outgoingSinceReply = 0L;
                    outgoingWindowAt = -1L;
                } else if (sent >= MEANINGFUL_OUTBOUND_BYTES) {
                    // Do not accumulate occasional 32-byte keepalives over an idle session.
                    if (outgoingWindowAt < 0L) outgoingWindowAt = now;
                    outgoingSinceReply = MEANINGFUL_OUTBOUND_BYTES;
                }
            }
            previousRx = rxBytes;
            previousTx = txBytes;
            baseline = true;
            if (lastRxAt >= 0L && now - lastRxAt <= RECENT_REPLY_MS) {
                health = Health.RECEIVING;
                summary = "Есть ответ от VPN-сервера";
                details = "Счётчики включают служебные пакеты. Доступность сайтов не проверялась.";
            } else if (outgoingWindowAt >= 0L && outgoingSinceReply >= MEANINGFUL_OUTBOUND_BYTES
                    && now - outgoingWindowAt >= QUIET_RX_MS) {
                health = Health.QUIET_RX;
                long quietSeconds = (now - (lastRxAt >= 0L ? lastRxAt : outgoingWindowAt)) / 1_000L;
                summary = "Нет входящих данных " + quietSeconds + " с";
                details = "Были исходящие данные. Это наблюдение, а не подтверждение сбоя; "
                        + "автоматического переподключения нет.";
            } else {
                health = Health.OBSERVING;
                summary = "VPN включён; наблюдаем обмен";
                details = "При простое входящих данных может не быть. Доступность сайтов не проверялась.";
            }
        }
        long rxAge = lastRxAt < 0L ? -1L : Math.max(0L, now - lastRxAt) / 1_000L;
        Report next = new Report(health, summary, details, handshakeAge, rxAge,
                Math.max(0L, rxBytes), Math.max(0L, txBytes));
        boolean healthChanged = report.health != next.health;
        report = next;
        if (stateChanged || healthChanged || lastSummaryAt < 0L
                || now - lastSummaryAt >= SUMMARY_INTERVAL_MS) {
            append("sample state=" + normalizedState + " observation=" + health.name()
                    + " counters=" + (available && !stale ? "available" : "unknown")
                    + " rx=" + next.rxBytes + " tx=" + next.txBytes
                    + " handshake_age_s=" + handshakeAge + " last_rx_age_s=" + rxAge);
            lastSummaryAt = now;
        }
        return next;
    }

    /** Only coarse properties are accepted: no SSID, endpoint, IP, URL, or network ID. */
    public synchronized void noteUnderlay(String event, String transport,
                                          boolean validated, boolean metered) {
        String safeEvent = allow(event, "AVAILABLE", "LOST", "CAPABILITIES", "LINK_PROPERTIES", "CHANGED");
        String safeTransport = allow(transport, "WIFI", "CELLULAR", "ETHERNET", "OTHER", "NONE");
        String observation = "underlay event=" + safeEvent + " transport=" + safeTransport
                + " validated=" + validated + " metered=" + metered;
        if (!observation.equals(lastUnderlay)) {
            lastUnderlay = observation;
            append(observation);
        }
    }

    /** Lifecycle events use a fixed vocabulary, so free-form error/config text cannot leak. */
    public synchronized void noteEvent(String event) {
        String safeEvent = allow(event, "SERVICE_STARTED", "SERVICE_STOPPED", "CONFIG_CHANGED");
        if ("UNKNOWN".equals(safeEvent)) return;
        append("event=" + safeEvent);
        if ("SERVICE_STOPPED".equals(safeEvent)) {
            sample("DOWN", false, false, 0L, 0L, 0L);
        } else if ("SERVICE_STARTED".equals(safeEvent)) {
            resetCounters();
            lastState = "";
            sample("CONNECTING", false, false, 0L, 0L, 0L);
        }
    }

    /** Derive from the local public key only. Never pass private or preshared keys. */
    public synchronized void notePublicKey(String base64PublicKey) {
        String fingerprint = publicKeyFingerprint(base64PublicKey);
        if (!fingerprint.isEmpty()) append("client_public_key_sha256_12=" + fingerprint);
    }

    public static String publicKeyFingerprint(String base64PublicKey) {
        if (base64PublicKey == null) return "";
        try {
            byte[] publicKey = Base64.getDecoder().decode(base64PublicKey.trim());
            if (publicKey.length != 32) return "";
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey);
            StringBuilder result = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                result.append(String.format(Locale.ROOT, "%02x", digest[index] & 0xff));
            }
            return result.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    public synchronized Report getReport() { return report; }
    public synchronized String getSummary() { return report.summary; }
    public synchronized String getLogText() { return String.join("\n", entries) + "\n"; }

    /** Explicit journal-button action. USB debugging can read these allowlisted lines without run-as. */
    public synchronized void dumpToUsb() {
        for (String line : entries) logSafeLine(line);
    }

    static boolean isSafeJournalLine(String line) {
        return line != null && line.length() <= 512 && SAFE_JOURNAL_LINE.matcher(line).matches();
    }

    private static void logSafeLine(String line) {
        if (!isSafeJournalLine(line)) return;
        try { Log.i("WireRouteSession", line); }
        catch (RuntimeException | LinkageError ignored) {
            // A diagnostic sink must never affect the tunnel, including in host-side tests.
        }
    }

    private void resetCounters() {
        baseline = false;
        lastRxAt = -1L;
        outgoingSinceReply = 0L;
        outgoingWindowAt = -1L;
    }

    private void append(String event) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String line = format.format(clock.wallMillis()) + " elapsed_ms=" + clock.elapsedMillis() + " " + event;
        addLine(line);
        logSafeLine(line);
        store.write(getLogText());
    }

    private void addLine(String line) {
        entries.addLast(line);
        while (entries.size() > MAX_ENTRIES) entries.removeFirst();
    }

    private static String normalizeState(String state) {
        return allow(state, "DOWN", "ERROR", "CONNECTING", "DISCONNECTING", "UP");
    }

    private static String allow(String value, String... accepted) {
        if (value != null) {
            for (String option : accepted) if (option.equalsIgnoreCase(value)) return option;
        }
        return "UNKNOWN";
    }

    private static final class FileStore implements Store {
        private final AtomicFile file;
        FileStore(File path) { file = new AtomicFile(path); }
        @Override public String read() {
            try {
                if (file.getBaseFile().length() > 128L * 1_024L) return "";
                return new String(file.readFully(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return "";
            }
        }
        @Override public void write(String text) {
            FileOutputStream stream = null;
            try {
                stream = file.startWrite();
                stream.write(text.getBytes(StandardCharsets.UTF_8));
                file.finishWrite(stream);
            } catch (Exception ignored) {
                if (stream != null) file.failWrite(stream);
                // Diagnostics must not bring down a healthy tunnel if storage fails.
            }
        }
    }
}
