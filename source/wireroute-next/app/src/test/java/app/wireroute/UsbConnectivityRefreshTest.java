package app.wireroute;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UsbConnectivityRefreshTest {
    @Test public void latePreparationRenewsExactlyTheNetworkThatMissedTheEvent() {
        UsbConnectivityRefresh refresh = new UsbConnectivityRefresh();
        assertFalse(refresh.claim(11, 0, 90, true));
        assertTrue(refresh.claim(11, 90, 90, true));
        assertFalse(refresh.claim(11, 90, 90, true));
        assertFalse(refresh.claim(11, 90, 91, true));
    }
    @Test public void userVpnToggleAlreadyCreatedANewNetworkAfterPreparation() {
        UsbConnectivityRefresh refresh = new UsbConnectivityRefresh();
        assertFalse(refresh.claim(11, 90, 91, true));
        assertFalse(refresh.claim(11, 90, 90, true));
    }
    @Test public void incompleteOrChangingModeDoesNotConsumeTheOneRefresh() {
        UsbConnectivityRefresh refresh = new UsbConnectivityRefresh();
        assertFalse(refresh.claim(0, 90, 90, true));
        assertFalse(refresh.claim(11, 90, 0, true));
        assertFalse(refresh.claim(11, 90, 90, false));
        assertTrue(refresh.claim(11, 90, 90, true));
    }
    @Test public void aNewUsbSessionCanRecoverItsOwnInitialPlainNetwork() {
        UsbConnectivityRefresh refresh = new UsbConnectivityRefresh();
        assertTrue(refresh.claim(11, 90, 90, true));
        assertTrue(refresh.claim(12, 95, 95, true));
        assertFalse(refresh.claim(12, 95, 96, true));
    }
    @Test public void queuedRefreshCannotOverrideNewUserChoiceOrCableSession() {
        assertTrue(UsbConnectivityRefresh.current(11, 11, 4, 4));
        assertFalse(UsbConnectivityRefresh.current(11, 0, 4, 4));
        assertFalse(UsbConnectivityRefresh.current(11, 12, 4, 4));
        assertFalse(UsbConnectivityRefresh.current(11, 11, 4, 5));
        assertFalse(UsbConnectivityRefresh.current(0, 0, 4, 4));
    }
    @Test(expected = java.io.IOException.class)
    public void bridgeDisappearingAfterTheInitialCheckCannotFallBackToPhoneNetwork() throws Exception {
        assertTrue(UsbConnectivityRefresh.current(11, 11, 4, 4));
        UsbConnectivityRefresh.requireSameSession(11, 0);
    }
    @Test(expected = java.io.IOException.class)
    public void cableReconnectAfterTheInitialCheckCannotAdoptAnotherSession() throws Exception {
        assertTrue(UsbConnectivityRefresh.current(11, 11, 4, 4));
        UsbConnectivityRefresh.requireSameSession(11, 12);
    }
    @Test public void exactRefreshSessionAndExplicitConnectKeepTheirAllowedSources() throws Exception {
        UsbConnectivityRefresh.requireSameSession(11, 11);
        UsbConnectivityRefresh.requireSameSession(0, 0);
        UsbConnectivityRefresh.requireSameSession(0, 12);
    }
    @Test public void offCancelsAnAlreadyClaimedRefreshWithoutRearmingIt() {
        UsbConnectivityRefresh refresh = new UsbConnectivityRefresh();
        assertTrue(refresh.claim(11, 90, 90, true));
        assertFalse(UsbConnectivityRefresh.current(11, 11, 4, 5));
        assertFalse(refresh.claim(11, 90, 90, true));
    }
}
