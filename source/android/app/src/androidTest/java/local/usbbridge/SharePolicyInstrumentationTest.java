package local.usbbridge;
import android.content.*;import android.os.*;import android.test.InstrumentationTestCase;
import java.util.concurrent.*;import java.util.concurrent.atomic.*;

public final class SharePolicyInstrumentationTest extends InstrumentationTestCase {
 Context context;SharedPreferences prefs;String old;boolean had;TestService service;
 static final class TestService extends BridgeService {
  final AtomicInteger calls=new AtomicInteger();final CountDownLatch twice=new CountDownLatch(2);boolean repeat;
  TestService(Context context){attachBaseContext(context);}
  @Override void applyRouter(){calls.incrementAndGet();twice.countDown();if(repeat&&calls.get()==1)scheduleRouter();}
  @Override void sendStatus(){}
 }
 static final class Router extends IRouter.Stub {
  int calls,wifiStops;boolean required;long handle;
  public String start(String l,int p,IBinder o){return "READY|test";}public String stop(){return "STOPPED|test";}public String status(){return "READY|test";}
  public String probe(){return "PASS";}public String setWifi(boolean e){if(!e)wifiStops++;return status();}public String configureVpn(String p){return "OK";}
  public long networkHandle(){return 0;}public String refreshDatapath(){return "OK";}public String setVpnDefaultPackages(String[] p){return "OK";}
  public String setWifiVpn(String p,long h,boolean r){calls++;required=r;handle=h;return r&&h==0?"BLOCKED:VPN_REQUIRED":r?"OK:VPN":"OK:PC";}public void destroy(){}
 }
 protected void setUp()throws Exception{super.setUp();context=getInstrumentation().getTargetContext();assertNull(BridgeService.instance);prefs=context.getSharedPreferences("bridge",0);had=prefs.contains("sharingMode");old=prefs.getString("sharingMode","");prefs.edit().putString("sharingMode",SharePolicy.VPN).commit();service=new TestService(context);}
 protected void tearDown()throws Exception{service.stopping=true;service.tasks.shutdownNow();service.tasks.awaitTermination(2,TimeUnit.SECONDS);SharedPreferences.Editor edit=prefs.edit();if(had)edit.putString("sharingMode",old);else edit.remove("sharingMode");edit.commit();BridgeService.wifiVpnRequired=false;BridgeService.ownVpnMode="";BridgeService.ownVpnSeenAt=0;BridgeService.ownVpnNetwork=0;BridgeService.shareWanted=false;BridgeService.routerState="STOPPED|test";super.tearDown();}
 public void testDirectRemainsDirectWhenPhoneVpnIsOn(){prefs.edit().putString("sharingMode",SharePolicy.DIRECT).commit();BridgeService.wifiVpnRequired=true;BridgeService.ownVpnNetwork=123;BridgeService.ownVpnSeenAt=SystemClock.elapsedRealtime();Router router=new Router();service.router=router;assertTrue(service.applyWifiPolicy());assertFalse(router.required);assertEquals(0L,router.handle);assertTrue("Phone encryption latch must remain independent",BridgeService.wifiVpnRequired);}
 public void testDirectSharingDoesNotBlockPhoneVpnStartupWithoutHelper(){prefs.edit().putString("sharingMode",SharePolicy.DIRECT).commit();BridgeService.shareWanted=true;assertTrue(service.vpnReport("starting","vpn",0));assertTrue(BridgeService.wifiVpnRequired);}
 public void testVpnOffFallsBackToPcAndKeepsPreference(){BridgeService.wifiVpnRequired=false;Router router=new Router();service.router=router;assertTrue(service.applyWifiPolicy());assertFalse(router.required);assertEquals(SharePolicy.VPN,BridgeService.sharingMode(context));}
 public void testSameNetworkHandleCanRecoverFailedGateway(){BridgeService.wifiVpnRequired=true;BridgeService.ownVpnSeenAt=SystemClock.elapsedRealtime();BridgeService.ownVpnNetwork=123;Router router=new Router();service.router=router;assertTrue(service.applyWifiPolicy());BridgeService.routerState="READY|Wi-Fi ждёт VPN";assertTrue(service.applyWifiPolicy());assertEquals(2,router.calls);assertTrue(router.required);}
 public void testUpdateArrivingDuringApplyRunsWithoutPollingDelay()throws Exception{service.repeat=true;service.scheduleRouter();assertTrue(service.twice.await(2,TimeUnit.SECONDS));assertEquals(2,service.calls.get());}
 public void testDelayedOffCannotStopNewerSharingIntent(){Router router=new Router();service.router=router;BridgeService.instance=service;try{service.shareEpoch=2;BridgeService.shareWanted=true;service.stopSharingIfCurrent(router,1);assertEquals(0,router.wifiStops);BridgeService.shareWanted=false;service.stopSharingIfCurrent(router,1);assertEquals(0,router.wifiStops);service.stopSharingIfCurrent(router,2);assertEquals(1,router.wifiStops);}finally{BridgeService.instance=null;}}
}
