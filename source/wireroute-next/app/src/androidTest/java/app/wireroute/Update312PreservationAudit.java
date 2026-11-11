package app.wireroute;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.AtomicFile;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Explicit two-phase audit of today's production data, not the older 3.1 backup.
 * Does not open screens, touch VPN state, decrypt profiles or edit preferences.
 * The only write is its private, hash-only snapshot in no_backup.
 */
public final class Update312PreservationAudit {
    private static final String PACKAGE = "app.wireroute.release263";
    private static final String SNAPSHOT = "update312-preservation.json";
    private static final String[] HASH_FIELDS = {
            "routingHash", "packagesHash", "modeHash", "sitesHash",
            "profileMetadataHash", "activeProfileHash", "profileLibraryHash"
    };

    @Test public void currentChoicesSurviveUpdate312() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assertEquals("Production audit requires explicit opt-in", "true",
                arguments.getString("verifyUpdate312"));
        String phase = arguments.getString("phase");
        assertTrue("Audit phase must be before or after",
                "before".equals(phase) || "after".equals(phase));
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertEquals("Audit must target the unchanged production package", PACKAGE,
                context.getPackageName());

        JSONObject current = snapshot(context);
        AtomicFile saved = new AtomicFile(new File(context.getNoBackupFilesDir(), SNAPSHOT));
        Bundle report = new Bundle();
        report.putString("update312AuditPhase", phase);
        report.putInt("selectedApplicationCount", current.getInt("selectedApplicationCount"));
        report.putInt("siteCount", current.getInt("siteCount"));
        report.putInt("legacySiteCount", current.getInt("legacySiteCount"));
        if ("before".equals(phase)) {
            writeSnapshot(saved, current);
            report.putString("update312Audit", "BASELINE_SAVED");
        } else {
            assertTrue("Current-update baseline must exist", saved.getBaseFile().isFile());
            JSONObject before = new JSONObject(new String(saved.readFully(), StandardCharsets.UTF_8));
            assertEquals("Snapshot belongs to this production package", PACKAGE,
                    before.getString("package"));
            assertEquals("Expected audit snapshot schema", 1, before.getInt("schema"));
            for (String field : HASH_FIELDS) {
                boolean matches = before.getString(field).equals(current.getString(field));
                report.putBoolean(field + "Matches", matches);
                assertTrue("Preserved user data differs: " + field, matches);
            }
            assertEquals("Selected application count is unchanged",
                    before.getInt("selectedApplicationCount"), current.getInt("selectedApplicationCount"));
            assertEquals("Site count is unchanged", before.getInt("siteCount"), current.getInt("siteCount"));
            assertEquals("Legacy site count is unchanged",
                    before.getInt("legacySiteCount"), current.getInt("legacySiteCount"));
            report.putString("update312Audit", "PASS");
        }
        instrumentation.sendStatus(0, report);
    }

    private static JSONObject snapshot(Context context) throws Exception {
        SharedPreferences routing = context.getSharedPreferences("routing", Context.MODE_PRIVATE);
        Set<String> packages = new HashSet<>(routing.getStringSet("packages", Collections.emptySet()));
        Map<String, Object> selected = new HashMap<>();
        selected.put("packages", packages);
        Map<String, Object> mode = new HashMap<>();
        // Keep absent mode distinct from a stored default without invoking migration.
        if (routing.contains("mode")) mode.put("mode", routing.getAll().get("mode"));

        Map<String, Object> siteStores = new HashMap<>();
        TreeSet<String> siteNames = new TreeSet<>();
        siteNames.add("site_library_v1");
        siteNames.add("wireroute_site_routes_v1");
        File directory = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".xml") && name.toLowerCase(Locale.ROOT).contains("site"))
                siteNames.add(name.substring(0, name.length() - 4));
        }
        for (String name : siteNames)
            siteStores.put(name, preferencesHash(context.getSharedPreferences(name, Context.MODE_PRIVATE)));
        int siteCount = -1;
        String sites = context.getSharedPreferences("site_library_v1", Context.MODE_PRIVATE)
                .getString("sites", "[]");
        try { siteCount = new JSONArray(sites).length(); }
        catch (org.json.JSONException unreadable) { /* Exact raw document remains hashed. */ }
        int legacySiteCount = context.getSharedPreferences("wireroute_site_routes_v1", Context.MODE_PRIVATE)
                .getStringSet("domains", Collections.emptySet()).size();

        File noBackup = context.getNoBackupFilesDir();
        return new JSONObject().put("schema", 1).put("package", PACKAGE)
                .put("selectedApplicationCount", packages.size())
                .put("siteCount", siteCount).put("legacySiteCount", legacySiteCount)
                .put("routingHash", preferencesHash(routing))
                .put("packagesHash", canonicalHash(selected))
                .put("modeHash", canonicalHash(mode))
                .put("sitesHash", canonicalHash(siteStores))
                .put("profileMetadataHash", preferencesHash(
                        context.getSharedPreferences("profile_metadata", Context.MODE_PRIVATE)))
                .put("activeProfileHash", encryptedFileHash(new File(noBackup, "profile.wrc")))
                .put("profileLibraryHash", encryptedFileHash(new File(noBackup, "profile-library.wrc")));
    }

    private static String preferencesHash(SharedPreferences preferences) throws Exception {
        return canonicalHash(preferences.getAll());
    }

    /** Typed, length-prefixed encoding avoids key/value or set-order ambiguities. */
    private static String canonicalHash(Map<String, ?> values) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            List<String> keys = new ArrayList<>(values.keySet());
            Collections.sort(keys);
            output.writeInt(keys.size());
            for (String key : keys) {
                writeString(output, key);
                Object value = values.get(key);
                if (value == null) output.writeByte(0);
                else if (value instanceof String) {
                    output.writeByte(1); writeString(output, (String) value);
                } else if (value instanceof Boolean) {
                    output.writeByte(2); output.writeBoolean((Boolean) value);
                } else if (value instanceof Integer) {
                    output.writeByte(3); output.writeInt((Integer) value);
                } else if (value instanceof Long) {
                    output.writeByte(4); output.writeLong((Long) value);
                } else if (value instanceof Float) {
                    output.writeByte(5); output.writeInt(Float.floatToIntBits((Float) value));
                } else if (value instanceof Set<?>) {
                    output.writeByte(6);
                    List<String> items = new ArrayList<>();
                    for (Object item : (Set<?>) value) {
                        assertTrue("Preference sets must contain strings", item instanceof String);
                        items.add((String) item);
                    }
                    Collections.sort(items);
                    output.writeInt(items.size());
                    for (String item : items) writeString(output, item);
                } else throw new AssertionError("Unsupported preference value type");
            }
        }
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(utf8.length);
        output.write(utf8);
    }

    private static String encryptedFileHash(File file) throws Exception {
        if (!file.exists()) return "absent";
        assertTrue("Encrypted profile must be an ordinary file", file.isFile());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return "sha256:" + hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static void writeSnapshot(AtomicFile file, JSONObject snapshot) throws Exception {
        FileOutputStream output = file.startWrite();
        try {
            output.write(snapshot.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Throwable failure) {
            file.failWrite(output);
            throw failure;
        }
    }
}
