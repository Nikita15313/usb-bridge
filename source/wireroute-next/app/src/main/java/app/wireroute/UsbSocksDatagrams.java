package app.wireroute;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** The existing USB Bridge SOCKS5 UDP-over-stream dialect. No profile secrets. */
final class UsbSocksDatagrams {
    private final InputStream in;
    private final OutputStream out;
    private final byte[] sendFrame;
    private final byte[] receiveHeader = new byte[256];
    private final int headerLength;
    private final int port;

    UsbSocksDatagrams(InputStream in, OutputStream out, String host, int port) throws IOException {
        if (port < 1 || port > 65535) throw new IOException("Invalid UDP destination");
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        if (name.length == 0 || name.length > 248 || host.indexOf(':') >= 0
                || !host.equals(new String(name, StandardCharsets.US_ASCII)))
            throw new IOException("USB requires an IPv4 address or ASCII endpoint hostname");
        this.in = in;
        this.out = out;
        this.port = port;
        // Domain form deliberately resolves on the PC, never using the phone's DNS.
        headerLength = name.length + 7;
        sendFrame = new byte[headerLength + 65507];
        sendFrame[2] = (byte) headerLength;
        sendFrame[3] = 3;
        sendFrame[4] = (byte) name.length;
        System.arraycopy(name, 0, sendFrame, 5, name.length);
        sendFrame[headerLength - 2] = (byte) (port >>> 8);
        sendFrame[headerLength - 1] = (byte) port;
    }

    void handshake() throws IOException {
        out.write(new byte[]{5, 1, 0});
        out.flush();
        byte[] method = read(in, 2);
        if (method[0] != 5 || method[1] != 0) throw new IOException("USB SOCKS negotiation failed");
        out.write(new byte[]{5, 5, 0, 1, 0, 0, 0, 0, 0, 0});
        out.flush();
        byte[] reply = read(in, 4);
        if (reply[0] != 5 || reply[1] != 0 || reply[2] != 0)
            throw new IOException("USB UDP relay unavailable");
        readAddress(in, reply[3] & 255);
        read(in, 2);
    }

    synchronized void send(byte[] data, int offset, int length) throws IOException {
        if (length < 0 || length > 65507 || offset < 0 || offset > data.length - length)
            throw new IOException("Invalid UDP payload");
        sendFrame[0] = (byte) (length >>> 8);
        sendFrame[1] = (byte) length;
        System.arraycopy(data, offset, sendFrame, headerLength, length);
        // Each write crosses a PFD and the USB multiplexer. Keep the complete
        // datagram in one write instead of four separate tiny USB frames.
        out.write(sendFrame, 0, headerLength + length);
        out.flush();
    }

    byte[] receive() throws IOException {
        int length = readFrameHeader();
        byte[] data = new byte[length];
        readFully(in, data, 0, length);
        return data;
    }

    /** Reuses the caller's packet storage on the high-throughput relay path. */
    int receive(byte[] destination) throws IOException {
        int length = readFrameHeader();
        if (length > destination.length) throw new IOException("UDP receive buffer too small");
        readFully(in, destination, 0, length);
        return length;
    }

    private int readFrameHeader() throws IOException {
        readFully(in, receiveHeader, 0, 4);
        int length = ((receiveHeader[0] & 255) << 8) | (receiveHeader[1] & 255);
        if (length > 65507) throw new IOException("Oversize UDP payload");
        int expectedHeaderLength = receiveHeader[2] & 255;
        int type = receiveHeader[3] & 255;
        int addressLength;
        if (type == 1) { readFully(in, receiveHeader, 0, 4); addressLength = 4; }
        else if (type == 4) { readFully(in, receiveHeader, 0, 16); addressLength = 16; }
        else if (type == 3) {
            int nameLength = in.read();
            if (nameLength < 1) throw new IOException("Invalid UDP address");
            readFully(in, receiveHeader, 0, nameLength);
            addressLength = nameLength + 1;
        } else throw new IOException("Invalid UDP address type");
        if (expectedHeaderLength != addressLength + 6) throw new IOException("Invalid UDP header length");
        readFully(in, receiveHeader, 0, 2);
        if ((((receiveHeader[0] & 255) << 8) | (receiveHeader[1] & 255)) != port)
            throw new IOException("Unexpected UDP source port");
        return length;
    }

    private static int readAddress(InputStream in, int type) throws IOException {
        if (type == 1) { read(in, 4); return 4; }
        if (type == 4) { read(in, 16); return 16; }
        if (type == 3) {
            int length = in.read();
            if (length < 1) throw new IOException("Invalid UDP address");
            read(in, length);
            return length + 1;
        }
        throw new IOException("Invalid UDP address type");
    }

    static byte[] read(InputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
        readFully(in, bytes, 0, count);
        return bytes;
    }

    private static void readFully(InputStream in, byte[] bytes, int offset, int count) throws IOException {
        int at = 0;
        while (at < count) {
            int n = in.read(bytes, offset + at, count - at);
            if (n < 0) throw new EOFException("USB relay closed");
            if (n == 0) {
                int one = in.read();
                if (one < 0) throw new EOFException("USB relay closed");
                bytes[offset + at++] = (byte)one;
                continue;
            }
            at += n;
        }
    }
}
