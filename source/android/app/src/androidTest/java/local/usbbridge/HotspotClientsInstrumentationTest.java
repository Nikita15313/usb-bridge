package local.usbbridge;

import android.net.LinkAddress;
import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public final class HotspotClientsInstrumentationTest extends InstrumentationTestCase {
    public static final class NewState { public int getState() { return 14; } }
    public static final class UnsupportedState { public String getState() { return "unknown"; } }
    public static final class ThrowingState { public int getState() { throw new IllegalStateException("unavailable"); } }

    public void testLegacyAndObjectSoftApStateCallbacksAreAccepted() {
        assertEquals(Integer.valueOf(11), RouterService.softApState(11));
        assertEquals(Integer.valueOf(14), RouterService.softApState(new NewState()));
    }

    public void testUnsupportedSoftApStateCannotCrashCallback() {
        assertNull(RouterService.softApState(null));
        assertNull(RouterService.softApState(new Object()));
        assertNull(RouterService.softApState(new UnsupportedState()));
        assertNull(RouterService.softApState(new ThrowingState()));
    }

    public void testActualAndroidSoftApStateObjectIsRead() throws Exception {
        // Android 16 dispatches this overload as well as the legacy int callback.
        if (android.os.Build.VERSION.SDK_INT < 36) return;
        Class<?> type = Class.forName("android.net.wifi.SoftApState");
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            // Use the framework's Parcelable path: the constructor is hidden
            // from app reflection on newer builds even though the class exists.
            parcel.writeString(type.getName());
            parcel.writeInt(11); parcel.writeInt(0);
            parcel.writeParcelable(null, 0); parcel.writeString("wlan0");
            parcel.setDataPosition(0);
            Object state = parcel.readParcelable(type.getClassLoader());
            assertNotNull(state);
            assertEquals(type, state.getClass());
            assertEquals(Integer.valueOf(11), RouterService.softApState(state));
        } finally { parcel.recycle(); }
    }
    public static final class Lease {
        final String name, address;
        Lease(String name, String address) { this.name = name; this.address = address; }
        public String getHostname() { return name; }
        public LinkAddress getAddress() throws Exception {
            return LinkAddress.class.getConstructor(String.class).newInstance(address);
        }
    }
    public static final class Client {
        final int type; final String mac; final List<Lease> addresses;
        Client(int type, String mac, Lease... leases) {
            this.type = type; this.mac = mac; addresses = Arrays.asList(leases);
        }
        public int getTetheringType() { return type; }
        public String getMacAddress() { return mac; }
        public List<Lease> getAddresses() { return addresses; }
    }

    public void testNamedWifiClientsExcludeUsbAndPreferIpv4() throws Exception {
        HotspotClients.Snapshot snapshot = HotspotClients.read(Arrays.asList(
                new Client(1, "01:02:03:04:05:06", new Lease("USB PC", "192.168.42.2/24")),
                new Client(0, "11:22:33:44:55:66", new Lease(null, "fe80::1/64"),
                        new Lease("My-Laptop", "192.168.43.10/24"))));
        assertTrue(snapshot.known);
        JSONArray list = new JSONArray(snapshot.clients);
        assertEquals(1, list.length());
        assertEquals("My-Laptop", list.getJSONObject(0).getString("name"));
        assertEquals("192.168.43.10", list.getJSONObject(0).getString("address"));
        assertTrue(list.getJSONObject(0).getBoolean("named"));
    }

    public void testUnknownNamesAreNotInventedAndDuplicateClientsCollapse() throws Exception {
        Client client = new Client(0, "aa:bb:cc:dd:ee:ff");
        JSONArray list = new JSONArray(HotspotClients.read(Arrays.asList(client, client)).clients);
        assertEquals(1, list.length());
        assertEquals("Устройство DD:EE:FF", list.getJSONObject(0).getString("name"));
        assertFalse(list.getJSONObject(0).getBoolean("named"));
    }

    public void testMissingApiDoesNotClaimNoConnectedDevices() throws Exception {
        assertFalse(HotspotClients.read(Collections.singletonList(new Object())).known);
        assertFalse(HotspotClients.read(null).known);
        assertTrue(HotspotClients.read(Collections.emptyList()).known);
    }

    public void testHostnameCannotBreakStatusProtocolOrSpoofMultipleRows() throws Exception {
        HotspotClients.Snapshot snapshot = HotspotClients.read(Collections.singletonList(
                new Client(0, "aa:bb:cc:dd:ee:ff", new Lease("Laptop|ACTIVE\n\u202e", "192.168.43.10/24"))));
        String json = HotspotClients.json(true, true, false, snapshot);
        assertFalse(json.contains("|"));
        JSONObject result = new JSONObject(json);
        assertEquals("Laptop ACTIVE", result.getJSONArray("clients").getJSONObject(0).getString("name"));
        assertTrue(result.getBoolean("routing"));
    }

    public void testStoppedHotspotClearsStaleClientsAndRouting() throws Exception {
        HotspotClients.Snapshot snapshot = HotspotClients.read(Collections.singletonList(
                new Client(0, "aa:bb:cc:dd:ee:ff", new Lease("Laptop", "192.168.43.10/24"))));
        JSONObject result = new JSONObject(HotspotClients.json(false, true, true, snapshot));
        assertFalse(result.getBoolean("enabled"));
        assertFalse(result.getBoolean("routing"));
        assertFalse(result.getBoolean("clientsKnown"));
        assertEquals(0, result.getJSONArray("clients").length());
    }

    public void testLeaseAloneDoesNotClaimAConnectedDevice() {
        HotspotClients.Tracker tracker = new HotspotClients.Tracker();
        assertFalse(tracker.metadata(Collections.singletonList(
                new Client(0, "aa:bb:cc:dd:ee:ff", new Lease("Old laptop", "192.168.43.10/24")))).known);
    }

    public void testDisconnectRemovesDeviceEvenWhileLeaseRemains() throws Exception {
        HotspotClients.Tracker tracker = new HotspotClients.Tracker();
        Client laptop = new Client(0, "aa:bb:cc:dd:ee:ff", new Lease("Laptop", "192.168.43.10/24"));
        tracker.metadata(Collections.singletonList(laptop));
        assertEquals("Laptop", new JSONArray(tracker.associations(Collections.singletonList(laptop)).clients)
                .getJSONObject(0).getString("name"));
        HotspotClients.Snapshot disconnected = tracker.associations(Collections.emptyList());
        assertTrue(disconnected.known);
        assertEquals(0, new JSONArray(disconnected.clients).length());
        assertEquals(0, new JSONArray(tracker.metadata(Collections.singletonList(laptop)).clients).length());
    }

    public void testNewAssociationWithoutDhcpAppearsImmediatelyAndThenGetsName() throws Exception {
        HotspotClients.Tracker tracker = new HotspotClients.Tracker();
        Client joining = new Client(0, "aa:bb:cc:dd:ee:ff");
        JSONArray before = new JSONArray(tracker.associations(Collections.singletonList(joining)).clients);
        assertEquals(1, before.length());assertFalse(before.getJSONObject(0).getBoolean("named"));
        JSONArray after = new JSONArray(tracker.metadata(Arrays.asList(
                new Client(0, "AA:BB:CC:DD:EE:FF", new Lease("Current laptop", "192.168.43.10/24")),
                new Client(0, "11:22:33:44:55:66", new Lease("Disconnected", "192.168.43.11/24")))).clients);
        assertEquals(1, after.length());assertEquals("Current laptop", after.getJSONObject(0).getString("name"));
    }

    public void testAggregateAssociationsDeduplicateBandsAndRemoveOnlyDepartedClient() throws Exception {
        HotspotClients.Tracker tracker = new HotspotClients.Tracker();
        Client one = new Client(0, "aa:bb:cc:dd:ee:01"), two = new Client(0, "aa:bb:cc:dd:ee:02");
        assertEquals(2, new JSONArray(tracker.associations(Arrays.asList(one, two, one)).clients).length());
        JSONArray remaining = new JSONArray(tracker.associations(Collections.singletonList(two)).clients);
        assertEquals(1, remaining.length());assertEquals("aa:bb:cc:dd:ee:02", remaining.getJSONObject(0).getString("id"));
    }

    public void testHotspotRestartDoesNotReusePreviousClientsOrNames() throws Exception {
        HotspotClients.Tracker tracker = new HotspotClients.Tracker();
        Client old = new Client(0, "aa:bb:cc:dd:ee:ff", new Lease("Old name", "192.168.43.10/24"));
        tracker.associations(Collections.singletonList(old));tracker.metadata(Collections.singletonList(old));
        assertFalse(tracker.reset().known);
        JSONObject newClient = new JSONArray(tracker.associations(Collections.singletonList(old)).clients).getJSONObject(0);
        assertFalse(newClient.getBoolean("named"));assertEquals("", newClient.getString("address"));
    }

    public void testUnsupportedAssociationApiNeverFallsBackToLeases() {
        HotspotClients.Tracker tracker = new HotspotClients.Tracker();
        tracker.associations(Collections.singletonList(new Object()));
        assertFalse(tracker.metadata(Collections.singletonList(new Client(0, "aa:bb:cc:dd:ee:ff"))).known);
        assertFalse(HotspotClients.readAssociations(Collections.singletonList(new Client(0, "invalid"))).known);
    }
}
