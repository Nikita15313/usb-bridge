package app.wireroute;

import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.*;

public final class SessionDiagnosticsTest {
    private static final class TestClock implements SessionDiagnostics.Clock {
        long elapsed;
        long wall = 1_800_000_000_000L;
        @Override public long elapsedMillis() { return elapsed; }
        @Override public long wallMillis() { return wall; }
        void advance(long millis) { elapsed += millis; wall += millis; }
    }
    private static final class MemoryStore implements SessionDiagnostics.Store {
        String text = "";
        int writes;
        @Override public String read() { return text; }
        @Override public void write(String text) { this.text = text; writes++; }
    }

    @Test public void oldHandshakeAndIdleTunnelAreNotFailure() {
        TestClock clock = new TestClock();
        SessionDiagnostics observer = new SessionDiagnostics(clock, new MemoryStore());
        observer.sample("UP", true, false, 10_000, 10_000, clock.wall - 900_000);
        clock.advance(180_000);
        SessionDiagnostics.Report report = observer.sample("UP", true, false, 10_000, 10_000,
                clock.wall - 1_080_000);
        assertEquals(SessionDiagnostics.Health.OBSERVING, report.health);
        assertEquals(1080, report.handshakeAgeSeconds);
        assertFalse(report.summary.contains("сбой"));
    }

    @Test public void keepaliveSizedOutboundIsNotEvidenceOfStall() {
        TestClock clock = new TestClock();
        SessionDiagnostics observer = new SessionDiagnostics(clock, new MemoryStore());
        observer.sample("UP", true, false, 0, 0, 0);
        for (int index = 1; index <= 100; index++) {
            clock.advance(25_000);
            assertEquals(SessionDiagnostics.Health.OBSERVING,
                    observer.sample("UP", true, false, 0, index * 32, 0).health);
        }
    }

    @Test public void outboundWithoutInboundIsNeutralObservationAndReplyClearsIt() {
        TestClock clock = new TestClock();
        SessionDiagnostics observer = new SessionDiagnostics(clock, new MemoryStore());
        observer.sample("UP", true, false, 0, 0, 0);
        clock.advance(5_000);
        observer.sample("UP", true, false, 0, 2048, 0);
        clock.advance(30_000);
        SessionDiagnostics.Report quiet = observer.sample("UP", true, false, 0, 4096, 0);
        assertEquals(SessionDiagnostics.Health.QUIET_RX, quiet.health);
        assertTrue(quiet.details.contains("не подтверждение сбоя"));
        clock.advance(5_000);
        SessionDiagnostics.Report received = observer.sample("UP", true, false, 32, 4096, clock.wall);
        assertEquals(SessionDiagnostics.Health.RECEIVING, received.health);
        assertTrue(received.details.contains("служебные пакеты"));
        assertTrue(received.details.contains("не проверялась"));
    }

    @Test public void unavailableOrResetCountersDoNotManufactureTraffic() {
        TestClock clock = new TestClock();
        SessionDiagnostics observer = new SessionDiagnostics(clock, new MemoryStore());
        observer.sample("UP", true, false, 4000, 4000, 0);
        clock.advance(5_000);
        assertEquals(SessionDiagnostics.Health.UNKNOWN,
                observer.sample("UP", true, true, 9000, 9000, 0).health);
        clock.advance(5_000);
        assertEquals(SessionDiagnostics.Health.OBSERVING,
                observer.sample("UP", true, false, 20_000, 20_000, 0).health);
        clock.advance(5_000);
        assertEquals(SessionDiagnostics.Health.OBSERVING,
                observer.sample("UP", true, false, 0, 0, 0).health);
        assertTrue(observer.getLogText().contains("counters_reset"));
    }

    @Test public void wallClockAdjustmentDoesNotAdvanceQuietReceiveTimer() {
        TestClock clock = new TestClock();
        SessionDiagnostics observer = new SessionDiagnostics(clock, new MemoryStore());
        observer.sample("UP", true, false, 0, 0, 0);
        clock.advance(5_000);
        observer.sample("UP", true, false, 0, 2048, 0);
        clock.wall += 3_600_000;
        assertEquals(SessionDiagnostics.Health.OBSERVING,
                observer.sample("UP", true, false, 0, 4096, 0).health);
    }

    @Test public void serviceStopClearsLastReplyAndNewSessionDoesNotReuseCounters() {
        TestClock clock = new TestClock();
        SessionDiagnostics observer = new SessionDiagnostics(clock, new MemoryStore());
        observer.sample("UP", true, false, 0, 0, 0);
        clock.advance(5_000);
        observer.sample("UP", true, false, 32, 32, clock.wall);
        assertEquals(SessionDiagnostics.Health.RECEIVING, observer.getReport().health);
        observer.noteEvent("SERVICE_STOPPED");
        assertEquals(SessionDiagnostics.Health.STOPPED, observer.getReport().health);
        observer.noteEvent("SERVICE_STARTED");
        assertEquals(SessionDiagnostics.Health.TRANSITIONING, observer.getReport().health);
        assertEquals(SessionDiagnostics.Health.OBSERVING,
                observer.sample("UP", true, false, 2000, 2000, clock.wall).health);
    }

    @Test public void summaryWritesAreRateLimitedAndJournalIsBoundedAcrossLoads() {
        TestClock clock = new TestClock();
        MemoryStore store = new MemoryStore();
        SessionDiagnostics observer = new SessionDiagnostics(clock, store);
        observer.sample("UP", true, false, 0, 0, 0);
        int initialWrites = store.writes;
        for (int index = 0; index < 11; index++) {
            clock.advance(5_000);
            observer.sample("UP", true, false, 0, 0, 0);
        }
        assertEquals(initialWrites, store.writes);
        clock.advance(5_000);
        observer.sample("UP", true, false, 0, 0, 0);
        assertEquals(initialWrites + 1, store.writes);
        for (int index = 0; index < 400; index++) {
            observer.noteUnderlay(index % 2 == 0 ? "AVAILABLE" : "LOST", "WIFI", true, false);
        }
        assertEquals(SessionDiagnostics.MAX_ENTRIES, observer.getLogText().split("\n").length);
        observer = new SessionDiagnostics(clock, store);
        assertEquals(SessionDiagnostics.MAX_ENTRIES, observer.getLogText().split("\n").length);
    }

    @Test public void networkFieldsAreAllowlistedAndOnlyPublicFingerprintIsStored() {
        SessionDiagnostics observer = new SessionDiagnostics(new TestClock(), new MemoryStore());
        observer.noteUnderlay("https://private.example/a", "192.0.2.1", false, true);
        observer.noteEvent("https://private.example/a");
        observer.noteEvent("SERVICE_STARTED");
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        assertEquals("66687aadf862", SessionDiagnostics.publicKeyFingerprint(key));
        observer.notePublicKey(key);
        assertFalse(observer.getLogText().contains(key));
        assertFalse(observer.getLogText().contains("private.example"));
        assertFalse(observer.getLogText().contains("192.0.2.1"));
        assertTrue(observer.getLogText().contains("66687aadf862"));
        assertTrue(observer.getLogText().contains("event=SERVICE_STARTED"));
        assertEquals("", SessionDiagnostics.publicKeyFingerprint("bad key"));
        assertEquals("", SessionDiagnostics.publicKeyFingerprint("YWJj"));
    }

    @Test public void persistedNonJournalTextIsNeverRecoveredForUsbExport() {
        MemoryStore store = new MemoryStore();
        store.write("PrivateKey = must-never-be-logged\n"
                + "2026-09-06T00:00:00Z elapsed_ms=1 state=UP secret.example\n"
                + "2026-09-06T00:00:00Z elapsed_ms=1 state=UP\n");
        SessionDiagnostics observer = new SessionDiagnostics(new TestClock(), store);
        assertFalse(observer.getLogText().contains("must-never"));
        assertFalse(observer.getLogText().contains("secret.example"));
        assertTrue(observer.getLogText().contains("elapsed_ms=1 state=UP"));
        for (String line : observer.getLogText().split("\n")) {
            assertTrue(line, SessionDiagnostics.isSafeJournalLine(line));
        }
    }
}
