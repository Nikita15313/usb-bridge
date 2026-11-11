package local.usbbridge.vpnprobe;
import android.app.*;import android.content.*;import android.net.*;import android.os.*;import android.util.Log;import java.io.*;import hev.htproxy.TProxyService;
/** Separate APK and UID, occupying Android's single VPN slot. Test only. */
public class TestVpn extends VpnService {
 ParcelFileDescriptor tun;
 public int onStartCommand(Intent intent,int flags,int id){try{
  NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("test","VPN compatibility test",NotificationManager.IMPORTANCE_LOW));
  startForeground(1,new Notification.Builder(this,"test").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Independent test VPN").build());
  ConnectivityManager cm=getSystemService(ConnectivityManager.class);Bundle data=getContentResolver().call(Uri.parse("content://local.usbbridge.underlay"),"network",null,null);
  Network underlay=data==null?null:Network.fromNetworkHandle(data.getLong("network"));if(underlay==null||!cm.bindProcessToNetwork(underlay))throw new IOException("Cannot bind explicit PC underlay");
  String host=intent.getStringExtra("host");if(host==null)throw new IOException("Missing PC test proxy IP");
  Builder builder=new Builder().setSession("Independent VPN test").addAddress("198.18.9.1",30).addRoute("0.0.0.0",0).addDnsServer("1.1.1.1").addDisallowedApplication(getPackageName()).addDisallowedApplication("local.usbbridge").addDisallowedApplication("com.android.shell");
  if(underlay!=null)builder.setUnderlyingNetworks(new Network[]{underlay});
  tun=builder.establish();if(tun==null)throw new IOException("No consent");System.load(getApplicationInfo().nativeLibraryDir+"/libhev-socks5-tunnel.so");
  File config=new File(getFilesDir(),"test.yml");try(FileWriter out=new FileWriter(config)){out.write("tunnel:\n  name: testvpn\n  mtu: 1500\n  ipv4: 198.18.9.1\nsocks5:\n  address: "+host+"\n  port: 39500\n  udp: tcp\nmisc:\n  max-session-count: 40\n  log-level: warn\n");}
  if(!TProxyService.TProxyStartService(config.getAbsolutePath(),tun.getFd()))throw new IOException("Native test VPN failed");Log.i("USBBridgeProbe","PASS independent VPN started; underlay="+underlay+" caps="+cm.getNetworkCapabilities(underlay));
 }catch(Exception e){Log.e("USBBridgeProbe","FAIL test VPN",e);stopSelf();}return START_NOT_STICKY;}
 public void onRevoke(){onDestroy();stopSelf();}
 public void onDestroy(){try{TProxyService.TProxyStopService();}catch(Throwable ignored){}try{if(tun!=null)tun.close();}catch(Exception ignored){}tun=null;getSystemService(ConnectivityManager.class).bindProcessToNetwork(null);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
