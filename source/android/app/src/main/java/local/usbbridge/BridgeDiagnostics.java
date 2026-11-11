package local.usbbridge;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

/** Bounded observations, never packet contents, addresses, SSIDs or VPN secrets. */
final class BridgeDiagnostics {
 static final int HELPER_RESULT_LIMIT=500;
 static String helperResult(String raw){
  if(raw==null||raw.trim().isEmpty())return "Компьютер прислал пустой ответ";
  // Bound work before regexes, then bound the displayed/logged result too.
  String text=raw.substring(0,Math.min(raw.length(),4096));
  text=text.replaceAll("[\\p{Cntrl}\\p{Cf}\\s]+"," ");
  text=text.replaceAll("(?i)\\b(?:https?|file)://[^\\s]+","[address]");
  text=text.replaceAll("(?i)\\b(?:password|passwd|token|secret|privatekey|authorization|пароль)\\s*[:=]\\s*[^\\s;]+","[secret]");
  text=text.replaceAll("(?i)\\b[A-Z]:[\\\\/][^;]+|(?<!\\w)/(?:data|sdcard|storage|home|Users|tmp)/[^;]+","[path]");
  text=text.replaceAll("(?<![\\w.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]+)?(?![\\w.])","[address]");
  text=text.replaceAll("(?i)(?<![\\w:])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.%]+","[address]");
  text=text.replaceAll("(?i)\\b[0-9a-z_+/=-]{24,}\\b","[identifier]").trim();
  if(text.isEmpty())return "Компьютер прислал пустой ответ";
  return text.length()<=HELPER_RESULT_LIMIT?text:text.substring(0,HELPER_RESULT_LIMIT-1)+"…";
 }
 static boolean systemChannelReady(boolean routerAlive,long testHandle,boolean defaultReady){
  return routerAlive&&testHandle!=0&&defaultReady;
 }
 static String selectedVpn(Context context,String name){
  if(name==null||name.isEmpty())return "none";
  try{
   PackageInfo info=context.getPackageManager().getPackageInfo(name,0);
   ApplicationInfo app=context.getPackageManager().getApplicationInfo(name,PackageManager.GET_META_DATA);
   int relay=app.metaData==null?0:app.metaData.getInt("local.usbbridge.UDP_RELAY_API",0);
   return name+"; version="+helperResult(info.versionName)+"; code="+info.getLongVersionCode()+"; relayApi="+relay;
  }catch(PackageManager.NameNotFoundException missing){return name+"; not installed";}
  catch(Exception error){return name+"; version query failed="+error.getClass().getSimpleName();}
 }
 @SuppressWarnings("deprecation")
 static String networkObservation(Context context){
  StringBuilder out=new StringBuilder("Android networks AS OBSERVED BY BRIDGE UID ").append(android.os.Process.myUid()).append(" (not Telegram or other app UIDs)");
  try{
   ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
   if(manager==null)return out.append("\nConnectivityManager unavailable").toString();
   try{
    NetworkInfo legacy=manager.getActiveNetworkInfo();
    out.append("\nlegacy currentNetworkInfo=");
    if(legacy==null)out.append("null");
    else out.append("type=").append(legacy.getType()).append("; state=").append(legacy.getState()).append("; detailedState=").append(legacy.getDetailedState()).append("; available=").append(legacy.isAvailable()).append("; connected=").append(legacy.isConnected()).append("; connectedOrConnecting=").append(legacy.isConnectedOrConnecting());
   }catch(Exception error){out.append("\nlegacy query failed=").append(error.getClass().getSimpleName());}
   Network active=manager.getActiveNetwork();Network[] visible=manager.getAllNetworks();
   out.append("\nmodern activeHandle=").append(active==null?0:active.getNetworkHandle()).append("; visibleCount=").append(visible.length);
   for(int index=0;index<Math.min(visible.length,8);index++){
    Network network=visible[index];NetworkCapabilities caps=manager.getNetworkCapabilities(network);
    out.append("\nnetworkHandle=").append(network.getNetworkHandle()).append("; active=").append(network.equals(active));
    if(caps==null){out.append("; capabilities=null");continue;}
    out.append("; VPN=").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
     .append("; WIFI=").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
     .append("; CELLULAR=").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
     .append("; ETHERNET=").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
     .append("; USB=").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_USB))
     .append("; INTERNET=").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
     .append("; VALIDATED=").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
     .append("; NOT_SUSPENDED=").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
     .append("; NOT_RESTRICTED=").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))
     .append("; NOT_VPN=").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN));
   }
  }catch(Exception error){out.append("\nmodern query failed=").append(error.getClass().getSimpleName());}
  return out.toString();
 }
}
