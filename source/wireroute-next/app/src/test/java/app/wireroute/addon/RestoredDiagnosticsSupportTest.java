package app.wireroute.addon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.wireroute.TrafficStatsRepository;

import org.junit.Test;

public final class RestoredDiagnosticsSupportTest {
    @Test
    public void privateDnsProfileParserCountsAddressesWithoutExposingThem() {
        PrivateDnsDiagnostics.ProfileDnsSummary summary =
                PrivateDnsDiagnostics.inspectProfile(
                        "[Interface]\nDNS = 1.1.1.1, 2606:4700:4700::1111, resolver.example\n");

        assertTrue(summary.hasInterface);
        assertTrue(summary.hasDnsLine);
        assertEquals(3, summary.entryCount);
        assertEquals(1, summary.ipv4Count);
        assertEquals(1, summary.ipv6Count);
        assertEquals(1, summary.otherCount);
        assertFalse(summary.safeSummary().contains("1.1.1.1"));
    }

    @Test
    public void natTunerAddsKeepaliveOnceAndPreservesExistingConfig() {
        String config = "[Interface]\n"
                + "PrivateKey = private\n"
                + "MTU = 1420\n\n"
                + "[Peer]\n"
                + "PublicKey = public\n"
                + "Endpoint = vpn.example:51820\n"
                + "AllowedIPs = 0.0.0.0/0, ::/0\n";

        WireGuardPerformanceTuner.Assessment before =
                WireGuardPerformanceTuner.inspect(config);
        assertTrue(before.supported);
        assertTrue(before.needsNatKeepalive());
        assertEquals(1420, before.effectiveMtu);

        WireGuardPerformanceTuner.TuneResult first =
                WireGuardPerformanceTuner.applyNatStability(config);
        assertEquals(1, first.changedPeers);
        assertTrue(first.config.contains("PersistentKeepalive = 25"));

        WireGuardPerformanceTuner.TuneResult second =
                WireGuardPerformanceTuner.applyNatStability(first.config);
        assertEquals(0, second.changedPeers);
        assertEquals(first.config, second.config);
    }

    @Test
    public void resilienceAdvisorRecognizesFullRouteAndAmneziaFields() {
        String config = "[Interface]\nJc = 4\n\n"
                + "[Peer]\nEndpoint = vpn.example:51820\n"
                + "AllowedIPs = 0.0.0.0/0, ::/0\n"
                + "PersistentKeepalive = 25\n";

        ResilienceAdvisor.Assessment assessment = ResilienceAdvisor.analyze(config);
        assertTrue(assessment.hasProfile);
        assertTrue(assessment.hasAmneziaParameters);
        assertTrue(assessment.fullTunnelV4AndV6);
        assertEquals(1, assessment.peerCount);
        assertEquals(1, assessment.peersWithKeepalive);
    }

    @Test
    public void trafficCountersSaturateInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE,
                TrafficStatsRepository.saturatedAdd(Long.MAX_VALUE - 2L, 10L));
        assertEquals(12L, TrafficStatsRepository.saturatedAdd(5L, 7L));
    }
}
