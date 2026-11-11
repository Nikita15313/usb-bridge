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
}
