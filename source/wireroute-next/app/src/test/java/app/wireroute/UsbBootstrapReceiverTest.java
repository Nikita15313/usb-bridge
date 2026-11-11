package app.wireroute;

import static org.junit.Assert.*;
import org.junit.Test;

public final class UsbBootstrapReceiverTest {
    @Test public void bootstrapNeedsExactLiveSession() {
        assertFalse(UsbBootstrapReceiver.matchesSession(0, 0));
        assertFalse(UsbBootstrapReceiver.matchesSession(-1, -1));
        assertFalse(UsbBootstrapReceiver.matchesSession(42, 0));
        assertFalse(UsbBootstrapReceiver.matchesSession(41, 42));
        assertTrue(UsbBootstrapReceiver.matchesSession(42, 42));
    }
}
