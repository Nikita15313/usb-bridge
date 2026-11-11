package app.wireroute.diagnostics;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads Android's own per-UID counters after the user grants Usage access. */
public final class TrafficUsageRepository {
    private final Context context;
    private final NetworkStatsManager statsManager;
    private final PackageManager packageManager;

    public TrafficUsageRepository(Context context) {
        this.context = context.getApplicationContext();
        statsManager = (NetworkStatsManager) this.context.getSystemService(Context.NETWORK_STATS_SERVICE);
        packageManager = this.context.getPackageManager();
    }

    public boolean hasAccess() {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    public UsageReport loadLastThirtyDays() {
        return loadDays(30);
    }

    public UsageReport loadDays(int days) {
        if (!hasAccess()) return UsageReport.permissionRequired();
        if (statsManager == null) return UsageReport.failed("Android не отдал статистику трафика");
        long end = System.currentTimeMillis();
        int safeDays = Math.max(1, Math.min(365, days));
        long start = startOfCalendarPeriod(end, safeDays);
        Map<Integer, MutableUsage> byUid = new HashMap<>();
        StringBuilder notes = new StringBuilder();
        collect(ConnectivityManager.TYPE_WIFI, start, end, byUid, notes);
        collect(ConnectivityManager.TYPE_MOBILE, start, end, byUid, notes);

        List<AppUsage> apps = new ArrayList<>();
        long wifi = 0L;
        long mobile = 0L;
        for (MutableUsage usage : byUid.values()) {
            wifi = safeAdd(wifi, usage.wifiBytes);
            mobile = safeAdd(mobile, usage.mobileBytes);
            long total = safeAdd(usage.wifiBytes, usage.mobileBytes);
            if (total <= 0) continue;
            apps.add(new AppUsage(labelForUid(usage.uid), usage.uid,
                    usage.wifiBytes, usage.mobileBytes, total));
        }
        apps.sort(Comparator.comparingLong((AppUsage value) -> value.totalBytes).reversed());
        if (apps.size() > 10) apps = new ArrayList<>(apps.subList(0, 10));
        return UsageReport.available(start, end, wifi, mobile, apps, notes.toString());
    }

    static long startOfCalendarPeriod(long endMillis, int days) {
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(endMillis);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        start.add(Calendar.DAY_OF_YEAR, -(Math.max(1, days) - 1));
        return start.getTimeInMillis();
    }

    private void collect(int networkType, long start, long end,
                         Map<Integer, MutableUsage> byUid, StringBuilder notes) {
        try (NetworkStats stats = statsManager.querySummary(networkType, null, start, end)) {
            NetworkStats.Bucket bucket = new NetworkStats.Bucket();
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket);
                int uid = bucket.getUid();
                if (uid < 0) continue;
                long bytes = safeAdd(Math.max(0L, bucket.getRxBytes()),
                        Math.max(0L, bucket.getTxBytes()));
                MutableUsage usage = byUid.computeIfAbsent(uid, MutableUsage::new);
                if (networkType == ConnectivityManager.TYPE_WIFI) {
                    usage.wifiBytes = safeAdd(usage.wifiBytes, bytes);
                } else {
                    usage.mobileBytes = safeAdd(usage.mobileBytes, bytes);
                }
            }
        } catch (Exception error) {
            if (notes.length() > 0) notes.append(" · ");
            notes.append(networkType == ConnectivityManager.TYPE_WIFI
                    ? "Wi‑Fi недоступен" : "мобильные данные недоступны");
        }
    }

    private String labelForUid(int uid) {
        String[] packages = packageManager.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) return "Системный трафик";
        String packageName = packages[0];
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(info);
            if (label != null && label.length() > 0) return label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return packageName;
    }

    static long safeAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }

    public static String formatBytes(long bytes) {
        double value = Math.max(0L, bytes);
        String[] units = {"Б", "КБ", "МБ", "ГБ", "ТБ"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.getDefault(), value >= 100 || unit == 0 ? "%.0f %s" : "%.1f %s",
                value, units[unit]);
    }

    private static final class MutableUsage {
        final int uid;
        long wifiBytes;
        long mobileBytes;

        MutableUsage(int uid) { this.uid = uid; }
    }

    public static final class AppUsage {
        public final String label;
        public final int uid;
        public final long wifiBytes;
        public final long mobileBytes;
        public final long totalBytes;

        AppUsage(String label, int uid, long wifiBytes, long mobileBytes, long totalBytes) {
            this.label = label;
            this.uid = uid;
            this.wifiBytes = wifiBytes;
            this.mobileBytes = mobileBytes;
            this.totalBytes = totalBytes;
        }
    }

    public static final class UsageReport {
        public final boolean accessRequired;
        public final boolean available;
        public final String error;
        public final long startedAt;
        public final long endedAt;
        public final long wifiBytes;
        public final long mobileBytes;
        public final List<AppUsage> apps;
        public final String notes;

        private UsageReport(boolean accessRequired, boolean available, String error,
                            long startedAt, long endedAt, long wifiBytes, long mobileBytes,
                            List<AppUsage> apps, String notes) {
            this.accessRequired = accessRequired;
            this.available = available;
            this.error = error;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.wifiBytes = wifiBytes;
            this.mobileBytes = mobileBytes;
            this.apps = apps;
            this.notes = notes;
        }

        static UsageReport permissionRequired() {
            return new UsageReport(true, false, "Нужно разрешение Android", 0, 0,
                    0, 0, new ArrayList<>(), "");
        }

        static UsageReport failed(String error) {
            return new UsageReport(false, false, error, 0, 0,
                    0, 0, new ArrayList<>(), "");
        }

        static UsageReport available(long start, long end, long wifi, long mobile,
                                     List<AppUsage> apps, String notes) {
            return new UsageReport(false, true, "", start, end, wifi, mobile, apps, notes);
        }

        public long totalBytes() { return safeAdd(wifiBytes, mobileBytes); }
    }
}
