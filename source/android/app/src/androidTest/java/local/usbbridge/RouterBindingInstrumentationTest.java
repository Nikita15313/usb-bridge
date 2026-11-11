package local.usbbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.test.InstrumentationTestCase;
import java.util.ArrayList;
import java.util.List;
import rikka.shizuku.Shizuku;

/** Exercises the real ServiceConnection lifecycle without starting Shizuku or a VPN. */
@SuppressWarnings("deprecation")
public final class RouterBindingInstrumentationTest extends InstrumentationTestCase {
 public void testLegacyRefreshIdentifiesOnlyTheActuallyPreparedVpnNetwork(){
  assertEquals(401L<<32,BridgeService.preferenceNetwork("OK:15|NETWORK:"+(401L<<32)));
  assertEquals(0,BridgeService.preferenceNetwork("WAIT:VPN_NETWORK"));
  assertEquals(0,BridgeService.preferenceNetwork("OK:15"));
  assertEquals(0,BridgeService.preferenceNetwork("FAIL:missing|NETWORK:123"));
  assertEquals(0,BridgeService.preferenceNetwork("OK:15|NETWORK:bad"));
  assertEquals(0,BridgeService.preferenceNetwork("OK:15|NETWORK:-1"));
 }
 private Fixture service;
 private static final class Fixture extends BridgeService {
  long now=1000;int schedules;boolean throwBind;
  final List<ServiceConnection> binds=new ArrayList<>(),detached=new ArrayList<>();
  Fixture(Context context){attachBaseContext(context);}
  @Override boolean needsRouter(){return true;}
  @Override boolean routerAccessReady(){return true;}
  @Override long routerBindingNow(){return now;}
  @Override void scheduleRouter(){schedules++;}
  @Override void releaseNetwork(boolean destroy,Runnable done){done.run();}
  @Override void bindRouterService(Shizuku.UserServiceArgs args,ServiceConnection callback){binds.add(callback);if(throwBind)throw new IllegalStateException("fixture bind failure");}
  @Override void unbindRouterService(Shizuku.UserServiceArgs args,ServiceConnection callback,boolean remove){if(remove)throw new AssertionError("Retry must not kill a shared replacement service");detached.add(callback);}
 }
 private static final class Router extends IRouter.Stub {
  public String start(String library,int port,IBinder owner){return "READY|fixture";}
  public String stop(){return "STOPPED|fixture";}
  public String status(){return "READY|fixture";}
  public String probe(){return "OK";}
  public String setWifi(boolean enabled){return "OK";}
  public String configureVpn(String name){return "OK";}
  public long networkHandle(){return 0;}
  public String refreshDatapath(){return "OK";}
  public String setVpnDefaultPackages(String[] names){return "OK";}
  public String setWifiVpn(String name,long handle,boolean required){return "OK";}
  public void destroy(){}
 }
 @Override protected void setUp(){
  assertNull("Stop USB Bridge before binding tests",BridgeService.instance);
  service=new Fixture(getInstrumentation().getTargetContext());BridgeService.instance=service;
  BridgeService.connected=true;BridgeService.verified=true;BridgeService.vpnDefaultReady=false;
 }
 @Override protected void tearDown(){
  service.stopping=true;service.cancelPendingRouterBind();service.tasks.shutdownNow();
  BridgeService.instance=null;BridgeService.connected=false;BridgeService.verified=false;BridgeService.vpnDefaultReady=false;
  BridgeService.helperIssue="";BridgeService.vpnMessage="";
 }
 private ComponentName component(){return new ComponentName(getInstrumentation().getTargetContext(),RouterService.class);}
 private ServiceConnection begin(){service.ensureRouter();assertTrue(service.binding);return service.connection;}
 public void testSilentStartExpiresAndRetriesWithoutAcceptingLateBinder(){
  ServiceConnection old=begin();service.ensureRouter();assertEquals(1,service.binds.size());
  service.now+=BridgeService.ROUTER_BIND_TIMEOUT_MS-1;service.checkRouterBindDeadline();assertTrue(service.binding);
  service.now++;service.checkRouterBindDeadline();assertFalse(service.binding);assertNull(service.connection);assertSame(old,service.detached.get(0));
  assertTrue(service.routerBindingDiagnostics().contains("не ответил за 35 секунд"));
  service.ensureRouter();assertEquals("No retry before backoff",1,service.binds.size());
  service.now+=1999;service.ensureRouter();assertEquals(1,service.binds.size());service.now++;
  ServiceConnection next=begin();assertNotSame(old,next);
  old.onServiceConnected(component(),new Router());assertNull(service.router);assertTrue(service.binding);assertEquals(0,service.schedules);
  Router router=new Router();next.onServiceConnected(component(),router);assertSame(router,service.router);assertFalse(service.binding);assertNull(service.routerBindTimeout);assertEquals(1,service.schedules);assertEquals(0,service.routerBindFailures);
  old.onServiceDisconnected(component());assertSame(router,service.router);
 }
 public void testRepeatedSilentStartsBackOffWithBoundedRetryRate(){
  long[] delays={2000,4000,8000,16000,30000,30000};
  for(long delay:delays){begin();service.now+=BridgeService.ROUTER_BIND_TIMEOUT_MS;service.checkRouterBindDeadline();assertEquals(delay,service.routerBindRetryAt-service.now);service.now+=delay;}
  assertEquals(delays.length,service.binds.size());assertEquals(delays.length,service.detached.size());
 }
 public void testDisconnectCancelsDeadlineAndRejectsLateConnection(){
  ServiceConnection old=begin();BridgeService.connected=false;service.cancelPendingRouterBind();
  assertFalse(service.binding);assertNull(service.routerBindTimeout);assertSame(old,service.detached.get(0));
  old.onServiceConnected(component(),new Router());assertNull(service.router);
  service.now+=100000;service.checkRouterBindDeadline();service.ensureRouter();assertEquals(1,service.binds.size());
  BridgeService.connected=true;ServiceConnection replacement=begin();assertNotSame(old,replacement);
 }
 public void testStopAndServiceReplacementRejectOldCallbacks(){
  ServiceConnection old=begin();service.onDestroy();assertFalse(service.binding);assertNull(service.routerBindTimeout);old.onServiceConnected(component(),new Router());assertNull(service.router);
  service=new Fixture(getInstrumentation().getTargetContext());BridgeService.instance=service;BridgeService.connected=true;BridgeService.verified=true;
  ServiceConnection pending=begin();Fixture replacement=new Fixture(getInstrumentation().getTargetContext());BridgeService.instance=replacement;
  try{pending.onServiceConnected(component(),new Router());pending.onServiceDisconnected(component());assertNull(service.router);assertNull(replacement.router);}
  finally{replacement.tasks.shutdownNow();BridgeService.instance=service;}
 }
 public void testEarlyDisconnectAndBindExceptionReleasePendingState(){
  ServiceConnection early=begin();early.onServiceDisconnected(component());assertFalse(service.binding);assertTrue(service.routerBindLastFailure.contains("до передачи доступа"));
  service.now=service.routerBindRetryAt;service.throwBind=true;service.ensureRouter();assertFalse(service.binding);assertNull(service.routerBindTimeout);assertTrue(service.routerBindLastFailure.contains("fixture bind failure"));assertEquals(2,service.detached.size());
 }
 public void testNullBindingIsRetriedAndConnectedLossInvalidatesDefault(){
  ServiceConnection empty=begin();empty.onNullBinding(component());assertFalse(service.binding);assertTrue(service.routerBindLastFailure.contains("не передал доступ"));
  service.now=service.routerBindRetryAt;ServiceConnection live=begin();live.onServiceConnected(component(),new Router());BridgeService.vpnDefaultReady=true;
  live.onServiceDisconnected(component());assertNull(service.router);assertFalse(BridgeService.vpnDefaultReady);assertNull(service.connection);
  begin();assertEquals(3,service.binds.size());
 }
}
