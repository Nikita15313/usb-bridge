package local.usbbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs on an emulator without USB, Shizuku, a VPN profile, or internet access.
 * Uses real Android socket-pair file descriptors and an authenticated, encrypted
 * Wire peer. The service lifecycle is deliberately not started, so these tests
 * cannot change the emulator's network routes or start its hotspot.
 */
@SuppressWarnings("deprecation")
public final class RelayInstrumentationTest extends InstrumentationTestCase {
    private static final Uri PROVIDER = Uri.parse("content://local.usbbridge.underlay");
    private static final long SESSION = 10601L;
    private Context target;
    private SharedPreferences preferences;
    private String previousPackage;
    private boolean hadPreviousPackage;
    private TestBridgeService service;
    private Fixture fixture;
    private ExecutorService workers;

    /** Attach app context but never call onCreate/onStartCommand. */
    private static final class TestBridgeService extends BridgeService {
        TestBridgeService(Context context) { attachBaseContext(context); }
        @Override void activate() { /* VPN selection cleanup only, no real services. */ }
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        target = getInstrumentation().getTargetContext();
        assertNull("Stop an existing USB Bridge connection before running tests", BridgeService.instance);
        preferences = target.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        hadPreviousPackage = preferences.contains("vpnPackage");
        previousPackage = preferences.getString("vpnPackage", "");
        assertTrue(preferences.edit().putString("vpnPackage", target.getPackageName()).commit());
        workers = Executors.newCachedThreadPool();
        service = new TestBridgeService(target);
        service.relaySession = SESSION;
        service.sessionProven = true;
        BridgeService.connected = true;
        BridgeService.verified = true;
        BridgeService.shareWanted = false;
        BridgeService.wifiVpnRequired = false;
        BridgeService.ownVpnNetwork = 0;
        BridgeService.instance = service;
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (service != null) {
                service.stopping = true;
                service.relaySession = 0;
                service.sessionProven = false;
                // Closing the Mux also wakes senders intentionally blocked by
                // the saturation test, before closing the descriptors.
                if (fixture != null) fixture.close();
                service.closeRelays();
                service.closeLocalRelays();
                if (service.socks != null) service.socks.close();
                if (service.phoneSocks != null) service.phoneSocks.close();
                service.tasks.shutdownNow();
            }
            if (workers != null) {
                workers.shutdownNow();
                workers.awaitTermination(3, TimeUnit.SECONDS);
            }
        } finally {
            BridgeService.instance = null;
            BridgeService.connected = false;
            BridgeService.verified = false;
            BridgeService.shareWanted = false;
            BridgeService.ownVpnSeenAt = 0;
            BridgeService.ownVpnMode = "";
            BridgeService.phoneIssue = "";
            BridgeService.wifiVpnRequired = false;
            BridgeService.ownVpnNetwork = 0;
            if (preferences != null) {
                SharedPreferences.Editor edit = preferences.edit();
                if (hadPreviousPackage) edit.putString("vpnPackage", previousPackage);
                else edit.remove("vpnPackage");
                edit.commit();
            }
            super.tearDown();
        }
    }

    private void wire(boolean startSender) throws Exception {
        fixture = new Fixture(workers, startSender);
        service.mux = fixture.mux;
    }

    private Future<?> echoPeer() {
        return workers.submit(() -> {
            try { for (;;) {
                byte[] incoming = fixture.peer.receive();
                int type = incoming[0] & 255, id = Wire.number(incoming, 1);
                if (type == 5) fixture.peer.send(frame(6, id, new byte[0]));
                else if (type == 2) fixture.peer.send(frame(2, id, Arrays.copyOfRange(incoming, 5, incoming.length)));
                else if (type == 3) fixture.peer.send(frame(3, id, new byte[0]));
            } } catch (Exception closed) { return null; }
        });
    }

    private Socket localClient(boolean phone) throws Exception {
        ServerSocket listener = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        if (phone) { service.phoneSocks = listener; workers.submit(() -> { service.acceptPhoneSocks(); }); }
        else { service.socks = listener; workers.submit(() -> { service.acceptSocks(); }); }
        Socket client = new Socket("127.0.0.1", listener.getLocalPort());
        client.setSoTimeout(3000);
        return client;
    }

    private void assertEcho(Socket client) throws Exception {
        byte[] payload = Wire.cat(Wire.bytes("ordinary-PC hotspot payload"), new byte[]{0, 17, (byte) 255});
        client.getOutputStream().write(payload);
        assertTrue(Arrays.equals(payload, Wire.read(client.getInputStream(), payload.length)));
    }

    public void testEightyPhoneDescriptorsCannotStarvePlainHotspotPayload() throws Exception {
        wire(true); echoPeer();
        java.util.List<ParcelFileDescriptor> held = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 80; i++) held.add(service.openRelay(target.getPackageName(), SESSION));
            assertEquals(0, service.clients.availablePermits());
            assertFalse(BridgeService.wifiVpnRequired);
            try (Socket hotspot = localClient(false)) {
                // Real acceptSocks -> authenticated Mux -> PC peer -> reply.
                // The previous shared pool immediately closed this socket.
                assertEcho(hotspot);
                assertEquals(0, service.clients.availablePermits());
                assertEquals(95, service.localClients.availablePermits());
                service.closeRelays();
                assertEquals(80, service.clients.availablePermits());
                assertEcho(hotspot); // phone revocation must not revoke AP traffic.
                service.closeLocalRelays();
                assertEquals(-1, hotspot.getInputStream().read());
            }
            assertEquals(96, service.localClients.availablePermits());
            assertTrue(service.socketRelays.isEmpty());
            assertTrue(fixture.mux.channels.isEmpty());
        } finally { for (ParcelFileDescriptor fd : held) fd.close(); }
    }

    public void testLegacyPhoneListenerUsesPhonePoolAndCleanupIsIdempotent() throws Exception {
        wire(true); echoPeer();
        try (Socket phone = localClient(true); Socket hotspot = localClient(false)) {
            assertEcho(phone); assertEcho(hotspot);
            assertEquals(79, service.clients.availablePermits());
            assertEquals(95, service.localClients.availablePermits());
            service.closeRelays(); service.closeRelays();
            assertEquals(-1, phone.getInputStream().read());
            assertEquals(80, service.clients.availablePermits());
            assertEcho(hotspot);
            service.closeLocalRelays(); service.closeLocalRelays();
            assertEquals(-1, hotspot.getInputStream().read());
            assertEquals(96, service.localClients.availablePermits());
            assertTrue(fixture.mux.channels.isEmpty());
        }
    }

    public void testSaturatedUsbQueueRejectsLocalSocketWithoutLeakingSlotOrChannel() throws Exception {
        wire(false);
        Wire.Channel existing = fixture.mux.openNow();
        while (fixture.mux.sends.offer(frame(8, 0, new byte[0]))) { }
        try (Socket hotspot = localClient(false)) {
            // No sender is running: blocking open() would wait forever while
            // retaining the new channel, even after local socket revocation.
            assertEquals(-1, hotspot.getInputStream().read());
            await(() -> service.localClients.availablePermits() == 96 && service.socketRelays.isEmpty(),
                    "Rejected AP admission leaked its local connection slot");
            workers.submit(() -> { service.closeLocalRelays(); }).get(1, TimeUnit.SECONDS);
            assertEquals(1, fixture.mux.channels.size());
            assertSame(existing, fixture.mux.channels.get(existing.id));
            assertFalse(existing.dead);
            assertFalse(fixture.mux.closed);
            assertEquals(80, service.clients.availablePermits());
        }
    }

    public void testPeerCapacityIsNegotiatedOverAuthenticatedStatusFrame() throws Exception {
        wire(true);
        assertEquals(96, fixture.mux.peerLimit);
        fixture.peer.send(frame(8, 0, Wire.bytes("LIMIT|192")));
        await(() -> fixture.mux.peerLimit == 192, "Authenticated peer limit was not applied");
        for (String invalid : new String[]{"LIMIT|0", "LIMIT|-1", "LIMIT|193", "LIMIT|x", "LIMIT|2147483648"})
            fixture.mux.capacityStatus(invalid);
        assertEquals(192, fixture.mux.peerLimit);
    }

    public void testOldPeerLimitAndNegotiatedCapacityDoNotCloseExistingStreams() throws Exception {
        wire(false);
        for (int i = 0; i < 96; i++) { fixture.mux.openNow(); fixture.mux.sends.clear(); }
        try { fixture.mux.openNow(); fail("Old peer must retain its 96 stream limit"); } catch (IOException expected) { }
        assertEquals(96, fixture.mux.channels.size()); assertFalse(fixture.mux.closed);
        fixture.mux.capacityStatus("LIMIT|192");
        for (int i = 96; i < 192; i++) { fixture.mux.open(); fixture.mux.sends.clear(); }
        try { fixture.mux.open(); fail("Negotiated limit was exceeded"); } catch (IOException expected) { }
        assertEquals(192, fixture.mux.channels.size()); assertFalse(fixture.mux.closed);
        fixture.mux.close(); assertTrue(fixture.mux.channels.isEmpty());
        try { fixture.mux.openNow(); fail("Closed transport accepted a stream"); } catch (IOException expected) { }
    }

    public void testConcurrentBlockingAndNonblockingAdmissionShareOneLimit() throws Exception {
        wire(false);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Future<?>> attempts = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 32; i++) { final boolean blocking = (i & 1) == 0;
            attempts.add(workers.submit(() -> { start.await(); for (int k = 0; k < 4; k++) {
                try { if (blocking) fixture.mux.open(); else fixture.mux.openNow(); accepted.incrementAndGet(); }
                catch (IOException full) { }
            } return null; }));
        }
        start.countDown(); for (Future<?> attempt : attempts) attempt.get(3, TimeUnit.SECONDS);
        assertEquals(96, accepted.get()); assertEquals(96, fixture.mux.channels.size());
        assertFalse(fixture.mux.closed);
    }

    public void testPermissionReportIsNotAnInternetOrVpnSuccess() {
        BridgeService.ownVpnMode="";BridgeService.ownVpnSeenAt=0;BridgeService.phoneIssue="";
        assertTrue(service.vpnReport(target.getPackageName(),SESSION,"plain_permission","plain",0));
        assertEquals("plain_permission",BridgeService.phoneIssue);
        assertEquals("",BridgeService.ownVpnMode);
        assertEquals(0,BridgeService.ownVpnSeenAt);
        assertFalse(service.vpnReport("unselected.vpn",SESSION,"plain_failed","plain",0));
        assertFalse(service.vpnReport(target.getPackageName(),SESSION-1,"plain_failed","plain",0));
        assertEquals("plain_permission",BridgeService.phoneIssue);
    }

    public void testVpnStartingDoesNotClaimEncryptedConnectionReady() {
        BridgeService.ownVpnMode="plain";BridgeService.ownVpnSeenAt=SystemClock.elapsedRealtime();
        assertTrue(service.vpnReport(target.getPackageName(),SESSION,"starting","vpn",0));
        assertEquals("starting",BridgeService.ownVpnMode);
        assertTrue(BridgeService.wifiVpnRequired);
        assertFalse("Old plain heartbeat must not release encrypted sharing barrier",
            service.vpnReport(target.getPackageName(),SESSION,"alive","plain",0));
        assertTrue(service.vpnReport(target.getPackageName(),SESSION,"stopped","vpn",0));
        assertEquals("",BridgeService.ownVpnMode);
        assertTrue("Unplanned failure must keep Wi-Fi fail-closed",BridgeService.wifiVpnRequired);
        assertTrue(service.vpnReport(target.getPackageName(),SESSION,"started","plain",0));
        assertFalse("Explicit plain start releases VPN barrier",BridgeService.wifiVpnRequired);
        assertEquals("plain",BridgeService.ownVpnMode);
    }

    public void testWrongPackageAndStaleSessionCannotObtainDescriptor() throws Exception {
        wire(false);
        expectDenied(() -> service.openRelay("unselected.vpn", SESSION));
        expectDenied(() -> service.openRelay(target.getPackageName(), SESSION - 1));
        expectDenied(() -> service.openRelay(target.getPackageName(), 0));
        assertEquals(80, service.clients.availablePermits());
        assertTrue(service.relayPipes.isEmpty());
        assertTrue(fixture.mux.channels.isEmpty());

        try (ParcelFileDescriptor valid = service.openRelay(target.getPackageName(), SESSION)) {
            assertTrue(valid.getFd() >= 0);
            assertEquals(79, service.clients.availablePermits());
        }
        service.closeRelays();
        assertEquals(80, service.clients.availablePermits());
    }

    public void testProviderEnforcesCallerAndSession() throws Exception {
        wire(false);
        Bundle state = target.getContentResolver().call(PROVIDER, "network", null, null);
        assertNotNull(state);
        assertTrue(state.getBoolean("selected"));
        assertTrue(state.getBoolean("connected"));
        assertEquals(SESSION, state.getLong("session"));
        expectFileDenied(relayUri(SESSION + 1), "rw");
        expectFileDenied(relayUri(SESSION), "r");

        try (ParcelFileDescriptor valid = target.getContentResolver().openFileDescriptor(relayUri(SESSION), "rw")) {
            assertNotNull(valid);
        }
        service.closeRelays();
        preferences.edit().putString("vpnPackage", "other.selected.vpn").commit();
        Bundle denied = target.getContentResolver().call(PROVIDER, "network", null, null);
        assertNotNull(denied);
        assertTrue("An unselected caller must not learn session or source", denied.isEmpty());
        try {
            ParcelFileDescriptor unexpected = target.getContentResolver().openFileDescriptor(relayUri(SESSION), "rw");
            if (unexpected != null) unexpected.close();
            fail("Provider accepted the unselected calling package");
        } catch (SecurityException expected) { }
        assertEquals(80, service.clients.availablePermits());
    }

    public void testDescriptorCarriesPayloadThroughAuthenticatedMux() throws Exception {
        wire(true);
        final byte[] outbound = Wire.cat(Wire.bytes("phone-to-PC: binary "), new byte[]{0, 1, (byte) 255});
        final byte[] inbound = Wire.cat(Wire.bytes("PC-to-phone: reply "), new byte[]{2, 0, (byte) 254});
        Future<byte[]> remote = workers.submit(() -> {
            for (;;) {
                byte[] frame = fixture.peer.receive();
                int type = frame[0] & 255;
                int id = Wire.number(frame, 1);
                if (type == 5) { fixture.peer.send(frame(6, id, new byte[0])); continue; }
                if (type != 2) continue;
                byte[] payload = Arrays.copyOfRange(frame, 5, frame.length);
                fixture.peer.send(frame(2, id, inbound));
                return payload;
            }
        });
        try (ParcelFileDescriptor fd = service.openRelay(target.getPackageName(), SESSION)) {
            FileOutputStream out = new FileOutputStream(fd.getFileDescriptor());
            out.write(outbound); out.flush();
            Future<byte[]> received = workers.submit(() -> Wire.read(new FileInputStream(fd.getFileDescriptor()), inbound.length));
            assertTrue(Arrays.equals(outbound, remote.get(5, TimeUnit.SECONDS)));
            assertTrue(Arrays.equals(inbound, received.get(5, TimeUnit.SECONDS)));
        }
        service.closeRelays();
        assertEquals(80, service.clients.availablePermits());
    }

    public void testSaturatedMuxDoesNotBlockRevocationOrLeakPermits() throws Exception {
        wire(false);
        try (ParcelFileDescriptor fd = service.openRelay(target.getPackageName(), SESSION)) {
            BridgeService.RelayPipe pipe = service.relayPipes.iterator().next();
            while (fixture.mux.sends.offer(frame(8, 0, new byte[0]))) { }
            assertEquals(0, fixture.mux.sends.remainingCapacity());
            new FileOutputStream(fd.getFileDescriptor()).write(new byte[4096]);
            await(() -> { synchronized (pipe.channel) { return pipe.channel.credit < 65536; } },
                    "The relay worker did not enter the saturated send queue");

            Future<?> closed = workers.submit(() -> service.closeRelays());
            closed.get(2, TimeUnit.SECONDS);
            assertTrue(pipe.closed.get());
            assertTrue(service.relayPipes.isEmpty());
            assertTrue(fixture.mux.channels.isEmpty());
            assertEquals("Revocation must return its connection slot immediately", 80, service.clients.availablePermits());
            assertRemoteClosed(fd);
            service.closeRelays();
            pipe.close();
            assertEquals("Repeated close must not double-release a slot", 80, service.clients.availablePermits());
        }
    }

    public void testChangingSelectedVpnRevokesExistingPipes() throws Exception {
        wire(false);
        try (ParcelFileDescriptor fd = service.openRelay(target.getPackageName(), SESSION)) {
            BridgeService.ownVpnMode = "vpn";
            BridgeService.ownVpnSeenAt = SystemClock.elapsedRealtime();
            preferences.edit().putString("vpnPackage", "new.selected.vpn").commit();
            service.vpnChanged();
            assertRemoteClosed(fd);
            assertTrue(service.relayPipes.isEmpty());
            assertEquals(80, service.clients.availablePermits());
            assertEquals(0L, BridgeService.ownVpnSeenAt);
            assertEquals("", BridgeService.ownVpnMode);
            expectDenied(() -> service.openRelay(target.getPackageName(), SESSION));
            try (ParcelFileDescriptor replacement = service.openRelay("new.selected.vpn", SESSION)) {
                assertTrue(replacement.getFd() >= 0);
            }
        }
        service.closeRelays();
        assertEquals(80, service.clients.availablePermits());
    }

    public void testSaturatedQueueCannotHoldServiceLockDuringNewOpen() throws Exception {
        wire(false);
        try (ParcelFileDescriptor existing = service.openRelay(target.getPackageName(), SESSION)) {
            while (fixture.mux.sends.offer(frame(8, 0, new byte[0]))) { }
            Future<?> opening = workers.submit(() -> {
                try {
                    ParcelFileDescriptor unexpected = service.openRelay(target.getPackageName(), SESSION);
                    unexpected.close();
                    throw new AssertionError("A saturated transport accepted another stream");
                } catch (IOException expected) { }
            });
            // Admission itself must be bounded. Otherwise a synchronized open
            // can hold the service monitor indefinitely and prevent revocation.
            opening.get(2, TimeUnit.SECONDS);
            assertEquals("Failed admission must release its slot", 79, service.clients.availablePermits());
            assertEquals("Failed admission must remove its unfinished channel", 1, fixture.mux.channels.size());
            Future<?> closing = workers.submit(() -> service.closeRelays());
            closing.get(2, TimeUnit.SECONDS);
            assertRemoteClosed(existing);
            assertEquals(80, service.clients.availablePermits());
            assertTrue(fixture.mux.channels.isEmpty());
        }
    }

    public void testPingDoesNotBlockReceiverBehindSaturatedWriter() throws Exception {
        wire(false);
        while (fixture.mux.sends.offer(frame(8, 0, new byte[0]))) { }
        Future<?> pong = workers.submit(() -> fixture.mux.pong(new byte[0]));
        pong.get(1, TimeUnit.SECONDS);
        assertEquals("An optional pong must not discard queued data", 128, fixture.mux.sends.size());
        assertFalse("Congestion is not a disconnected cable", fixture.mux.closed);
    }

    public void testVpnReportRejectsOldSelectionAndSession() throws Exception {
        assertFalse(service.vpnReport("unselected.vpn", SESSION, "starting", "vpn", 0));
        assertFalse(service.vpnReport(target.getPackageName(), SESSION - 1, "starting", "vpn", 0));
        assertFalse(BridgeService.wifiVpnRequired);
        assertTrue(service.vpnReport(target.getPackageName(), SESSION, "starting", "vpn", 0));
        assertTrue(BridgeService.wifiVpnRequired);
        assertTrue(preferences.edit().putString("vpnPackage", "another.vpn").commit());
        assertFalse(service.vpnReport(target.getPackageName(), SESSION, "started", "plain", 0));
        assertTrue("An old selected VPN must not release the hotspot barrier", BridgeService.wifiVpnRequired);
    }

    private static final class PolicyRouter extends IRouter.Stub {
        String barrier="BLOCKED:VPN_REQUIRED";
        @Override public String start(String lib,int port,android.os.IBinder owner){return "READY|test";}
        @Override public String stop(){return "STOPPED|test";}
        @Override public String status(){return "READY|test";}
        @Override public String probe(){return "PASS test";}
        @Override public String setWifi(boolean enabled){return "READY|test";}
        @Override public String configureVpn(String pkg){return "OK";}
        @Override public long networkHandle(){return 0;}
        @Override public String refreshDatapath(){return "OK";}
        @Override public String setVpnDefaultPackages(String[] packages){return "OK";}
        @Override public String setWifiVpn(String pkg,long handle,boolean required){return required?barrier:"OK:PC";}
        @Override public void destroy(){}
    }

    public void testVpnStartupRequiresConfirmedSharingBarrier() throws Exception {
        BridgeService.shareWanted=true;
        assertFalse("A missing helper must not acknowledge an existing hotspot",service.vpnReport("starting","vpn",0));
        PolicyRouter router=new PolicyRouter();service.router=router;
        router.barrier="WAIT: native could still be running";
        assertFalse("A generic WAIT is not proof of a blocked route",service.vpnReport("starting","vpn",0));
        router.barrier="FAIL:VPN_BARRIER_NOT_CONFIRMED";
        assertFalse(service.vpnReport("starting","vpn",0));
        router.barrier="BLOCKED:VPN_REQUIRED";
        assertTrue(service.vpnReport("starting","vpn",0));
        assertTrue(BridgeService.wifiVpnRequired);
        assertEquals(0L,BridgeService.ownVpnNetwork);
        assertFalse("A late plain heartbeat cannot remove the barrier",service.vpnReport("alive","plain",0));
        assertTrue(BridgeService.wifiVpnRequired);
        service.vpnReport("stopped","plain",0);
        assertTrue(BridgeService.wifiVpnRequired);
        service.vpnReport("started","plain",0);
        assertFalse("Explicit new plain mode may restore ordinary sharing",BridgeService.wifiVpnRequired);
    }

    public void testVpnStartupWithoutAnOwnedHotspotNeedsNoHelper() throws Exception {
        BridgeService.shareWanted=false;service.router=null;
        assertTrue(service.vpnReport("starting","vpn",0));
        assertTrue(BridgeService.wifiVpnRequired);
    }

    public void testProvenCableSessionSurvivesProbeFailureButNotDisconnect() throws Exception {
        wire(false);
        BridgeService.verified = false; // A later website probe failed, cable still alive.
        assertTrue(service.relayAvailable());
        try (ParcelFileDescriptor fd = service.openRelay(target.getPackageName(), SESSION)) {
            assertTrue(fd.getFd() >= 0);
        }
        service.closeRelays();
        service.sessionProven = false;
        assertFalse(service.relayAvailable());
        expectDenied(() -> service.openRelay(target.getPackageName(), SESSION));
        service.sessionProven = true;
        BridgeService.connected = false;
        assertFalse(service.relayAvailable());
        expectDenied(() -> service.openRelay(target.getPackageName(), SESSION));
        BridgeService.connected = true;
        service.relaySession = SESSION + 1;
        expectDenied(() -> service.openRelay(target.getPackageName(), SESSION));
        try (ParcelFileDescriptor next = service.openRelay(target.getPackageName(), SESSION + 1)) {
            assertTrue(next.getFd() >= 0);
        }
    }

    private void assertRemoteClosed(ParcelFileDescriptor fd) throws Exception {
        Future<Integer> read = workers.submit(() -> {
            try { return new FileInputStream(fd.getFileDescriptor()).read(); }
            catch (IOException reset) { return -1; }
        });
        assertEquals("Revoked Android descriptor must reach EOF/reset", -1, read.get(2, TimeUnit.SECONDS).intValue());
    }

    private interface Condition { boolean get() throws Exception; }
    private void await(Condition condition, String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 3000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.get()) return;
            Thread.sleep(10);
        }
        fail(message);
    }

    private void expectDenied(Callable<ParcelFileDescriptor> action) throws Exception {
        try {
            ParcelFileDescriptor unexpected = action.call();
            if (unexpected != null) unexpected.close();
            fail("An unauthorized/stale session obtained a descriptor");
        } catch (IOException expected) { }
    }

    private void expectFileDenied(Uri uri, String mode) throws Exception {
        try {
            ParcelFileDescriptor unexpected = target.getContentResolver().openFileDescriptor(uri, mode);
            if (unexpected != null) unexpected.close();
            fail("Provider accepted an invalid relay request");
        } catch (FileNotFoundException expected) { }
    }

    private static Uri relayUri(long session) { return PROVIDER.buildUpon().appendPath("relay").appendQueryParameter("session", String.valueOf(session)).build(); }
    private static byte[] frame(int type, int id, byte[] payload) {
        byte[] frame = new byte[payload.length + 5]; frame[0] = (byte) type;
        Wire.put(frame, 1, id); System.arraycopy(payload, 0, frame, 5, payload.length); return frame;
    }

    private static final class Fixture implements Closeable {
        final Socket phoneSocket, peerSocket;
        final Wire.Secure peer;
        final Wire.Mux mux;
        Fixture(ExecutorService workers, boolean startSender) throws Exception {
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                phoneSocket = new Socket("127.0.0.1", listener.getLocalPort());
                peerSocket = listener.accept();
            }
            phoneSocket.setSoTimeout(6000); peerSocket.setSoTimeout(6000);
            Future<Wire.Secure> handshake = workers.submit(() -> new Wire.Secure(peerSocket.getInputStream(), peerSocket.getOutputStream(), peerSocket, "EMULATOR-RELAY-TEST", true));
            Wire.Secure phone = new Wire.Secure(phoneSocket.getInputStream(), phoneSocket.getOutputStream(), phoneSocket, "EMULATOR-RELAY-TEST", false);
            peer = handshake.get(5, TimeUnit.SECONDS);
            mux = new Wire.Mux(phone, new Wire.Events() {
                public void status(String text) { }
                public void closed(String reason) { }
            });
            if (startSender) mux.start();
        }
        public void close() {
            mux.close();
            try { peer.close(); } catch (IOException ignored) { }
            try { phoneSocket.close(); } catch (IOException ignored) { }
            try { peerSocket.close(); } catch (IOException ignored) { }
        }
    }
}
