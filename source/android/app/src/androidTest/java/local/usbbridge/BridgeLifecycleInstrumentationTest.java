package local.usbbridge;

import android.content.Context;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Real encrypted handshakes plus deterministic cleanup, without VPN/hotspot changes. */
@SuppressWarnings("deprecation")
public final class BridgeLifecycleInstrumentationTest extends InstrumentationTestCase {
 private TestService service;
 private ExecutorService workers;
 private final List<Socket> sockets=new ArrayList<>();
 private final List<Wire.Mux> muxes=new ArrayList<>();
 private Wire.Secure pcWire;
 private static final class TestService extends BridgeService{
  int cleanupCalls,stopCalls;Runnable cleanup;
  boolean realCleanup,helperAvailable=true,captureRepairs;final CountDownLatch cleanupFinished=new CountDownLatch(1);
  final List<Runnable> repairs=new ArrayList<>();
  TestService(Context context){attachBaseContext(context);}
  @Override synchronized void verify(){}
  @Override void activate(){}
  @Override boolean routerAccessReady(){return helperAvailable;}
  @Override void dispatchHelperRepair(Runnable attempt){if(captureRepairs)repairs.add(attempt);else super.dispatchHelperRepair(attempt);}
  @Override void releaseNetwork(boolean destroy,Runnable done){cleanupCalls++;cleanup=done;if(realCleanup)super.releaseNetwork(destroy,()->{try{done.run();}finally{cleanupFinished.countDown();}});}
  @Override void requestServiceStop(){stopCalls++;}
 }
 private static final class BlockingRouter extends IRouter.Stub{
  final CountDownLatch stopping=new CountDownLatch(1),releaseStop=new CountDownLatch(1);
  volatile boolean stopped,refreshBeforeStop;int starts,refreshes,defaultPreferences;
  public String start(String library,int port,android.os.IBinder owner){starts++;return "READY|fixture";}
  public String stop(){stopping.countDown();try{if(!releaseStop.await(6,TimeUnit.SECONDS))throw new IllegalStateException("Router stop fixture timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}stopped=true;return "STOPPED|fixture";}
  public String status(){return stopped?"STOPPED|fixture":"READY|fixture";}
  public String probe(){return "OK";}
  public String setWifi(boolean enabled){return "OK";}
  public String configureVpn(String packageName){return "OK";}
  public long networkHandle(){return 0;}
  public String refreshDatapath(){refreshes++;refreshBeforeStop|=!stopped;return "OK";}
  public String setVpnDefaultPackages(String[] packages){defaultPreferences++;return "OK";}
  public String setWifiVpn(String packageName,long handle,boolean required){return "OK";}
  public void destroy(){}
 }
 @Override protected void setUp(){
  Thread watcher=new Thread(()->{try{Thread.sleep(12000);}catch(InterruptedException ignored){}for(Map.Entry<Thread,StackTraceElement[]> e:Thread.getAllStackTraces().entrySet()){StringBuilder s=new StringBuilder(e.getKey().getName());for(StackTraceElement frame:e.getValue())s.append("\n").append(frame);android.util.Log.i("LifecycleStacks",s.toString());}});watcher.setDaemon(true);watcher.start();
  assertNull("Stop USB Bridge before lifecycle tests",BridgeService.instance);
  assertEquals(0,BridgeService.previousNetworkCleanup.getCount());
  service=new TestService(getInstrumentation().getTargetContext());BridgeService.instance=service;
  BridgeService.connected=false;BridgeService.verified=false;BridgeService.shareWanted=false;BridgeService.source="";
  workers=Executors.newCachedThreadPool();
 }
 @Override protected void tearDown(){
  service.stopping=true;for(Wire.Mux mux:muxes)mux.close();for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}
  service.tasks.shutdownNow();workers.shutdownNow();BridgeService.instance=null;BridgeService.connected=false;BridgeService.verified=false;BridgeService.shareWanted=false;BridgeService.ownVpnMode="";BridgeService.ownVpnSeenAt=0;BridgeService.ownVpnNetwork=0;
 }
 private Wire.Mux connect()throws Exception{
  Socket phone,pc;try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){phone=new Socket("127.0.0.1",listener.getLocalPort());pc=listener.accept();}
  phone.setSoTimeout(4000);pc.setSoTimeout(4000);sockets.add(phone);sockets.add(pc);
  Future<?> accepting=workers.submit(()->{try{service.accept(phone.getInputStream(),phone.getOutputStream(),phone,phone,"MIDI");}catch(IOException e){throw new RuntimeException(e);}});
  pc.getOutputStream().write(Wire.bytes("USBBR05!"));byte[] secret=Wire.read(pc.getInputStream(),32);StringBuilder hex=new StringBuilder();for(byte b:secret)hex.append(String.format(Locale.ROOT,"%02X",b&255));
  pcWire=new Wire.Secure(pc.getInputStream(),pc.getOutputStream(),pc,hex.toString(),true);accepting.get(4,TimeUnit.SECONDS);
  assertTrue(BridgeService.connected);Wire.Mux result=service.mux;assertNotNull(result);muxes.add(result);return result;
 }
 public void testEofReleasesSessionButKeepsReceiverReadyForNewHandshake()throws Exception{
  Wire.Mux old=connect();long oldSession=service.relaySession;service.sessionProven=true;BridgeService.verified=true;BridgeService.shareWanted=true;
  service.linkClosed(old,"test EOF");assertFalse(BridgeService.connected);assertFalse(BridgeService.shareWanted);assertEquals(0,service.relaySession);assertTrue(service.recovering);assertFalse(service.stopping);assertEquals(0,service.stopCalls);assertEquals(1,service.cleanupCalls);
  final boolean[] denied={false};service.accept(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream(),()->denied[0]=true,null,"MIDI");assertTrue("Wait for old network cleanup before accepting a new host",denied[0]);
  service.linkClosed(old,"duplicate EOF");assertEquals(1,service.cleanupCalls);service.cleanup.run();assertFalse(service.recovering);
  Wire.Mux next=connect();assertNotSame(old,next);assertTrue(service.relaySession>oldSession);assertFalse("A new host needs its own source verification",service.sessionProven);
  BridgeService.source="new source";service.linkClosed(old,"late EOF");service.linkStatus(old,"SOURCE|old source");service.cleanup.run();assertTrue(BridgeService.connected);assertSame(next,service.mux);assertEquals("new source",BridgeService.source);
 }
 public void testServiceReplacementDuringHandshakeCannotPublishOldSession()throws Exception{
  Socket phone,pc;try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){phone=new Socket("127.0.0.1",listener.getLocalPort());pc=listener.accept();}
  phone.setSoTimeout(4000);pc.setSoTimeout(4000);sockets.add(phone);sockets.add(pc);
  Future<?> accepting=workers.submit(()->{try{service.accept(phone.getInputStream(),phone.getOutputStream(),phone,phone,"MIDI");}catch(IOException e){throw new RuntimeException(e);}});
  pc.getOutputStream().write(Wire.bytes("USBBR05!"));byte[] secret=Wire.read(pc.getInputStream(),32);StringBuilder hex=new StringBuilder();for(byte b:secret)hex.append(String.format(Locale.ROOT,"%02X",b&255));
  TestService newer=new TestService(getInstrumentation().getTargetContext());
  OutputStream client=new FilterOutputStream(pc.getOutputStream()){int writes;@Override public void write(byte[] b)throws IOException{if(++writes==2){BridgeService.instance=newer;BridgeService.source="replacement";}out.write(b);}};
  try{new Wire.Secure(pc.getInputStream(),client,pc,hex.toString(),true);accepting.get(4,TimeUnit.SECONDS);assertNull(service.mux);assertEquals(0,service.relaySession);assertFalse(BridgeService.connected);assertSame(newer,BridgeService.instance);assertEquals("replacement",BridgeService.source);}
  finally{newer.tasks.shutdownNow();}
 }
 public void testDestroyCleanupBlocksReplacementUntilOldNetworkIsReleased()throws Exception{
  service.onDestroy();assertEquals(1,BridgeService.previousNetworkCleanup.getCount());TestService newer=new TestService(getInstrumentation().getTargetContext());BridgeService.instance=newer;
  try{final boolean[] denied={false};newer.accept(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream(),()->denied[0]=true,null,"MIDI");assertTrue(denied[0]);assertFalse(BridgeService.connected);service.cleanup.run();assertEquals(0,BridgeService.previousNetworkCleanup.getCount());}
  finally{service.cleanup.run();newer.tasks.shutdownNow();}
 }
 public void testRepeatedStopStartCannotReplacePendingRouterCleanupBarrier()throws Exception{
  assertNull("Lifecycle fixture must not stop a running phone VPN",PhoneVpn.instance);
  TestService first=service,second=new TestService(getInstrumentation().getTargetContext()),third=new TestService(getInstrumentation().getTargetContext());
  BlockingRouter router=new BlockingRouter();first.router=router;first.realCleanup=true;second.realCleanup=true;
  try{
   first.onDestroy();assertTrue(router.stopping.await(2,TimeUnit.SECONDS));CountDownLatch barrier=BridgeService.previousNetworkCleanup;
   BridgeService.instance=second;second.onDestroy();assertTrue(second.cleanupFinished.await(2,TimeUnit.SECONDS));
   assertSame("Destroying a waiting replacement must retain the older router barrier",barrier,BridgeService.previousNetworkCleanup);
   assertEquals("Old real router.stop is still running",1,barrier.getCount());assertFalse(router.stopped);
   BridgeService.instance=third;third.router=router;final boolean[] rejected={false};
   third.accept(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream(),()->rejected[0]=true,null,"MIDI");
   assertTrue("Third service must reject handshake until every predecessor releases its network",rejected[0]);assertNull(third.mux);assertEquals(0,router.refreshes);assertEquals(0,router.starts);
   router.releaseStop.countDown();assertTrue(first.cleanupFinished.await(2,TimeUnit.SECONDS));assertEquals(0,barrier.getCount());assertTrue(router.stopped);
   service=third;connect();assertEquals(1,router.refreshes);assertFalse("Replacement datapath may refresh only after real old router.stop returns",router.refreshBeforeStop);
   // Completion is idempotent: late repeated callbacks cannot decrement another
   // service's barrier or accidentally release its still-running cleanup.
   first.cleanup.run();second.cleanup.run();assertEquals(0,BridgeService.previousNetworkCleanup.getCount());
  }finally{
   router.releaseStop.countDown();first.cleanupFinished.await(2,TimeUnit.SECONDS);second.tasks.shutdownNow();
   if(service!=third)third.tasks.shutdownNow();
  }
 }
 public void testOldServiceCallbacksAndDestroyCannotClearNewServiceState()throws Exception{
  Wire.Mux old=connect();TestService newer=new TestService(getInstrumentation().getTargetContext());BridgeService.instance=newer;BridgeService.connected=true;BridgeService.source="new source";
  try{service.linkClosed(old,"old EOF");service.linkStatus(old,"SOURCE|old source");service.onDestroy();assertSame(newer,BridgeService.instance);assertTrue(BridgeService.connected);assertEquals("new source",BridgeService.source);assertEquals(0,service.cleanupCalls);}finally{newer.tasks.shutdownNow();}
 }
 public void testBootstrapFailureIsCapturedWithoutClosingWorkingTransport()throws Exception{
  Wire.Mux current=connect();service.sessionProven=true;BridgeService.verified=true;
  service.linkStatus(current,"HELPER|RESULT|На этом ПК нет ADB; host=192.168.1.4; token=private-value\nследующая строка");
  assertTrue(BridgeService.helperLastResult.contains("нет ADB"));assertFalse(BridgeService.helperLastResult.contains("192.168"));assertFalse(BridgeService.helperLastResult.contains("private-value"));assertFalse(BridgeService.helperLastResult.contains("\n"));
  assertTrue(BridgeService.helperResultAt>0);assertTrue(service.relayAvailable());assertSame(current,service.mux);assertFalse(current.closed);
  String report=BridgeService.routingDiagnostics(service);assertTrue(report.contains("result=На этом ПК нет ADB"));assertTrue(report.contains("vpnDefaultReady="));assertTrue(report.contains("TEST connectivityHandle="));
 }
 public void testReconnectRetriesHelperAndIgnoresOldPcResponse()throws Exception{
  Wire.Mux old=connect();service.helperBootstrapAttempted=true;service.helperRepairRequested=true;BridgeService.helperRequestAt=SystemClock.elapsedRealtime();BridgeService.helperStartRequests=1;
  service.legacyRefreshNetwork=401L<<32;
  service.linkStatus(old,"HELPER|RESULT|previous computer");service.linkClosed(old,"fixture EOF");service.cleanup.run();Wire.Mux current=connect();
  assertEquals("A replacement USB session cannot renew its predecessor's VPN",0,service.legacyRefreshNetwork);
  assertFalse("A new connection must be allowed to bootstrap its helper",service.helperBootstrapAttempted);assertFalse("A new connection gets its own bounded repair",service.helperRepairRequested);assertEquals(0,BridgeService.helperRequestAt);assertEquals(0,BridgeService.helperStartRequests);assertEquals("",BridgeService.helperLastResult);
  service.linkStatus(old,"HELPER|RESULT|late old response");assertEquals("",BridgeService.helperLastResult);
  service.linkStatus(current,"HELPER|RESULT|current computer");assertEquals("current computer",BridgeService.helperLastResult);
 }
 private void failRouterBind(String reason){
  service.binding=true;android.content.ServiceConnection pending=service.new RouterConnection(++service.routerBindGeneration);service.connection=pending;
  service.routerBindFailed(pending,reason);
 }
 private void runRepairs()throws Exception{
  List<Runnable> pending=new ArrayList<>(service.repairs);service.repairs.clear();
  for(Runnable repair:pending)workers.submit(repair).get(2,TimeUnit.SECONDS);
 }
 private void assertPcReceivedHelperStart()throws Exception{
  byte[] frame;
  do{frame=pcWire.receive();}while(frame[0]==5||frame[0]==6);
  assertEquals(8,frame[0]);assertEquals("HELPER|START",new String(frame,5,frame.length-5,java.nio.charset.StandardCharsets.UTF_8));
 }
 public void testBindFailureRepairsLiveHelperOnceEvenAfterRecentBootstrap()throws Exception{
  connect();service.captureRepairs=true;service.helperBootstrapAttempted=true;BridgeService.helperRequestAt=SystemClock.elapsedRealtime();BridgeService.helperStartRequests=1;
  failRouterBind("fixture timeout");failRouterBind("fixture retry failure");
  assertEquals("Repair is queued, never sent from the binding callback",1,BridgeService.helperStartRequests);
  runRepairs();assertTrue(service.helperRepairRequested);assertEquals("Exactly one repair bypasses the recent startup throttle",2,BridgeService.helperStartRequests);assertPcReceivedHelperStart();
  failRouterBind("fixture later failure");runRepairs();assertEquals(2,BridgeService.helperStartRequests);
  assertTrue(BridgeService.routingDiagnostics(service).contains("repairRequested=true"));
 }
 public void testQueuedHelperRepairCannotCrossUsbSessionOrStop()throws Exception{
  Wire.Mux old=connect();service.captureRepairs=true;failRouterBind("fixture old failure");
  service.linkClosed(old,"fixture disconnect before repair");service.cleanup.run();connect();runRepairs();
  assertFalse(service.helperRepairRequested);assertEquals(0,BridgeService.helperStartRequests);
  failRouterBind("fixture new failure");service.stopping=true;runRepairs();assertFalse(service.helperRepairRequested);assertEquals(0,BridgeService.helperStartRequests);
  service.stopping=false;failRouterBind("fixture current failure");runRepairs();assertTrue(service.helperRepairRequested);assertEquals(1,BridgeService.helperStartRequests);assertPcReceivedHelperStart();
 }
 public void testBindRepairWaitsForReadyMainHelperWithoutConsumingAllowance()throws Exception{
  connect();service.captureRepairs=true;service.helperAvailable=false;failRouterBind("fixture unavailable main");runRepairs();assertFalse(service.helperRepairRequested);assertEquals(0,BridgeService.helperStartRequests);
  service.helperAvailable=true;failRouterBind("fixture ready main but broken attachment");runRepairs();assertTrue(service.helperRepairRequested);assertEquals(1,BridgeService.helperStartRequests);assertPcReceivedHelperStart();
 }
 public void testTransportEofCancelsPendingRouterBindBeforeLateCallback()throws Exception{
  Wire.Mux current=connect();service.binding=true;
  android.content.ServiceConnection pending=service.new RouterConnection(++service.routerBindGeneration);service.connection=pending;
  service.routerBindTimeout=service.tasks.schedule(()->{throw new AssertionError("Canceled bind deadline ran");},35,TimeUnit.SECONDS);
  service.linkClosed(current,"fixture EOF during helper start");
  assertFalse(service.binding);assertNull(service.connection);assertNull(service.routerBindTimeout);
  pending.onServiceConnected(new android.content.ComponentName(getInstrumentation().getTargetContext(),RouterService.class),new BlockingRouter());
  assertNull("A late binder must not revive a disconnected USB session",service.router);
  service.cleanup.run();connect();assertNull(service.router);assertFalse(service.binding);
 }
 public void testUsbModeReenumerationDoesNotStopService()throws Exception{
  service.everConnected=true;service.usbConnectionChanged(false);service.usbConnectionChanged(true);SystemClock.sleep(BridgeService.USB_DISCONNECT_DEBOUNCE_MS+250);assertEquals(0,service.stopCalls);
 }
 public void testReplacementRouterRestoresDefaultNetworkPreference()throws Exception{
  connect();BridgeService.verified=true;BridgeService.ownVpnMode="plain";BridgeService.vpnDefaultReady=true;
  service.routerBinderLost();
  assertFalse("A replacement router needs its own Android network preference",BridgeService.vpnDefaultReady);
  assertEquals("",BridgeService.helperIssue);assertEquals("",BridgeService.vpnMessage);
  BlockingRouter replacement=new BlockingRouter();service.router=replacement;service.applyRouter();
  assertEquals(1,replacement.defaultPreferences);assertTrue(BridgeService.vpnDefaultReady);
 }
 public void testPersistentUnplugStillRequestsAutomaticStop()throws Exception{
  service.everConnected=true;service.usbConnectionChanged(false);SystemClock.sleep(BridgeService.USB_DISCONNECT_DEBOUNCE_MS+250);assertEquals(1,service.stopCalls);
 }
 public void testOldDisconnectTimerCannotStopReplacementService()throws Exception{
  service.everConnected=true;service.usbConnectionChanged(false);TestService newer=new TestService(getInstrumentation().getTargetContext());BridgeService.instance=newer;
  try{SystemClock.sleep(BridgeService.USB_DISCONNECT_DEBOUNCE_MS+250);assertEquals(0,service.stopCalls);assertEquals(0,newer.stopCalls);}finally{newer.tasks.shutdownNow();}
 }
}
