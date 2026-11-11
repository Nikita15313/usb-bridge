package app.wireroute.diagnostics;

import app.wireroute.ConfigUtils;
import app.wireroute.RoutingMode;
import com.wireguard.config.InetEndpoint;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public final class ProfileDiagnosticsTest {
    private static final String ZERO_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String CONFIG = "[Interface]\nPrivateKey = " + ZERO_KEY
            + "\nAddress = 10.42.3.9/32, fd42::9/128\nDNS = 1.1.1.1, 2606:4700:4700::1111, private.search.example"
            + "\nMTU = 1420\n[Peer]\nPublicKey = " + ZERO_KEY + "\nPresharedKey = " + ZERO_KEY
            + "\nEndpoint = private.server.example:51820\nAllowedIPs = 0.0.0.0/1, 128.0.0.0/1, ::/0"
            + "\nPersistentKeepalive = 0\n";

    @Test public void reportOmitsSecretsAndPrivateAddressesButPreservesTransportChoices() throws Exception {
        String result = ProfileDiagnostics.inspect(ConfigUtils.parse(CONFIG), RoutingMode.ONLY_SELECTED,
                Set.of("private.selected.app"), "diagnostic.app");
        for (String secret : new String[] {ZERO_KEY, "10.42.3.9", "fd42::9", "1.1.1.1",
                "2606:4700", "private.search.example", "private.server.example", "private.selected.app",
                "PrivateKey", "PresharedKey"}) assertFalse(secret, result.contains(secret));
        assertTrue(result.contains("profile_mtu=1420"));
        assertTrue(result.contains("keepalive_s=0"));
        assertTrue(result.contains("dns_v4=1 dns_v6=1 search_domains=1"));
        assertTrue(result.contains("full_v4=true full_v6=true"));
        assertTrue(result.contains("selected_count=1 diagnostic_app_selected=false"));
        assertTrue(result.contains("peer_1_public_key_sha256_12=66687aadf862"));
        assertTrue(result.contains("interface_listen_port=AUTO"));
        assertTrue(result.contains("endpoint_port=51820 endpoint_text_sha256_12="));
    }

    @Test public void missingMtuIsReportedAsAutoWithoutApplyingHistoricalOverrides() throws Exception {
        String result = ProfileDiagnostics.inspect(ConfigUtils.parse(CONFIG.replace("MTU = 1420\n", "")),
                RoutingMode.BYPASS_SELECTED, Set.of("diagnostic.app"), "diagnostic.app");
        assertTrue(result.contains("profile_mtu=AUTO"));
        assertTrue(result.contains("routing=BYPASS_SELECTED selected_count=1 diagnostic_app_selected=true"));
        assertTrue(result.contains("config_source=stored_encrypted"));
    }

    @Test public void endpointFingerprintNormalizesCaseButDistinguishesServerAndPort() throws Exception {
        String base = ProfileDiagnostics.endpointFingerprint(InetEndpoint.parse("private.server.example:51820"));
        assertEquals(base, ProfileDiagnostics.endpointFingerprint(InetEndpoint.parse("PRIVATE.SERVER.EXAMPLE.:51820")));
        assertNotEquals(base, ProfileDiagnostics.endpointFingerprint(InetEndpoint.parse("another.server.example:51820")));
        assertNotEquals(base, ProfileDiagnostics.endpointFingerprint(InetEndpoint.parse("private.server.example:51821")));
        assertTrue(base.matches("[a-f0-9]{12}"));
    }
}
