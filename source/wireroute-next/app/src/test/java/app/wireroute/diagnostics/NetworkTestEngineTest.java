package app.wireroute.diagnostics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public final class NetworkTestEngineTest {
    @Test
    public void medianHandlesOddAndEvenSamples() {
        assertEquals(20.0, NetworkTestEngine.median(Arrays.asList(10.0, 20.0, 30.0)), 0.001);
        assertEquals(15.0, NetworkTestEngine.median(Arrays.asList(10.0, 20.0)), 0.001);
    }

    @Test
    public void byteFormattingUsesReadableUnits() {
        assertEquals("1.0 КБ", TrafficUsageRepository.formatBytes(1024).replace(',', '.'));
        assertEquals("1.0 МБ", TrafficUsageRepository.formatBytes(1024 * 1024).replace(',', '.'));
    }
}
