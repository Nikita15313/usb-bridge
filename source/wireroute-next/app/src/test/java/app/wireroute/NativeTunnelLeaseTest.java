package app.wireroute;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeTunnelLeaseTest {
    static final class Fake implements NativeTunnelLease.Backend {
        boolean running, failStart, failStop;
        int starts, stops;
        public boolean start(String path, int fd) { starts++; if (failStart) return false; return running = true; }
        public boolean stop() { stops++; if (failStop) return false; running = false; return true; }
        public boolean isRunning() { return running; }
        public long[] statistics() { return new long[]{1, 2, 3, 4}; }
    }

    @Test public void rejectedPlainBootstrapCannotStopEncryptedWorker() {
        Fake nativeWorker = new Fake(); NativeTunnelLease lease = new NativeTunnelLease(nativeWorker);
        Object encrypted = new Object(), delayedPlain = new Object();
        assertTrue(lease.start(encrypted, "encrypted.yml", 7));
        assertFalse(lease.start(delayedPlain, "plain.yml", 9));
        assertTrue(lease.stop(delayedPlain));
        assertEquals(0, nativeWorker.stops);
        assertTrue(lease.isRunning(encrypted));
        assertFalse(lease.isRunning(delayedPlain));
        assertArrayEquals(new long[4], lease.statistics(delayedPlain));
    }

    @Test public void failedStartNeverAcquiresOwnershipOrStopsAnotherWorker() {
        Fake nativeWorker = new Fake(); NativeTunnelLease lease = new NativeTunnelLease(nativeWorker);
        Object failed = new Object(), successor = new Object(); nativeWorker.failStart = true;
        assertFalse(lease.start(failed, "bad.yml", 7));
        nativeWorker.failStart = false;
        assertTrue(lease.start(successor, "good.yml", 9));
        assertTrue(lease.stop(failed));
        assertTrue(lease.isRunning(successor));
        assertEquals(0, nativeWorker.stops);
    }

    @Test public void exitedWorkerMustBeJoinedBeforeSuccessorStarts() {
        Fake nativeWorker = new Fake(); NativeTunnelLease lease = new NativeTunnelLease(nativeWorker);
        Object previous = new Object(), next = new Object();
        assertTrue(lease.start(previous, "first.yml", 7)); nativeWorker.running = false;
        assertFalse(lease.start(next, "next.yml", 8));
        assertTrue(lease.stop(previous));
        assertTrue(lease.start(next, "next.yml", 8));
        assertTrue(lease.stop(previous));
        assertTrue(lease.isRunning(next));
        assertEquals(1, nativeWorker.stops);
    }

    @Test public void failedStopRetainsOwnershipInsteadOfAdvertisingFreeEngine() {
        Fake nativeWorker = new Fake(); NativeTunnelLease lease = new NativeTunnelLease(nativeWorker);
        Object owner = new Object(), other = new Object();
        assertTrue(lease.start(owner, "one.yml", 7)); nativeWorker.failStop = true;
        assertFalse(lease.stop(owner)); assertTrue(lease.owns(owner));
        assertFalse(lease.start(other, "other.yml", 9));
        nativeWorker.failStop = false; assertTrue(lease.stop(owner));
        assertTrue(lease.start(other, "other.yml", 9));
    }

    @Test public void successfulStopCanBeRepeatedWithoutTouchingNewSession() {
        Fake nativeWorker = new Fake(); NativeTunnelLease lease = new NativeTunnelLease(nativeWorker);
        Object first = new Object(), second = new Object();
        assertTrue(lease.start(first, "first.yml", 7)); assertTrue(lease.stop(first));
        assertTrue(lease.start(second, "second.yml", 9)); assertTrue(lease.stop(first));
        assertTrue(lease.isRunning(second)); assertEquals(1, nativeWorker.stops);
    }
}
