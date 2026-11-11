package local.usbbridge;

import android.test.InstrumentationTestCase;
import java.util.*;

public final class HotspotDisplayInstrumentationTest extends InstrumentationTestCase {
    private String state(String prefix, boolean enabled, boolean routing, boolean vpn,
                         HotspotClients.Snapshot clients) {
        return prefix + "|Телефон готов к раздаче|testtun0|testtun0||PC|"
                + HotspotClients.json(enabled, routing, vpn, clients);
    }

    public void testObservedHotspotReplacesReadyMessage() {
        HotspotDisplay view = HotspotDisplay.parse(state("ACTIVE", true, true, false, HotspotClients.EMPTY), true);
        assertTrue(view.enabled);
        assertEquals("Wi-Fi включён · интернет компьютера", view.summary);
        assertEquals("Ждём подключения устройств", view.clients);
    }

    public void testVpnAndPlainSourceAreDifferentAfterSwitch() {
        HotspotDisplay vpn = HotspotDisplay.parse(state("ACTIVE", true, true, true, HotspotClients.EMPTY), true);
        assertEquals("Wi-Fi включён · через VPN", vpn.summary);
        HotspotDisplay plain = HotspotDisplay.parse(state("ACTIVE", true, true, false, HotspotClients.EMPTY), true);
        assertFalse(plain.summary.contains("VPN"));
    }

    public void testReadyRequestDoesNotPretendHotspotIsEnabled() {
        HotspotDisplay view = HotspotDisplay.parse(state("READY", false, false, false, HotspotClients.UNKNOWN), true);
        assertFalse(view.enabled);
        assertFalse(view.summary.startsWith("Wi-Fi включён"));
        assertEquals("", view.clients);
    }

    public void testStartedApWithoutConfirmedUpstreamIsStillConfiguring() {
        HotspotDisplay view = HotspotDisplay.parse(state("READY", true, false, false, HotspotClients.UNKNOWN), true);
        assertTrue(view.enabled);
        assertEquals("Wi-Fi включён · настраиваем интернет", view.summary);
        assertEquals("Телефон не передал список устройств", view.clients);
    }

    public void testNamedDevicesAppearRatherThanOnlyCount() {
        HotspotClients.Snapshot clients = new HotspotClients.Snapshot(true,
                "[{\"name\":\"My-Laptop\"},{\"name\":\"OnePlus 15\"}]");
        HotspotDisplay view = HotspotDisplay.parse(state("ACTIVE", true, true, false, clients), true);
        assertEquals("• My-Laptop\n• OnePlus 15", view.clients);
    }

    public void testLegacyAndMalformedStatusNeverClaimSuccess() {
        assertFalse(HotspotDisplay.parse("READY|Телефон готов к раздаче", true).enabled);
        assertFalse(HotspotDisplay.parse("READY|Ждём|||||not json", true).enabled);
        assertEquals("Wi-Fi выключен", HotspotDisplay.parse("STOPPED|Раздача выключена", false).summary);
    }
    public void testManualHotspotIsNotMistakenForOwnedPcSharing() {
        String state = "READY|Готов|testtun0|wlan0||PC|"
                + HotspotClients.json(true, false, false, HotspotClients.EMPTY, false);
        HotspotDisplay view = HotspotDisplay.parse(state, false);
        assertTrue(view.enabled);
        assertFalse(view.owned);
        assertEquals("Wi-Fi включён в настройках телефона", view.summary);
        assertFalse(HotspotDisplay.parse("READY|Готов|||||{\"enabled\":true}", false).owned);
    }
    public void testVpnBarrierShowsConnectedClientsWithoutClaimingWorkingInternet() {
        HotspotClients.Snapshot clients = new HotspotClients.Snapshot(true, "[{\"name\":\"My-Laptop\"}]");
        String state = "READY|Ждём VPN|testtun0|testtun0||VPN:0|"
                + HotspotClients.json(true, false, true, clients, true, true);
        HotspotDisplay view = HotspotDisplay.parse(state, true);
        assertTrue(view.enabled);assertTrue(view.owned);
        assertEquals("Wi-Fi включён · ждём VPN", view.summary);
        assertEquals("• My-Laptop", view.clients);
    }
    public void testPlainAndReadyRoutesNeverAdvertiseVpnWait() {
        String plain = "ACTIVE|Включён|testtun0|testtun0||PC|"
                + HotspotClients.json(true, true, false, HotspotClients.EMPTY, true, true);
        assertEquals("Wi-Fi включён · интернет компьютера", HotspotDisplay.parse(plain, true).summary);
        String vpn = "ACTIVE|Включён|testtun0|testtun0||VPN:1|"
                + HotspotClients.json(true, true, true, HotspotClients.EMPTY, true, true);
        assertEquals("Wi-Fi включён · через VPN", HotspotDisplay.parse(vpn, true).summary);
    }
}
