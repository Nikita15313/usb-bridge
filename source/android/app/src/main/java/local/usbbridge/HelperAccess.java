package local.usbbridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import rikka.shizuku.Shizuku;

/** A missing client Binder is not proof that the Shizuku server has stopped. */
final class HelperAccess {
 static final int MISSING=0,UNAVAILABLE=1,LIMITED=2,PERMISSION=3,READY=4,ERROR=5;
 static final String[] PERMISSIONS={"android.permission.MANAGE_TEST_NETWORKS","android.permission.NETWORK_SETTINGS","android.permission.TETHER_PRIVILEGED"};
 interface Probe {
  boolean alive();
  boolean allowed();
  int uid();
  boolean permission(String name);
 }
 static final class Result {
  final int state;final String detail;
  Result(int state,String detail){this.state=state;this.detail=detail;}
 }
 private static final Probe API=new Probe(){
  public boolean alive(){return Shizuku.pingBinder();}
  public boolean allowed(){return Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}
  public int uid(){return Shizuku.getUid();}
  public boolean permission(String name){return Shizuku.checkRemotePermission(name)==PackageManager.PERMISSION_GRANTED;}
 };
 static Result inspect(boolean installed,Probe api){
  if(!installed)return new Result(MISSING,"manager missing");
  try{
   if(!api.alive())return new Result(UNAVAILABLE,"client binder unavailable");
   if(!api.allowed())return new Result(PERMISSION,"client permission required");
   int uid=api.uid();if(uid!=2000)return new Result(LIMITED,"unexpected uid="+uid);
   for(String permission:PERMISSIONS)if(!api.permission(permission))return new Result(LIMITED,"shell permission denied: "+permission);
   return new Result(READY,"uid=2000; network permissions granted");
  }catch(SecurityException error){return new Result(PERMISSION,"client permission query rejected: "+error);}
  catch(Exception error){
   boolean alive=false;try{alive=api.alive();}catch(Exception ignored){}
   return new Result(alive?ERROR:UNAVAILABLE,"access query failed: "+error);
  }
 }
 private static boolean observing;
 private static String lastResult="";
 private static volatile String usb="USB state not observed";
 static Result query(Context context){
  Result result=inspect(BridgeService.helperInstalled(context),API);
  synchronized(HelperAccess.class){String key=result.state+"|"+result.detail;if(!key.equals(lastResult)){lastResult=key;BridgeLog.add(context,"Helper access "+key+"; "+snapshot(context));}}
  return result;
 }
 static boolean ready(){return inspect(true,API).state==READY;}
 static void observe(Context context){
  synchronized(HelperAccess.class){if(observing)return;observing=true;}
  final Context app=context.getApplicationContext();
  Shizuku.addBinderReceivedListenerSticky(()->{
   BridgeLog.add(app,"Helper binder received; "+snapshot(app));
   Result result=query(app);BridgeService live=BridgeService.instance;
   if(result.state==READY&&live!=null&&!live.stopping){BridgeService.helperIssue="";live.activate();}
  });
  Shizuku.addBinderDeadListener(()->BridgeLog.add(app,"Helper binder lost; "+snapshot(app)));
 }
 static void usbEvent(Context context,Intent intent){
  usb="USB connected="+intent.getBooleanExtra("connected",false)+", configured="+intent.getBooleanExtra("configured",false)+", adbFunction="+intent.getBooleanExtra("adb",false);
  BridgeLog.add(context,"USB event; "+snapshot(context));
 }
 static String snapshot(Context context){
  String debug="unknown";try{debug=String.valueOf(Settings.Global.getInt(context.getContentResolver(),Settings.Global.ADB_ENABLED,-1));}catch(Exception ignored){}
  boolean binder=false;try{binder=Shizuku.pingBinder();}catch(Exception ignored){}
  BridgeService live=BridgeService.instance;IRouter router=live==null?null:live.router;
  return "adbEnabled="+debug+"; helperBinder="+binder+"; routerBinder="+(router!=null&&router.asBinder().isBinderAlive())+"; "+usb;
 }
}
