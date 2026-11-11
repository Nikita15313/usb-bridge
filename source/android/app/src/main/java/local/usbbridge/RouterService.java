package local.usbbridge;
import android.content.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import hev.htproxy.TProxyService;

/** Runs as Android shell through the user-authorized Shizuku service, never root. */
public final class RouterService extends IRouter.Stub {
 final Context ctx;final ConnectivityManager cm;final Object test,tether;final ExecutorService callbacks=Executors.newSingleThreadExecutor();
 ParcelFileDescriptor tun;volatile Network network;ConnectivityManager.NetworkCallback keep;Object observer;Binder lifetime=new Binder();IBinder owner;IBinder.DeathRecipient death;File config;
 volatile String iface="",upstream="",message="Системный канал выключен",vpnPackage="";volatile boolean resetting,wifi,active,starting,configuring,ownsWifi;volatile long pulse;
 String preferredSession="";
 int pcSocksPort;volatile boolean wifiVpnRequired;volatile long wifiVpnHandle;volatile String wifiVpnPackage="";
 volatile NetworkSocksGateway vpnGateway;ConnectivityManager.NetworkCallback vpnObserver;
 volatile HotspotClients.Snapshot wifiClients=HotspotClients.UNKNOWN;volatile long observationEpoch,networkEpoch,routeEpoch,validationEpoch;
 final HotspotClients.Tracker clientTracker=new HotspotClients.Tracker();Object wifiManager,wifiObserver;
 final Object networkStateLock=new Object();
 final DatapathState datapath=new DatapathState();
 // test_network is a shell-only Android service omitted from SDK service names.
 @android.annotation.SuppressLint("WrongConstant")
 public RouterService(Context supplied){try{ctx=supplied.createPackageContext("com.android.shell",0);Field f=ctx.getClass().getDeclaredField("mAttributionSource");f.setAccessible(true);Object a=f.get(ctx);f.set(ctx,a.getClass().getMethod("withPackageName",String.class).invoke(a,"com.android.shell"));cm=(ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);test=ctx.getSystemService("test_network");tether=ctx.getSystemService("tethering");}catch(Exception e){throw new IllegalStateException("Shell context: "+e,e);}}
 public RouterService(){this(systemContext());}
 static Context systemContext(){try{Class<?> c=Class.forName("android.app.ActivityThread");Object a=c.getMethod("systemMain").invoke(null);return (Context)c.getMethod("getSystemContext").invoke(a);}catch(Exception e){throw new IllegalStateException(e);}}
 static Object call(Object object,String name,Class<?>[] types,Object...args)throws Exception{return object.getClass().getMethod(name,types).invoke(object,args);}
 static String error(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getClass().getSimpleName()+": "+e.getMessage();}
 Object proxy(Class<?> c,java.util.function.BiConsumer<String,Object[]> action){return java.lang.reflect.Proxy.newProxyInstance(c.getClassLoader(),new Class<?>[]{c},(p,m,a)->{if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==a[0];if(m.getName().equals("toString"))return "USBBridgeCallback";action.accept(m.getName(),a);return null;});}
 void observe()throws Exception{
  final long epoch=++observationEpoch;
  Class<?> c=Class.forName("android.net.TetheringManager$TetheringEventCallback");
  observer=proxy(c,(name,args)->{
   if(epoch!=observationEpoch)return;
   try{
    if(name.equals("onUpstreamChanged")){
     Network n=(Network)args[0];LinkProperties p=n==null?null:cm.getLinkProperties(n);
     upstream=p==null?"":String.valueOf(p.getInterfaceName());
     // Even while the native VPN barrier is closed the AP must never acquire
     // another Android upstream (for example cellular data). Keep the radio
     // only while its upstream is still our persistent TEST network.
     if((active||(ownsWifi&&wifi&&!configuring))&&!upstream.equals(iface)){active=false;message="Android сменил источник. Wi-Fi остановлен";stopWifi();}
    }
    if(name.equals("onTetheredInterfacesChanged")&&args!=null){
     boolean found=false;
     for(Object x:(Collection<?>)args[0]){
      if(x instanceof String){String s=(String)x;found|=s.startsWith("wlan")||s.startsWith("ap")||s.startsWith("swlan");}
      else found|=((Number)x.getClass().getMethod("getType").invoke(x)).intValue()==0;
     }
     boolean wasWifi=wifi;wifi=found;
     if(!found){active=false;if(wasWifi)wifiClients=clientTracker.reset();if(!configuring)message="Раздача Wi-Fi выключена";}
    }
    if(name.equals("onClientsChanged")&&args!=null&&args.length>0)
     wifiClients=clientTracker.metadata((Collection<?>)args[0]);
   }catch(Exception e){android.util.Log.w("USBBridgeHotspot",error(e));}
  });
  call(tether,"registerTetheringEventCallback",new Class<?>[]{Executor.class,c},callbacks,observer);
  observeAssociations(epoch);
 }
 void observeAssociations(long epoch){
  try{
   wifiManager=ctx.getSystemService(Context.WIFI_SERVICE);
   Class<?> type=Class.forName("android.net.wifi.WifiManager$SoftApCallback");
   wifiObserver=proxy(type,(name,args)->{
    if(epoch!=observationEpoch||args==null)return;
    try{
    // This aggregate callback includes every AP instance (also bridged bands).
    // The two-argument overload is per instance and must not replace the union.
    if(name.equals("onConnectedClientsChanged")&&args.length==1&&args[0] instanceof Collection)
     wifiClients=clientTracker.associations((Collection<?>)args[0]);
    if(name.equals("onStateChanged")&&args.length>0){Integer state=softApState(args[0]);if(state!=null&&(state==11||state==14))wifiClients=clientTracker.reset();}
    }catch(Exception error){android.util.Log.w("USBBridgeHotspot","Wi-Fi association callback: "+error(error));}
   });
   call(wifiManager,"registerSoftApCallback",new Class<?>[]{Executor.class,type},callbacks,wifiObserver);
  }catch(Exception unavailable){wifiObserver=null;wifiClients=clientTracker.reset();android.util.Log.w("USBBridgeHotspot","Live Wi-Fi associations unavailable: "+error(unavailable));}
 }
 static Integer softApState(Object value){
  if(value instanceof Number)return ((Number)value).intValue();
  if(value==null)return null;
  try{Object state=value.getClass().getMethod("getState").invoke(value);return state instanceof Number?((Number)state).intValue():null;}
  catch(Exception unavailable){return null;}
 }
 void stopObservingAssociations(){
  if(wifiObserver!=null)try{call(wifiManager,"unregisterSoftApCallback",new Class<?>[]{Class.forName("android.net.wifi.WifiManager$SoftApCallback")},wifiObserver);}catch(Exception ignored){}
  wifiObserver=null;wifiManager=null;wifiClients=clientTracker.reset();
 }
 void trace(String stage){android.util.Log.i("USBBridgeHotspot",stage+"; source="+(wifiVpnRequired?"VPN":"PC")+"; iface="+iface+"; upstream="+upstream+"; wifi="+wifi+"; active="+active);}
 // TRANSPORT_TEST=7 is intentionally hidden from the public SDK constants.
 @android.annotation.SuppressLint("WrongConstant")
 @Override public synchronized String start(String library,int port,IBinder owner){if(tun!=null)return status();pcSocksPort=port;pulse=System.nanoTime();starting=true;message="Подготовка системного канала";try{if(android.os.Process.myUid()!=2000)throw new IOException("Требуется Shizuku с доступом shell");if(test==null||tether==null)throw new IOException("Прошивка не предоставляет test_network/tethering");this.owner=owner;death=()->{active=false;message="Приложение остановлено";Wire.run(()->stop());};owner.linkToDeath(death,0);System.load(library);observe();Object created;
   try{created=call(test,"createTunInterface",new Class<?>[]{Collection.class},Arrays.asList(LinkAddress.class.getConstructor(String.class).newInstance("198.18.7.1/30")));}catch(NoSuchMethodException e){created=call(test,"createTunInterface",new Class<?>[]{LinkAddress[].class},(Object)new LinkAddress[]{LinkAddress.class.getConstructor(String.class).newInstance("198.18.7.1/30")});}
   iface=(String)call(created,"getInterfaceName",new Class<?>[]{});tun=(ParcelFileDescriptor)call(created,"getFileDescriptor",new Class<?>[]{});
   LinkProperties props=new LinkProperties();call(props,"setInterfaceName",new Class<?>[]{String.class},iface);call(props,"addDnsServer",new Class<?>[]{java.net.InetAddress.class},java.net.InetAddress.getByName("198.18.7.2"));
   final long createdNetworkEpoch;synchronized(networkStateLock){createdNetworkEpoch=++networkEpoch;}
   NetworkRequest request=new NetworkRequest.Builder().clearCapabilities().addTransportType(7).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).setNetworkSpecifier((NetworkSpecifier)Class.forName("android.net.TestNetworkSpecifier").getConstructor(String.class).newInstance(iface)).build();CountDownLatch available=new CountDownLatch(1);keep=new ConnectivityManager.NetworkCallback(){public void onLinkPropertiesChanged(Network n,LinkProperties p){synchronized(networkStateLock){if(createdNetworkEpoch==networkEpoch&&iface.equals(p.getInterfaceName())){network=n;available.countDown();}}}};cm.requestNetwork(request,keep);
   publishPreferredTestNetwork(tether,test,props,lifetime);if(!available.await(12,TimeUnit.SECONDS))throw new IOException("Android не опубликовал тестовую сеть");
   config=new File("/data/local/tmp/usbbridge-"+android.os.Process.myPid()+".yml");config.createNewFile();config.setReadable(false,false);config.setWritable(false,false);config.setReadable(true,true);config.setWritable(true,true);
   // A VPN report can arrive before start() obtains the lifecycle lock. Honor
   // that already-applied policy; never initialize a cached VPN route as direct PC.
   if(!wifiVpnRequired||vpnGateway!=null){
    ensureDatapathRunning();
   }
   starting=false;message=wifiVpnRequired&&vpnGateway==null?"Системный канал готов. Ждём VPN телефона":"Системный канал ПК готов";Wire.run(()->{while(tun!=null){try{Thread.sleep(2000);synchronized(RouterService.this){if(!starting&&!configuring&&!resetting&&(System.nanoTime()-pulse>TimeUnit.SECONDS.toNanos(20)||((!wifiVpnRequired||vpnGateway!=null)&&!TProxyService.TProxyIsRunning()))){message="Связь с приложением потеряна";stop();break;}}}catch(Exception ignored){break;}}});return status();
  }catch(Throwable e){String failure=error(e);trace("Router startup failed");android.util.Log.e("USBBridgeHotspot","Router startup: "+failure,e);stop();message="Раздача не запущена: "+failure;starting=false;return status();}}
 static String nativeConfig(int port){return "tunnel:\n  name: usbbridge\n  mtu: 1500\n  ipv4: 198.18.7.1\nsocks5:\n  address: 127.0.0.1\n  port: "+port+"\n  udp: tcp\nmisc:\n  max-session-count: 80\n  connect-timeout: 15000\n  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n  log-level: warn\n";}
 void writeDatapathConfig()throws IOException{
  NetworkSocksGateway gateway=vpnGateway;
  if(wifiVpnRequired&&gateway==null)throw new IOException("Ждём включения VPN на телефоне");
  String value=nativeConfig(gateway==null?pcSocksPort:gateway.port());
  if(gateway!=null)value=value.replace("  udp: tcp\n","  udp: tcp\n  username: 'usbbridge'\n  password: '"+gateway.password+"'\n");
  try(FileOutputStream out=new FileOutputStream(config)){out.write(Wire.bytes(value));}
 }
 void ensureDatapathRunning()throws Exception{
  if(tun==null||config==null)throw new IOException("Нет канала компьютера");
  if(wifiVpnRequired){if(vpnGateway==null)throw new IOException("Ждём включения VPN на телефоне");vpnGateway.route.check();}
  datapath.apply(routeEpoch,TProxyService::TProxyIsRunning,TProxyService::TProxyStopService,()->{
   writeDatapathConfig();
   if(!TProxyService.TProxyStartService(config.getAbsolutePath(),tun.getFd()))throw new IOException("Не удалось запустить сетевой модуль");
   Thread.sleep(150);
   if(wifiVpnRequired){if(vpnGateway==null)throw new IOException("VPN выключен во время переключения");vpnGateway.route.check();}
  },this::stopWifi);
 }
 interface CheckedOperation {void run()throws Exception;}
 static final class DatapathState {
  private long appliedEpoch=-1;
  void invalidate(){appliedEpoch=-1;}
  boolean applied(long requestedEpoch){return appliedEpoch==requestedEpoch;}
  void apply(long requestedEpoch,java.util.function.BooleanSupplier running,java.util.function.BooleanSupplier stop,CheckedOperation start,Runnable stopAccessPoint)throws Exception{
   try{if(applied(requestedEpoch)&&running.getAsBoolean())return;}
   catch(Exception|Error unavailable){invalidate();pauseForwarding(true,stop,running,stopAccessPoint);throw unavailable;}
   invalidate();
   if(!pauseForwarding(true,stop,running,stopAccessPoint))throw new IOException("Не удалось остановить прежний источник раздачи");
   try{
    start.run();
    if(!running.getAsBoolean())throw new IOException("Сетевой модуль завершился");
    appliedEpoch=requestedEpoch;
   }catch(Exception|Error failure){invalidate();pauseForwarding(true,stop,running,stopAccessPoint);throw failure;}
  }
 }
 void closeVpnGateway(){
  NetworkSocksGateway previous=vpnGateway;vpnGateway=null;if(previous!=null)previous.close();
  if(vpnObserver!=null){try{cm.unregisterNetworkCallback(vpnObserver);}catch(Exception ignored){}vpnObserver=null;}
 }
 void failVpnRoute(NetworkSocksGateway failed,String why){
  // Close sockets before obtaining the lifecycle lock: a long hotspot probe
  // must never postpone revocation of an obsolete VPN route.
  revokeRoute(this,failed,()->vpnGateway,failed::close,()->{
   datapath.invalidate();routeEpoch++;active=false;closeVpnGateway();
   boolean stopped=pauseForwarding(tun!=null,TProxyService::TProxyStopService,TProxyService::TProxyIsRunning,this::stopWifi);
   message=stopped?why:"Не удалось остановить прежний источник. Wi-Fi остановлен";trace("VPN revoked");
  });
 }
 static <T> void revokeRoute(Object lifecycleLock,T failed,java.util.function.Supplier<T> current,Runnable close,Runnable stop){
  // Closing the obsolete route is urgent, but stopping the AP is conditional.
  // A delayed onLost must not stop a newer route installed while we waited.
  close.run();
  synchronized(lifecycleLock){if(current.get()==failed)stop.run();}
 }
 boolean validVpn(Network selected,int ownerUid){
  NetworkCapabilities caps=cm.getNetworkCapabilities(selected);
  // ConnectivityService redacts the owner for callers other than that VPN.
  // The exact handle was reported by the selected, session-authenticated VPN
  // through Bridge's provider; never discover or substitute another VPN here.
  return validVpnCapabilities(caps,ownerUid);
 }
 static boolean validVpnCapabilities(NetworkCapabilities caps,int ownerUid){return caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&(caps.getOwnerUid()==ownerUid||caps.getOwnerUid()<0);}
 static boolean stopDatapathVerified(boolean hasTun,java.util.function.BooleanSupplier stop,java.util.function.BooleanSupplier running){
  if(!hasTun)return true;
  try{return stop.getAsBoolean()&&!running.getAsBoolean();}catch(Throwable unavailable){return false;}
 }
 static boolean pauseForwarding(boolean hasTun,java.util.function.BooleanSupplier stop,java.util.function.BooleanSupplier running,Runnable stopAccessPoint){
  // Closing the native reader is the barrier. It leaves the same TEST network,
  // AP association and DHCP lease intact; no packet can use a fallback source.
  // If native shutdown is unconfirmed, stop the radio as the safe fallback.
  boolean stopped=stopDatapathVerified(hasTun,stop,running);
  if(!stopped)stopAccessPoint.run();
  return stopped;
 }
 String blockWifiForVpn(){
  // Called with the router lifecycle lock, which also protects start/refresh.
  // An ACK is evidence of stopped forwarding, never merely of a stop request.
  datapath.invalidate();active=false;closeVpnGateway();
  boolean stopped=pauseForwarding(tun!=null,TProxyService::TProxyStopService,TProxyService::TProxyIsRunning,this::stopWifi);
  message=stopped?"Wi-Fi ждёт включения VPN":"Не удалось подтвердить остановку прежнего источника раздачи";
  return stopped?"BLOCKED:VPN_REQUIRED":"FAIL:VPN_BARRIER_NOT_CONFIRMED";
 }
 final class VpnRoute implements NetworkSocksGateway.Route{
  final Network selected;final int ownerUid;final long epoch;volatile long checked;volatile boolean invalid;
  VpnRoute(Network selected,int ownerUid){this.selected=selected;this.ownerUid=ownerUid;this.epoch=routeEpoch;}
  public void check()throws IOException{
   if(invalid||epoch!=routeEpoch||selected.getNetworkHandle()!=wifiVpnHandle||!wifiVpnRequired)throw new IOException("VPN route revoked");
   long now=System.nanoTime();
   if(now-checked>TimeUnit.MILLISECONDS.toNanos(250)){
    if(!validVpn(selected,ownerUid)){invalid=true;throw new IOException("VPN network is no longer available");}
    checked=now;
   }
  }
  public java.net.InetAddress resolve(String host)throws IOException{check();return selected.getByName(host);}
  public java.net.InetAddress dns()throws IOException{
   check();LinkProperties properties=cm.getLinkProperties(selected);
   if(properties==null||properties.getDnsServers().isEmpty())throw new IOException("VPN не сообщил DNS для раздачи");
   return properties.getDnsServers().get(0);
  }
  public java.net.Socket tcp(java.net.InetAddress address,int port)throws IOException{
   check();java.net.Socket socket=selected.getSocketFactory().createSocket();
   try{socket.connect(new java.net.InetSocketAddress(address,port),12000);check();return socket;}catch(IOException e){socket.close();throw e;}
  }
  public java.net.DatagramSocket udp(java.net.InetAddress address,int port)throws IOException{
   check();java.net.DatagramSocket socket=new java.net.DatagramSocket(null);
   try{selected.bindSocket(socket);socket.connect(address,port);check();return socket;}catch(IOException e){socket.close();throw e;}
  }
 }
 @Override public synchronized String setWifiVpn(String packageName,long handle,boolean required){
  pulse=System.nanoTime();packageName=packageName==null?"":packageName;
  if(required&&wifiVpnRequired&&handle<=0&&wifiVpnHandle==handle&&wifiVpnPackage.equals(packageName)&&vpnGateway==null)return blockWifiForVpn();
  if((tun==null||datapath.applied(routeEpoch))&&wifiVpnRequired==required&&(!required||(wifiVpnHandle==handle&&wifiVpnPackage.equals(packageName)&&vpnGateway!=null))){
   try{if(required)vpnGateway.route.check();if(tun!=null)ensureDatapathRunning();resumeObservedWifi();return required?"OK:VPN":"OK:PC";}catch(Exception ignored){}
  }
  // A source change invalidates established streams. In particular, traffic
  // cannot escape over the PC's direct route between VPN sessions.
  datapath.invalidate();routeEpoch++;active=false;wifiVpnRequired=required;wifiVpnHandle=handle;wifiVpnPackage=packageName;closeVpnGateway();
  if(required&&handle<=0)return blockWifiForVpn();
  try{
   if(!pauseForwarding(tun!=null,TProxyService::TProxyStopService,TProxyService::TProxyIsRunning,this::stopWifi))throw new IOException("Не удалось остановить прежний источник раздачи");
   if(required){
    if(handle<=0||packageName.isEmpty())throw new IOException("Включите VPN на телефоне — раздача ждёт его");
    int uid=ctx.getPackageManager().getApplicationInfo(packageName,0).uid;
    Network selected=Network.fromNetworkHandle(handle);
    if(!validVpn(selected,uid))throw new IOException("Сеть выбранного VPN уже недоступна");
    VpnRoute route=new VpnRoute(selected,uid);
    final NetworkSocksGateway gateway=new NetworkSocksGateway(route);vpnGateway=gateway;
    vpnObserver=new ConnectivityManager.NetworkCallback(){
     @Override public void onLost(Network lost){if(selected.equals(lost)){route.invalid=true;failVpnRoute(gateway,"Wi-Fi ждёт включения VPN");}}
     @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities caps){if(selected.equals(n)&&!validVpnCapabilities(caps,uid)){route.invalid=true;failVpnRoute(gateway,"Wi-Fi ждёт восстановления VPN");}}
    };
    cm.registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(),vpnObserver);
    route.check();
   }
   if(tun!=null&&config!=null)ensureDatapathRunning();
   resumeObservedWifi();
   message=active?(required?"Wi-Fi включён · через VPN":"Wi-Fi включён · интернет компьютера"):(required?"VPN телефона выбран для раздачи Wi-Fi":"Интернет компьютера выбран для раздачи Wi-Fi");trace("Source applied");
   return required?"OK:VPN":"OK:PC";
  }catch(Exception failure){datapath.invalidate();closeVpnGateway();pauseForwarding(tun!=null,TProxyService::TProxyStopService,TProxyService::TProxyIsRunning,this::stopWifi);message=error(failure);android.util.Log.e("USBBridgeHotspot","Source switch failed: "+message,failure);return "WAIT:"+message;}
 }
 void resumeObservedWifi(){active=tun!=null&&datapath.applied(routeEpoch)&&wifi&&ownsWifi&&iface.equals(upstream)&&(!wifiVpnRequired||vpnGateway!=null);}
 void startWifi()throws Exception{Class<?> b=Class.forName("android.net.TetheringManager$TetheringRequest$Builder");Object builder=b.getConstructor(int.class).newInstance(0);Object req=b.getMethod("build").invoke(builder);Class<?> rc=Class.forName("android.net.TetheringManager$TetheringRequest"),cc=Class.forName("android.net.TetheringManager$StartTetheringCallback");CountDownLatch latch=new CountDownLatch(1);int[] result={-1};Object cb=proxy(cc,(m,a)->{if(m.equals("onTetheringStarted")){result[0]=0;latch.countDown();}else if(m.equals("onTetheringFailed")){result[0]=((Number)a[0]).intValue();latch.countDown();}});call(tether,"startTethering",new Class<?>[]{rc,Executor.class,cc},req,callbacks,cb);if(!latch.await(15,TimeUnit.SECONDS)||result[0]!=0)throw new IOException("Ошибка запуска Wi-Fi: "+result[0]);}
 void stopWifi(){if(!ownsWifi)return;try{call(tether,"stopTethering",new Class<?>[]{int.class},0);}catch(Exception e){message="Не удалось остановить Wi-Fi: "+error(e);}}
 void preferPcNetwork()throws Exception{
  // Keep the preference continuous for the lifetime of our TEST network.
  // false->true does not trigger upstream selection in Android and creates
  // a window in which an unrelated network event can select another source.
  call(tether,"setPreferTestNetworks",new Class<?>[]{boolean.class},true);
 }
 static void publishPreferredTestNetwork(Object tether,Object test,LinkProperties properties,IBinder lifetime)throws Exception{
  // USB tethering may already keep Android's upstream monitor alive. Its
  // preference setter only changes a flag: it does not re-evaluate a network
  // published earlier. Wait for that flag before the TEST availability event,
  // as AOSP's EthernetTetheringTestBase.createTestUpstream does. Otherwise the
  // first plain hotspot can wait for an unrelated VPN/default-network event.
  // Router.stop() restores the preference after teardown, also on start error.
  call(tether,"setPreferTestNetworks",new Class<?>[]{boolean.class},true);
  call(test,"setupTestNetwork",new Class<?>[]{LinkProperties.class,boolean.class,IBinder.class},properties,true,lifetime);
 }
 void restartWifi()throws Exception{
  // The user explicitly requested USB Bridge's hotspot. Only from this point
  // may cleanup stop Wi-Fi tethering; plain phone internet must leave it alone.
  ownsWifi=true;
  trace("Stop previous AP");
  stopWifi();long off=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
  while(wifi&&System.nanoTime()<off)Thread.sleep(100);
  if(wifi)throw new IOException("Android не остановил прежнюю точку доступа");
  trace("Request Android AP start");
  startWifi();
  trace("Android AP start acknowledged");
 }
 @Override public synchronized String stop(){datapath.invalidate();synchronized(networkStateLock){networkEpoch++;}routeEpoch++;validationEpoch++;active=false;starting=false;closeVpnGateway();setVpnDefaultPackages(new String[0]);stopWifi();try{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(ownsWifi&&wifi&&System.nanoTime()<until)Thread.sleep(100);if(ownsWifi&&wifi){message="Выключите точку доступа в настройках. Канал сохранён до её остановки";return status();}ownsWifi=false;vpnPackage="";wifiVpnRequired=false;wifiVpnHandle=0;wifiVpnPackage="";try{TProxyService.TProxyStopService();}catch(Throwable ignored){}if(network!=null){call(test,"teardownTestNetwork",new Class<?>[]{Network.class},network);network=null;}if(keep!=null){cm.unregisterNetworkCallback(keep);keep=null;}if(tun!=null){tun.close();tun=null;}call(tether,"setPreferTestNetworks",new Class<?>[]{boolean.class},false);observationEpoch++;stopObservingAssociations();if(observer!=null){call(tether,"unregisterTetheringEventCallback",new Class<?>[]{Class.forName("android.net.TetheringManager$TetheringEventCallback")},observer);observer=null;}wifi=false;wifiClients=clientTracker.reset();upstream="";if(owner!=null&&death!=null)owner.unlinkToDeath(death,0);owner=null;if(config!=null)config.delete();iface="";message="Раздача выключена";}catch(Exception e){message="Ошибка остановки: "+error(e);}return status();}
 @Override public synchronized String setVpnDefaultPackages(String[] packages){
  try{
   java.lang.reflect.Method setter=cm.getClass().getMethod("setVpnDefaultForUids",String.class,Collection.class);
   if(packages!=null&&packages.length==0){
    if(!preferredSession.isEmpty())setter.invoke(cm,preferredSession,java.util.Collections.emptyList());
    preferredSession="";return "OK:0";
   }
   Collection<android.util.Range<Integer>> ranges=new ArrayList<>();
   String session="USB Bridge";long preferredNetwork=0;
   if(packages==null){
    // App UIDs have no default network while the physical underlay is a TEST
    // network. Give them the VPN as their default, but keep our own socket relay
    // outside the VPN to avoid tunnelling it back into itself.
    int bridgeUid=ctx.getPackageManager().getApplicationInfo("local.usbbridge",0).uid;
    NetworkCapabilities vpn=null;
    for(Network candidate:cm.getAllNetworks()){
     NetworkCapabilities caps=cm.getNetworkCapabilities(candidate);
     if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){vpn=caps;preferredNetwork=candidate.getNetworkHandle();break;}
    }
    if(vpn==null)return "WAIT:VPN_NETWORK";
    Object transport=vpn.getTransportInfo();
    if(transport==null)return "WAIT:VPN_SESSION";
    session=(String)transport.getClass().getMethod("getSessionId").invoke(transport);
    if(session==null||session.isEmpty())return "WAIT:VPN_SESSION";
    @SuppressWarnings("unchecked") Set<android.util.Range<Integer>> allowed=
        (Set<android.util.Range<Integer>>)vpn.getClass().getMethod("getUids").invoke(vpn);
    if(allowed==null)return "WAIT:VPN_UIDS";
    for(android.util.Range<Integer> entry:allowed){
     int first=Math.max(10000,entry.getLower()),last=Math.min(99999,entry.getUpper());
     if(first>last)continue;
     if(first<bridgeUid&&bridgeUid<=last){ranges.add(android.util.Range.create(first,bridgeUid-1));first=bridgeUid+1;}
     if(first==bridgeUid)first++;
     if(first<=last)ranges.add(android.util.Range.create(first,last));
    }
    if(ranges.isEmpty())return "WAIT:NO_APP_UIDS";
   }else for(String name:packages){int uid=ctx.getPackageManager().getApplicationInfo(name,0).uid;ranges.add(android.util.Range.create(uid,uid));}
   if(!preferredSession.isEmpty()&&!preferredSession.equals(session))setter.invoke(cm,preferredSession,java.util.Collections.emptyList());
   setter.invoke(cm,session,ranges);preferredSession=session;
   return "OK:"+ranges.size()+"|NETWORK:"+preferredNetwork;
  }catch(Exception e){return "FAIL:"+error(e);}
 }
 @Override public synchronized String setWifi(boolean enabled){
  if(tun==null)return "STOPPED|Сначала подключите компьютер";
  configuring=true;pulse=System.nanoTime();
  String stage=enabled?"datapath":"stop";
  try{
   if(enabled){
    if(wifiVpnRequired&&vpnGateway==null){blockWifiForVpn();return status();}
    active=false;trace("Check native datapath");ensureDatapathRunning();
    // Switching PC/VPN replaces only native forwarding. An already-associated
    // client keeps its radio and lease, rather than waiting for another AP/DHCP
    // cycle. Repeated application of the same policy is idempotent too.
    if(wifi&&ownsWifi&&iface.equals(upstream)){
     active=true;message=wifiVpnRequired?"Wi-Fi включён · через VPN":"Wi-Fi включён · интернет компьютера";
     trace("Reuse Android AP");return status();
    }
    stage="upstream-preference";message="Выбираем кабель как источник Wi-Fi";trace("Apply upstream preference");preferPcNetwork();
    stage="ap-start";if(wifi)restartWifi();else{ownsWifi=true;startWifi();}
    stage="upstream-selection";long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
    while(System.nanoTime()<end&&(!wifi||!iface.equals(upstream)))Thread.sleep(100);
    trace("Upstream selection completed");
    if(!wifi||!iface.equals(upstream))throw new IOException("Android не выбрал интернет ПК");
    stage="route-validation";
    if(wifiVpnRequired){if(vpnGateway==null)throw new IOException("VPN выключен во время запуска раздачи");vpnGateway.route.check();}
    if(!TProxyService.TProxyIsRunning())throw new IOException("Сетевой модуль остановился");
    active=true;message="Точка Wi-Fi включена. Проверяем доступ к сайтам…";trace("AP route ready");verifyHotspotAsync();
   }else{
    validationEpoch++;active=false;trace("Stop requested");stopWifi();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
    while(ownsWifi&&wifi&&System.nanoTime()<end)Thread.sleep(100);
    if(ownsWifi&&wifi)throw new IOException("Выключите точку доступа в настройках");
    ownsWifi=false;message="Раздача остановлена";trace("AP stopped");
   }
  }catch(Exception e){
   active=false;trace("Failed stage="+stage);android.util.Log.e("USBBridgeHotspot","Wi-Fi stage="+stage+": "+error(e),e);
   if(enabled&&"datapath".equals(stage)&&wifi&&ownsWifi&&iface.equals(upstream)){
    datapath.invalidate();closeVpnGateway();pauseForwarding(tun!=null,TProxyService::TProxyStopService,TProxyService::TProxyIsRunning,this::stopWifi);
    message=wifiVpnRequired?"Wi-Fi ждёт восстановления VPN":"Восстанавливаем интернет для Wi-Fi";
   }else{stopWifi();message="Раздача не включена: "+error(e);}
  }finally{configuring=false;pulse=System.nanoTime();}return status();
 }
 void verifyHotspotAsync(){
  final Network checkedNetwork=network;final long checkedRoute=routeEpoch,checkedValidation=++validationEpoch;final boolean required=wifiVpnRequired;
  Wire.run(()->{
   String result=probe();android.util.Log.i("USBBridgeHotspot","Datapath probe: "+result);
   synchronized(RouterService.this){
    if(!active||network!=checkedNetwork||routeEpoch!=checkedRoute||validationEpoch!=checkedValidation||wifiVpnRequired!=required)return;
    // The site's availability says nothing about Android's AP lifecycle. Keep
    // a valid route up when a probe is censored, busy or temporarily unavailable.
    if(result.startsWith("PASS"))message=required?"Точка Wi-Fi включена · интернет через VPN телефона. Подключите другое устройство для проверки.":"Точка Wi-Fi включена · источник — компьютер. Подключите другое устройство для проверки.";
    else message="Точка Wi-Fi включена. Проверка сайта пока не прошла; проверьте интернет на подключённом устройстве.";
   }
  });
 }
 // Do not use OEM per-UID preferences: TEST-derived VPN capabilities can select
 // the VPN itself and crash Android during network removal. Compatible clients
 // bind their own sockets/process to this explicit Network instead.
 @Override public synchronized String configureVpn(String packageName){
  try{if(!packageName.isEmpty()){if(tun==null)throw new IOException("Нет канала ПК");android.content.pm.ApplicationInfo app=ctx.getPackageManager().getApplicationInfo(packageName,android.content.pm.PackageManager.GET_META_DATA);if(app.metaData==null||app.metaData.getInt("local.usbbridge.UNDERLAY_API",0)!=1)throw new IOException("Эта версия VPN не поддерживает USB Bridge");}vpnPackage=packageName;return "OK";}catch(Exception e){return "FAIL: "+error(e);}
 }
 @Override public long networkHandle(){pulse=System.nanoTime();return network==null?0:network.getNetworkHandle();}
 @Override public synchronized String refreshDatapath(){
  if(tun==null||config==null)return "STOPPED";resetting=true;active=false;validationEpoch++;datapath.invalidate();
  try{ensureDatapathRunning();resumeObservedWifi();return "OK";}catch(Exception e){datapath.invalidate();active=false;pauseForwarding(true,TProxyService::TProxyStopService,TProxyService::TProxyIsRunning,this::stopWifi);return "FAIL: "+error(e);}finally{resetting=false;}
 }
 @Override public String probe(){
  Network n=network;if(n==null)return "FAIL: нет сети раздачи";
  final long routeHandle=wifiVpnHandle,checkedRoute=routeEpoch;final boolean required=wifiVpnRequired;
  if(required&&vpnGateway==null)return "FAIL: ждём VPN телефона";
  Exception last=null;
  for(String[] target:new String[][]{{"www.gstatic.com","/generate_204","204"},{"example.com","/","200"}}){
   try(java.net.Socket socket=n.getSocketFactory().createSocket()){
    socket.connect(new java.net.InetSocketAddress(n.getByName(target[0]),443),12000);socket.setSoTimeout(12000);
    try(javax.net.ssl.SSLSocket tls=(javax.net.ssl.SSLSocket)((javax.net.ssl.SSLSocketFactory)javax.net.ssl.SSLSocketFactory.getDefault()).createSocket(socket,target[0],443,true)){
     tls.setSoTimeout(12000);javax.net.ssl.SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);tls.startHandshake();
     tls.getOutputStream().write(Wire.bytes("GET "+target[1]+" HTTP/1.1\r\nHost: "+target[0]+"\r\nConnection: close\r\n\r\n"));
     String line=new BufferedReader(new InputStreamReader(tls.getInputStream())).readLine();
     if(line==null||!line.startsWith("HTTP/1.")||!line.contains(" "+target[2]+" "))throw new IOException("Ответ "+line);
     if(checkedRoute!=routeEpoch||network!=n||required!=wifiVpnRequired||routeHandle!=wifiVpnHandle)throw new IOException("Источник изменился во время проверки");if(required){if(vpnGateway==null)throw new IOException("VPN выключен");vpnGateway.route.check();}
     long[] stats=TProxyService.TProxyGetStats();return "PASS native test TUN HTTPS "+target[0]+" "+target[2]+"; tx="+stats[0]+" rx="+stats[2];
    }
   }catch(Exception e){last=e;}
  }
  return "FAIL "+(last==null?"нет ответа":error(last));
 }
 @Override public String status(){pulse=System.nanoTime();boolean forwarding=active&&wifi&&iface.equals(upstream);return (forwarding?"ACTIVE":starting?"STARTING":tun!=null?"READY":"STOPPED")+"|"+message+"|"+iface+"|"+upstream+"|"+vpnPackage+"|"+(wifiVpnRequired?"VPN:"+wifiVpnHandle:"PC")+"|"+HotspotClients.json(wifi,forwarding,wifiVpnRequired,wifiClients,ownsWifi,wifiVpnRequired&&vpnGateway==null);}
 @Override public void destroy(){stop();if(tun==null)System.exit(0);}
}
