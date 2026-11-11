package local.usbbridge;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;

/** Only the user-selected VPN can obtain a stream, scoped to this cable session. */
public final class UnderlayProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 private boolean authorized(String caller){return caller!=null&&!caller.isEmpty()&&caller.equals(BridgeService.selectedVpn(getContext()));}
 @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
  String caller=getCallingPackage();
  if(!authorized(caller))throw new SecurityException("VPN is not selected");
  if(!"rw".equals(mode)||!"/relay".equals(uri.getPath()))throw new FileNotFoundException("Unknown stream");
  try{
   BridgeService service=BridgeService.instance;
   if(service==null)throw new IOException("USB Bridge is stopped");
   long session=Long.parseLong(uri.getQueryParameter("session"));
   return service.openRelay(caller,session);
  }catch(Exception e){FileNotFoundException failure=new FileNotFoundException("USB session is not available");failure.initCause(e);throw failure;}
 }
 @Override public Bundle call(String method,String arg,Bundle extras){
  if(!java.util.Arrays.asList("network","starting","started","alive","stopped","plain_permission","plain_background","plain_failed").contains(method))throw new IllegalArgumentException("Unknown method");
  Bundle result=new Bundle();if(!authorized(getCallingPackage()))return result;
  BridgeService service=BridgeService.instance;
  if(service==null||service.stopping)return result;
  result.putBoolean("selected",true);
  result.putBoolean("connected",service.relayAvailable());
  result.putString("source",BridgeService.source);
  result.putInt("relayVersion",1);
  result.putLong("session",service.relaySession);
  // Read-only readiness facts. Older clients ignore them; absence on an older
  // provider must not be confused with a verified false value by diagnostics.
  result.putBoolean("helperReady",BridgeService.helperReady());
  result.putBoolean("appNetworkReady",BridgeService.appNetworkReady());
  result.putBoolean("vpnDefaultReady",BridgeService.vpnDefaultReady);
  if(BridgeService.relayVpn(getContext())){
   // This is metadata for Android's connectivity APIs, not the PFD data path.
   // Never create a fake Wi-Fi/mobile connection or bind VPN traffic to it here.
   result.putLong("connectivityNetwork",service.connectivityNetworkHandle);
   // The VPN's first legacy CONNECTED broadcast can precede UID-default setup.
   // Its owner may renew that exact network once; a newer VPN needs no refresh.
   result.putLong("legacyRefreshNetwork",BridgeService.appNetworkReady()?service.legacyRefreshNetwork:0);
   if(!"network".equals(method)&&extras!=null&&service.relaySession>0&&extras.getLong("session")==service.relaySession){
    String mode=extras.getString("mode","");
    boolean applied=service.vpnReport(getCallingPackage(),extras.getLong("session"),method,mode,extras.getLong("vpnNetwork"));
    if("starting".equals(method))result.putBoolean("wifiBarrierReady",applied);
   }
   return result;
  }
  // Compatibility with older clients which bind to the privileged test network.
  if(service.router==null)return result;
  try{
   long handle=service.router.networkHandle();result.putLong("network",handle);
   if(!"network".equals(method)&&extras!=null&&handle!=0&&extras.getLong("network")==handle){
    if("stopped".equals(method)){BridgeService.ownVpnSeenAt=0;BridgeService.vpnDefaultReady=false;IRouter router=service.router;Wire.run(()->{try{router.setVpnDefaultPackages(new String[0]);}catch(Exception ignored){}});}
    else{BridgeService.ownVpnSeenAt=SystemClock.elapsedRealtime();if("started".equals(method)){BridgeService.vpnDefaultReady=false;service.ensureRouter();}}
   }
  }catch(RemoteException ignored){}
  return result;
 }
 public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
 public String getType(Uri u){return null;}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
 public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
