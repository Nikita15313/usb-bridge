package app.wireroute;

import android.app.Application;

import app.wireroute.sites.LegacyProfileMigration;

public final class WireRouteApplication extends Application {
    private SecureConfigStore configStore;
    private RoutingPreferences routingPreferences;
    private TunnelController tunnelController;

    @Override
    public void onCreate() {
        super.onCreate();
        UpgradeSnapshot.preserve(this);
        configStore = new SecureConfigStore(this);
        routingPreferences = new RoutingPreferences(this);
        tunnelController = new TunnelController(this, configStore, routingPreferences);
        // No job or alternative VPN engine survives the upgrade. Recovery of an
        // old encrypted profile, if needed, is performed once from the main UI.
        LegacyProfileMigration.retireExperiments(this);
    }

    public SecureConfigStore getConfigStore() {
        return configStore;
    }

    public RoutingPreferences getRoutingPreferences() {
        return routingPreferences;
    }

    public TunnelController getTunnelController() {
        return tunnelController;
    }
}
