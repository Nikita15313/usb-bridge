package app.wireroute.diagnostics;

import org.junit.Test;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import static org.junit.Assert.*;

public final class TunnelProbeTest {
    @Test public void permissionDenialIsDistinctFromFailedConnectionAndDoesNotCopyErrorText() {
        assertEquals("NOT_PERMITTED", TunnelProbe.errorStatus(
                new IOException("secret-address", new SecurityException("secret-profile"))));
        assertEquals("TIMEOUT", TunnelProbe.errorStatus(new SocketTimeoutException("secret-host")));
        assertEquals("CHECK_FAILED", TunnelProbe.errorStatus(new IOException("secret-host")));
    }

    @Test public void reportDoesNotClaimAnInvisibleNetworkOrIpv6FailureProvesVpnOutage() {
        String report = new TunnelProbe.Result(List.of("VPN network=NOT_VISIBLE_OR_UNAVAILABLE")).format();
        assertTrue(report.contains("это не доказательство обрыва VPN"));
        assertTrue(report.contains("Отсутствие IPv6 не означает отказ IPv4"));
        assertTrue(report.contains("только установление соединения, не видео"));
    }
}
