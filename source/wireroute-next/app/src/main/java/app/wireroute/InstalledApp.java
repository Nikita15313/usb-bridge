package app.wireroute;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class InstalledApp {
    public final String label;
    public final String packageName;
    public final boolean recommended;
    public final boolean linkHandler;
    public final boolean systemComponent;
    public final boolean browser;
    public final String subtitle;
    public final String routingPackage;
    public final Set<String> routingPackages;
    public final String searchText;

    InstalledApp(String label, String packageName, boolean recommended, boolean linkHandler) {
        this(label, packageName, recommended, linkHandler, false, false, "",
                packageName, Collections.singleton(packageName), "");
    }

    InstalledApp(String label, String packageName, boolean recommended, boolean linkHandler,
                 boolean systemComponent, boolean browser, String subtitle,
                 String routingPackage, Set<String> routingPackages, String aliases) {
        this.label = label;
        this.packageName = packageName;
        this.recommended = recommended;
        this.linkHandler = linkHandler;
        this.systemComponent = systemComponent;
        this.browser = browser;
        this.subtitle = subtitle;
        this.routingPackage = routingPackage;
        this.routingPackages = Collections.unmodifiableSet(new HashSet<>(routingPackages));
        this.searchText = (label + " " + packageName + " " + aliases)
                .toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
