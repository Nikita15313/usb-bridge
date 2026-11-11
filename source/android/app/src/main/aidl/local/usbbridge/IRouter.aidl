package local.usbbridge;
import android.os.IBinder;
interface IRouter {
 String start(String library, int socksPort, IBinder owner) = 0;
 String stop() = 1;
 String status() = 2;
 String probe() = 3;
 String setWifi(boolean enabled) = 4;
 String configureVpn(String packageName) = 5;
 long networkHandle() = 6;
 String refreshDatapath() = 7;
 String setVpnDefaultPackages(in String[] packages) = 8;
 String setWifiVpn(String packageName, long vpnNetworkHandle, boolean required) = 9;
 void destroy() = 16777114;
}
