package app.wireroute;

import com.wireguard.config.BadConfigException;
import com.wireguard.config.Config;
import com.wireguard.config.InetNetwork;
import com.wireguard.config.Interface;
import com.wireguard.config.Peer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;

public final class ConfigUtils {
    private ConfigUtils() {
    }

    public static Config parse(String text) throws IOException, BadConfigException {
        return Config.parse(new BufferedReader(new StringReader(text)));
    }

    /** The imported profile is immutable; only the in-memory transport endpoint changes. */
    public static Config withUsbRelayEndpoint(Config base, int port) throws BadConfigException, com.wireguard.config.ParseException {
        if (port < 1 || port > 65535 || base.getPeers().size() != 1)
            throw new IllegalArgumentException("Invalid USB relay endpoint");
        Peer old = base.getPeers().iterator().next();
        Peer.Builder peer = new Peer.Builder().setPublicKey(old.getPublicKey())
                .addAllowedIps(old.getAllowedIps())
                .setEndpoint(com.wireguard.config.InetEndpoint.parse("127.0.0.1:" + port));
        if (old.getPreSharedKey().isPresent()) peer.setPreSharedKey(old.getPreSharedKey().get());
        if (old.getPersistentKeepalive().isPresent()) peer.setPersistentKeepalive(old.getPersistentKeepalive().get());
        return new Config.Builder().setInterface(base.getInterface()).addPeer(peer.build()).build();
    }

    public static Config withRouting(Config base, RoutingMode mode, Set<String> packages)
            throws BadConfigException {
        return withRouting(base, mode, packages, false);
    }

    public static Config withRouting(Config base, RoutingMode mode, Set<String> packages,
                                     boolean usbUnderlay) throws BadConfigException {
        if (mode == RoutingMode.ONLY_SELECTED && packages.isEmpty()) {
            throw new EmptySelectionException();
        }

        Interface old = base.getInterface();
        Interface.Builder builder = new Interface.Builder()
                .addAddresses(old.getAddresses())
                .addDnsServers(old.getDnsServers())
                .addDnsSearchDomains(old.getDnsSearchDomains())
                .setKeyPair(old.getKeyPair());

        // With no VPN DNS configured, Android inherits the physical network's
        // resolver. It may filter names on Wi-Fi even when WireGuard is healthy;
        // a USB underlay may have no resolver at all. Supply runtime-only DNS
        // when both fallback addresses are covered by the VPN's IPv4 routes.
        // Explicit DNS and split/IPv6-only profiles retain their own behavior.
        if (old.getDnsServers().isEmpty() && inspect(base).fullIpv4) {
            try {
                builder.addDnsServers(java.util.Arrays.asList(
                        InetAddress.getByAddress(new byte[]{1, 1, 1, 1}),
                        InetAddress.getByAddress(new byte[]{8, 8, 8, 8})));
            } catch (UnknownHostException impossibleForNumericAddress) {
                throw new AssertionError(impossibleForNumericAddress);
            }
        }

        if (old.getListenPort().isPresent()) {
            builder.setListenPort(old.getListenPort().get());
        }
        if (old.getMtu().isPresent()) {
            builder.setMtu(old.getMtu().get());
        }

        if (mode == RoutingMode.ONLY_SELECTED) {
            builder.includeApplications(packages);
        } else {
            builder.excludeApplications(packages);
        }

        return new Config.Builder()
                .setInterface(builder.build())
                .addPeers(base.getPeers())
                .build();
    }

    // GoBackend only blocks the unused address family for a single peer with
    // a default route. Partial IPv6 must not escape onto Wi-Fi/mobile data.
    public static boolean supportsUsb(Config config) {
        if (config.getPeers().size() != 1) return false;
        boolean default4 = false, has6 = false;
        for (InetNetwork address : config.getInterface().getAddresses())
            has6 |= address.getAddress() instanceof Inet6Address;
        for (java.net.InetAddress dns : config.getInterface().getDnsServers())
            has6 |= dns instanceof Inet6Address;
        for (Peer peer : config.getPeers()) for (InetNetwork route : peer.getAllowedIps()) {
            default4 |= route.getAddress() instanceof Inet4Address && route.getMask() == 0;
            has6 |= route.getAddress() instanceof Inet6Address;
        }
        return default4 && (!has6 || inspect(config).fullIpv6);
    }

    public static Info inspect(Config config) {
        boolean fullIpv4 = false;
        boolean fullIpv6 = false;
        boolean lowerIpv4 = false;
        boolean upperIpv4 = false;
        boolean lowerIpv6 = false;
        boolean upperIpv6 = false;
        for (Peer peer : config.getPeers()) {
            for (InetNetwork network : peer.getAllowedIps()) {
                if (network.getAddress() instanceof Inet4Address) {
                    if (network.getMask() == 0) {
                        fullIpv4 = true;
                    } else if (network.getMask() == 1) {
                        int half = routeHalf(network);
                        lowerIpv4 |= half < 0;
                        upperIpv4 |= half > 0;
                    }
                } else if (network.getAddress() instanceof Inet6Address) {
                    if (network.getMask() == 0) {
                        fullIpv6 = true;
                    } else if (network.getMask() == 1) {
                        int half = routeHalf(network);
                        lowerIpv6 |= half < 0;
                        upperIpv6 |= half > 0;
                    }
                }
            }
        }
        fullIpv4 |= lowerIpv4 && upperIpv4;
        fullIpv6 |= lowerIpv6 && upperIpv6;
        return new Info(config.getPeers().size(), fullIpv4, fullIpv6);
    }

    /** Returns -1 for the lower /1, +1 for the upper /1, or 0 for neither. */
    private static int routeHalf(InetNetwork network) {
        byte[] address = network.getAddress().getAddress();
        for (int index = 1; index < address.length; index++) {
            if (address[index] != 0) {
                return 0;
            }
        }
        int first = address[0] & 0xff;
        if (first == 0) {
            return -1;
        }
        return first == 128 ? 1 : 0;
    }

    public static final class Info {
        public final int peerCount;
        public final boolean fullIpv4;
        public final boolean fullIpv6;

        Info(int peerCount, boolean fullIpv4, boolean fullIpv6) {
            this.peerCount = peerCount;
            this.fullIpv4 = fullIpv4;
            this.fullIpv6 = fullIpv6;
        }
    }

    public static final class EmptySelectionException extends IllegalArgumentException {
        EmptySelectionException() {
            super("At least one included application is required");
        }
    }
}
