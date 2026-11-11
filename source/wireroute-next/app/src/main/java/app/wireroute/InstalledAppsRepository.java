package app.wireroute;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class InstalledAppsRepository {
    private final Context context;

    public InstalledAppsRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<InstalledApp> load() {
        PackageManager packageManager = context.getPackageManager();
        Set<String> linkHandlers = discoverLinkHandlers(packageManager);
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, String> launcherLabels = new HashMap<>();
        for (ResolveInfo activity : queryActivities(packageManager, launcher)) {
            if (activity.activityInfo == null || !activity.activityInfo.enabled) continue;
            String pkg = activity.activityInfo.packageName;
            try {
                CharSequence label = activity.loadLabel(packageManager);
                if (label != null) launcherLabels.putIfAbsent(pkg, label.toString());
            } catch (RuntimeException ignored) { /* One broken APK must not hide the rest. */ }
        }
        Set<String> browsers = discoverBrowsers(packageManager);
        List<ApplicationInfo> installed = getInstalledApplications(packageManager);
        Map<String, ApplicationInfo> byPackage = new HashMap<>();
        Map<Integer, Set<String>> uidPackages = new HashMap<>();
        for (ApplicationInfo info : installed) {
            if (!info.enabled || (info.flags & ApplicationInfo.FLAG_INSTALLED) == 0) continue;
            byPackage.put(info.packageName, info);
            if (!context.getPackageName().equals(info.packageName)) {
                uidPackages.computeIfAbsent(info.uid, ignored -> new HashSet<>()).add(info.packageName);
            }
        }
        List<InstalledApp> apps = new ArrayList<>(installed.size());
        for (ApplicationInfo info : installed) {
            boolean hasInternet = packageManager.checkPermission(
                    Manifest.permission.INTERNET,
                    info.packageName
            ) == PackageManager.PERMISSION_GRANTED;
            if (!isEligibleForVpn(
                    context.getPackageName(),
                    info.packageName,
                    info.enabled,
                    (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0,
                    hasInternet || launcherLabels.containsKey(info.packageName)
            )) {
                continue;
            }
            String label = launcherLabels.get(info.packageName);
            if (label == null) {
                try {
                    CharSequence loaded = info.loadLabel(packageManager);
                    label = loaded == null ? info.packageName : loaded.toString();
                } catch (RuntimeException ignored) { label = info.packageName; }
            }
            String routingPackage = info.packageName;
            Set<String> routingPackages = new HashSet<>(uidPackages.getOrDefault(info.uid,
                    Collections.singleton(info.packageName)));
            String aliases = "";
            String subtitle = browsers.contains(info.packageName) ? "Браузер" : "";
            if ("com.google.android.apps.bard".equals(info.packageName)) {
                aliases = "Gemini Джемини Гемини Google";
                // Gemini's launcher can delegate networking to the Google app. Showing
                // only INTERNET packages hid that launcher and selecting it alone did
                // not route the real network owner. Keep the two choices together.
                if (byPackage.containsKey("com.google.android.googlequicksearchbox")) {
                    routingPackage = "com.google.android.googlequicksearchbox";
                    routingPackages.addAll(uidPackages.getOrDefault(
                            byPackage.get(routingPackage).uid, Collections.singleton(routingPackage)));
                    subtitle = "Вместе с приложением Google";
                }
            } else if ("com.google.android.googlequicksearchbox".equals(info.packageName)) {
                aliases = "Gemini Джемини Гемини Google Гугл Ассистент";
                if (byPackage.containsKey("com.google.android.apps.bard")) {
                    routingPackages.add("com.google.android.apps.bard");
                    subtitle = "Поиск, Ассистент и Gemini";
                }
            }
            boolean systemComponent = !launcherLabels.containsKey(info.packageName)
                    && (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (systemComponent) subtitle = info.packageName;
            else if (subtitle.isEmpty() && routingPackages.size() > 1)
                subtitle = "Общий режим с связанными приложениями";
            apps.add(new InstalledApp(
                    label,
                    info.packageName,
                    VpnAppCatalog.contains(info.packageName),
                    linkHandlers.contains(info.packageName)
                            || VpnAppCatalog.isKnownLinkApp(info.packageName),
                    systemComponent, browsers.contains(info.packageName), subtitle,
                    routingPackage, routingPackages, aliases
            ));
        }

        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        apps.sort((left, right) -> {
            int byLabel = collator.compare(left.label, right.label);
            return byLabel != 0 ? byLabel : left.packageName.compareTo(right.packageName);
        });
        return apps;
    }

    /** Resolve generic web URLs: app links and the WebView provider aren't browsers. */
    private Set<String> discoverBrowsers(PackageManager packageManager) {
        Set<String> packages = new HashSet<>();
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        for (ResolveInfo handler : queryActivities(packageManager, web)) {
            if (handler.activityInfo != null && handler.filter != null
                    && handler.filter.countDataAuthorities() == 0)
                packages.add(handler.activityInfo.packageName);
        }
        // Some OEM browsers do not set handleAllWebDataURI. A Custom Tabs service
        // and an enabled launcher provide a useful, conservative fallback.
        for (ResolveInfo service : queryServices(packageManager,
                new Intent("android.support.customtabs.action.CustomTabsService"))) {
            if (service.serviceInfo != null
                    && packageManager.getLaunchIntentForPackage(service.serviceInfo.packageName) != null)
                packages.add(service.serviceInfo.packageName);
        }
        return packages;
    }

    /**
     * Finds the packages that Android can actually use when another application
     * opens a web link.  The union is intentional: an ordinary VIEW query alone
     * can return a native App Link target instead of the user's browser.
     */
    private Set<String> discoverLinkHandlers(PackageManager packageManager) {
        Set<String> packages = new HashSet<>();
        for (String scheme : new String[]{"https", "http"}) {
            Intent web = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(scheme + "://example.com/"));
            web.addCategory(Intent.CATEGORY_BROWSABLE);
            addActivityPackages(packages, queryActivities(packageManager, web));
            addResolvedActivity(packages, resolveActivity(packageManager, web));
        }

        Intent customTabs = new Intent(
                "android.support.customtabs.action.CustomTabsService");
        addServicePackages(packages, queryServices(packageManager, customTabs));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                android.content.pm.PackageInfo provider = WebView.getCurrentWebViewPackage();
                if (provider != null && provider.packageName != null) {
                    packages.add(provider.packageName);
                }
            } catch (RuntimeException ignored) {
                // A device may not have an enabled WebView provider.
            }
        }
        return packages;
    }

    private static void addActivityPackages(Set<String> destination,
                                            List<ResolveInfo> handlers) {
        for (ResolveInfo handler : handlers) {
            if (handler != null && handler.activityInfo != null
                    && handler.activityInfo.packageName != null) {
                destination.add(handler.activityInfo.packageName);
            }
        }
    }

    private static void addServicePackages(Set<String> destination,
                                           List<ResolveInfo> handlers) {
        for (ResolveInfo handler : handlers) {
            if (handler != null && handler.serviceInfo != null
                    && handler.serviceInfo.packageName != null) {
                destination.add(handler.serviceInfo.packageName);
            }
        }
    }

    private static void addResolvedActivity(Set<String> destination, ResolveInfo handler) {
        if (handler != null && handler.activityInfo != null
                && handler.activityInfo.packageName != null) {
            destination.add(handler.activityInfo.packageName);
        }
    }

    private static List<ResolveInfo> queryActivities(PackageManager packageManager,
                                                      Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.queryIntentActivities(intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL | PackageManager.GET_RESOLVED_FILTER));
        }
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL | PackageManager.GET_RESOLVED_FILTER);
    }

    private static List<ResolveInfo> queryServices(PackageManager packageManager,
                                                   Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.queryIntentServices(intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL));
        }
        return packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL);
    }

    private static ResolveInfo resolveActivity(PackageManager packageManager,
                                                Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.resolveActivity(intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY));
        }
        return packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
    }

    private static List<ApplicationInfo> getInstalledApplications(PackageManager packageManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0L)
            );
        }
        return packageManager.getInstalledApplications(0);
    }

    static boolean isEligibleForVpn(
            String ownPackage,
            String candidatePackage,
            boolean enabled,
            boolean installed,
            boolean hasInternet
    ) {
        return candidatePackage != null
                && !candidatePackage.equals(ownPackage)
                && enabled
                && installed
                && hasInternet;
    }
}
