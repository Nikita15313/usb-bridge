package local.usbbridge;

import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import hev.htproxy.TProxyService;

public class PhoneVpn extends VpnService {
    static volatile PhoneVpn instance;
    static volatile boolean running,healthy;
    volatile boolean resetting;
    volatile boolean stopping;
    volatile int generation;
    ParcelFileDescriptor tun;

    public void onCreate() { super.onCreate(); instance = this; }

    static void stopNow() {
        PhoneVpn service = instance;
        if (service != null) new Handler(Looper.getMainLooper()).post(service::shutdown);
    }

    public int onStartCommand(Intent intent, int flags, int id) {
        if (running) return START_NOT_STICKY;
        stopping = false;
        final int run = ++generation;
        startForeground(2, BridgeService.notice(this, "Интернет компьютера на телефоне", 2));
        try {
            if (!BridgeService.connected) throw new IOException("Сначала подключите ПК");
            System.load(getApplicationInfo().nativeLibraryDir + "/libhev-socks5-tunnel.so");
            Builder builder = new Builder().setSession("USB Bridge").setMtu(1500)
                .addAddress("198.18.8.1", 30).addRoute("0.0.0.0", 0).addDnsServer("198.18.7.2")
                .addDisallowedApplication(getPackageName()).addDisallowedApplication("com.android.shell");
            tun = builder.establish();
            if (tun == null) throw new IOException("VPN не разрешён");
            File file = new File(getFilesDir(), "vpn.yml");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(Wire.bytes(RouterService.nativeConfig(BridgeService.PHONE_SOCKS)));
            }
            if (!TProxyService.TProxyStartService(file.getAbsolutePath(), tun.getFd()))
                throw new IOException("Не запустился сетевой модуль");
            Thread.sleep(150);
            if (!TProxyService.TProxyIsRunning()) throw new IOException("Сетевой модуль завершился");
            running = true;healthy = true;
            IRouter router = BridgeService.instance == null ? null : BridgeService.instance.router;
            if (router != null) try {
                String preference=router.setVpnDefaultPackages(null);
                BridgeService.vpnDefaultReady=preference.startsWith("OK");
                android.util.Log.i("USBBridgeVpnDefault", preference);
            } catch (Exception error) { android.util.Log.w("USBBridgeVpnDefault", error.toString()); }
            Wire.run(() -> {
                while (!stopping && run == generation) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                    if (!stopping && !resetting && run == generation && !TProxyService.TProxyIsRunning()) {
                        BridgeService.state = "VPN: сетевой модуль остановился";
                        healthy = false;
                        // Retain the established TUN as a black hole on native failure.
                        return;
                    }
                }
            });
        } catch (Throwable error) {
            BridgeService.state = "VPN: " + error.getMessage();
            healthy = false;
            if(tun==null)shutdown();else running=true;
        }
        return START_NOT_STICKY;
    }

    static void refreshDatapath() {
        PhoneVpn service=instance;
        if(service==null||service.tun==null||service.stopping)return;
        new Handler(Looper.getMainLooper()).post(()->{
            if(service.tun==null||service.stopping)return;
            service.resetting=true;
            try{TProxyService.TProxyStopService();healthy=TProxyService.TProxyStartService(new File(service.getFilesDir(),"vpn.yml").getAbsolutePath(),service.tun.getFd());}
            catch(Throwable error){healthy=false;}
            finally{service.resetting=false;}
        });
    }

    public void onRevoke() { shutdown(); }

    // Android binds an established VpnService from system_server. stopService alone
    // only clears startRequested and does not call onDestroy while that binding lives.
    void shutdown() {
        if (stopping) return;
        stopping = true;
        generation++;
        running = false;healthy = false;
        BridgeService.vpnDefaultReady=false;
        IRouter router = BridgeService.instance == null ? null : BridgeService.instance.router;
        if (router != null) try { router.setVpnDefaultPackages(new String[0]); } catch (Exception ignored) {}
        try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
        try { if (tun != null) tun.close(); } catch (IOException ignored) {}
        tun = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    public void onDestroy() {
        shutdown();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}

