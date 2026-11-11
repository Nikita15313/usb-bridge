package app.wireroute;

/** HEV has one process-global native worker; Java service instances do not. */
final class NativeTunnelLease {
    interface Backend {
        boolean start(String path, int descriptor);
        boolean stop();
        boolean isRunning();
        long[] statistics();
    }
    private final Backend backend;
    private volatile Object owner;

    NativeTunnelLease(Backend backend) { this.backend = backend; }

    synchronized boolean start(Object candidate, String path, int descriptor) {
        if (candidate == null) throw new IllegalArgumentException("Native owner is required");
        // Even an exited worker belongs to its original service until that
        // service joins/releases it. Otherwise an old cleanup could kill a
        // successor which happened to start between two watchdog ticks.
        if (owner != null) return false;
        if (!backend.start(path, descriptor)) return false;
        owner = candidate;
        return true;
    }

    synchronized boolean isRunning(Object candidate) {
        return owner != null && owner == candidate && backend.isRunning();
    }

    boolean owns(Object candidate) { return owner != null && owner == candidate; }

    synchronized boolean stop(Object candidate) {
        if (owner == null || owner != candidate) return true;
        boolean stopped = backend.stop();
        if (stopped || !backend.isRunning()) owner = null;
        return owner == null;
    }

    synchronized long[] statistics(Object candidate) {
        return owner != null && owner == candidate ? backend.statistics() : new long[4];
    }
}
