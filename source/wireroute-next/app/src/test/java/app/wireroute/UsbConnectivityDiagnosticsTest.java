package app.wireroute;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UsbConnectivityDiagnosticsTest {
    @Test public void missingHelperPreparationIsSeparateFromModernDataPath() {
        assertEquals("ANDROID_NETWORK_PREPARATION_INCOMPLETE",
                UsbConnectivityDiagnostics.classify(true, true, false, false, true));
        assertEquals("LEGACY_STATUS_DIFFERS_FOR_OBSERVER",
                UsbConnectivityDiagnostics.classify(true, true, true, false, true));
    }
    @Test public void absentOlderProviderMetadataIsUnknownInsteadOfFailure() {
        assertEquals("UNKNOWN", UsbConnectivityDiagnostics.classify(true, true, null, null, null));
        assertEquals("USB_NOT_SELECTED", UsbConnectivityDiagnostics.classify(false, false, null, null, null));
        assertEquals("USB_DISCONNECTED", UsbConnectivityDiagnostics.classify(true, false, true, true, true));
    }
    @Test public void legacyPredicateIncludesAllTelegramFallbacks() {
        assertFalse(UsbConnectivityDiagnostics.legacyOnline(false, false, false, false));
        assertTrue(UsbConnectivityDiagnostics.legacyOnline(true, false, false, false));
        assertTrue(UsbConnectivityDiagnostics.legacyOnline(false, true, false, false));
        assertTrue(UsbConnectivityDiagnostics.legacyOnline(false, false, true, false));
        assertTrue(UsbConnectivityDiagnostics.legacyOnline(false, false, false, true));
    }
    @Test public void inclusionAndBypassModesHaveOppositeApplicationMeaning() {
        assertTrue(UsbConnectivityDiagnostics.configuredVpn(RoutingMode.ONLY_SELECTED, true));
        assertFalse(UsbConnectivityDiagnostics.configuredVpn(RoutingMode.ONLY_SELECTED, false));
        assertFalse(UsbConnectivityDiagnostics.configuredVpn(RoutingMode.BYPASS_SELECTED, true));
        assertTrue(UsbConnectivityDiagnostics.configuredVpn(RoutingMode.BYPASS_SELECTED, false));
    }
    @Test public void multipleTelegramVariantsAreNotMistakenForSameSelectedApp() {
        assertEquals("SOME_KNOWN_VARIANTS_OUTSIDE_CONFIGURED_VPN",
                UsbConnectivityDiagnostics.telegramCheck(true, 2, 1));
        assertEquals("KNOWN_VARIANTS_OUTSIDE_CONFIGURED_VPN",
                UsbConnectivityDiagnostics.telegramCheck(true, 1, 0));
        assertEquals("PHONE_VPN_OFF", UsbConnectivityDiagnostics.telegramCheck(false, 2, 2));
        assertEquals("KNOWN_VARIANT_NOT_FOUND", UsbConnectivityDiagnostics.telegramCheck(true, 0, 0));
    }
    @Test public void positiveStatusDoesNotClaimTelegramRemoteConnectionWorks() {
        assertEquals("KNOWN_VARIANTS_IN_CONFIGURED_VPN_REMOTE_NOT_TESTED",
                UsbConnectivityDiagnostics.telegramCheck(true, 1, 1));
        assertEquals("LEGACY_STATUS_AVAILABLE_FOR_OBSERVER",
                UsbConnectivityDiagnostics.classify(true, true, true, true, true));
    }
}
