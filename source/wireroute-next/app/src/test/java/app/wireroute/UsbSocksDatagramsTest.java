package app.wireroute;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import static org.junit.Assert.*;

public final class UsbSocksDatagramsTest {
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] joined = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, joined, a.length, b.length); return joined;
    }
    private static byte[] response(int port, byte[] payload) {
        byte[] result = new byte[payload.length + 10];
        result[0] = (byte)(payload.length >>> 8); result[1] = (byte)payload.length;
        result[2] = 10; result[3] = 1; result[4] = (byte)192; result[5] = 0; result[6] = 2; result[7] = 1;
        result[8] = (byte)(port >>> 8); result[9] = (byte)port;
        System.arraycopy(payload, 0, result, 10, payload.length); return result;
    }

    @Test public void remoteDnsAndUdpFrameMatchWindowsRelayProtocol() throws Exception {
        byte[] payload = new byte[]{1, 0, 0, 0, 42};
        byte[] handshake = new byte[]{5, 0, 5, 0, 0, 1, 0, 0, 0, 0, 0, 0};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        UsbSocksDatagrams codec = new UsbSocksDatagrams(new ByteArrayInputStream(concat(handshake,
                response(51820, payload))), out, "vpn.example", 51820);
        codec.handshake(); codec.send(payload, 0, payload.length);
        byte[] bytes = out.toByteArray();
        assertArrayEquals(new byte[]{5, 1, 0, 5, 5, 0, 1, 0, 0, 0, 0, 0, 0}, java.util.Arrays.copyOf(bytes, 13));
        assertEquals(18, bytes[15] & 255); // domain header: 7 + eleven ASCII characters
        assertEquals(3, bytes[16]); assertEquals(11, bytes[17]);
        assertEquals("vpn.example", new String(bytes, 18, 11, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(51820, ((bytes[29] & 255) << 8) | (bytes[30] & 255));
        assertArrayEquals(payload, codec.receive());
    }

    @Test public void consecutiveAndEmptyDatagramsPreserveBoundaries() throws Exception {
        byte[] first = response(55, new byte[0]), second = response(55, new byte[]{9, 8, 7});
        UsbSocksDatagrams codec = new UsbSocksDatagrams(new ByteArrayInputStream(concat(first, second)), new ByteArrayOutputStream(), "192.0.2.1", 55);
        assertArrayEquals(new byte[0], codec.receive()); assertArrayEquals(new byte[]{9, 8, 7}, codec.receive());
    }

    @Test public void oneUsbWritePerDatagramPreservesOffsetsAndReusedBuffer() throws Exception {
        class CountingOutput extends ByteArrayOutputStream {
            int writes;
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                writes++; super.write(bytes, offset, length);
            }
        }
        CountingOutput out = new CountingOutput();
        UsbSocksDatagrams codec = new UsbSocksDatagrams(new ByteArrayInputStream(new byte[0]), out, "vpn.example", 51820);
        codec.send(new byte[]{99, 8, 7, 66}, 1, 2);
        codec.send(new byte[]{42}, 0, 1);
        codec.send(new byte[0], 0, 0);
        assertEquals(3, out.writes);
        UsbSocksDatagrams reader = new UsbSocksDatagrams(new ByteArrayInputStream(out.toByteArray()), new ByteArrayOutputStream(), "vpn.example", 51820);
        assertArrayEquals(new byte[]{8, 7}, reader.receive());
        assertArrayEquals(new byte[]{42}, reader.receive());
        assertArrayEquals(new byte[0], reader.receive());
    }

    @Test public void largestDatagramAndEndpointPreserveFrameLength() throws Exception {
        byte[] payload = new byte[65507];
        new java.util.Random(314).nextBytes(payload);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String host = "a".repeat(248);
        new UsbSocksDatagrams(new ByteArrayInputStream(new byte[0]), out, host, 65535).send(payload, 0, payload.length);
        assertEquals(255 + payload.length, out.size());
        assertArrayEquals(payload, new UsbSocksDatagrams(new ByteArrayInputStream(out.toByteArray()), new ByteArrayOutputStream(), host, 65535).receive());
    }

    @Test public void reusableReceivePreservesShortAndEmptyFramesAfterLargeFrame() throws Exception {
        byte[] large = new byte[65507]; new java.util.Random(317).nextBytes(large);
        byte[] frames = concat(response(55, large), concat(response(55, new byte[]{9}), response(55, new byte[0])));
        UsbSocksDatagrams codec = new UsbSocksDatagrams(new ByteArrayInputStream(frames), new ByteArrayOutputStream(), "192.0.2.1", 55);
        byte[] buffer = new byte[65507];
        assertEquals(large.length, codec.receive(buffer)); assertArrayEquals(large, buffer);
        assertEquals(1, codec.receive(buffer)); assertEquals(9, buffer[0]);
        assertEquals(0, codec.receive(buffer));
    }

    @Test public void fragmentedStreamAndZeroLengthReadCannotStallReusableReceiver() throws Exception {
        byte[] payload = new byte[1280]; new java.util.Random(318).nextBytes(payload);
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        new UsbSocksDatagrams(new ByteArrayInputStream(new byte[0]), frames, "vpn.example", 51820)
                .send(payload, 0, payload.length);
        ByteArrayInputStream fragmented = new ByteArrayInputStream(frames.toByteArray()) {
            boolean zero;
            @Override public synchronized int read(byte[] b, int off, int len) {
                zero = !zero;
                return zero ? 0 : super.read(b, off, Math.min(len, 3));
            }
        };
        UsbSocksDatagrams codec = new UsbSocksDatagrams(fragmented, new ByteArrayOutputStream(), "vpn.example", 51820);
        byte[] buffer = new byte[1500];
        assertEquals(payload.length, codec.receive(buffer));
        assertArrayEquals(payload, java.util.Arrays.copyOf(buffer, payload.length));
    }

    @Test(expected = IOException.class) public void reusableReceiverRejectsSmallBufferWithoutTruncation() throws Exception {
        new UsbSocksDatagrams(new ByteArrayInputStream(response(55, new byte[3])), new ByteArrayOutputStream(), "192.0.2.1", 55)
                .receive(new byte[2]);
    }

    @Test(expected = IOException.class) public void reusableReceiverStillRejectsBadSourcePort() throws Exception {
        new UsbSocksDatagrams(new ByteArrayInputStream(response(56, new byte[3])), new ByteArrayOutputStream(), "192.0.2.1", 55)
                .receive(new byte[1500]);
    }

    @Test(expected = IOException.class) public void truncatedDatagramRejected() throws Exception {
        byte[] frame = response(123, new byte[]{1, 2, 3});
        new UsbSocksDatagrams(new ByteArrayInputStream(java.util.Arrays.copyOf(frame, frame.length - 1)), new ByteArrayOutputStream(), "192.0.2.1", 123).receive();
    }
    @Test(expected = IOException.class) public void malformedHeaderRejected() throws Exception {
        byte[] frame = response(123, new byte[0]); frame[2] = 9;
        new UsbSocksDatagrams(new ByteArrayInputStream(frame), new ByteArrayOutputStream(), "192.0.2.1", 123).receive();
    }
    @Test(expected = IOException.class) public void unexpectedSourcePortRejected() throws Exception {
        new UsbSocksDatagrams(new ByteArrayInputStream(response(124, new byte[0])), new ByteArrayOutputStream(), "192.0.2.1", 123).receive();
    }
    @Test(expected = IOException.class) public void unsupportedIpv6EndpointFailsBeforeNativeVpn() throws Exception {
        new UsbSocksDatagrams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), "2001:db8::1", 55);
    }
    @Test(expected = IOException.class) public void oversizedPayloadRejected() throws Exception {
        new UsbSocksDatagrams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), "192.0.2.1", 55).send(new byte[65508], 0, 65508);
    }
}
