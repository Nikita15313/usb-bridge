package app.wireroute;

import hev.htproxy.TProxyService;

/** All HEV users in this process must participate in the same ownership gate. */
final class HevDatapath {
    private static final NativeTunnelLease LEASE = new NativeTunnelLease(new NativeTunnelLease.Backend() {
        public boolean start(String path, int fd) { return TProxyService.TProxyStartService(path, fd); }
        public boolean stop() { return TProxyService.TProxyStopService(); }
        public boolean isRunning() { return TProxyService.TProxyIsRunning(); }
        public long[] statistics() { return TProxyService.TProxyGetStats(); }
    });
    static boolean start(Object owner, String path, int fd) { return LEASE.start(owner, path, fd); }
    static boolean stop(Object owner) { return LEASE.stop(owner); }
    static boolean isRunning(Object owner) { return LEASE.isRunning(owner); }
    static boolean owns(Object owner) { return LEASE.owns(owner); }
    static long[] statistics(Object owner) { return LEASE.statistics(owner); }
    private HevDatapath() { }
}
