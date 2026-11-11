package app.wireroute;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import app.wireroute.release263.R;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Keeps USB internet when the encryption switch is off, under the same VPN package. */
public final class UsbPlainVpnService extends VpnService {
    private static volatile UsbPlainVpnService instance;
    static volatile boolean running;
    static volatile long startAttempts;
    private static final String CHANNEL = "usb_internet";
    private final ScheduledExecutorService watch = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<ParcelFileDescriptor> descriptors = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(80);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final CountDownLatch destroyed = new CountDownLatch(1);
    private volatile boolean stopping;
    private volatile boolean starting;
    private volatile long[] nativeStats = new long[4];
    private volatile long lastNativeSample;
    private volatile String shutdownReason = "";
    private boolean reportedStarted;
    private long session;
    private long declaredConnectivityHandle = Long.MIN_VALUE;
    private ParcelFileDescriptor tun;
    private ServerSocket socks;

    static void startIfAvailable(Context context) {
        long offered = UsbBridgeUnderlay.offeredRelaySession(context);
        if (offered == 0) return;
        TunnelController controller = ((WireRouteApplication)context.getApplicationContext()).getTunnelController();
        if (!controller.canStartPlainUsb()) return;
        if (running) {
            UsbPlainVpnService active = instance;
            if (active != null && active.session == offered)
                new Handler(Looper.getMainLooper()).post(() -> active.publishHealth(offered));
            else if (active != null) new Handler(Looper.getMainLooper()).post(active::shutdown);
            return;
        }
        if (VpnService.prepare(context) != null) {
            UsbBridgeUnderlay.reportSession(context, "plain_permission", offered, "plain");
            return;
        }
        Intent start = new Intent(context, UsbPlainVpnService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start);
            else context.startService(start);
        } catch (RuntimeException failure) {
            android.util.Log.w("WireRouteUsb", "Plain USB service launch failed", failure);
            UsbBridgeUnderlay.reportSession(context, "plain_background", offered, "plain");
        }
    }

    static void stopForVpn() throws IOException {
        UsbPlainVpnService service = instance;
        if (service == null) return;
        new Handler(Looper.getMainLooper()).post(service::shutdown);
        try {
            if (!service.stopped.await(4000, TimeUnit.MILLISECONDS)) throw new IOException("USB mode did not stop");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IOException(error);
        }
    }

    static void stopForConnectivityRefresh(long expectedSession) throws IOException {
        UsbPlainVpnService service = instance;
        if (service == null || service.session != expectedSession) throw new IOException("USB mode changed");
        new Handler(Looper.getMainLooper()).post(() -> {
            if (instance == service && service.session == expectedSession) {
                service.shutdownReason = "Android connectivity became ready";
                service.shutdown();
            }
        });
        try {
            // Same-class restart must wait for destruction, not merely native stop.
            if (!service.destroyed.await(4000, TimeUnit.MILLISECONDS))
                throw new IOException("USB service destruction timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException(interrupted);
        }
    }

    @Override public void onCreate() { super.onCreate(); instance = this; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startAttempts++;
        if (running || starting || stopping) return START_NOT_STICKY;
        promote();
        try {
            TunnelController controller = ((WireRouteApplication)getApplication()).getTunnelController();
            if (!controller.canStartPlainUsb()) throw new IOException("VPN is active or changing mode");
            session = UsbBridgeUnderlay.offeredRelaySession(this);
            if (session == 0) throw new IOException("USB disconnected");
            starting = true;
            System.loadLibrary("hev-socks5-tunnel");
            socks = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
            workers.execute(this::accept);
            Builder builder = new Builder().setSession("WireRoute · интернет USB").setMtu(1500)
                    .addAddress("198.18.8.1", 30).addRoute("0.0.0.0", 0).addDnsServer("198.18.7.2")
                    .addDisallowedApplication(getPackageName()).addDisallowedApplication("local.usbbridge")
                    .addDisallowedApplication("com.android.shell");
            // Keep the VPN free of TRANSPORT_TEST: otherwise Android tethering
            // may choose this VPN instead of the real USB test upstream.
            builder.setUnderlyingNetworks(null);
            tun = builder.establish();
            if (tun == null) throw new IOException("USB internet permission missing");
            declaredConnectivityHandle = 0;
            File config = new File(getFilesDir(), "usb-plain.yml");
            String yaml = "tunnel:\n  name: wireroute-usb\n  mtu: 1500\n  ipv4: 198.18.8.1\n"
                    + "socks5:\n  address: 127.0.0.1\n  port: " + socks.getLocalPort()
                    + "\n  udp: tcp\nmisc:\n  max-session-count: 80\n  connect-timeout: 15000\n"
                    + "  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n  log-level: warn\n";
            try (FileOutputStream out = new FileOutputStream(config)) { out.write(yaml.getBytes(StandardCharsets.UTF_8)); }
            if (!HevDatapath.start(this, config.getAbsolutePath(), tun.getFd())) throw new IOException("USB datapath unavailable");
            // JNI start only confirms creation of a native thread. Let its
            // initial configuration failure surface before publishing started.
            // Actual internet reachability is still a separate Bridge check.
            watch.scheduleWithFixedDelay(this::checkNativeHealth, 150, 1000, TimeUnit.MILLISECONDS);
        } catch (Exception | LinkageError failure) {
            android.util.Log.e("WireRouteUsb", "Plain USB datapath failed", failure);
            long failedSession = session;
            shutdownReason = "startup: " + failure.getClass().getSimpleName();
            shutdown();
            if (failedSession != 0)
                UsbBridgeUnderlay.reportSession(this, "plain_failed", failedSession, "plain");
        }
        return START_NOT_STICKY;
    }

    private void checkNativeHealth() {
        if (stopping) return;
        try {
            if (UsbBridgeUnderlay.offeredRelaySession(this) != session) {
                shutdownReason = "USB session ended";
                new Handler(Looper.getMainLooper()).post(this::shutdown);
                return;
            }
            if (!HevDatapath.isRunning(this)) throw new IOException("Native USB worker stopped");
            long[] sampled = HevDatapath.statistics(this);
            synchronized (this) {
                if (stopping) return;
                starting = false; running = true;
                if (sampled != null && sampled.length == 4) nativeStats = sampled.clone();
                lastNativeSample = android.os.SystemClock.elapsedRealtime();
            }
            refreshConnectivityDeclaration();
            ((WireRouteApplication)getApplication()).getTunnelController().refreshUsbConnectivity(false);
            final long sampledSession = session;
            // Serialize publication with Activity connect/disconnect and service
            // shutdown. A worker report from the old plain mode must never
            // arrive after the user's request to start encrypted hotspot egress.
            new Handler(Looper.getMainLooper()).post(() -> publishHealth(sampledSession));
        } catch (Exception | LinkageError failure) {
            // An uncaught exception in scheduleWithFixedDelay silently cancels
            // every later check, including cable-loss detection.
            android.util.Log.e("WireRouteUsb", "Plain USB health check failed", failure);
            shutdownReason = "native health: " + failure.getClass().getSimpleName();
            new Handler(Looper.getMainLooper()).post(() -> {
                if (stopping) return;
                long failedSession = session;
                shutdown();
                UsbBridgeUnderlay.reportSession(this, "plain_failed", failedSession, "plain");
            });
        }
    }

    private void publishHealth(long sampledSession) {
        TunnelController controller = ((WireRouteApplication)getApplication()).getTunnelController();
        if (stopping || !running || instance != this || session != sampledSession
                || !HevDatapath.owns(this) || !controller.canStartPlainUsb()) return;
        if (UsbBridgeUnderlay.offeredRelaySession(this) != sampledSession) return;
        // The provider lookup can yield to another process. Recheck local intent
        // immediately before the Binder report, without holding the service lock.
        if (stopping || !controller.canStartPlainUsb()) return;
        boolean first = !reportedStarted;
        UsbBridgeUnderlay.reportSession(this, first ? "started" : "alive", sampledSession, "plain");
        reportedStarted = true;
        if (first) {
            android.util.Log.i("WireRouteUsb", "Plain USB native worker running; session=" + sampledSession);
            changed();
        }
    }

    private void refreshConnectivityDeclaration() {
        try {
            if (declaredConnectivityHandle != 0 && setUnderlyingNetworks(null))
                declaredConnectivityHandle = 0;
        } catch (RuntimeException ignored) {
            // Optional status support must never cancel the cable-loss watcher.
        }
    }

    private void accept() {
        while (!stopping) try {
            Socket socket = socks.accept();
            socket.setTcpNoDelay(true);
            synchronized (this) {
                if (stopping || !slots.tryAcquire()) { socket.close(); continue; }
                sockets.add(socket);
                try { workers.execute(() -> forward(socket)); }
                catch (java.util.concurrent.RejectedExecutionException failure) {
                    sockets.remove(socket); slots.release(); socket.close();
                }
            }
        } catch (Exception failure) { if (!stopping) new Handler(Looper.getMainLooper()).post(this::shutdown); return; }
    }

    private void forward(Socket socket) {
        ParcelFileDescriptor fd = null;
        try {
            fd = UsbBridgeUnderlay.openRelay(this, session);
            synchronized (this) {
                if (stopping) return;
                descriptors.add(fd);
            }
            ParcelFileDescriptor active = fd;
            InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd);
            OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
            workers.execute(() -> {
                try {
                    copy(socket.getInputStream(), output);
                    android.system.Os.shutdown(active.getFileDescriptor(), android.system.OsConstants.SHUT_WR);
                } catch (Exception ignored) {
                    close(socket); try { active.close(); } catch (IOException ignoredClose) { }
                }
            });
            copy(input, socket.getOutputStream());
        } catch (IOException | java.util.concurrent.RejectedExecutionException ignored) { }
        finally {
            close(socket); sockets.remove(socket);
            if (fd != null) { descriptors.remove(fd); try { fd.close(); } catch (IOException ignored) { } }
            slots.release();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] bytes = new byte[65536]; int n;
        // These are unbuffered socket/PFD streams. Writes are immediate; a
        // separate flush per chunk adds work without releasing queued data.
        while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
    }
    private static void close(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }

    private void promote() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                "Интернет от компьютера", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(R.drawable.ic_vpn_notification).setContentTitle("Интернет от компьютера")
                .setContentText("VPN телефона выключен · интернет по USB").setContentIntent(open).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(295, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(295, notification);
    }

    private void changed() { ((WireRouteApplication)getApplication()).getTunnelController().notifyUsbModeChanged(); }

    private synchronized void shutdown() {
        if (stopping) return;
        stopping = true; running = false; starting = false;
        watch.shutdownNow();
        try {
            if (!HevDatapath.stop(this)) android.util.Log.e("WireRouteUsb", "Plain native worker did not stop");
        } catch (Throwable failure) { android.util.Log.e("WireRouteUsb", "Plain native cleanup failed", failure); }
        try { if (tun != null) tun.close(); } catch (IOException ignored) { }
        tun = null;
        try { if (socks != null) socks.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) close(socket);
        for (ParcelFileDescriptor fd : descriptors) try { fd.close(); } catch (IOException ignored) { }
        sockets.clear(); descriptors.clear(); workers.shutdownNow();
        if (session != 0) UsbBridgeUnderlay.reportSession(this, "stopped", session, "plain");
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        changed(); stopped.countDown();
        android.util.Log.i("WireRouteUsb", "Plain USB stopped; reason=" + shutdownReason + "; session=" + session);
    }

    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        long[] stats = nativeStats;
        writer.println("WireRoutePlainUsb running=" + running + " starting=" + starting + " stopping=" + stopping
                + " session=" + session + " native_owned=" + HevDatapath.owns(this)
                + " start_attempts=" + startAttempts + " started_reported=" + reportedStarted);
        writer.println("tx_packets=" + stats[0] + " tx_bytes=" + stats[1]
                + " rx_packets=" + stats[2] + " rx_bytes=" + stats[3]
                + " sample_age_ms=" + (lastNativeSample == 0 ? -1 : android.os.SystemClock.elapsedRealtime() - lastNativeSample));
        writer.println("shutdown_reason=" + shutdownReason);
    }

    @Override public void onRevoke() { shutdown(); }
    @Override public void onDestroy() {
        try { shutdown(); if (instance == this) instance = null; super.onDestroy(); }
        finally { destroyed.countDown(); }
    }
}
