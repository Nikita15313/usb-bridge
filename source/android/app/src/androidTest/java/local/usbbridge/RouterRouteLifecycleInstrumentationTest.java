package local.usbbridge;

import android.test.InstrumentationTestCase;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class RouterRouteLifecycleInstrumentationTest extends InstrumentationTestCase {
    public void testCurrentVpnLossClosesSocketsBeforeStoppingItsHotspot() {
        Object lock = new Object(), route = new Object();
        List<String> order = new ArrayList<>();
        RouterService.revokeRoute(lock, route, () -> route,
                () -> order.add("closed"), () -> order.add("stopped"));
        assertEquals(Arrays.asList("closed", "stopped"), order);
    }

    public void testOldVpnLossCannotStopReplacementPlainHotspot() throws Exception {
        Object lock = new Object(), oldVpn = new Object(), newPlain = new Object();
        AtomicReference<Object> current = new AtomicReference<>(oldVpn);
        AtomicBoolean stopped = new AtomicBoolean();
        CountDownLatch socketsClosed = new CountDownLatch(1);
        Thread callback = new Thread(() -> RouterService.revokeRoute(lock, oldVpn, current::get,
                socketsClosed::countDown, () -> stopped.set(true)));
        synchronized (lock) {
            callback.start();
            assertTrue("Revocation must not wait for a long AP lifecycle operation", socketsClosed.await(2, TimeUnit.SECONDS));
            current.set(newPlain);
        }
        callback.join(2000);
        assertFalse("Callback did not finish", callback.isAlive());
        assertFalse("Old onLost stopped the new plain AP", stopped.get());
    }

    public void testQueuedOldVpnFailureAfterStopDoesNotRestartCleanup() {
        Object oldVpn = new Object();AtomicBoolean closed = new AtomicBoolean(), stopped = new AtomicBoolean();
        RouterService.revokeRoute(new Object(), oldVpn, () -> null,
                () -> closed.set(true), () -> stopped.set(true));
        assertTrue(closed.get());assertFalse(stopped.get());
    }

    public void testConfirmedForwardingBarrierKeepsAssociatedWifiClients() {
        AtomicBoolean forwarding = new AtomicBoolean(true), radioStopped = new AtomicBoolean();
        assertTrue(RouterService.pauseForwarding(true, () -> {forwarding.set(false);return true;}, forwarding::get,
                () -> radioStopped.set(true)));
        assertFalse("Native forwarding must be stopped before ACK", forwarding.get());
        assertFalse("A verified barrier must preserve the Wi-Fi association and lease", radioStopped.get());
    }

    public void testUnconfirmedBarrierStopsRadioRatherThanLeaksDirectTraffic() {
        AtomicBoolean radioStopped = new AtomicBoolean();
        assertFalse(RouterService.pauseForwarding(true, () -> true, () -> true,
                () -> radioStopped.set(true)));
        assertTrue("If native forwarding survives, preserving the AP is unsafe", radioStopped.get());
    }

    public void testNativeShutdownExceptionCannotBeAcknowledgedAsBlocked() {
        AtomicBoolean radioStopped = new AtomicBoolean();
        assertFalse(RouterService.pauseForwarding(true, () -> { throw new IllegalStateException("native failed"); },
                () -> false, () -> radioStopped.set(true)));
        assertTrue(radioStopped.get());
    }

    public void testNativeStateFailureCannotBeAcknowledgedAsBlocked() {
        AtomicBoolean radioStopped = new AtomicBoolean();
        assertFalse(RouterService.pauseForwarding(true, () -> true,
                () -> { throw new IllegalStateException("state unavailable"); }, () -> radioStopped.set(true)));
        assertTrue(radioStopped.get());
    }

    public void testInitialVpnPolicyWithoutTestNetworkDoesNotTouchManualHotspot() {
        AtomicInteger nativeCalls = new AtomicInteger(), radioCalls = new AtomicInteger();
        assertTrue(RouterService.pauseForwarding(false, () -> {nativeCalls.incrementAndGet();return true;},
                () -> { nativeCalls.incrementAndGet(); return true; }, radioCalls::incrementAndGet));
        assertEquals(0, nativeCalls.get());assertEquals(0, radioCalls.get());
    }

    public void testSourceReplacedWhileOldLossWaitsCannotPauseNewForwarding() throws Exception {
        Object lock = new Object(), old = new Object(), replacement = new Object();
        AtomicReference<Object> current = new AtomicReference<>(old);
        AtomicBoolean forwarding = new AtomicBoolean(true), radioStopped = new AtomicBoolean();
        CountDownLatch closed = new CountDownLatch(1);
        Thread loss = new Thread(() -> RouterService.revokeRoute(lock, old, current::get, closed::countDown,
                () -> RouterService.pauseForwarding(true, () -> {forwarding.set(false);return true;}, forwarding::get,
                        () -> radioStopped.set(true))));
        synchronized(lock) {
            loss.start();assertTrue(closed.await(2, TimeUnit.SECONDS));current.set(replacement);
        }
        loss.join(2000);assertFalse(loss.isAlive());
        assertTrue("A delayed onLost must not pause a replacement PC/VPN source", forwarding.get());
        assertFalse(radioStopped.get());
    }

    public void testFalseNativeStopNeverConfirmsBarrierEvenIfRunningFlagIsFalse() {
        AtomicBoolean radioStopped = new AtomicBoolean();
        assertFalse(RouterService.pauseForwarding(true, () -> false, () -> false, () -> radioStopped.set(true)));
        assertTrue("A failed native join is not proof of stopped forwarding", radioStopped.get());
    }

    public void testFailedSwitchCannotReuseOldNativeWorkerOnIdenticalRetry() throws Exception {
        RouterService.DatapathState state = new RouterService.DatapathState();
        AtomicBoolean running = new AtomicBoolean();List<String> order = new ArrayList<>();
        state.apply(1, running::get, () -> {running.set(false);return true;}, () -> running.set(true), () -> {});
        assertTrue(state.applied(1));
        try {
            state.apply(2, running::get, () -> {order.add("failed stop");return false;},
                    () -> order.add("must not start"), () -> order.add("radio stopped"));
            throw new AssertionError("Failed barrier was acknowledged");
        } catch(IOException expected) {}
        assertFalse("Requested generation must not be marked applied before confirmed restart", state.applied(2));
        assertTrue("The old worker is deliberately still running in this fault", running.get());
        state.apply(2, running::get, () -> {order.add("verified stop");running.set(false);return true;},
                () -> {order.add("new config and start");running.set(true);}, () -> order.add("unexpected radio stop"));
        assertEquals(Arrays.asList("failed stop", "radio stopped", "verified stop", "new config and start"), order);
        assertTrue(state.applied(2));assertFalse(state.applied(1));
    }

    public void testFailedStartInvalidatesAppliedGenerationAndRechecksBarrierOnRetry() throws Exception {
        RouterService.DatapathState state = new RouterService.DatapathState();
        AtomicBoolean running = new AtomicBoolean();AtomicInteger stops = new AtomicInteger(), starts = new AtomicInteger();
        java.util.function.BooleanSupplier stop = () -> {stops.incrementAndGet();running.set(false);return true;};
        try {
            state.apply(7, running::get, stop, () -> {starts.incrementAndGet();running.set(true);throw new IOException("late startup failure");}, () -> {});
            throw new AssertionError("Failed native start was accepted");
        } catch(IOException expected) {}
        assertFalse(state.applied(7));assertFalse(running.get());
        assertEquals(2, stops.get()); // Before start and cleanup of the failed worker.
        state.apply(7, running::get, stop, () -> {starts.incrementAndGet();running.set(true);}, () -> {});
        assertEquals(3, stops.get());assertEquals(2, starts.get());assertTrue(state.applied(7));
    }

    public void testOnlyHealthyAppliedGenerationCanUseFastPath() throws Exception {
        RouterService.DatapathState state = new RouterService.DatapathState();
        AtomicBoolean running = new AtomicBoolean();AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger();
        java.util.function.BooleanSupplier stop = () -> {stops.incrementAndGet();running.set(false);return true;};
        RouterService.CheckedOperation start = () -> {starts.incrementAndGet();running.set(true);};
        state.apply(3, running::get, stop, start, () -> {});
        state.apply(3, running::get, stop, start, () -> {});
        assertEquals(1, stops.get());assertEquals(1, starts.get());
        state.invalidate(); // Refresh or a source failure, although native is still alive.
        state.apply(3, running::get, stop, start, () -> {});
        assertEquals(2, stops.get());assertEquals(2, starts.get());
    }

    // Model an already active USB downstream. Android only selects on network
    // events; changing the preference alone does not retry an ignored TEST.
    public static final class ExistingUsbTethering {
        boolean preferTest, selected, rejectPreference;
        int published;
        public void setPreferTestNetworks(boolean prefer) {
            if(rejectPreference)throw new SecurityException("preference denied");
            preferTest=prefer;
        }
        public void setupTestNetwork(android.net.LinkProperties properties,boolean metered,android.os.IBinder lifetime) {
            published++;
            selected=preferTest;
        }
    }

    public void testFirstPlainHotspotGetsTestUpstreamWithUsbTetheringAlreadyActive() throws Exception {
        ExistingUsbTethering oldOrder=new ExistingUsbTethering();
        oldOrder.setupTestNetwork(null,true,null);
        oldOrder.setPreferTestNetworks(true);
        assertFalse("Late preference cannot replay the missed availability event",oldOrder.selected);

        ExistingUsbTethering fixedOrder=new ExistingUsbTethering();
        RouterService.publishPreferredTestNetwork(fixedOrder,fixedOrder,null,null);
        assertEquals(1,fixedOrder.published);
        assertTrue("Initial plain sharing must not wait for a VPN/default-network change",fixedOrder.selected);
    }

    public void testFailedUpstreamPreferenceCannotPublishUnpreferredTestNetwork() throws Exception {
        ExistingUsbTethering state=new ExistingUsbTethering();state.rejectPreference=true;
        try {
            RouterService.publishPreferredTestNetwork(state,state,null,null);
            throw new AssertionError("Preference failure must abort network startup");
        } catch(java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof SecurityException);
        }
        assertEquals(0,state.published);assertFalse(state.selected);
    }
}
