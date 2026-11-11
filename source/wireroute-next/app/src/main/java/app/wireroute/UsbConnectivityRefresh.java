package app.wireroute;

/** One real VPN network renewal after late Android UID-default preparation. */
final class UsbConnectivityRefresh {
    private long handledSession;

    synchronized boolean claim(long session, long preparedNetwork, long currentNetwork,
                               boolean stableMode) {
        if (!stableMode || session <= 0 || preparedNetwork <= 0 || currentNetwork <= 0
                || handledSession == session) return false;
        handledSession = session;
        // A user mode change already produced a real, newer CONNECTED event.
        return preparedNetwork == currentNetwork;
    }

    static boolean current(long expectedSession, long offeredSession,
                           long expectedAction, long currentAction) {
        return expectedSession > 0 && expectedSession == offeredSession
                && expectedAction == currentAction;
    }

    static void requireSameSession(long requiredSession, long offeredSession)
            throws java.io.IOException {
        if (requiredSession != 0 && (requiredSession < 0 || requiredSession != offeredSession))
            throw new java.io.IOException("USB session changed before connectivity renewal");
    }
}
