package app.wireroute;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class RuntimeEndpointClassTest {
    @Test public void loopbackAndKnownDesktopAreRecognizedWithoutDns() {
        for (String host : new String[]{"127.0.0.1", "127.99.8.7", "::1", "0:0:0:0:0:0:0:1", "[::ffff:127.0.0.1]"})
            assertEquals("LOOPBACK", RuntimeEndpointClass.classify(host));
        for (String host : new String[]{"192.168.0.176", "192.168.0.66", "::ffff:192.168.0.176"})
            assertEquals("DESKTOP_LAN", RuntimeEndpointClass.classify(host));
    }
    @Test public void numericPrivateAndOtherNumericClassesRemainCoarse() {
        for (String host : new String[]{"192.168.0.235", "10.2.3.4", "172.16.0.1", "172.31.255.254", "169.254.1.2", "100.64.0.1", "fc00::1", "fe80::123%wlan0", "fec0::8"})
            assertEquals("PRIVATE_LAN", RuntimeEndpointClass.classify(host));
        for (String host : new String[]{"203.0.113.7", "172.32.0.1", "2001:db8::1", "[2001:db8:0:0:0:0:0:2]"})
            assertEquals("REMOTE_NUMERIC", RuntimeEndpointClass.classify(host));
    }
    @Test public void hostnameAndMalformedInputsNeverAssertRemote() {
        for (String host : new String[]{"localhost", "vpn.example.invalid", "relay-host.example"})
            assertEquals("HOSTNAME_UNVERIFIED", RuntimeEndpointClass.classify(host));
        for (String host : new String[]{null, "", "256.1.2.3", "1.2.3", "::", ":::1", "1:2:3", "ff02::1", "[::1", "host/path"})
            assertEquals("UNKNOWN", RuntimeEndpointClass.classify(host));
    }
    @Test public void multiplePeersDoNotHideConflictingEndpointClasses() {
        assertEquals("REMOTE_NUMERIC", RuntimeEndpointClass.merge(null, "REMOTE_NUMERIC"));
        assertEquals("REMOTE_NUMERIC", RuntimeEndpointClass.merge("REMOTE_NUMERIC", "REMOTE_NUMERIC"));
        assertEquals("UNKNOWN", RuntimeEndpointClass.merge("REMOTE_NUMERIC", "LOOPBACK"));
        assertEquals("UNKNOWN", RuntimeEndpointClass.merge("UNKNOWN", "REMOTE_NUMERIC"));
        assertEquals("UNKNOWN", RuntimeEndpointClass.merge(null, "untrusted-text"));
    }
}
