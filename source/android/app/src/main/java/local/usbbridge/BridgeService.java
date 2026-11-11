package local.usbbridge;
import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import rikka.shizuku.Shizuku;

public class BridgeService extends Service {
 static volatile BridgeService instance;
 static final RouterUiState cleanupUi=new RouterUiState();
 long cleanupUiOwner;
 static volatile String state="Подключите телефон кабелем",routerState="STOPPED|Раздача выключена",check="",source="",linkName="",vpnMessage="",helperIssue="";
 static volatile boolean connected,verified,shareWanted,checking,vpnDefaultReady;
 static volatile long verifiedAt,proofBytes,ownVpnSeenAt;
 static volatile int probeFailures;
 static volatile String lastProof="";
 final InternetHealth internetHealth=new InternetHealth();
 static volatile String ownVpnMode="",phoneIssue="";
 volatile long plainRequestAt;
 String loggedState="";
 static volatile long ownVpnNetwork;
 static volatile boolean wifiVpnRequired;
 volatile long connectivityNetworkHandle;
 volatile long legacyRefreshNetwork;
 String appliedWifiPolicy="";
 static volatile String helperAutoMessage="";
 static volatile long helperRequestAt;
 static volatile String helperLastResult="";
 static volatile long helperResultAt;
 static volatile int helperStartRequests;
 volatile long relaySession;
 volatile boolean sessionProven;
 boolean relayAvailable(){return !stopping&&connected&&sessionProven&&relaySession>0;}
 static volatile int proofNumber;
 static final int HELPER_MISSING=HelperAccess.MISSING,HELPER_UNAVAILABLE=HelperAccess.UNAVAILABLE,HELPER_LIMITED=HelperAccess.LIMITED,HELPER_PERMISSION=HelperAccess.PERMISSION,HELPER_READY=HelperAccess.READY,HELPER_ERROR=HelperAccess.ERROR;
 static final int PORT=38473,SOCKS=38474,PHONE_SOCKS=38475;
 static final int PHONE_CLIENTS=80,LOCAL_CLIENTS=96;
 // USB_STATE is a system broadcast, but its SDK constant is hidden from apps.
 static final String USB_STATE="android.hardware.usb.action.USB_STATE";
 volatile boolean stopping,binding,everConnected;
 volatile boolean recovering,usbDisconnected;
 volatile long connectionEpoch,usbStateEpoch;
 volatile long shareEpoch;
 static volatile CountDownLatch previousNetworkCleanup=new CountDownLatch(0);
 static final Object networkCleanupGate=new Object();
 static int networkCleanupCount;
 // A rapid Stop/Start/Stop can destroy a replacement before its predecessor
 // finishes stop/unbind. Keep one barrier for ALL outstanding cleanup work;
 // replacing it per service would let a third service reuse the old router.
 static Runnable beginNetworkCleanup(){
  final CountDownLatch barrier;
  synchronized(networkCleanupGate){if(networkCleanupCount++==0)previousNetworkCleanup=new CountDownLatch(1);barrier=previousNetworkCleanup;}
  final java.util.concurrent.atomic.AtomicBoolean completed=new java.util.concurrent.atomic.AtomicBoolean();
  return ()->{if(!completed.compareAndSet(false,true))return;synchronized(networkCleanupGate){if(--networkCleanupCount==0)barrier.countDown();}};
 }
 static final long USB_DISCONNECT_DEBOUNCE_MS=1000;
 boolean helperBootstrapAttempted;
 volatile boolean helperRepairRequested;
 final BroadcastReceiver usbState=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
  HelperAccess.usbEvent(context,intent);
  usbConnectionChanged(intent.getBooleanExtra("connected",true));
 }};
 void usbConnectionChanged(boolean attached){
  usbDisconnected=!attached;final long event=++usbStateEpoch;
  if(attached||!everConnected||stopping)return;
  try{tasks.schedule(()->{if(instance==this&&!stopping&&usbDisconnected&&usbStateEpoch==event){
   state="Кабель отключён · интернет ПК остановлен";requestServiceStop();
  }},USB_DISCONNECT_DEBOUNCE_MS,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException ignored){}
 }
 void requestServiceStop(){stopSelf();}
 final Map<String,ServerSocket> listeners=new ConcurrentHashMap<>();
 ServerSocket socks,phoneSocks;MidiLink midi;volatile Wire.Mux mux;PowerManager.WakeLock wake;
 volatile IRouter router;String boundVpn="";final Object configureLock=new Object();
 final Binder lease=new Binder();final ScheduledExecutorService tasks=Executors.newScheduledThreadPool(3);
 final java.util.concurrent.atomic.AtomicBoolean routerApplyScheduled=new java.util.concurrent.atomic.AtomicBoolean();
 final java.util.concurrent.atomic.AtomicBoolean routerApplyAgain=new java.util.concurrent.atomic.AtomicBoolean();
 final Set<Socket> pending=ConcurrentHashMap.newKeySet();final Semaphore clients=new Semaphore(PHONE_CLIENTS),localClients=new Semaphore(LOCAL_CLIENTS);
 final Set<SocketRelay> socketRelays=ConcurrentHashMap.newKeySet();
 final Set<RelayPipe> relayPipes=ConcurrentHashMap.newKeySet();
 Shizuku.UserServiceArgs args;
 static final long ROUTER_BIND_TIMEOUT_MS=35000;
 final Object routerBindingLock=new Object();
 ServiceConnection connection;
 ScheduledFuture<?> routerBindTimeout;
 long routerBindGeneration,routerBindStarted,routerBindRetryAt;
 int routerBindFailures;
 String routerBindLastFailure="";
 static String selectedVpn(Context context){return context.getSharedPreferences("bridge",0).getString("vpnPackage","");}
 static String sharingMode(Context context){return SharePolicy.normalize(context.getSharedPreferences("bridge",0).getString("sharingMode",SharePolicy.VPN));}
 static boolean encryptedReady(){return SharePolicy.vpnReady(ownVpnMode,ownVpnSeenAt,SystemClock.elapsedRealtime(),ownVpnNetwork);}
 void sharingModeChanged(){scheduleRouter();}
 static boolean relayVpn(Context context){try{String selected=selectedVpn(context);if(selected.isEmpty())return false;android.content.pm.ApplicationInfo info=context.getPackageManager().getApplicationInfo(selected,android.content.pm.PackageManager.GET_META_DATA);return info.metaData!=null&&info.metaData.getInt("local.usbbridge.UDP_RELAY_API",0)>=1;}catch(Exception e){return false;}}
 boolean needsRouter(){return shareWanted||(!selectedVpn(this).isEmpty()&&(!relayVpn(this)||helperReady()));}
 static boolean helperInstalled(Context context){try{context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api",0);return true;}catch(Exception e){return false;}}
 static int helperState(Context context){
  return HelperAccess.query(context).state;
 }
 static boolean helperReady(){return HelperAccess.ready();}
 static String helperDetails(Context context){
  HelperAccess.Result result=HelperAccess.query(context);int state=result.state;
  String label=state==HELPER_READY?"готов":state==HELPER_PERMISSION?"нужно разрешение":state==HELPER_LIMITED?"сетевые права ограничены":state==HELPER_UNAVAILABLE?"нет связи":state==HELPER_ERROR?"ошибка проверки доступа":"не установлен";
  return label+"; "+result.detail+"; "+HelperAccess.snapshot(context);
 }
 static boolean externalVpn(Context context){try{ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);for(Network n:cm.getAllNetworks()){NetworkCapabilities c=cm.getNetworkCapabilities(n);if(c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return !PhoneVpn.running;}}catch(Exception ignored){}return false;}
 static boolean phoneReady(Context context){
  if(!connected||!verified)return false;
  if(relayVpn(context))return ownVpnSeenAt!=0&&SystemClock.elapsedRealtime()-ownVpnSeenAt<15000&&("plain".equals(ownVpnMode)||"vpn".equals(ownVpnMode));
  return PhoneVpn.running&&PhoneVpn.healthy;
 }
 static String channelLabel(){return "USB-сеть".equals(linkName)?"USB-модем":linkName;}
 static boolean appNetworkReady(){
  BridgeService service=instance;IRouter active=service==null?null:service.router;
  return BridgeDiagnostics.systemChannelReady(active!=null&&active.asBinder().isBinderAlive(),service==null?0:service.connectivityNetworkHandle,vpnDefaultReady);
 }
 static String headline(Context context){
  if(!connected)return state;
  if(!verified)return checking?"Кабель подключён · проверяем интернет ПК":"Кабель подключён · интернет ПК не подтверждён";
  if(relayVpn(context)&&!phoneReady(context))return "plain_permission".equals(phoneIssue)?"Разрешите интернет USB в WireRoute":!phoneIssue.isEmpty()?"Откройте WireRoute для запуска интернета":"Компьютер подключён · запускаем интернет телефона";
  if(!relayVpn(context)&&!phoneReady(context))return "Кабель работает · канал Android восстанавливается";
  if(!appNetworkReady())return "Интернет ПК проверен · настройка Android не завершена";
  if(relayVpn(context)&&"vpn".equals(ownVpnMode))return "Интернет ПК · VPN включён";
  return "Интернет идёт через компьютер";
 }
 final class RouterConnection implements ServiceConnection {
  final long generation;
  RouterConnection(long generation){this.generation=generation;}
  boolean current(){return connection==this&&routerBindGeneration==generation&&instance==BridgeService.this&&!stopping;}
  public void onServiceConnected(ComponentName name,IBinder binder){
   boolean invalid;
   synchronized(routerBindingLock){
    if(!current()||!binding||!connected)return;
    invalid=binder==null||!binder.isBinderAlive();
    if(!invalid){cancelRouterBindTimer();binding=false;router=IRouter.Stub.asInterface(binder);routerBindFailures=0;routerBindRetryAt=0;routerBindLastFailure="";}
   }
   if(invalid){routerBindFailed(this,"Системный процесс завершился до подключения");return;}
   scheduleRouter();
  }
  void lost(String reason){
   final boolean pending;final Shizuku.UserServiceArgs previousArgs;
   synchronized(routerBindingLock){
    if(!current())return;
    pending=binding;previousArgs=args;
    if(!pending){connection=null;routerBinderLost();}
   }
   if(pending)routerBindFailed(this,reason);
   else try{if(previousArgs!=null)unbindRouterService(previousArgs,this,false);}catch(Exception ignored){}
  }
  public void onServiceDisconnected(ComponentName name){lost("Системный процесс отключился до передачи доступа");}
  public void onNullBinding(ComponentName name){routerBindFailed(this,"Системный процесс не передал доступ приложению");}
  public void onBindingDied(ComponentName name){lost("Системный процесс завершился во время подключения");}
 }
 void routerBinderLost(){
  connectivityNetworkHandle=0;appliedWifiPolicy="";vpnDefaultReady=false;binding=false;router=null;boundVpn="";
  routerState="STOPPED|Восстанавливаем системный канал";helperIssue="";vpnMessage="";
  BridgeLog.add(this,"Router binder lost; "+HelperAccess.snapshot(this));
 }
 long routerBindingNow(){return SystemClock.elapsedRealtime();}
 boolean routerAccessReady(){return helperReady();}
 void bindRouterService(Shizuku.UserServiceArgs selected,ServiceConnection callback){Shizuku.bindUserService(selected,callback);}
 void unbindRouterService(Shizuku.UserServiceArgs selected,ServiceConnection callback,boolean remove){Shizuku.unbindUserService(selected,callback,remove);}
 void cancelRouterBindTimer(){if(routerBindTimeout!=null){routerBindTimeout.cancel(false);routerBindTimeout=null;}}
 static long routerBindRetryDelay(int failures){return Math.min(30000L,2000L<<Math.min(4,Math.max(0,failures-1)));}
 void routerBindFailed(ServiceConnection expected,String reason){
  final Shizuku.UserServiceArgs failedArgs;
  final Wire.Mux failedMux;final long failedEpoch;
  synchronized(routerBindingLock){
   if(connection!=expected||!binding||instance!=this||stopping)return;
   failedMux=mux;failedEpoch=connectionEpoch;
   failedArgs=args;cancelRouterBindTimer();connection=null;binding=false;routerBindGeneration++;
   routerBindFailures=Math.min(30,routerBindFailures+1);routerBindRetryAt=routerBindingNow()+routerBindRetryDelay(routerBindFailures);
   routerBindLastFailure=reason;helperIssue=reason+". Повторяем подключение автоматически";routerState="STOPPED|"+helperIssue;vpnMessage="FAIL: "+helperIssue;
   BridgeLog.add(this,"Router bind failed: "+reason+"; attempt="+routerBindFailures+"; retryMs="+routerBindRetryDelay(routerBindFailures));
  }
  // Detach only this callback. Removing the shared user service here could kill
  // a newer attempt that has already acquired it while this one was expiring.
  try{if(failedArgs!=null)unbindRouterService(failedArgs,expected,false);}catch(Exception ignored){}
  queueHelperRepair(failedMux,failedEpoch);
 }
 void dispatchHelperRepair(Runnable attempt){tasks.execute(attempt);}
 void queueHelperRepair(Wire.Mux expected,long epoch){
  // The main Shizuku binder can be alive while its user-service attachment is
  // broken. Ask the PC to repair that server once for this USB session, even
  // if ordinary initial startup already succeeded. Never perform IPC or send
  // the repair request from the binding callback/lock itself.
  try{dispatchHelperRepair(()->{synchronized(BridgeService.this){
   if(instance!=this||stopping||!connected||mux!=expected||connectionEpoch!=epoch||expected==null||expected.closed||helperRepairRequested||!routerAccessReady())return;
   if(requestHelperStart(true))helperRepairRequested=true;
  }});}catch(RejectedExecutionException ignored){}
 }
 void checkRouterBindDeadline(){
  final ServiceConnection expired;
  synchronized(routerBindingLock){expired=binding&&routerBindingNow()-routerBindStarted>=ROUTER_BIND_TIMEOUT_MS?connection:null;}
  if(expired!=null)routerBindFailed(expired,"Системный доступ не ответил за 35 секунд");
 }
 void cancelPendingRouterBind(){
  final ServiceConnection pending;final Shizuku.UserServiceArgs pendingArgs;
  synchronized(routerBindingLock){
   cancelRouterBindTimer();pending=binding?connection:null;pendingArgs=args;
   if(binding){connection=null;binding=false;routerBindGeneration++;}
   routerBindFailures=0;routerBindRetryAt=0;
  }
  try{if(pending!=null&&pendingArgs!=null)unbindRouterService(pendingArgs,pending,false);}catch(Exception ignored){}
 }
 String routerBindingDiagnostics(){synchronized(routerBindingLock){return "pending="+binding+"; failures="+routerBindFailures+"; retryInMs="+Math.max(0,routerBindRetryAt-routerBindingNow())+"; lastFailure="+(routerBindLastFailure.isEmpty()?"none":routerBindLastFailure);}}
 public static Notification notice(Context c,String text,int id){
  NotificationManager nm=c.getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("bridge","USB Bridge",NotificationManager.IMPORTANCE_LOW));
  PendingIntent open=PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  PendingIntent stop=PendingIntent.getService(c,0,new Intent(c,BridgeService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  return new Notification.Builder(c,"bridge").setSmallIcon(R.drawable.ic_bridge).setContentTitle("USB Bridge").setContentText(text).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Остановить",stop).build()).build();
 }
 public void onCreate(){super.onCreate();UpdateManager.get(this).serviceActive(true);HelperAccess.observe(this);cleanupUiOwner=cleanupUi.begin();instance=this;connected=false;verified=false;shareWanted=false;ownVpnSeenAt=0;ownVpnMode="";phoneIssue="";source="";linkName="";state="Ожидаем компьютер";internetHealth.reset();probeFailures=0;lastProof="";verifiedAt=0;proofBytes=0;proofNumber=0;checking=false;ownVpnNetwork=0;wifiVpnRequired=false;connectivityNetworkHandle=0;resetHelperAttempt();registerReceiver(usbState,new IntentFilter(USB_STATE),Context.RECEIVER_NOT_EXPORTED);startForeground(1,notice(this,"Выберите USB-модем или MIDI в уведомлении USB",1));wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"USBBridge:link");wake.acquire(12*60*60*1000L);}
 void resetHelperAttempt(){helperBootstrapAttempted=false;helperRepairRequested=false;helperRequestAt=0;helperAutoMessage="";helperLastResult="";helperResultAt=0;helperStartRequests=0;legacyRefreshNetwork=0;}
 public int onStartCommand(Intent intent,int flags,int id){
  if(intent!=null&&"STOP".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}if(socks!=null)return START_NOT_STICKY;
  try{
   socks=new ServerSocket(SOCKS,32,InetAddress.getByName("127.0.0.1"));
   phoneSocks=new ServerSocket(PHONE_SOCKS,32,InetAddress.getByName("127.0.0.1"));
   Wire.run(this::acceptSocks);Wire.run(this::acceptPhoneSocks);
   try{midi=new MidiLink(this,new MidiLink.Listener(){public void status(String text){if(instance==BridgeService.this&&!stopping&&!connected&&!recovering)state=text;}public void opened(MidiLink.Pipe pipe){accept(pipe.in,pipe.out,pipe,null,"MIDI");}});}catch(Exception e){state="Выберите USB-модем в настройках USB";}
   scanUsb();tasks.scheduleWithFixedDelay(this::tick,2,2,TimeUnit.SECONDS);
   tasks.scheduleWithFixedDelay(()->{if(connected)verify();},45,45,TimeUnit.SECONDS);
  }catch(Exception e){state="Не удалось запустить: "+e.getMessage();stopSelf();}return START_NOT_STICKY;
 }
 static boolean usbInterface(String name){return name.startsWith("rndis")||name.startsWith("ncm")||name.startsWith("usb");}
 void scanUsb()throws IOException {
  Set<String> addresses=new HashSet<>();if(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"))addresses.add("127.0.0.1");
  for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces()))if(ni.isUp()&&usbInterface(ni.getName()))for(InetAddress a:Collections.list(ni.getInetAddresses()))if(a instanceof Inet4Address)addresses.add(a.getHostAddress());
  for(String address:addresses)if(!listeners.containsKey(address)&&!stopping){
   ServerSocket server=new ServerSocket(PORT,4,InetAddress.getByName(address));listeners.put(address,server);
   Wire.run(()->{while(!stopping&&!server.isClosed())try{
    Socket socket=server.accept();socket.setTcpNoDelay(true);socket.setSoTimeout(15000);if(pending.size()>=3){socket.close();continue;}pending.add(socket);
    Wire.run(()->{try{accept(socket.getInputStream(),socket.getOutputStream(),socket,socket,"USB-модем");}catch(Exception ignored){try{socket.close();}catch(Exception x){}}finally{pending.remove(socket);}});
   }catch(Exception e){if(!stopping&&!server.isClosed())state="USB-соединение прервано";}});
  }
  for(String address:new ArrayList<>(listeners.keySet()))if(!addresses.contains(address)){ServerSocket old=listeners.remove(address);if(old!=null)old.close();}
 }
 synchronized void accept(InputStream in,OutputStream out,Closeable owner,Socket socket,String kind){
  if(instance!=this||stopping||connected||recovering||previousNetworkCleanup.getCount()!=0||(owner instanceof MidiLink.Pipe&&((MidiLink.Pipe)owner).closed)){try{owner.close();}catch(Exception ignored){}return;}
  ScheduledFuture<?> deadline=tasks.schedule(()->{try{owner.close();}catch(Exception ignored){}},15,TimeUnit.SECONDS);
  try{
   state="Соединяем с компьютером…";String secret=UsbPair.accept(in,out);Wire.Secure wire=new Wire.Secure(in,out,owner,secret,false);deadline.cancel(false);if(socket!=null)socket.setSoTimeout(25000);if(instance!=this||stopping||(owner instanceof MidiLink.Pipe&&((MidiLink.Pipe)owner).closed)){wire.close();return;}
   final Wire.Mux[] holder=new Wire.Mux[1];Wire.Mux next=new Wire.Mux(wire,new Wire.Events(){
    public void status(String text){linkStatus(holder[0],text);}
    public void closed(String why){linkClosed(holder[0],why);}
    });holder[0]=next;mux=next;connectionEpoch++;relaySession=Math.max(1,SystemClock.elapsedRealtimeNanos());sessionProven=false;internetHealth.reset();checking=false;probeFailures=0;lastProof="";check="";verifiedAt=0;proofBytes=0;ownVpnSeenAt=0;ownVpnMode="";phoneIssue="";plainRequestAt=0;resetHelperAttempt();connected=true;everConnected=true;verified=false;linkName=kind;state="Кабель подключён";next.start();
   IRouter r=router;if(r!=null){String reset=r.refreshDatapath();if(reset.startsWith("FAIL"))vpnMessage=reset;}
   PhoneVpn.refreshDatapath();verify();
  }catch(Exception e){if(instance==this&&!stopping&&!connected)state="Подключение не завершено: "+e.getMessage();try{owner.close();}catch(Exception ignored){}}finally{deadline.cancel(false);}
 }
 synchronized void linkStatus(Wire.Mux expected,String text){
  if(instance!=this||stopping||!connected||mux!=expected)return;
  if(text.startsWith("SOURCE|"))source=text.substring(7,Math.min(text.length(),207));
  else if(text.startsWith("HELPER|RESULT|")){
   helperAutoMessage=BridgeDiagnostics.helperResult(text.substring(14));helperLastResult=helperAutoMessage;helperResultAt=SystemClock.elapsedRealtime();
   BridgeLog.add(this,"HELPER|RESULT|"+helperLastResult);
   if(helperAutoMessage.startsWith("Разрешите USB-отладку на телефоне. После"))helperRequestAt=SystemClock.elapsedRealtime();
  }
 }
 synchronized void linkClosed(Wire.Mux expected,String why){
  if(instance!=this||stopping||!connected||mux!=expected)return;
  final long epoch=++connectionEpoch;mux=null;recovering=true;connected=false;verified=false;relaySession=0;sessionProven=false;checking=false;
  cancelPendingRouterBind();
  expected.close();closeRelays();closeLocalRelays();ownVpnSeenAt=0;ownVpnNetwork=0;ownVpnMode="";wifiVpnRequired=false;shareWanted=false;connectivityNetworkHandle=0;vpnDefaultReady=false;
  check="Соединение с ПК потеряно";state="Соединение прервано · готовим повторное подключение";
  BridgeLog.add(this,"Transport closed: "+why);
  releaseNetwork(false,()->{synchronized(BridgeService.this){
   if(instance!=BridgeService.this||stopping||connectionEpoch!=epoch||connected)return;
   recovering=false;boundVpn="";appliedWifiPolicy="";state="Связь прервалась. Нажмите «Подключить» на компьютере";
  }});
 }
 /** Shut down only network ownership; a recoverable stream EOF keeps USB listeners alive. */
 void releaseNetwork(boolean destroy,Runnable done){
  final PhoneVpn phone=PhoneVpn.instance;final IRouter oldRouter=router;
  final Shizuku.UserServiceArgs oldArgs=args;final ServiceConnection oldConnection=connection;
  final long cleanupEpoch=connectionEpoch;final String priorState=routerState;
  Runnable stopRouter=()->Wire.run(()->{try{synchronized(configureLock){
   // A later onDestroy owns final cleanup; its predecessor must not stop a
   // router that a newly started service is about to reuse.
   if(!destroy&&(stopping||instance!=BridgeService.this))return;
   if(oldRouter!=null){String result=oldRouter.stop();publishCleanup(destroy,cleanupEpoch,RouterUiState.completed(result));
    if(destroy&&result.startsWith("STOPPED")&&oldArgs!=null&&oldConnection!=null)unbindRouterService(oldArgs,oldConnection,true);}
   else publishCleanup(destroy,cleanupEpoch,"STOPPED|Раздача выключена");
  }}catch(Exception e){publishCleanup(destroy,cleanupEpoch,RouterUiState.failed(priorState));}finally{done.run();}});
  if(phone==null)stopRouter.run();
  else new Handler(Looper.getMainLooper()).post(()->{try{if(PhoneVpn.instance==phone)phone.shutdown();}finally{stopRouter.run();}});
 }
 void publishCleanup(boolean destroy,long epoch,String result){
  // Android constructs/services on the main thread. Publish on that same
  // thread, after checking the generation: instance==null alone is unsafe
  // when a newer service has already started and stopped again.
  new Handler(Looper.getMainLooper()).post(()->{
   if(destroy||connectionEpoch==epoch)cleanupUi.publish(cleanupUiOwner,()->routerState=result);
  });
 }
 void acceptSocks(){acceptSocks(socks,localClients);}
 void acceptPhoneSocks(){acceptSocks(phoneSocks,clients);}
 void acceptSocks(ServerSocket listener,Semaphore pool){while(!stopping&&!listener.isClosed())try{
  Socket local=listener.accept();local.setTcpNoDelay(true);SocketRelay relay=null;
  synchronized(this){Wire.Mux current=mux;if(!stopping&&current!=null&&!current.closed&&pool.tryAcquire()){relay=new SocketRelay(local,pool,current);socketRelays.add(relay);}}
  if(relay==null)local.close();else{SocketRelay accepted=relay;Wire.run(accepted::start);}
 }catch(IOException ignored){}}
 synchronized void closeLocalRelays(){for(SocketRelay relay:new ArrayList<>(socketRelays))relay.close();}
 final class SocketRelay implements Closeable{
  final Socket socket;final Semaphore pool;final Wire.Mux transport;Wire.Channel channel;
  final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
  SocketRelay(Socket socket,Semaphore pool,Wire.Mux transport){this.socket=socket;this.pool=pool;this.transport=transport;}
  void start(){try{
   // Admission must not retain an unowned channel behind a stalled USB queue.
   if(closed.get())return;Wire.Channel opened=transport.openNow();
   synchronized(this){if(closed.get()){abortChannel(opened);return;}channel=opened;}
   CountDownLatch done=new CountDownLatch(1);
   Wire.run(()->{try{copy(socketInput(),opened.out);opened.finish();}catch(Exception error){close();}finally{done.countDown();}});
   copy(opened.in,socket.getOutputStream());socket.shutdownOutput();done.await(5,TimeUnit.SECONDS);
  }catch(Exception ignored){}finally{close();}}
  InputStream socketInput()throws IOException{return socket.getInputStream();}
  public void close(){if(!closed.compareAndSet(false,true))return;try{socket.close();}catch(IOException ignored){}synchronized(this){if(channel!=null)abortChannel(channel);}socketRelays.remove(this);pool.release();}
 }
 static void abortChannel(Wire.Channel channel){channel.abort();channel.mux.channels.remove(channel.id);Wire.run(()->{if(!channel.mux.closed)try{channel.mux.send(4,channel.id,new byte[0]);}catch(IOException ignored){}});}
 synchronized ParcelFileDescriptor openRelay(String caller,long session)throws IOException{
  Wire.Mux current=mux;
  if(!relayAvailable()||current==null||current.closed||session<=0||session!=relaySession||!caller.equals(selectedVpn(this)))throw new IOException("USB session is not available");
  if(!clients.tryAcquire())throw new IOException("USB session is busy");
  ParcelFileDescriptor[] pair=null;Wire.Channel channel=null;
  try{
   pair=ParcelFileDescriptor.createSocketPair();channel=current.openNow();
   RelayPipe pipe=new RelayPipe(pair[0],channel);relayPipes.add(pipe);pipe.start();return pair[1];
  }catch(Exception e){if(pair!=null)for(ParcelFileDescriptor fd:pair)try{fd.close();}catch(Exception ignored){}if(channel!=null)channel.close();clients.release();throw new IOException("Cannot open USB stream",e);}
 }
 synchronized void closeRelays(){for(RelayPipe pipe:new ArrayList<>(relayPipes))pipe.close();for(SocketRelay relay:new ArrayList<>(socketRelays))if(relay.pool==clients)relay.close();}
 final class RelayPipe implements Closeable{
  final ParcelFileDescriptor fd;final Wire.Channel channel;final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();final java.util.concurrent.atomic.AtomicInteger halves=new java.util.concurrent.atomic.AtomicInteger(2);
  RelayPipe(ParcelFileDescriptor fd,Wire.Channel channel){this.fd=fd;this.channel=channel;}
  void start(){
   Wire.run(()->{try{copy(new FileInputStream(fd.getFileDescriptor()),channel.out);channel.finish();}catch(Exception e){close();}finally{finished();}});
   Wire.run(()->{try{copy(channel.in,new FileOutputStream(fd.getFileDescriptor()));android.system.Os.shutdown(fd.getFileDescriptor(),android.system.OsConstants.SHUT_WR);}catch(Exception e){close();}finally{finished();}});
  }
  void finished(){if(halves.decrementAndGet()==0)close();}
  public void close(){if(!closed.compareAndSet(false,true))return;try{android.system.Os.shutdown(fd.getFileDescriptor(),android.system.OsConstants.SHUT_RDWR);}catch(Exception ignored){}try{fd.close();}catch(Exception ignored){}channel.abort();channel.mux.channels.remove(channel.id);relayPipes.remove(this);clients.release();Wire.run(()->{if(!channel.mux.closed)try{channel.mux.send(4,channel.id,new byte[0]);}catch(IOException ignored){}});}
 }
 static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){out.write(b,0,n);out.flush();}}
 synchronized void verify(){
  if(checking||!connected||stopping)return;checking=true;Wire.Mux expected=mux;if(!sessionProven)check="Открываем HTTPS-сайт через компьютер…";
  final long before=expected==null?0:expected.sent.get()+expected.received.get();
  Wire.run(()->{try{
   String result=InternetProbe.check();
   synchronized(BridgeService.this){if(stopping||instance!=BridgeService.this||mux!=expected||!connected)return;
    verifiedAt=System.currentTimeMillis();internetHealth.success(verifiedAt);probeFailures=0;verified=true;sessionProven=true;proofNumber++;
    proofBytes=Math.max(0,expected.sent.get()+expected.received.get()-before);
    String time=new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(verifiedAt));
    lastProof="Тест №"+proofNumber+" · "+time+" · "+result;check=lastProof;
   }
   BridgeLog.add(this,"PASS PC-only "+check);activate();
  }catch(Exception e){synchronized(BridgeService.this){if(!stopping&&instance==BridgeService.this&&mux==expected&&connected){
    internetHealth.failure();probeFailures=internetHealth.failures();
    // Preserve the session and its proven source. Website filtering/congestion must
    // neither tear down VPN/Wi-Fi nor turn earlier HTTPS evidence into a cable error.
    verified=internetHealth.proven();
    check=verified?(lastProof+"\n"+(internetHealth.repeatedFailure()?"Проверочные сайты пока не отвечают. Если приложения работают, подключение менять не нужно.":"Повторная проверка задержалась. Подключение сохранено; проверим ещё раз автоматически.")):
       "Кабель подключён, но проверочные сайты пока не ответили. Проверьте, открываются ли сайты на компьютере.";
    BridgeLog.add(this,"Probe failed #"+probeFailures+": "+e);
   }}}finally{synchronized(BridgeService.this){if(instance==BridgeService.this&&mux==expected)checking=false;}if(!stopping)sendStatus();}});
 }
 void verifyNow(){if(connected&&!checking)verify();}
 static String formatBytes(long value){if(value>=1048576)return String.format(Locale.getDefault(),"%.1f МБ",value/1048576.0);return String.format(Locale.getDefault(),"%.1f КБ",value/1024.0);}
 void ensurePlainInternet(){
  if(instance!=this||stopping||!relayAvailable()||!relayVpn(this))return;
  if(ownVpnSeenAt!=0&&SystemClock.elapsedRealtime()-ownVpnSeenAt<15000)return;
  long now=SystemClock.elapsedRealtime();if(plainRequestAt!=0&&now-plainRequestAt<10000)return;
  plainRequestAt=now;
  try{sendBroadcast(new Intent("local.usbbridge.START_PLAIN").setPackage(selectedVpn(this)).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).putExtra("session",relaySession));BridgeLog.add(this,"Request plain USB internet, session="+relaySession);}
  catch(Exception e){BridgeLog.add(this,"Plain start request failed: "+e);}
 }
 void activate(){if(stopping||!connected||!verified)return;
  ensurePlainInternet();
  // Choosing a second VPN is only a preference. Keep the already working USB
  // tunnel until Android actually replaces it with that VPN.
  if(!relayVpn(this)&&!PhoneVpn.running&&android.net.VpnService.prepare(this)==null&&!externalVpn(this))
   new Handler(Looper.getMainLooper()).post(()->{if(!stopping&&connected&&!relayVpn(this)&&!PhoneVpn.running&&android.net.VpnService.prepare(this)==null&&!externalVpn(this))startForegroundService(new Intent(this,PhoneVpn.class));});
  if(needsRouter())ensureRouter();
  // A ready TEST underlay lets legacy Android apps recognize USB internet too.
  // Reuse existing authorized ADB automatically; do not require Wi-Fi for this.
  if(relayVpn(this)&&!helperBootstrapAttempted&&helperState(this)==HELPER_UNAVAILABLE){helperBootstrapAttempted=true;requestHelperStart();}
 }
 void ensureRouter(){
  if(instance!=this||stopping||!connected||!verified||!needsRouter())return;if(!routerAccessReady()){routerState="STOPPED|Нужно запустить системный доступ";vpnMessage="FAIL: Нужно запустить Shizuku";return;}
  if(router!=null){scheduleRouter();return;}
  synchronized(routerBindingLock){
   if(instance!=this||stopping||!connected||!verified||router!=null||binding||routerBindingNow()<routerBindRetryAt)return;
   binding=true;routerBindStarted=routerBindingNow();
   final RouterConnection attempt=new RouterConnection(++routerBindGeneration);connection=attempt;
   try{
    args=new Shizuku.UserServiceArgs(new ComponentName(this,RouterService.class)).daemon(false).processNameSuffix("router").debuggable(false).version(BuildConfig.VERSION_CODE);
    routerBindTimeout=tasks.schedule(this::checkRouterBindDeadline,ROUTER_BIND_TIMEOUT_MS,TimeUnit.MILLISECONDS);
    bindRouterService(args,attempt);
   }catch(Exception failure){routerBindFailed(attempt,"Ошибка подключения системного доступа: "+BridgeDiagnostics.helperResult(failure.toString()));}
  }
 }
 void scheduleRouter(){
  if(stopping)return;routerApplyAgain.set(true);if(!routerApplyScheduled.compareAndSet(false,true))return;
  try{tasks.execute(()->{try{while(!stopping&&routerApplyAgain.getAndSet(false)){applyRouter();sendStatus();}}
   finally{routerApplyScheduled.set(false);if(!stopping&&routerApplyAgain.get())scheduleRouter();}});}
  catch(RejectedExecutionException e){routerApplyScheduled.set(false);}
 }
 void tick(){if(instance!=this||stopping)return;try{
  checkRouterBindDeadline();scanUsb();ensurePlainInternet();IRouter r=router;
  if(r!=null){if(r.asBinder().isBinderAlive()){applyWifiPolicy();routerState=r.status();}
   else{
    ServiceConnection obsolete=null;Shizuku.UserServiceArgs obsoleteArgs=null;
    synchronized(routerBindingLock){if(router==r){obsolete=connection;obsoleteArgs=args;connection=null;routerBinderLost();}}
    try{if(obsolete!=null&&obsoleteArgs!=null)unbindRouterService(obsoleteArgs,obsolete,false);}catch(Exception ignored){}
   }}
  if(router==null&&!binding&&connected&&verified&&needsRouter()&&helperReady())ensureRouter();
  else if(router!=null&&connected&&verified&&needsRouter()&&((shareWanted&&!routerState.startsWith("ACTIVE"))||(!vpnDefaultReady&&ownVpnSeenAt!=0&&("plain".equals(ownVpnMode)||"vpn".equals(ownVpnMode)))))scheduleRouter();
  sendStatus();
 }catch(Exception e){if(!stopping)android.util.Log.w("USBBridge",e.toString());}}
 void applyRouter(){synchronized(configureLock){try{
   IRouter r=router;if(instance!=this||r==null||stopping||!connected)return;String started=r.start(getApplicationInfo().nativeLibraryDir+"/libhev-socks5-tunnel.so",SOCKS,lease);if(instance!=this||stopping||!connected)return;routerState=started;
   if(!routerState.startsWith("READY")&&!routerState.startsWith("ACTIVE")){helperIssue=friendlyHelperFailure(routerState);vpnMessage="FAIL: "+helperIssue;routerState="STOPPED|"+helperIssue;return;}helperIssue="";
   connectivityNetworkHandle=r.networkHandle();
   if(!applyWifiPolicy())return;
   String selected=relayVpn(this)?"":selectedVpn(this);if(!selected.equals(boundVpn)){vpnMessage=r.configureVpn(selected);if(!vpnMessage.equals("OK"))return;boundVpn=selected;}
   if(!vpnDefaultReady&&((selected.isEmpty()&&PhoneVpn.running)||("plain".equals(ownVpnMode)||"vpn".equals(ownVpnMode)))){String preference=r.setVpnDefaultPackages(null);android.util.Log.i("USBBridgeVpnDefault",preference);vpnDefaultReady=preference.startsWith("OK");if(vpnDefaultReady&&legacyRefreshNetwork==0)legacyRefreshNetwork=preferenceNetwork(preference);if(preference.startsWith("FAIL")){helperIssue="Не удалось назначить интернет приложениям: "+preference;return;}}
   if(shareWanted&&!routerState.startsWith("ACTIVE")){routerState=r.setWifi(true);}
  }catch(Exception e){helperIssue=friendlyHelperFailure(e.toString());vpnMessage="FAIL: "+helperIssue;routerState="ERROR|"+helperIssue;}}}
 boolean applyWifiPolicy(){synchronized(configureLock){
  boolean required=SharePolicy.requiresVpn(sharingMode(this),wifiVpnRequired);
  IRouter r=router;if(r==null)return !required;
  long handle=required&&ownVpnSeenAt!=0&&SystemClock.elapsedRealtime()-ownVpnSeenAt<15000?ownVpnNetwork:0;
  String selected=selectedVpn(this),key=selected+"/"+required+"/"+handle;
  // A revoked gateway can recover with the same Network handle. Do not let a
  // cached positive policy prevent RouterService from rebuilding that path.
  if(key.equals(appliedWifiPolicy)&&(!required||handle==0||routerState.startsWith("ACTIVE|")))return !required||handle!=0;
  try{String result=r.setWifiVpn(selected,handle,required);routerState=r.status();if(!result.startsWith("OK")){appliedWifiPolicy=required&&handle==0&&"BLOCKED:VPN_REQUIRED".equals(result)?key:"";return false;}appliedWifiPolicy=key;return !required||handle!=0;}
  catch(Exception e){appliedWifiPolicy="";routerState="ERROR|Не удалось направить раздачу через VPN";return false;}
 }}
 static long preferenceNetwork(String result){
  if(result==null||!result.startsWith("OK:"))return 0;
  int marker=result.indexOf("|NETWORK:");if(marker<0)return 0;
  try{return Math.max(0,Long.parseLong(result.substring(marker+9)));}catch(NumberFormatException invalid){return 0;}
 }
 synchronized boolean vpnReport(String caller,long session,String method,String mode,long handle){
  if(stopping||session<=0||session!=relaySession||!caller.equals(selectedVpn(this)))return false;
  return vpnReport(method,mode,handle);
 }
 synchronized boolean vpnReport(String method,String mode,long handle){
  if("plain_permission".equals(method)||"plain_background".equals(method)||"plain_failed".equals(method)){phoneIssue=method;BridgeLog.add(this,"WireRoute start: "+method);return true;}
  if("starting".equals(method)&&"vpn".equals(mode)){
   phoneIssue="";
   wifiVpnRequired=true;ownVpnNetwork=0;ownVpnSeenAt=SystemClock.elapsedRealtime();ownVpnMode="starting";vpnDefaultReady=false;
   synchronized(configureLock){boolean required=SharePolicy.requiresVpn(sharingMode(this),true);IRouter r=router;if(r==null)return !required||!shareWanted;
    try{String result=r.setWifiVpn(selectedVpn(this),0,required);routerState=r.status();
     boolean ready=required?"BLOCKED:VPN_REQUIRED".equals(result):result.startsWith("OK");appliedWifiPolicy=ready?selectedVpn(this)+"/"+required+"/0":"";return ready;
    }catch(Exception e){appliedWifiPolicy="";return false;}
   }
  }
  if("stopped".equals(method)){if(mode.equals(ownVpnMode)||("vpn".equals(mode)&&"starting".equals(ownVpnMode))){ownVpnSeenAt=0;ownVpnMode="";ownVpnNetwork=0;vpnDefaultReady=false;applyWifiPolicy();}return true;}
  if(!relayAvailable()||(!"plain".equals(mode)&&!"vpn".equals(mode)))return false;
  // Plain mode can still emit one heartbeat while encrypted startup is queued.
  // Only its new service start (explicit VPN off) releases the sharing barrier.
  if(wifiVpnRequired&&"plain".equals(mode)&&!"started".equals(method))return false;
  boolean changed=!mode.equals(ownVpnMode)||handle!=ownVpnNetwork;
  if(changed)BridgeLog.add(this,"Phone route: "+method+" mode="+mode+" network="+handle+" previous="+ownVpnMode+"; PC source="+source);
  phoneIssue="";ownVpnSeenAt=SystemClock.elapsedRealtime();ownVpnMode=mode;ownVpnNetwork=handle;wifiVpnRequired="vpn".equals(mode);
  if(changed){vpnDefaultReady=false;if(router==null&&needsRouter())ensureRouter();else scheduleRouter();}
  return true;
 }
 static String friendlyHelperFailure(String raw){String lower=String.valueOf(raw).toLowerCase(Locale.ROOT);if(lower.contains("security")||lower.contains("permission")||lower.contains("denial")||lower.contains("manage_test")||lower.contains("tether_privileged"))return "Система телефона не разрешила раздачу через помощника. Откройте «Настройка раздачи»: там показаны доступ и следующий шаг для этого телефона";return raw==null||raw.trim().isEmpty()?"Системный доступ не готов":raw.replace("STOPPED|","").replace("ERROR|","");}
 void share(boolean enabled){long epoch=++shareEpoch;shareWanted=enabled;if(enabled){ensureRouter();if(helperState(this)==HELPER_UNAVAILABLE)requestHelperStart();}else{helperRequestAt=0;helperAutoMessage="";IRouter r=router;if(r!=null)try{tasks.execute(()->stopSharingIfCurrent(r,epoch));}catch(RejectedExecutionException ignored){}}}
 void stopSharingIfCurrent(IRouter expected,long epoch){synchronized(configureLock){
  if(instance!=this||stopping||shareWanted||shareEpoch!=epoch||router!=expected)return;
  try{routerState=expected.setWifi(false);sendStatus();}catch(Exception ignored){}
 }}
 void requestHelperStart(){requestHelperStart(false);}
 synchronized boolean requestHelperStart(boolean repair){Wire.Mux current=mux;if(instance!=this||stopping||current==null||current.closed||!connected)return false;long now=SystemClock.elapsedRealtime();if(!repair&&helperRequestAt!=0&&now-helperRequestAt<10000)return false;
  helperRequestAt=now;helperAutoMessage="Пробуем запустить доступ через компьютер…";helperStartRequests++;
  BridgeLog.add(this,"HELPER|START request="+helperStartRequests+"; repair="+repair+"; "+HelperAccess.snapshot(this));
  Wire.run(()->{if(instance!=this||stopping||mux!=current||!connected)return;try{current.send(8,0,Wire.bytes("HELPER|START"));}catch(Exception error){synchronized(this){if(instance!=this||stopping||mux!=current||!connected)return;helperAutoMessage="Компьютер не получил запрос системного доступа";helperLastResult="send failed: "+error.getClass().getSimpleName();helperResultAt=SystemClock.elapsedRealtime();BridgeLog.add(this,"HELPER|RESULT|"+helperLastResult);}}});
  return true;
 }
 synchronized void vpnChanged(){closeRelays();ownVpnSeenAt=0;ownVpnNetwork=0;ownVpnMode="";appliedWifiPolicy="";if(selectedVpn(this).isEmpty())wifiVpnRequired=false;applyWifiPolicy();vpnMessage="";Wire.run(()->{IRouter r=router;if(r!=null)try{synchronized(configureLock){
   // Do not clear the current VPN default while PhoneVpn still carries traffic.
   if(selectedVpn(this).isEmpty()){r.setVpnDefaultPackages(new String[0]);vpnDefaultReady=false;}
   String target=relayVpn(this)?"":selectedVpn(this);String result=r.configureVpn(target);if(result.equals("OK"))boundVpn=target;else{vpnMessage=result;return;}
  }}catch(Exception e){vpnMessage="FAIL: "+e.getMessage();return;}activate();});}
 void sendStatus(){Wire.Mux current=mux;if(current!=null&&!current.closed)try{String[] parts=routerState.split("\\|");String proof=verified?(probeFailures==0?"Интернет компьютера проверен":"Повторяем проверку интернета"):"Проверяем интернет компьютера";String text="STATE|"+(verified?"1":"0")+"|"+headline(this)+"|"+(shareWanted&&parts.length>1?parts[1]:"Раздача Wi-Fi выключена")+"|"+proof+"|"+channelLabel();String snapshot=headline(this)+"; "+routerState+"; "+ownVpnMode;if(!snapshot.equals(loggedState)){loggedState=snapshot;BridgeLog.add(this,snapshot);}current.send(8,0,Wire.bytes(text));}catch(Exception ignored){}}
 static String addresses(){List<String> out=new ArrayList<>();try{for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces()))if(ni.isUp()&&usbInterface(ni.getName()))for(InetAddress a:Collections.list(ni.getInetAddresses()))if(a instanceof Inet4Address)out.add(ni.getName()+": "+a.getHostAddress());}catch(Exception ignored){}return out.isEmpty()?"USB-сеть не обнаружена. Можно использовать MIDI.":android.text.TextUtils.join("\n",out);}
 static String routingDiagnostics(Context context){
  BridgeService service=instance;Wire.Mux current=service==null?null:service.mux;
  String dnsMode="unknown";
  try{dnsMode=android.provider.Settings.Global.getString(context.getContentResolver(),"private_dns_mode");}catch(Exception ignored){}
  long age=ownVpnSeenAt==0?-1:SystemClock.elapsedRealtime()-ownVpnSeenAt;
  return "Phone mode="+ownVpnMode+"; network="+ownVpnNetwork+"; heartbeatAgeMs="+age+"; Wi-Fi VPN required="+wifiVpnRequired+"; sharingMode="+sharingMode(context)
   +"\nAndroid preparation: vpnDefaultReady="+vpnDefaultReady+"; TEST connectivityHandle="+(service==null?0:service.connectivityNetworkHandle)+"; systemChannelReady="+appNetworkReady()
   +"\nHelper bootstrap: requests="+helperStartRequests+"; repairRequested="+(service!=null&&service.helperRepairRequested)+"; requestAgeMs="+(helperRequestAt==0?-1:SystemClock.elapsedRealtime()-helperRequestAt)+"; resultAgeMs="+(helperResultAt==0?-1:SystemClock.elapsedRealtime()-helperResultAt)+"; result="+(helperLastResult.isEmpty()?"not received":helperLastResult)
   +"\nRouter bind: "+(service==null?"inactive":service.routerBindingDiagnostics())
   +"\nSelected VPN: "+BridgeDiagnostics.selectedVpn(context,selectedVpn(context))
   +"\nPrivate DNS mode="+dnsMode+"; streams="+(current==null?0:current.channels.size())+"/"+(current==null?96:current.peerLimit)
   +"; phoneSlots="+(service==null?0:PHONE_CLIENTS-service.clients.availablePermits())+"/"+PHONE_CLIENTS
   +"; hotspotAndProbeSlots="+(service==null?0:LOCAL_CLIENTS-service.localClients.availablePermits())+"/"+LOCAL_CLIENTS
   +"\n"+BridgeDiagnostics.networkObservation(context);
 }
 public IBinder onBind(Intent i){return null;}
 public void onDestroy(){
   final boolean current=instance==this;stopping=true;cancelPendingRouterBind();connectionEpoch++;usbStateEpoch++;relaySession=0;sessionProven=false;closeRelays();closeLocalRelays();try{unregisterReceiver(usbState);}catch(Exception ignored){}
   final Runnable cleanup=current?beginNetworkCleanup():()->{};
   if(current){routerState=RouterUiState.stopping(routerState);ownVpnMode="";ownVpnSeenAt=0;ownVpnNetwork=0;wifiVpnRequired=false;connected=false;verified=false;shareWanted=false;checking=false;instance=null;}
   for(ServerSocket listener:listeners.values())try{listener.close();}catch(Exception ignored){}listeners.clear();
  try{if(socks!=null)socks.close();}catch(Exception ignored){}try{if(phoneSocks!=null)phoneSocks.close();}catch(Exception ignored){}for(Socket socket:pending)try{socket.close();}catch(Exception ignored){}if(mux!=null)mux.close();if(midi!=null)midi.close();IRouter r=router;
  if(current)releaseNetwork(true,cleanup);
  tasks.shutdownNow();if(wake!=null&&wake.isHeld())wake.release();if(current){check="";helperIssue="";state="Отключено";UpdateManager.get(this).serviceActive(false);}super.onDestroy();
 }
}

