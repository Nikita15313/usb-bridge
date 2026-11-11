package app.wireroute;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UsbBridgeStartAckTest {
    @Test public void oldProviderWithoutExplicitBarrierCannotStartEncryption() {
        assertFalse(UsbBridgeUnderlay.acceptsStartingAck(314, 314, true, true, false));
    }
    @Test public void acknowledgedBarrierFromAnotherCableSessionIsRejected() {
        assertFalse(UsbBridgeUnderlay.acceptsStartingAck(314, 313, true, true, true));
        assertFalse(UsbBridgeUnderlay.acceptsStartingAck(0, 0, true, true, true));
    }
    @Test public void lostSelectionOrCableInvalidatesAcknowledgedBarrier() {
        assertFalse(UsbBridgeUnderlay.acceptsStartingAck(314, 314, false, true, true));
        assertFalse(UsbBridgeUnderlay.acceptsStartingAck(314, 314, true, false, true));
    }
    @Test public void exactLiveSelectedSessionWithVerifiedBarrierMayStart() {
        assertTrue(UsbBridgeUnderlay.acceptsStartingAck(314, 314, true, true, true));
    }
}
