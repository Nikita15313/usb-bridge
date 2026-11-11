package app.wireroute;

import com.wireguard.config.Config;

import org.junit.Test;

import java.util.Collections;
import java.net.InetAddress;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ConfigUtilsTest {
    private static final String BASE_CONFIG = ""
            + "[Interface]\n"
            + "PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
            + "Address = 10.0.0.2/32\n"
            + "DNS = 1.1.1.1\n"
            + "MTU = 1380\n"
            + "\n"
            + "[Peer]\n"
            + "PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
            + "AllowedIPs = 0.0.0.0/0, ::/0\n"
            + "Endpoint = 192.0.2.1:51820\n";

    @Test public void relayChangesOnlyRuntimeEndpointAndPreservesOriginalProfile() throws Exception {
        Config base = ConfigUtils.parse(BASE_CONFIG + "PersistentKeepalive = 19\nPresharedKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n");
        String original = base.toWgQuickString();
        Config runtime = ConfigUtils.withUsbRelayEndpoint(base, 38475);
        com.wireguard.config.Peer before = base.getPeers().get(0), after = runtime.getPeers().get(0);
        assertEquals("127.0.0.1:38475", after.getEndpoint().get().toString());
        assertEquals(before.getPublicKey(), after.getPublicKey());
        assertEquals(before.getPreSharedKey(), after.getPreSharedKey());
        assertEquals(before.getAllowedIps(), after.getAllowedIps());
        assertEquals(before.getPersistentKeepalive(), after.getPersistentKeepalive());
        assertEquals(base.getInterface(), runtime.getInterface());
        assertEquals(original, base.toWgQuickString());
    }

    @Test public void usbRejectsProfilesThatCouldBypassTheVpn() throws Exception {
        assertTrue(ConfigUtils.supportsUsb(ConfigUtils.parse(BASE_CONFIG)));
        assertTrue(ConfigUtils.supportsUsb(ConfigUtils.parse(BASE_CONFIG.replace(", ::/0", ""))));
        org.junit.Assert.assertFalse(ConfigUtils.supportsUsb(ConfigUtils.parse(BASE_CONFIG.replace("::/0", "fd00::/64"))));
        org.junit.Assert.assertFalse(ConfigUtils.supportsUsb(ConfigUtils.parse(BASE_CONFIG.replace("0.0.0.0/0", "10.0.0.0/8"))));
        org.junit.Assert.assertFalse(ConfigUtils.supportsUsb(ConfigUtils.parse(BASE_CONFIG.replace(", ::/0", "").replace("10.0.0.2/32", "10.0.0.2/32, fd00::2/128"))));
    }

    @Test
    public void onlySelectedCreatesAllowListAndKeepsTunnelSettings() throws Exception {
        Config base = ConfigUtils.parse(BASE_CONFIG);
        Config routed = ConfigUtils.withRouting(
                base,
                RoutingMode.ONLY_SELECTED,
                Set.of("com.android.chrome", "org.mozilla.firefox")
        );

        assertEquals(
                Set.of("com.android.chrome", "org.mozilla.firefox"),
                routed.getInterface().getIncludedApplications()
        );
        assertTrue(routed.getInterface().getExcludedApplications().isEmpty());
        assertEquals(base.getInterface().getAddresses(), routed.getInterface().getAddresses());
        assertEquals(base.getInterface().getDnsServers(), routed.getInterface().getDnsServers());
        assertEquals(base.getInterface().getMtu(), routed.getInterface().getMtu());
        assertEquals(base.getPeers(), routed.getPeers());
    }

    @Test
    public void bypassSelectedCreatesDenyList() throws Exception {
        Config routed = ConfigUtils.withRouting(
                ConfigUtils.parse(BASE_CONFIG),
                RoutingMode.BYPASS_SELECTED,
                Set.of("com.android.chrome")
        );

        assertEquals(
                Set.of("com.android.chrome"),
                routed.getInterface().getExcludedApplications()
        );
        assertTrue(routed.getInterface().getIncludedApplications().isEmpty());
    }

    @Test(expected = ConfigUtils.EmptySelectionException.class)
    public void emptyAllowListIsRejected() throws Exception {
        ConfigUtils.withRouting(
                ConfigUtils.parse(BASE_CONFIG),
                RoutingMode.ONLY_SELECTED,
                Collections.emptySet()
        );
    }

    @Test
    public void emptyBypassListMeansAllAppsUseVpn() throws Exception {
        Config routed = ConfigUtils.withRouting(
                ConfigUtils.parse(BASE_CONFIG),
                RoutingMode.BYPASS_SELECTED,
                Collections.emptySet()
        );

        assertTrue(routed.getInterface().getIncludedApplications().isEmpty());
        assertTrue(routed.getInterface().getExcludedApplications().isEmpty());
    }

    @Test
    public void pairedHalfRoutesAreRecognizedAsFullInternet() throws Exception {
        String pairedRoutes = BASE_CONFIG
                .replace("0.0.0.0/0, ::/0", "0.0.0.0/1, 128.0.0.0/1, ::/1, 8000::/1");

        ConfigUtils.Info info = ConfigUtils.inspect(ConfigUtils.parse(pairedRoutes));

        assertTrue(info.fullIpv4);
        assertTrue(info.fullIpv6);
    }

    @Test
    public void appRoutingDoesNotChangeExplicitTransportParameters() throws Exception {
        Config original = ConfigUtils.parse(BASE_CONFIG.replace("MTU = 1380", "MTU = 1420\nListenPort = 54321")
                + "PersistentKeepalive = 0\n");
        Config routed = ConfigUtils.withRouting(original, RoutingMode.ONLY_SELECTED, Set.of("video.app"));
        assertEquals(Integer.valueOf(1420), routed.getInterface().getMtu().get());
        assertEquals(Integer.valueOf(54321), routed.getInterface().getListenPort().get());
        assertEquals(original.getPeers(), routed.getPeers());
        assertEquals(original.toWgUserspaceString(), routed.toWgUserspaceString());
    }

    @Test
    public void missingTransportSettingsStayAbsentForOfficialBackendDefaults() throws Exception {
        Config original = ConfigUtils.parse(BASE_CONFIG.replace("MTU = 1380\n", ""));
        Config routed = ConfigUtils.withRouting(original, RoutingMode.ONLY_SELECTED, Set.of("video.app"));
        assertTrue(routed.getInterface().getMtu().isEmpty());
        assertTrue(routed.getInterface().getListenPort().isEmpty());
        assertEquals(original.toWgUserspaceString(), routed.toWgUserspaceString());
    }

    @Test
    public void missingDnsUsesVpnResolversForFullIpv4OnOrdinaryAndUsbNetworks() throws Exception {
        Config original = ConfigUtils.parse(BASE_CONFIG.replace("DNS = 1.1.1.1\n", "")
                .replace(", ::/0", ""));
        String savedProfile = original.toWgQuickString();

        for (boolean usb : new boolean[]{false, true}) {
            Config routed = ConfigUtils.withRouting(original, RoutingMode.ONLY_SELECTED,
                    Set.of("com.instagram.android"), usb);
            assertEquals(Set.of(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("8.8.8.8")),
                    routed.getInterface().getDnsServers());
            assertEquals(Set.of("com.instagram.android"), routed.getInterface().getIncludedApplications());
            assertEquals(original.toWgUserspaceString(), routed.toWgUserspaceString());
            assertEquals(original.getInterface().getAddresses(), routed.getInterface().getAddresses());
            assertEquals(original.getInterface().getMtu(), routed.getInterface().getMtu());
            assertEquals(original.getPeers(), routed.getPeers());
        }

        assertTrue(original.getInterface().getDnsServers().isEmpty());
        assertEquals(savedProfile, original.toWgQuickString());
    }

    @Test
    public void pairedIpv4HalfRoutesAlsoReceiveMissingDnsFallback() throws Exception {
        Config original = ConfigUtils.parse(BASE_CONFIG.replace("DNS = 1.1.1.1\n", "")
                .replace("0.0.0.0/0, ::/0", "0.0.0.0/1, 128.0.0.0/1"));
        Config routed = ConfigUtils.withRouting(original, RoutingMode.BYPASS_SELECTED, Set.of("direct.app"));

        assertEquals(Set.of(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("8.8.8.8")),
                routed.getInterface().getDnsServers());
        assertEquals(Set.of("direct.app"), routed.getInterface().getExcludedApplications());
        assertEquals(original.getPeers(), routed.getPeers());
    }

    @Test
    public void missingDnsDoesNotInventResolversForPartialOrIpv6OnlyRoutes() throws Exception {
        for (String routes : new String[]{"10.0.0.0/8", "0.0.0.0/1", "::/0", "::/1, 8000::/1"}) {
            Config original = ConfigUtils.parse(BASE_CONFIG.replace("DNS = 1.1.1.1\n", "")
                    .replace("0.0.0.0/0, ::/0", routes));
            for (boolean usb : new boolean[]{false, true}) {
                Config routed = ConfigUtils.withRouting(original, RoutingMode.ONLY_SELECTED,
                        Set.of("com.instagram.android"), usb);
                assertTrue(routed.getInterface().getDnsServers().isEmpty());
                assertEquals(original.toWgUserspaceString(), routed.toWgUserspaceString());
            }
        }
    }

    @Test
    public void explicitIpv4AndIpv6DnsAndSearchDomainsArePreserved() throws Exception {
        for (String dns : new String[]{"9.9.9.9, corp.example", "2606:4700:4700::1111, corp.example"}) {
            Config original = ConfigUtils.parse(BASE_CONFIG.replace("DNS = 1.1.1.1", "DNS = " + dns));
            for (boolean usb : new boolean[]{false, true}) {
                Config routed = ConfigUtils.withRouting(original, RoutingMode.ONLY_SELECTED,
                        Set.of("com.instagram.android"), usb);
                assertEquals(original.getInterface().getDnsServers(), routed.getInterface().getDnsServers());
                assertEquals(Set.of("corp.example"), routed.getInterface().getDnsSearchDomains());
                assertEquals(original.toWgUserspaceString(), routed.toWgUserspaceString());
            }
        }
    }

    @Test
    public void runtimeDnsFallbackKeepsExistingSearchDomainsAndAbsentTransportSettings() throws Exception {
        Config original = ConfigUtils.parse(BASE_CONFIG.replace("DNS = 1.1.1.1", "DNS = corp.example")
                .replace("MTU = 1380\n", ""));
        Config routed = ConfigUtils.withRouting(original, RoutingMode.ONLY_SELECTED,
                Set.of("com.instagram.android"));

        assertEquals(2, routed.getInterface().getDnsServers().size());
        assertEquals(Set.of("corp.example"), routed.getInterface().getDnsSearchDomains());
        assertTrue(routed.getInterface().getMtu().isEmpty());
        assertTrue(routed.getInterface().getListenPort().isEmpty());
        assertEquals(original.toWgUserspaceString(), routed.toWgUserspaceString());
        assertTrue(original.getInterface().getDnsServers().isEmpty());
    }
}
