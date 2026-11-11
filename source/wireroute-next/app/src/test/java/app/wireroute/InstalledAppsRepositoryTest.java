package app.wireroute;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class InstalledAppsRepositoryTest {
    private static final String OWN_PACKAGE = "app.wireroute.release263";
    private static final String CHROME = "com.android.chrome";

    @Test
    public void acceptsInstalledEnabledInternetApplication() {
        assertTrue(InstalledAppsRepository.isEligibleForVpn(
                OWN_PACKAGE, CHROME, true, true, true
        ));
    }

    @Test
    public void rejectsOwnDisabledUninstalledAndOfflinePackages() {
        assertFalse(InstalledAppsRepository.isEligibleForVpn(
                OWN_PACKAGE, OWN_PACKAGE, true, true, true
        ));
        assertFalse(InstalledAppsRepository.isEligibleForVpn(
                OWN_PACKAGE, CHROME, false, true, true
        ));
        assertFalse(InstalledAppsRepository.isEligibleForVpn(
                OWN_PACKAGE, CHROME, true, false, true
        ));
        assertFalse(InstalledAppsRepository.isEligibleForVpn(
                OWN_PACKAGE, CHROME, true, true, false
        ));
        assertFalse(InstalledAppsRepository.isEligibleForVpn(
                OWN_PACKAGE, null, true, true, true
        ));
    }
}
