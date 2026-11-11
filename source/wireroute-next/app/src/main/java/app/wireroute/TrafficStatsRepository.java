package app.wireroute;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/* JADX INFO: loaded from: classes4.dex */
public final class TrafficStatsRepository {
    public static final String DATA_SCOPE_LABEL = "Android-reported app traffic on Wi-Fi and mobile; approximate and potentially delayed";
    private final Context context;
    private final PackageManager packageManager;
    private final NetworkStatsManager statsManager;

    public TrafficStatsRepository(Context context) {
        Context context2 = (Context) Objects.requireNonNull(context, "context");
        Context applicationContext = context2.getApplicationContext();
        this.context = applicationContext != null ? applicationContext : context2;
        this.statsManager = (NetworkStatsManager) this.context.getSystemService(
                Context.NETWORK_STATS_SERVICE);
        this.packageManager = this.context.getPackageManager();
    }

    public enum Period {
        TODAY(1, "Today"),
        LAST_7_DAYS(7, "Last 7 calendar days"),
        LAST_30_DAYS(30, "Last 30 calendar days");

        public final int calendarDays;
        public final String label;

        Period(int i, String str) {
            this.calendarDays = i;
            this.label = str;
        }
    }

    public boolean hasUsageAccess() {
        int iCheckOpNoThrow;
        AppOpsManager appOpsManager = (AppOpsManager) this.context.getSystemService(
                Context.APP_OPS_SERVICE);
        if (appOpsManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            iCheckOpNoThrow = appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                    this.context.getPackageName());
        } else {
            iCheckOpNoThrow = appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                    this.context.getPackageName());
        }
        if (iCheckOpNoThrow == AppOpsManager.MODE_ALLOWED) {
            return true;
        }
        if (iCheckOpNoThrow != AppOpsManager.MODE_DEFAULT
                || this.context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return true;
    }

    public Intent createUsageAccessSettingsIntent() {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.parse("package:" + this.context.getPackageName()));
    }

    public UsageReport query(Period period) {
        Objects.requireNonNull(period, "period");
        long jCurrentTimeMillis = System.currentTimeMillis();
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(jCurrentTimeMillis);
        calendar.set(11, 0);
        calendar.set(12, 0);
        calendar.set(13, 0);
        calendar.set(14, 0);
        calendar.add(6, -(period.calendarDays - 1));
        return queryRange(calendar.getTimeInMillis(), jCurrentTimeMillis, period.label);
    }

    public UsageReport queryRange(long j, long j2, String str) {
        ensureWorkerThread();
        long j3 = 0;
        if (j < 0 || j2 <= j) {
            throw new IllegalArgumentException("endMillis must be greater than startMillis");
        }
        if (!hasUsageAccess()) {
            throw new UsageAccessRequiredException();
        }
        if (this.statsManager == null) {
            throw new StatisticsQueryException("Android network statistics service is unavailable on this device", null);
        }
        Map<Integer, MutableUsage> map = new HashMap<>();
        collect(1, j, j2, true, map);
        collect(0, j, j2, false, map);
        List<AppUsage> arrayList = new ArrayList<>(map.size());
        long jSaturatedAdd = 0;
        long jSaturatedAdd2 = 0;
        long jSaturatedAdd3 = 0;
        long jSaturatedAdd4 = 0;
        for (MutableUsage mutableUsage : map.values()) {
            if (mutableUsage.rxBytes != j3 || mutableUsage.txBytes != j3) {
                List<PackageIdentity> listResolvePackages = resolvePackages(mutableUsage.uid);
                AppUsage appUsage = new AppUsage(mutableUsage.uid, listResolvePackages, listResolvePackages.size() > 1, mutableUsage.rxBytes, mutableUsage.txBytes, mutableUsage.wifiRxBytes, mutableUsage.wifiTxBytes, mutableUsage.mobileRxBytes, mutableUsage.mobileTxBytes);
                arrayList.add(appUsage);
                jSaturatedAdd = saturatedAdd(jSaturatedAdd, appUsage.rxBytes);
                jSaturatedAdd2 = saturatedAdd(jSaturatedAdd2, appUsage.txBytes);
                jSaturatedAdd3 = saturatedAdd(jSaturatedAdd3, appUsage.wifiBytes);
                jSaturatedAdd4 = saturatedAdd(jSaturatedAdd4, appUsage.mobileBytes);
                j3 = 0;
            }
        }
        arrayList.sort(Comparator.comparingLong((AppUsage usage) -> usage.totalBytes)
                .reversed()
                .thenComparingInt(usage -> usage.uid));
        return new UsageReport(j, j2, (str == null || str.trim().isEmpty()) ? "Custom period" : str, DATA_SCOPE_LABEL, arrayList, jSaturatedAdd, jSaturatedAdd2, jSaturatedAdd3, jSaturatedAdd4);
    }

    private void collect(int i, long j, long j2, boolean z, Map<Integer, MutableUsage> map) {
        try {
            NetworkStats networkStatsQuerySummary = this.statsManager.querySummary(i, null, j, j2);
            if (networkStatsQuerySummary != null) {
                try {
                    NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                    while (networkStatsQuerySummary.hasNextBucket()) {
                        networkStatsQuerySummary.getNextBucket(bucket);
                        int uid = bucket.getUid();
                        long jMax = Math.max(0L, bucket.getRxBytes());
                        long jMax2 = Math.max(0L, bucket.getTxBytes());
                        MutableUsage mutableUsageComputeIfAbsent = map.computeIfAbsent(Integer.valueOf(uid), new Function() { // from class: app.wireroute.TrafficStatsRepository$$ExternalSyntheticLambda2
                            @Override // java.util.function.Function
                            public final Object apply(Object obj) {
                                return new TrafficStatsRepository.MutableUsage(((Integer) obj).intValue());
                            }
                        });
                        mutableUsageComputeIfAbsent.rxBytes = saturatedAdd(mutableUsageComputeIfAbsent.rxBytes, jMax);
                        mutableUsageComputeIfAbsent.txBytes = saturatedAdd(mutableUsageComputeIfAbsent.txBytes, jMax2);
                        if (z) {
                            mutableUsageComputeIfAbsent.wifiRxBytes = saturatedAdd(mutableUsageComputeIfAbsent.wifiRxBytes, jMax);
                            mutableUsageComputeIfAbsent.wifiTxBytes = saturatedAdd(mutableUsageComputeIfAbsent.wifiTxBytes, jMax2);
                        } else {
                            mutableUsageComputeIfAbsent.mobileRxBytes = saturatedAdd(mutableUsageComputeIfAbsent.mobileRxBytes, jMax);
                            mutableUsageComputeIfAbsent.mobileTxBytes = saturatedAdd(mutableUsageComputeIfAbsent.mobileTxBytes, jMax2);
                        }
                    }
                    if (networkStatsQuerySummary != null) {
                        networkStatsQuerySummary.close();
                        return;
                    }
                    return;
                } catch (Throwable th) {
                    if (networkStatsQuerySummary == null) {
                        throw th;
                    }
                    try {
                        networkStatsQuerySummary.close();
                        throw th;
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                        throw th;
                    }
                }
            }
            if (networkStatsQuerySummary != null) {
                networkStatsQuerySummary.close();
            }
        } catch (RemoteException e) {
            throw new StatisticsQueryException("Android's network statistics service could not complete the query", e);
        } catch (SecurityException e2) {
            throw new UsageAccessRequiredException(e2);
        }
    }

    private List<PackageIdentity> resolvePackages(int i) {
        String string;
        String[] packagesForUid = this.packageManager.getPackagesForUid(i);
        if (packagesForUid == null || packagesForUid.length == 0) {
            return Collections.emptyList();
        }
        ArrayList arrayList = new ArrayList(packagesForUid.length);
        for (String str : packagesForUid) {
            if (str != null && !str.trim().isEmpty()) {
                try {
                    CharSequence applicationLabel = this.packageManager.getApplicationLabel(this.packageManager.getApplicationInfo(str, 0));
                    if (applicationLabel != null && !applicationLabel.toString().trim().isEmpty()) {
                        string = applicationLabel.toString();
                    } else {
                        string = str;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    string = str;
                }
                arrayList.add(new PackageIdentity(str, string));
            }
        }
        arrayList.sort(Comparator.comparing(new Function() { // from class: app.wireroute.TrafficStatsRepository$$ExternalSyntheticLambda3
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return ((TrafficStatsRepository.PackageIdentity) obj).packageName;
            }
        }));
        return Collections.unmodifiableList(arrayList);
    }

    private static void ensureWorkerThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Network usage queries must run on a worker thread");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static long saturatedAdd(long j, long j2) {
        return (j2 <= 0 || j <= Long.MAX_VALUE - j2) ? j + j2 : Long.MAX_VALUE;
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class MutableUsage {
        long mobileRxBytes;
        long mobileTxBytes;
        long rxBytes;
        long txBytes;
        final int uid;
        long wifiRxBytes;
        long wifiTxBytes;

        MutableUsage(int i) {
            this.uid = i;
        }
    }

    public static final class PackageIdentity {
        public final String label;
        public final String packageName;

        PackageIdentity(String str, String str2) {
            this.packageName = str;
            this.label = str2;
        }
    }

    public static final class AppUsage {
        public final long mobileBytes;
        public final long mobileRxBytes;
        public final long mobileTxBytes;
        public final List<PackageIdentity> packages;
        public final long rxBytes;
        public final boolean sharedUid;
        public final long totalBytes;
        public final long txBytes;
        public final int uid;
        public final long wifiBytes;
        public final long wifiRxBytes;
        public final long wifiTxBytes;

        AppUsage(int i, List<PackageIdentity> list, boolean z, long j, long j2, long j3, long j4, long j5, long j6) {
            this.uid = i;
            this.packages = list;
            this.sharedUid = z;
            this.rxBytes = j;
            this.txBytes = j2;
            this.totalBytes = TrafficStatsRepository.saturatedAdd(j, j2);
            this.wifiRxBytes = j3;
            this.wifiTxBytes = j4;
            this.wifiBytes = TrafficStatsRepository.saturatedAdd(j3, j4);
            this.mobileRxBytes = j5;
            this.mobileTxBytes = j6;
            this.mobileBytes = TrafficStatsRepository.saturatedAdd(j5, j6);
        }

        public String displayName() {
            if (this.packages.isEmpty()) {
                return String.format(Locale.ROOT, "Android UID %d", Integer.valueOf(this.uid));
            }
            if (this.packages.size() == 1) {
                return this.packages.get(0).label;
            }
            return this.packages.get(0).label + " + " + (this.packages.size() - 1);
        }
    }

    public static final class UsageReport {
        public final List<AppUsage> apps;
        public final String dataScopeLabel;
        public final long endMillis;
        public final boolean fineGrainedRealtime = false;
        public final long mobileBytes;
        public final String rangeLabel;
        public final long rxBytes;
        public final long startMillis;
        public final long totalBytes;
        public final long txBytes;
        public final long wifiBytes;

        UsageReport(long j, long j2, String str, String str2, List<AppUsage> list, long j3, long j4, long j5, long j6) {
            this.startMillis = j;
            this.endMillis = j2;
            this.rangeLabel = str;
            this.dataScopeLabel = str2;
            this.apps = Collections.unmodifiableList(new ArrayList(list));
            this.rxBytes = j3;
            this.txBytes = j4;
            this.totalBytes = TrafficStatsRepository.saturatedAdd(j3, j4);
            this.wifiBytes = j5;
            this.mobileBytes = j6;
        }
    }

    public static final class UsageAccessRequiredException extends SecurityException {
        public UsageAccessRequiredException() {
            super("Usage access is required to read other apps' Android network statistics");
        }

        UsageAccessRequiredException(Throwable th) {
            super("Usage access is required to read other apps' Android network statistics", th);
        }
    }

    public static final class StatisticsQueryException extends RuntimeException {
        StatisticsQueryException(String str, Throwable th) {
            super(str, th);
        }
    }
}
