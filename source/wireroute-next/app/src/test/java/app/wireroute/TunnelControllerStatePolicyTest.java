package app.wireroute;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TunnelControllerStatePolicyTest {
    @Test
    public void unexpectedBackendDownInvalidatesVisibleOrPendingUp() {
        assertTrue(TunnelController.unexpectedBackendDownNeedsInvalidation(
                TunnelController.State.UP));
        assertTrue(TunnelController.unexpectedBackendDownNeedsInvalidation(
                TunnelController.State.CONNECTING));
        assertFalse(TunnelController.unexpectedBackendDownNeedsInvalidation(
                TunnelController.State.DISCONNECTING));
        assertFalse(TunnelController.unexpectedBackendDownNeedsInvalidation(
                TunnelController.State.ERROR));
        assertFalse(TunnelController.unexpectedBackendDownNeedsInvalidation(
                TunnelController.State.DOWN));
    }

    @Test
    public void upPublicationRequiresCurrentOperationAndDesiredMode() {
        assertTrue(TunnelController.canPublishUp(7L, 7L, true));
        assertFalse(TunnelController.canPublishUp(7L, 8L, true));
        assertFalse(TunnelController.canPublishUp(7L, 7L, false));
    }

    @Test
    public void plainBootstrapWaitsForNativeDownEvenAfterOffIntent() {
        for (TunnelController.State state : TunnelController.State.values()) {
            assertFalse(TunnelController.canStartPlainUsb(state, true));
            if (state != TunnelController.State.DOWN)
                assertFalse(TunnelController.canStartPlainUsb(state, false));
        }
        assertTrue(TunnelController.canStartPlainUsb(TunnelController.State.DOWN, false));
    }

    @Test
    public void selectedUsbSessionAdoptsAnAlreadyEnabledSystemVpn() {
        assertTrue(TunnelController.canHandoverSystemToUsb(
                TunnelController.State.UP, true, false, 42, 42, false));
        assertTrue(TunnelController.canHandoverSystemToUsb(
                TunnelController.State.CONNECTING, true, false, 42, 42, false));
        for (TunnelController.State state : TunnelController.State.values()) {
            assertFalse(TunnelController.canHandoverSystemToUsb(state, false, false, 42, 42, false));
            assertFalse(TunnelController.canHandoverSystemToUsb(state, true, true, 42, 42, false));
            assertFalse(TunnelController.canHandoverSystemToUsb(state, true, false, 42, 42, true));
            if (state != TunnelController.State.UP && state != TunnelController.State.CONNECTING)
                assertFalse(TunnelController.canHandoverSystemToUsb(state, true, false, 42, 42, false));
        }
    }

    @Test
    public void handoverRequiresTheExactCurrentlySelectedCableSession() {
        assertFalse(TunnelController.canHandoverSystemToUsb(
                TunnelController.State.UP, true, false, 0, 0, false));
        assertFalse(TunnelController.canHandoverSystemToUsb(
                TunnelController.State.UP, true, false, -1, -1, false));
        assertFalse(TunnelController.canHandoverSystemToUsb(
                TunnelController.State.UP, true, false, 42, 0, false));
        assertFalse(TunnelController.canHandoverSystemToUsb(
                TunnelController.State.UP, true, false, 42, 43, false));
    }

    @Test
    public void queuedHandoverStillHonorsTheExistingOffAndSessionGuards() throws Exception {
        assertTrue(TunnelController.canHandoverSystemToUsb(
                TunnelController.State.UP, true, false, 42, 42, false));
        assertTrue(UsbConnectivityRefresh.current(42, 42, 7, 7));
        assertFalse(UsbConnectivityRefresh.current(42, 42, 7, 8)); // New explicit Off/Connect.
        assertFalse(TunnelController.canPublishUp(9, 9, false)); // Off retains priority.
        assertFalse(TunnelController.canPublishUp(9, 10, true)); // Replacement operation wins.
        assertFalse(UsbConnectivityRefresh.current(42, 0, 7, 7)); // Cable removed.
        assertFalse(UsbConnectivityRefresh.current(42, 43, 7, 7)); // Another cable session.
        UsbConnectivityRefresh.requireSameSession(42, 42);
    }

    @Test
    public void lostPinnedSessionReleasesTheEnabledIntentAfterNativeCleanup() {
        assertTrue(TunnelController.shouldCancelPinnedUsbStart(
                TunnelController.Failure.USB_SOURCE, 42, 0, 9, 9, true));
        assertTrue(TunnelController.shouldCancelPinnedUsbStart(
                TunnelController.Failure.USB_SOURCE, 42, 43, 9, 9, true));
        assertFalse(TunnelController.shouldCancelPinnedUsbStart(
                TunnelController.Failure.USB_SOURCE, 42, 0, 9, 9, false));
        // Cancellation leaves the controller DOWN with desired=false: the next
        // ordinary Bridge bootstrap is eligible, but encrypted UP cannot publish.
        assertTrue(TunnelController.canStartPlainUsb(TunnelController.State.DOWN, false));
        assertFalse(TunnelController.canPublishUp(9, 9, false));
    }

    @Test
    public void failedStartCancellationCannotOverwriteNewerOffOrConnect() {
        assertFalse(TunnelController.shouldCancelPinnedUsbStart(
                TunnelController.Failure.USB_SOURCE, 42, 0, 9, 10, true));
        assertFalse(TunnelController.shouldCancelPinnedUsbStart(
                TunnelController.Failure.USB_SOURCE, 42, 43, 9, 10, true));
    }

    @Test
    public void sharingBarrierFailureIsCancelledOnlyAfterItsPinnedSessionEnded() {
        assertTrue(TunnelController.shouldCancelPinnedUsbStart(
                TunnelController.Failure.USB_SWITCH, 42, 0, 9, 9, true));
        assertTrue(TunnelController.shouldCancelPinnedUsbStart(
                TunnelController.Failure.USB_SWITCH, 42, 43, 9, 9, true));
        assertFalse(TunnelController.shouldCancelPinnedUsbStart(
                TunnelController.Failure.USB_SWITCH, 42, 42, 9, 9, true));
        assertFalse(TunnelController.shouldCancelPinnedUsbStart(
                TunnelController.Failure.USB_SWITCH, 42, 0, 9, 9, false));
        assertFalse(TunnelController.shouldCancelPinnedUsbStart(
                TunnelController.Failure.USB_SWITCH, 42, 0, 9, 10, true));
    }

    @Test
    public void validSessionAndOrdinaryFailuresKeepTheirErrorState() {
        assertFalse(TunnelController.shouldCancelPinnedUsbStart(
                TunnelController.Failure.USB_SOURCE, 42, 42, 9, 9, true));
        assertFalse(TunnelController.shouldCancelPinnedUsbStart(
                TunnelController.Failure.USB_SOURCE, 0, 0, 9, 9, true));
        assertFalse(TunnelController.shouldCancelPinnedUsbStart(
                TunnelController.Failure.USB_SOURCE, -1, 0, 9, 9, true));
        for (TunnelController.Failure failure : TunnelController.Failure.values()) {
            if (failure != TunnelController.Failure.USB_SOURCE
                    && failure != TunnelController.Failure.USB_SWITCH)
                assertFalse(TunnelController.shouldCancelPinnedUsbStart(failure, 42, 0, 9, 9, true));
        }
    }
}
