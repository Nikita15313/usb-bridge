package app.wireroute.sites;

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import app.wireroute.ConfigUtils;
import app.wireroute.SecureConfigStore;
import app.wireroute.TunnelController;
import app.wireroute.WireRouteApplication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Recovery-only reader for the encrypted backup created by retired site routing.
 * It never resolves sites, schedules work, opens sockets, or changes tunnel state.
 */
public final class LegacyProfileMigration {
    private static final Object LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final String SITE_PREFS = "wireroute_site_routes_v1";

    public interface Callback { void onComplete(boolean success, String message); }

    private LegacyProfileMigration() { }

    /** Retires persisted triggers; the encrypted recovery record is untouched. */
    public static void retireExperiments(Context context) {
        try {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler != null) scheduler.cancel(0x575253);
        } catch (RuntimeException ignored) {
            // The old job's service is absent from this APK; a transient system
            // scheduler failure must not prevent the VPN application from opening.
        }
        context.getSharedPreferences(SITE_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", false).putBoolean("auto", false)
                .putBoolean("telegram", false).putBoolean("telegram.auto_mtu", false)
                .putBoolean("telegram.watchdog", false).apply();
        context.getSharedPreferences("wireroute_bypass_v1", Context.MODE_PRIVATE).edit()
                .putBoolean("desired", false).putString("state", "stopped").apply();
        context.getSharedPreferences("wireroute_vpn_mode_v1", Context.MODE_PRIVATE).edit()
                .putString("desired_mode", "direct").apply();
    }

    /** A file-existence gate, safe to call before Connect or Import on the UI thread. */
    public static boolean hasPendingRecovery(Context context) {
        return new EncryptedProfileBackup(context).exists();
    }

    public static boolean hasConflict(Context context) {
        synchronized (LOCK) {
            try {
                EncryptedProfileBackup storage = new EncryptedProfileBackup(context);
                if (!storage.exists()) return false;
                return isProfileConflict(storage.load(), app(context).getConfigStore().load());
            } catch (Exception ignored) {
                // An unreadable backup never grants permission to overwrite a profile.
                return true;
            }
        }
    }

    public static void recover(Context context, Callback callback) {
        submit(context, null, callback);
    }

    public static void resolveConflict(Context context, boolean keepCurrent, Callback callback) {
        submit(context, keepCurrent, callback);
    }

    private static void submit(Context context, Boolean keepCurrent, Callback callback) {
        Context safe = context.getApplicationContext();
        WORKER.execute(() -> {
            String error;
            try { error = restore(safe, keepCurrent); }
            catch (Exception ignored) {
                // Parsing/decryption exception messages may contain confidential fields.
                error = "Не удалось восстановить профиль; зашифрованная копия сохранена";
            }
            String result = error;
            if (callback != null) new Handler(Looper.getMainLooper()).post(() ->
                    callback.onComplete(result == null, result == null
                            ? "Обычный профиль восстановлен" : result));
        });
    }

    private static String restore(Context context, Boolean keepCurrent) throws Exception {
        WireRouteApplication application = app(context);
        TunnelController controller = application.getTunnelController();
        SecureConfigStore store = application.getConfigStore();
        // Connect uses the same monitor and refuses to start while a backup exists.
        // MainActivity blocks edits during recovery; the store lock also excludes imports.
        synchronized (LOCK) {
            synchronized (controller) {
                TunnelController.State state = controller.getSnapshot().state;
                if (state != TunnelController.State.DOWN && state != TunnelController.State.ERROR) {
                    return "Сначала отключите VPN, чтобы восстановить старый профиль";
                }
                synchronized (store) {
                    EncryptedProfileBackup storage = new EncryptedProfileBackup(context);
                    if (!storage.exists()) return null;
                    EncryptedProfileBackup.Backup backup = storage.load();
                    String current = store.load();
                    RoutingSnapshot observed = RoutingSnapshot.read(context);
                    RoutingSnapshot chosenRouting = observed.isOverlay()
                            ? backup.originalRouting() : observed;
                    backup = backup.withOriginalRouting(chosenRouting);
                    if (keepCurrent == null && isProfileConflict(backup, current)) {
                        storage.save(backup.withPhase(EncryptedProfileBackup.PHASE_CONFLICT, false));
                        return "Старый профиль изменён — выберите текущий или исходный вариант";
                    }
                    if (Boolean.TRUE.equals(keepCurrent)) {
                        if (current == null) return "Текущий профиль отсутствует";
                        backup = new EncryptedProfileBackup.Backup(current, store.getDisplayName(),
                                chosenRouting.modePresent, chosenRouting.packagesPresent,
                                chosenRouting.storedMode, chosenRouting.storedPackages,
                                false, false, EncryptedProfileBackup.PHASE_RESTORING);
                    }
                    ConfigUtils.parse(backup.originalConfig);
                    // Save the exact authorized current hash before either durable mutation.
                    // Repeating after process death accepts only this value or the chosen original.
                    EncryptedProfileBackup.Backup restoring = backup.beginRestoring(
                            current == null ? null : sha256(current), false);
                    storage.save(restoring);
                    store.save(restoring.originalConfig, restoring.displayName);
                    restoring.originalRouting().restore(context);
                    if (!context.getSharedPreferences(SITE_PREFS, Context.MODE_PRIVATE).edit()
                            .putBoolean("enabled", false).commit()) {
                        throw new IllegalStateException("Could not finish migration");
                    }
                    storage.delete();
                    if (storage.exists()) throw new IllegalStateException("Recovery cleanup failed");
                    controller.noteProfileChanged();
                    return null;
                }
            }
        }
    }

    static boolean isProfileConflict(EncryptedProfileBackup.Backup backup, String current)
            throws Exception {
        if (backup.phase == EncryptedProfileBackup.PHASE_CONFLICT || current == null) return true;
        String currentHash = sha256(current);
        String originalHash = sha256(backup.originalConfig);
        if (backup.phase == EncryptedProfileBackup.PHASE_PREPARED) {
            return !originalHash.equals(currentHash);
        }
        if (backup.phase == EncryptedProfileBackup.PHASE_APPLIED) {
            return !currentHash.equals(backup.appliedConfigHash);
        }
        if (backup.phase == EncryptedProfileBackup.PHASE_APPLYING) {
            return !originalHash.equals(currentHash)
                    && !currentHash.equals(backup.appliedConfigHash)
                    && !currentHash.equals(backup.previousAppliedConfigHash);
        }
        if (backup.phase == EncryptedProfileBackup.PHASE_RESTORING) {
            return !originalHash.equals(currentHash)
                    && !currentHash.equals(backup.appliedConfigHash);
        }
        return true;
    }

    static String sha256(String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                int unsigned = item & 255;
                if (unsigned < 16) result.append('0');
                result.append(Integer.toHexString(unsigned));
            }
            return result.toString();
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (digest != null) Arrays.fill(digest, (byte) 0);
        }
    }

    private static WireRouteApplication app(Context context) {
        return (WireRouteApplication) context.getApplicationContext();
    }

    static final class RoutingSnapshot {
        final boolean modePresent;
        final boolean packagesPresent;
        final String storedMode;
        final Set<String> storedPackages;

        RoutingSnapshot(String effectiveMode, Set<String> effectivePackages, boolean modePresent,
                boolean packagesPresent, String storedMode, Set<String> storedPackages) {
            this.modePresent = modePresent;
            this.packagesPresent = packagesPresent;
            this.storedMode = storedMode;
            this.storedPackages = Collections.unmodifiableSet(new LinkedHashSet<>(storedPackages));
        }

        static RoutingSnapshot read(Context context) {
            SharedPreferences prefs = context.getSharedPreferences("routing", Context.MODE_PRIVATE);
            Map<String, ?> values = prefs.getAll();
            String mode = prefs.getString("mode", null);
            Set<String> packages = prefs.getStringSet("packages", Collections.emptySet());
            return new RoutingSnapshot(mode, packages, values.containsKey("mode"),
                    values.containsKey("packages"), mode, packages);
        }

        boolean isOverlay() {
            return modePresent && packagesPresent && "BYPASS_SELECTED".equals(storedMode)
                    && storedPackages.isEmpty();
        }

        void restore(Context context) {
            SharedPreferences.Editor editor = context.getSharedPreferences("routing", Context.MODE_PRIVATE).edit();
            if (modePresent) editor.putString("mode", storedMode); else editor.remove("mode");
            if (packagesPresent) editor.putStringSet("packages", new LinkedHashSet<>(storedPackages));
            else editor.remove("packages");
            if (!editor.commit()) throw new IllegalStateException("Could not restore app selection");
        }
    }
}
