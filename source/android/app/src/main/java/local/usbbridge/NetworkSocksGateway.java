package local.usbbridge;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private SOCKS endpoint for the shell-owned router. Every outbound socket is
 * created by one explicit route; there is deliberately no default-route fallback. */
final class NetworkSocksGateway implements Closeable {
    interface Route {
        void check() throws IOException;
        InetAddress resolve(String host) throws IOException;
        InetAddress dns() throws IOException;
        Socket tcp(InetAddress address, int port) throws IOException;
        DatagramSocket udp(InetAddress address, int port) throws IOException;
    }

    final Route route;
    final ServerSocket listener;
    final String password;
    final Set<Session> sessions = Collections.newSetFromMap(new ConcurrentHashMap<Session, Boolean>());
    private final Semaphore capacity = new Semaphore(80);
    private volatile boolean closed;

    NetworkSocksGateway(Route route) throws IOException {
        this.route = route;
        byte[] entropy = Wire.random(24);
        StringBuilder token = new StringBuilder();
        for (byte value : entropy) token.append(String.format(Locale.ROOT, "%02x", value & 255));
        password = token.toString();
        listener = new ServerSocket(0, 32, InetAddress.getByAddress(new byte[]{127,0,0,1}));
        Wire.run(this::accept);
    }

    int port() { return listener.getLocalPort(); }

    private void accept() {
        while (!closed) {
            try {
                Socket client = listener.accept();
                synchronized (this) {
                    if (closed || !capacity.tryAcquire()) { client.close(); continue; }
                    Session session = new Session(client);
                    sessions.add(session);
                    Wire.run(session::run);
                }
            } catch (IOException error) { if (!closed) close(); }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        quietly(listener);
        for (Session session : sessions) session.close();
    }

    private void check() throws IOException {
        if (closed) throw new SocketException("VPN gateway closed");
        route.check();
    }

    private static void quietly(Closeable closeable) {
        if (closeable != null) try { closeable.close(); } catch (IOException ignored) { }
    }

    static final class Destination {
        final int type, port;
        final String host;
        final byte[] address;
        Destination(int type, byte[] address, String host, int port) {
            this.type = type; this.address = address; this.host = host; this.port = port;
        }
        boolean fakeDns() { return port == 53 && "198.18.7.2".equals(host); }
        int headerLength() { return 6 + address.length + (type == 3 ? 1 : 0); }
    }

    static Destination destination(InputStream input, int type) throws IOException {
        byte[] address;
        String host;
        if (type == 1 || type == 4) {
            address = Wire.read(input, type == 1 ? 4 : 16);
            host = InetAddress.getByAddress(address).getHostAddress();
        } else if (type == 3) {
            int length = input.read();
            if (length <= 0) throw new IOException("Empty SOCKS destination");
            address = Wire.read(input, length);
            host = new String(address, StandardCharsets.US_ASCII);
            if (host.indexOf('\0') >= 0) throw new IOException("Invalid SOCKS destination");
        } else throw new IOException("Invalid SOCKS address type");
        byte[] port = Wire.read(input, 2);
        return new Destination(type, address, host, ((port[0]&255)<<8)|(port[1]&255));
    }

    final class Session implements Closeable {
        final Socket client;
        final AtomicBoolean ended = new AtomicBoolean();
        final Object writeLock = new Object();
        final Map<String, DatagramSocket> datagrams = new LinkedHashMap<>(16, .75f, true);
        volatile Socket remote;
        volatile DnsExchange dnsExchange;
        Session(Socket client) { this.client = client; }

        void run() {
            try {
                client.setTcpNoDelay(true);
                client.setSoTimeout(8000);
                InputStream input = client.getInputStream();
                OutputStream output = client.getOutputStream();
                byte[] greeting = Wire.read(input, 2);
                if (greeting[0] != 5 || greeting[1] == 0) throw new IOException("SOCKS greeting");
                boolean auth = false;
                for (byte method : Wire.read(input, greeting[1]&255)) auth |= method == 2;
                output.write(new byte[]{5, (byte)(auth ? 2 : 255)});
                if (!auth) return;
                byte[] login = Wire.read(input, 2);
                if (login[0] != 1) throw new IOException("SOCKS authentication");
                byte[] username = Wire.read(input, login[1]&255);
                int length = input.read();
                if (length < 0) throw new EOFException();
                byte[] secret = Wire.read(input, length);
                boolean accepted = MessageDigest.isEqual(username, Wire.bytes("usbbridge"))
                        & MessageDigest.isEqual(secret, Wire.bytes(password));
                output.write(new byte[]{1, (byte)(accepted ? 0 : 1)});
                if (!accepted) return;
                check();
                byte[] request = Wire.read(input, 4);
                if (request[0] != 5 || request[2] != 0) throw new IOException("SOCKS request");
                Destination target = destination(input, request[3]&255);
                if (request[1] == 1) tcp(input, output, target);
                else if (request[1] == 5) udp(input, output);
                else output.write(new byte[]{5,7,0,1,0,0,0,0,0,0});
            } catch (IOException ignored) {
                // The native caller receives EOF and cannot fall through to any other route.
            } finally { close(); }
        }

        InetAddress resolve(Destination target) throws IOException {
            check();
            return target.fakeDns() ? route.dns() : target.type == 3
                    ? route.resolve(target.host) : InetAddress.getByAddress(target.address);
        }

        void tcp(InputStream input, OutputStream output, Destination target) throws IOException {
            if (target.fakeDns()) { dnsTcp(input, output); return; }
            Socket outgoing = route.tcp(resolve(target), target.port);
            synchronized (this) {
                if (ended.get()) { outgoing.close(); return; }
                remote = outgoing;
            }
            check();
            outgoing.setTcpNoDelay(true);
            outgoing.setSoTimeout(300000);
            client.setSoTimeout(300000);
            output.write(new byte[]{5,0,0,1,0,0,0,0,0,0});
            Wire.run(() -> {
                try {
                    copy(input, outgoing.getOutputStream());
                    outgoing.shutdownOutput();
                } catch (IOException error) { close(); }
            });
            copy(outgoing.getInputStream(), output);
            client.shutdownOutput();
        }

        void dnsTcp(InputStream input, OutputStream output) throws IOException {
            // HEV sends the Android synthetic DNS endpoint over TCP. The VPN's
            // configured resolver may legitimately support only UDP: terminate
            // DNS framing here, then query that SAME resolver over this route.
            client.setSoTimeout(120000);
            output.write(new byte[]{5,0,0,1,0,0,0,0,0,0});
            for (;;) {
                int high = input.read(); if (high < 0) return;
                int low = input.read(); if (low < 0) throw new EOFException();
                int size = (high << 8) | low;
                if (size < 12) throw new IOException("Short DNS query");
                byte[] query = Wire.read(input, size);
                check();
                DnsExchange exchange = new DnsExchange(query);
                synchronized (this) {
                    if (ended.get()) throw new SocketException("Session closed");
                    dnsExchange = exchange;
                }
                byte[] response;
                try { response = exchange.run(); }
                finally { exchange.close(); synchronized (this) { if (dnsExchange == exchange) dnsExchange = null; } }
                check(); output.write(response.length >>> 8); output.write(response.length); output.write(response);
            }
        }

        final class DnsExchange implements Closeable {
            final byte[] query;
            final CountDownLatch answered = new CountDownLatch(1), startTcp = new CountDownLatch(1);
            final AtomicBoolean finished = new AtomicBoolean();
            final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(2);
            volatile byte[] answer;
            DatagramSocket udpSocket; Socket tcpSocket;
            DnsExchange(byte[] query) { this.query = query; }
            void offer(byte[] response) {
                if (!dnsResponseMatches(query, response) || (response[2] & 2) != 0) { startTcp.countDown(); return; }
                synchronized (this) { if (!finished.get() && answer == null) answer = response; }
                answered.countDown();
            }
            void complete() { if (remaining.decrementAndGet() == 0) answered.countDown(); }
            byte[] run() throws IOException {
                final InetAddress server = route.dns();
                Wire.run(() -> {
                    DatagramSocket socket = null;
                    try {
                        check(); socket = route.udp(server, 53);
                        synchronized (this) { if (finished.get()) return; udpSocket = socket; }
                        socket.setSoTimeout(500);
                        socket.send(new DatagramPacket(query, query.length));
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        byte[] buffer = new byte[65507];
                        while (!finished.get() && answer == null && System.nanoTime() < deadline) {
                            check(); DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                            try { socket.receive(packet); } catch (SocketTimeoutException timeout) { continue; }
                            byte[] response = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
                            if (dnsResponseMatches(query, response)) { offer(response); break; }
                        }
                    } catch (IOException unavailable) { startTcp.countDown(); }
                    finally { if (socket != null) socket.close(); complete(); }
                });
                Wire.run(() -> {
                    Socket socket = null;
                    try {
                        // UDP normally answers immediately. Hedge a slow or
                        // truncated reply; never serialize a long TCP timeout
                        // before trying a working UDP resolver.
                        startTcp.await(150, TimeUnit.MILLISECONDS);
                        if (finished.get() || answer != null) return;
                        check(); socket = route.tcp(server, 53);
                        synchronized (this) { if (finished.get()) return; tcpSocket = socket; }
                        socket.setSoTimeout(5000);
                        OutputStream out = socket.getOutputStream();
                        out.write(query.length >>> 8); out.write(query.length); out.write(query);
                        byte[] size = Wire.read(socket.getInputStream(), 2);
                        int length = ((size[0] & 255) << 8) | (size[1] & 255);
                        if (length >= 12) offer(Wire.read(socket.getInputStream(), length));
                    } catch (IOException | InterruptedException unavailable) { }
                    finally { quietly(socket); complete(); }
                });
                try { answered.await(6000, TimeUnit.MILLISECONDS); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException(stopped); }
                check();
                return answer != null ? answer : dnsFailure(query);
            }
            @Override public synchronized void close() {
                finished.set(true); startTcp.countDown(); answered.countDown();
                if (udpSocket != null) udpSocket.close(); quietly(tcpSocket);
            }
        }

        void copy(InputStream source, OutputStream destination) throws IOException {
            byte[] buffer = new byte[32768];
            for (int count; (count = source.read(buffer)) >= 0;) {
                if (ended.get()) throw new SocketException("Session closed");
                check();
                destination.write(buffer, 0, count);
            }
        }

        void udp(InputStream input, OutputStream output) throws IOException {
            client.setSoTimeout(120000);
            output.write(new byte[]{5,0,0,1,0,0,0,0,0,0});
            while (!ended.get()) {
                byte[] header = Wire.read(input, 4);
                int size = ((header[0]&255)<<8)|(header[1]&255);
                Destination target = destination(input, header[3]&255);
                if ((header[2]&255) != target.headerLength() || size > 65507)
                    throw new IOException("Invalid UDP frame");
                byte[] payload = Wire.read(input, size);
                check();
                String key = target.host + ":" + target.port;
                DatagramSocket socket;
                synchronized (this) { socket = datagrams.get(key); }
                if (socket == null) {
                    try { socket = route.udp(resolve(target), target.port); }
                    catch (IOException unreachable) { check(); continue; }
                    DatagramSocket evicted = null;
                    synchronized (this) {
                        if (ended.get()) {
                            socket.close();
                            throw new IOException("UDP session unavailable");
                        }
                        if (datagrams.size() >= 64) {
                            Iterator<Map.Entry<String, DatagramSocket>> oldest = datagrams.entrySet().iterator();
                            evicted = oldest.next().getValue(); oldest.remove();
                        }
                        datagrams.put(key, socket);
                    }
                    if (evicted != null) evicted.close();
                    final DatagramSocket receiver = socket;
                    Wire.run(() -> receiveUdp(receiver, key, target, output));
                }
                check();
                try { socket.send(new DatagramPacket(payload, payload.length)); }
                catch (IOException unreachable) { check(); dropDatagram(key, socket); }
            }
        }

        void dropDatagram(String key, DatagramSocket socket) {
            synchronized (this) {
                if (datagrams.get(key) == socket) datagrams.remove(key);
            }
            socket.close();
        }

        void receiveUdp(DatagramSocket socket, String key, Destination target, OutputStream output) {
            boolean outputFailed = false;
            try {
                socket.setSoTimeout(60000);
                byte[] data = new byte[65507];
                while (!ended.get()) {
                    DatagramPacket packet = new DatagramPacket(data, data.length);
                    // Inactive endpoints must not accumulate forever. A new
                    // packet recreates this one endpoint without resetting DNS
                    // and all other UDP traffic sharing the SOCKS association.
                    try { socket.receive(packet); } catch (SocketTimeoutException idle) { check(); break; }
                    check();
                    // Echo the requested destination, particularly the synthetic DNS address.
                    ByteArrayOutputStream frame = new ByteArrayOutputStream(target.headerLength()+packet.getLength());
                    frame.write(packet.getLength()>>>8); frame.write(packet.getLength());
                    frame.write(target.headerLength()); frame.write(target.type);
                    if (target.type == 3) frame.write(target.address.length);
                    frame.write(target.address); frame.write(target.port>>>8); frame.write(target.port);
                    frame.write(data, 0, packet.getLength());
                    synchronized (writeLock) {
                        try { output.write(frame.toByteArray()); }
                        catch (IOException brokenAssociation) { outputFailed = true; throw brokenAssociation; }
                    }
                }
            } catch (IOException error) {
                // An unreachable QUIC/DNS endpoint is not a broken cable. A
                // revoked VPN still closes the complete association, with no
                // fallback to another network.
                if (outputFailed) close();
                else try { check(); } catch (IOException revoked) { close(); }
            } finally { dropDatagram(key, socket); }
        }

        @Override public void close() {
            if (!ended.compareAndSet(false, true)) return;
            quietly(client);
            synchronized (this) {
                quietly(remote);
                if (dnsExchange != null) dnsExchange.close();
                for (DatagramSocket socket : datagrams.values()) socket.close();
                datagrams.clear();
            }
            sessions.remove(this);
            capacity.release();
        }
    }

    static int dnsQuestionEnd(byte[] query) {
        if (query.length < 12 || query[4] != 0 || query[5] != 1) return -1;
        int index = 12;
        while (index < query.length) {
            int length = query[index++] & 255;
            if (length == 0) return index + 4 <= query.length ? index + 4 : -1;
            if (length > 63 || index + length > query.length) return -1;
            index += length;
        }
        return -1;
    }
    static boolean dnsResponseMatches(byte[] query, byte[] response) {
        int end = dnsQuestionEnd(query);
        if (end < 0 || response.length < end || response[0] != query[0] || response[1] != query[1]
                || (response[2] & 128) == 0 || response[4] != query[4] || response[5] != query[5]) return false;
        for (int i = 12; i < end; i++) {
            int left = query[i] & 255, right = response[i] & 255;
            if (i < end - 4) {
                if (left >= 'A' && left <= 'Z') left += 32;
                if (right >= 'A' && right <= 'Z') right += 32;
            }
            if (left != right) return false;
        }
        return true;
    }
    static byte[] dnsFailure(byte[] query) {
        int end = dnsQuestionEnd(query);
        byte[] response = Arrays.copyOf(query, end >= 12 ? end : 12);
        response[2] = (byte)((query[2] & 1) | 128); response[3] = (byte)130;
        for (int i = 6; i < 12; i++) response[i] = 0;
        return response;
    }
}
