package app.wireroute;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes4.dex */
public final class ReportStore {
    public static final int DEFAULT_MAX_ENTRIES = 200;
    public static final String INTERPRETATION_NOTICE = "Результаты получены на этом телефоне и показывают различия сетевых путей. Они не доказывают наличие белого списка и не определяют причину ограничения.";
    private static final String KEY_EVENTS = "events";
    private static final int MAX_CATEGORY_CHARS = 128;
    private static final int MAX_ENTRIES_LIMIT = 1000;
    private static final int MAX_FIELD_CHARS = 4096;
    private static final int MAX_STORED_JSON_CHARS = 524288;
    private static final String PREFERENCES_NAME = "wireroute_report_timeline_v1";
    private final int maxEntries;
    private final SharedPreferences preferences;

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    public ReportStore(Context context) {
        this(context, DEFAULT_MAX_ENTRIES);
    }

    public ReportStore(Context context, int i) {
        Context context2 = (Context) Objects.requireNonNull(context, "context");
        Context applicationContext = context2.getApplicationContext();
        context2 = applicationContext != null ? applicationContext : context2;
        if (i < 1 || i > MAX_ENTRIES_LIMIT) {
            throw new IllegalArgumentException("maxEntries must be between 1 and 1000");
        }
        this.maxEntries = i;
        this.preferences = context2.getSharedPreferences(PREFERENCES_NAME, 0);
    }

    public synchronized Event append(Severity severity, String str, String str2, String str3, String str4) {
        Event event;
        String string = UUID.randomUUID().toString();
        long jCurrentTimeMillis = System.currentTimeMillis();
        if (severity == null) {
            severity = Severity.INFO;
        }
        event = new Event(string, jCurrentTimeMillis, severity, clip(str, MAX_CATEGORY_CHARS), clip(str2, 4096), clip(str3, 4096), clip(str4, 4096));
        append(event);
        return event;
    }

    public synchronized void append(Event event) {
        Objects.requireNonNull(event, "event");
        List<Event> events = readEvents();
        events.add(sanitizedCopy(event));
        while (events.size() > this.maxEntries) {
            events.remove(0);
        }
        String string = encode(events).toString();
        while (string.length() > MAX_STORED_JSON_CHARS && events.size() > 1) {
            events.remove(0);
            string = encode(events).toString();
        }
        this.preferences.edit().putString(KEY_EVENTS, string).apply();
    }

    public synchronized List<Event> getAll() {
        return Collections.unmodifiableList(new ArrayList(readEvents()));
    }

    public synchronized List<Event> getNewestFirst() {
        List<Event> events;
        events = readEvents();
        Collections.reverse(events);
        return Collections.unmodifiableList(events);
    }

    public synchronized void clear() {
        this.preferences.edit().remove(KEY_EVENTS).apply();
    }

    public synchronized String exportText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Отчёт WireRoute\n").append(INTERPRETATION_NOTICE).append("\n\n");
        List<Event> events = readEvents();
        if (events.isEmpty()) {
            sb.append("Записей пока нет.\n");
            return sb.toString();
        }
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT);
        simpleDateFormat.setTimeZone(TimeZone.getDefault());
        for (Event event : events) {
            sb.append(simpleDateFormat.format(new Date(event.timestampEpochMillis))).append(" · ").append(severityLabel(event.severity)).append(" · ").append(singleLine(event.category)).append('\n');
            appendTextField(sb, "Итог", event.observation);
            appendTextField(sb, "Что сделано", event.action);
            appendTextField(sb, "Данные", event.result);
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String severityLabel(Severity severity) {
        if (severity == Severity.WARNING) {
            return "ВНИМАНИЕ";
        }
        return severity == Severity.ERROR ? "ОШИБКА" : "ИНФОРМАЦИЯ";
    }

    public synchronized String exportJson() {
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("schemaVersion", 1);
            jSONObject.put("exportedAtEpochMillis", System.currentTimeMillis());
            jSONObject.put("interpretationNotice", INTERPRETATION_NOTICE);
            jSONObject.put(KEY_EVENTS, encode(readEvents()));
            return jSONObject.toString(2);
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to encode diagnostic report", error);
        }
    }

    private List<Event> readEvents() {
        Severity severityValueOf;
        String string = this.preferences.getString(KEY_EVENTS, null);
        if (string == null || string.isEmpty()) {
            return new ArrayList();
        }
        ArrayList arrayList = new ArrayList();
        try {
            JSONArray jSONArray = new JSONArray(string);
            for (int iMax = Math.max(0, jSONArray.length() - this.maxEntries); iMax < jSONArray.length(); iMax++) {
                JSONObject jSONObjectOptJSONObject = jSONArray.optJSONObject(iMax);
                if (jSONObjectOptJSONObject != null) {
                    String strOptString = jSONObjectOptJSONObject.optString("id", "");
                    long jOptLong = jSONObjectOptJSONObject.optLong("timestampEpochMillis", -1L);
                    if (!strOptString.isEmpty() && jOptLong >= 0) {
                        try {
                            severityValueOf = Severity.valueOf(jSONObjectOptJSONObject.optString("severity", Severity.INFO.name()));
                        } catch (IllegalArgumentException e) {
                            severityValueOf = Severity.INFO;
                        }
                        arrayList.add(new Event(strOptString, jOptLong, severityValueOf, jSONObjectOptJSONObject.optString("category", ""), jSONObjectOptJSONObject.optString("observation", ""), jSONObjectOptJSONObject.optString("action", ""), jSONObjectOptJSONObject.optString("result", "")));
                    }
                }
            }
        } catch (JSONException e2) {
        }
        return arrayList;
    }

    private static JSONArray encode(List<Event> list) {
        JSONArray jSONArray = new JSONArray();
        for (Event event : list) {
            JSONObject jSONObject = new JSONObject();
            try {
                jSONObject.put("id", event.id);
                jSONObject.put("timestampEpochMillis", event.timestampEpochMillis);
                jSONObject.put("severity", event.severity.name());
                jSONObject.put("category", event.category);
                jSONObject.put("observation", event.observation);
                jSONObject.put("action", event.action);
                jSONObject.put("result", event.result);
                jSONArray.put(jSONObject);
            } catch (JSONException e) {
                throw new IllegalStateException("Unable to encode timeline event", e);
            }
        }
        return jSONArray;
    }

    private static Event sanitizedCopy(Event event) {
        String string;
        long jCurrentTimeMillis;
        if (event.id == null || event.id.isEmpty()) {
            string = UUID.randomUUID().toString();
        } else {
            string = clip(event.id, MAX_CATEGORY_CHARS);
        }
        if (event.timestampEpochMillis < 0) {
            jCurrentTimeMillis = System.currentTimeMillis();
        } else {
            jCurrentTimeMillis = event.timestampEpochMillis;
        }
        return new Event(string, jCurrentTimeMillis, event.severity == null ? Severity.INFO : event.severity, clip(event.category, MAX_CATEGORY_CHARS), clip(event.observation, 4096), clip(event.action, 4096), clip(event.result, 4096));
    }

    private static String clip(String str, int i) {
        if (str == null) {
            return "";
        }
        if (str.length() <= i) {
            return str;
        }
        return str.substring(0, i - 1) + "…";
    }

    private static String singleLine(String str) {
        return str.replace('\n', ' ').replace('\r', ' ');
    }

    private static void appendTextField(StringBuilder sb, String str, String str2) {
        if (str2 == null || str2.isEmpty()) {
            return;
        }
        sb.append(str).append(": ").append(str2).append('\n');
    }

    public static final class Event {
        public final String action;
        public final String category;

        public final String id;
        public final String observation;
        public final String result;
        public final Severity severity;
        public final long timestampEpochMillis;

        public Event(String str, long j, Severity severity, String str2, String str3, String str4, String str5) {
            this.id = str;
            this.timestampEpochMillis = j;
            this.severity = severity;
            this.category = str2;
            this.observation = str3;
            this.action = str4;
            this.result = str5;
        }
    }
}
