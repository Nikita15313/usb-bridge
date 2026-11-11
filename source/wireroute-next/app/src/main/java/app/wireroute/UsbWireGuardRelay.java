package app.wireroute;

import android.os.ParcelFileDescriptor;
import com.wireguard.config.InetEndpoint;
import java.io.Closeable;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Only encrypted WireGuard packets cross this loopback/USB adapter. */
final class UsbWireGuardRelay implements Closeable {
    private final ParcelFileDescriptor descriptor;
    private final DatagramSocket local;
    private final UsbSocksDatagrams stream;
    private final Runnable lost;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private volatile boolean closed;
    private volatile int backendPort;

    UsbWireGuardRelay(ParcelFileDescriptor descriptor, InetEndpoint endpoint, Runnable lost) throws IOException {
        this.descriptor = descriptor;
        this.lost = lost;
        stream = new UsbSocksDatagrams(new BufferedInputStream(new ParcelFileDescriptor.AutoCloseInputStream(descriptor), 65536),
                new ParcelFileDescriptor.AutoCloseOutputStream(descriptor), endpoint.getHost(), endpoint.getPort());
        local = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        // Bounded burst buffers prevent loopback loss when USB delivers several
        // frames together. Android may clamp the hint to its kernel maximum.
        try { local.setReceiveBufferSize(1024 * 1024); } catch (java.net.SocketException ignored) { }
        try { local.setSendBufferSize(1024 * 1024); } catch (java.net.SocketException ignored) { }
        // Closing the descriptor bounds the handshake even if a peer stalls.
        java.util.concurrent.ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
        java.util.concurrent.ScheduledFuture<?> timeout = deadline.schedule(() -> {
            try { descriptor.close(); } catch (IOException ignored) { }
        }, 15, TimeUnit.SECONDS);
        try { stream.handshake(); }
        catch (IOException error) { close(); throw error; }
        finally { timeout.cancel(false); deadline.shutdownNow(); }
    }

    int port() { return local.getLocalPort(); }

    void acceptBackendPort(int port) throws IOException {
        if (port < 1 || port > 65535 || closed) throw new IOException("WireGuard socket unavailable");
        backendPort = port;
    }

    void start() {
        workers.execute(() -> {
            try {
                byte[] data = new byte[65507];
                DatagramPacket packet = new DatagramPacket(data, data.length);
                while (!closed) {
                    packet.setLength(data.length);
                    local.receive(packet);
                    // Do not trust the first sender: GoBackend.protect supplies its actual port.
                    if (backendPort == 0 || packet.getPort() != backendPort || !packet.getAddress().isLoopbackAddress()) continue;
                    stream.send(data, packet.getOffset(), packet.getLength());
                }
            } catch (IOException error) { fail(); }
        });
        workers.execute(() -> {
            try {
                byte[] data = new byte[65507];
                DatagramPacket packet = new DatagramPacket(data, data.length,
                        InetAddress.getByName("127.0.0.1"), 1);
                while (!closed) {
                    int length = stream.receive(data);
                    int port = backendPort;
                    if (port != 0) {
                        packet.setLength(length);
                        packet.setPort(port);
                        local.send(packet);
                    }
                }
            } catch (IOException error) { fail(); }
        });
    }

    private void fail() {
        if (closed) return;
        close();
        lost.run();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        local.close();
        try { descriptor.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
    }
}
