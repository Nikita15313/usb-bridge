package app.wireroute;

/** A separate inventory filter; it never changes a saved VPN choice. */
public enum AppCategory {
    APPS, SYSTEM, ALL;

    public boolean matches(InstalledApp app) {
        return this == ALL || (this == SYSTEM) == app.systemComponent;
    }
}
