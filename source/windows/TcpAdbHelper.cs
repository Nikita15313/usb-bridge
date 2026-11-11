using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Net.NetworkInformation;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading;

namespace UsbBridge {
// Created only from the successfully authenticated RNDIS data connection. There
// is no address box, discovery scan, remembered target or MIDI-to-IP fallback.
internal sealed class TcpAdbCableTarget {
 internal readonly IPAddress Source,Peer;internal readonly int Index;internal readonly string InterfaceId;
 TcpAdbCableTarget(IPAddress source,IPAddress peer,int index,string id){Source=source;Peer=peer;Index=index;InterfaceId=id;}
 internal static TcpAdbCableTarget FromAuthenticatedLink(IPAddress source,IPAddress peer,IPAddress mask,int index,string id,bool authenticatedUsb){
  if(!authenticatedUsb||index<=0||String.IsNullOrEmpty(id)||source==null||peer==null||mask==null||source.AddressFamily!=AddressFamily.InterNetwork||peer.AddressFamily!=AddressFamily.InterNetwork||mask.AddressFamily!=AddressFamily.InterNetwork||IPAddress.IsLoopback(source)||IPAddress.IsLoopback(peer)||source.Equals(peer))return null;
  byte[] local=source.GetAddressBytes(),remote=peer.GetAddressBytes(),bits=mask.GetAddressBytes();bool hostZero=true,hostOnes=true,seenZero=false,hasNetwork=false;
  if(local[0]==0||local[0]>=224||remote[0]==0||remote[0]>=224)return null;
  for(int n=0;n<4;n++){if((local[n]&bits[n])!=(remote[n]&bits[n]))return null;int host=remote[n]&(~bits[n]&255);hostZero&=host==0;hostOnes&=host==(~bits[n]&255);for(int bit=7;bit>=0;bit--){bool one=(bits[n]&(1<<bit))!=0;if(one){if(seenZero)return null;hasNetwork=true;}else seenZero=true;}}
  if(!hasNetwork||!seenZero||hostZero||hostOnes)return null;return new TcpAdbCableTarget(source,peer,index,id);
 }
 internal bool SameCurrentInterface(string id,int index,IPAddress address,bool up){return up&&index==Index&&String.Equals(id,InterfaceId,StringComparison.OrdinalIgnoreCase)&&Source.Equals(address);}
 internal bool InterfaceCurrent(){try{foreach(NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces()){if(ni.OperationalStatus!=OperationalStatus.Up||!String.Equals(ni.Id,InterfaceId,StringComparison.OrdinalIgnoreCase))continue;IPInterfaceProperties properties=ni.GetIPProperties();if(properties.GetIPv4Properties().Index!=Index)continue;foreach(UnicastIPAddressInformation address in properties.UnicastAddresses)if(SameCurrentInterface(ni.Id,Index,address.Address,true))return true;}}catch{}return false;}
}
internal delegate Socket TcpAdbConnector(TcpAdbCableTarget target,HelperSessionAlive alive);
internal delegate TcpAdbForwarder TcpAdbForwarderFactory(TcpAdbCableTarget target,HelperSessionAlive alive);

// ADB itself only contacts this loopback proxy. The remote socket is forced
// onto the authenticated USB interface, even if the office LAN overlaps it.
internal sealed class TcpAdbForwarder:IDisposable {
 readonly object gate=new object();readonly TcpListener listener;readonly TcpAdbCableTarget target;readonly HelperSessionAlive alive;readonly TcpAdbConnector connector;
 readonly List<Socket> sockets=new List<Socket>();readonly Thread accepting;readonly Timer watcher;readonly Stopwatch lifetime=Stopwatch.StartNew();int disposed,accepted;
 internal readonly string Serial;
 internal TcpAdbForwarder(TcpAdbCableTarget target,HelperSessionAlive alive):this(target,alive,ConnectBound){}
 internal TcpAdbForwarder(TcpAdbCableTarget target,HelperSessionAlive alive,TcpAdbConnector connector){
  if(target==null||alive==null||connector==null||!alive())throw new IOException("Нет текущего подтверждённого подключения USB-модема.");
  this.target=target;this.alive=alive;this.connector=connector;listener=new TcpListener(IPAddress.Loopback,0);listener.Start(2);Serial="127.0.0.1:"+((IPEndPoint)listener.LocalEndpoint).Port.ToString(System.Globalization.CultureInfo.InvariantCulture);
  accepting=new Thread(Accept);accepting.IsBackground=true;accepting.Name="USB Bridge cable ADB";
  bool boundConnector=connector==new TcpAdbConnector(ConnectBound);
  watcher=new Timer(delegate{if(!IsAlive()||(boundConnector&&!target.InterfaceCurrent()))Dispose();},null,250,250);
  try{accepting.Start();}catch{Dispose();throw;}
 }
 bool IsAlive(){try{return Thread.VolatileRead(ref disposed)==0&&lifetime.ElapsedMilliseconds<180000&&alive();}catch{return false;}}
 internal bool Running{get{return IsAlive();}}
 bool Track(Socket value){lock(gate){if(!IsAlive())return false;sockets.Add(value);return true;}}
 void ClosePair(Socket local,Socket remote){lock(gate){if(local!=null)sockets.Remove(local);if(remote!=null)sockets.Remove(remote);}try{if(local!=null)local.Close();}catch{}try{if(remote!=null)remote.Close();}catch{}}
 void Accept(){try{while(IsAlive()){
   if(!listener.Pending()){Thread.Sleep(25);continue;}Socket local=listener.AcceptSocket();
   if(++accepted>8||!Track(local)){local.Close();continue;}
   lock(gate){if(sockets.Count>4){sockets.Remove(local);local.Close();continue;}}
   Thread connection=new Thread(delegate(){Socket remote=null;try{remote=connector(target,IsAlive);if(!Track(remote))throw new IOException("USB disconnected");local.NoDelay=remote.NoDelay=true;int remaining=2;Socket captured=remote;ThreadStart ended=delegate{if(Interlocked.Decrement(ref remaining)==0)ClosePair(local,captured);};
     Thread toPhone=new Thread(delegate(){Pump(local,captured,ended);});toPhone.IsBackground=true;toPhone.Start();Pump(captured,local,ended);
    }catch{ClosePair(local,remote);}});connection.IsBackground=true;connection.Start();
  }}catch(SocketException){}catch(ObjectDisposedException){}finally{Dispose();}}
 void Pump(Socket source,Socket destination,ThreadStart ended){try{byte[] buffer=new byte[16384];for(;;){int count=source.Receive(buffer);if(count==0){try{destination.Shutdown(SocketShutdown.Send);}catch{}break;}int at=0;while(at<count){int sent=destination.Send(buffer,at,count-at,SocketFlags.None);if(sent<=0)throw new IOException("ADB cable closed");at+=sent;}}}catch{try{source.Close();}catch{}try{destination.Close();}catch{}}finally{ended();}}
 internal static Socket ConnectBound(TcpAdbCableTarget target,HelperSessionAlive alive){
  if(target==null||target.Index<=0||!alive()||!target.InterfaceCurrent())throw new IOException("USB-подключение изменилось.");Socket socket=new Socket(AddressFamily.InterNetwork,SocketType.Stream,ProtocolType.Tcp);
  try{socket.SetSocketOption(SocketOptionLevel.IP,(SocketOptionName)31,IPAddress.HostToNetworkOrder(target.Index));socket.Bind(new IPEndPoint(target.Source,0));socket.NoDelay=true;
   IAsyncResult operation=socket.BeginConnect(new IPEndPoint(target.Peer,5555),null,null);Stopwatch timeout=Stopwatch.StartNew();using(WaitHandle wait=operation.AsyncWaitHandle){while(!wait.WaitOne(50,false)){if(!alive()||timeout.ElapsedMilliseconds>=3000)throw new IOException("Служебный доступ телефона не отвечает. На ПК с работающей USB-отладкой откройте «Подключение» → «Служебный доступ».");}socket.EndConnect(operation);}
   if(!alive()||!target.InterfaceCurrent()||!((IPEndPoint)socket.LocalEndPoint).Address.Equals(target.Source))throw new IOException("USB-подключение изменилось.");return socket;
  }catch{socket.Close();throw;}
 }
 public void Dispose(){if(Interlocked.Exchange(ref disposed,1)!=0)return;if(watcher!=null)watcher.Dispose();try{listener.Stop();}catch{}lock(gate){foreach(Socket socket in sockets)try{socket.Close();}catch{}sockets.Clear();}}
}

internal static class TcpAdbHelper {
 internal const string MissingUsb="ADB запущен, но телефон по USB не найден.";
 internal static bool ShouldFallback(TcpAdbCableTarget target,string usbResult){return target!=null&&usbResult!=null&&usbResult.StartsWith(MissingUsb,StringComparison.Ordinal);}
 internal static bool IsLoopbackSerial(string value){if(value==null||!value.StartsWith("127.0.0.1:",StringComparison.Ordinal))return false;int port;return int.TryParse(value.Substring(10),System.Globalization.NumberStyles.None,System.Globalization.CultureInfo.InvariantCulture,out port)&&port>0&&port<=65535&&value=="127.0.0.1:"+port.ToString(System.Globalization.CultureInfo.InvariantCulture);}
 internal static string SelectExact(string listing,string serial){
  string state=State(listing,serial);if(state=="")throw new IOException("Для VPN и Wi-Fi нужен служебный доступ телефона. На ПК с работающей USB-отладкой: «Подключение» → «Служебный доступ» → «Разрешить доступ».");
  if(state=="unauthorized")throw new IOException("На телефоне разрешите USB-отладку для этого компьютера. Затем повторите подключение.");
  if(state!="device")throw new IOException("Телефон ещё не готов к служебному подключению. Разблокируйте его и повторите попытку.");return serial;
 }
 static string State(string listing,string serial){if(!IsLoopbackSerial(serial))throw new IOException("Недопустимый служебный адрес.");int matches=0;string state="";foreach(string raw in (listing??"").Split('\n')){string[] fields=System.Text.RegularExpressions.Regex.Split(raw.Trim(),"\\s+");if(fields.Length>=2&&fields[0]==serial){matches++;state=fields[1];}}if(matches>1)throw new IOException("Служебное подключение неоднозначно.");return state;}
 internal static string TryStart(HelperAdbLocation adb,HelperProcessRunner run,HelperAdbPortAvailable available,HelperSessionAlive alive,TcpAdbCableTarget target,Action<string> progress){return TryStart(adb,run,available,alive,target,progress,45000);}
 internal static string TryStart(HelperAdbLocation adb,HelperProcessRunner run,HelperAdbPortAvailable available,HelperSessionAlive alive,TcpAdbCableTarget target,Action<string> progress,int authorizationBudget){return TryStart(adb,run,available,alive,target,progress,authorizationBudget,delegate(TcpAdbCableTarget t,HelperSessionAlive a){return new TcpAdbForwarder(t,a);});}
 internal static string TryStart(HelperAdbLocation adb,HelperProcessRunner run,HelperAdbPortAvailable available,HelperSessionAlive alive,TcpAdbCableTarget target,Action<string> progress,int authorizationBudget,TcpAdbForwarderFactory create){
  if(adb.Executable==null)return adb.Problem;HelperProcessRunner client=AdbHelper.ClientRunner(adb,run,available);string serial=null;
  try{using(TcpAdbForwarder forward=create(target,alive)){
   HelperSessionAlive current=delegate{return alive()&&forward.Running;};
   serial=forward.Serial;
   // Forget only a possible old entry for our newly allocated loopback port.
   client(adb.Executable,new string[]{"disconnect",serial},1500);
   if(!current())throw new IOException("Кабель отключён.");HelperProcessResult connect=client(adb.Executable,new string[]{"connect",serial},5000);if(connect==null||connect.TimedOut||!current())throw new IOException("Служебное подключение не ответило вовремя. Для проверки откройте «Подключение» → «Служебный доступ» на ПК с работающей USB-отладкой.");
   string listing=AdbHelper.CheckedCall(adb.Executable,client,current,4000,"devices","-l").Output;string state=State(listing,serial);
   if(state=="unauthorized"||state=="offline"||(state==""&&connect.ExitCode==0)){
    bool notified=false;long absentSince=state==""?0:-1;Stopwatch authorization=Stopwatch.StartNew();
    while(current()&&authorization.ElapsedMilliseconds<Math.Min(45000,Math.Max(0,authorizationBudget))){
     if((state=="unauthorized"||state=="offline")&&!notified){notified=true;if(progress!=null)progress("Разрешите USB-отладку на телефоне. После подтверждения продолжим автоматически.");}
     if(state==""&&absentSince>=0&&authorization.ElapsedMilliseconds-absentSince>=1500)break;
     Thread.Sleep(250);listing=AdbHelper.CheckedCall(adb.Executable,client,current,4000,"devices","-l").Output;state=State(listing,serial);if(state=="device")break;if(state==""){if(absentSince<0)absentSince=authorization.ElapsedMilliseconds;}else{absentSince=-1;if(state!="unauthorized"&&state!="offline")break;}
    }
   }
   if(!current())throw new IOException("Кабель отключён. Подключение помощника отменено.");
   string exact=SelectExact(listing,serial);
   if(AdbHelper.CheckedCall(adb.Executable,client,current,4000,"-s",exact,"get-serialno").Output.Trim()!=serial)throw new IOException("Служебное USB-подключение изменилось.");
   return AdbHelper.StartSelected(adb.Executable,client,current,exact);
  }}catch(IOException error){return error.Message;}catch(Exception){return "Не удалось запустить служебный доступ через USB-модем. Для проверки откройте «Подключение» → «Служебный доступ» на ПК с работающей USB-отладкой.";}
  finally{if(serial!=null)try{client(adb.Executable,new string[]{"disconnect",serial},1500);}catch{}}
 }
 // Called exclusively from an explicit stopped-UI confirmation, never fallback.
 internal static string SetPreparedMode(HelperAdbLocation adb,HelperProcessRunner run,HelperAdbPortAvailable available,HelperSessionAlive alive,bool enable,bool confirmed){
  return SetPreparedMode(adb,run,available,alive,enable,confirmed,12000);
 }
 internal static string SetPreparedMode(HelperAdbLocation adb,HelperProcessRunner run,HelperAdbPortAvailable available,HelperSessionAlive alive,bool enable,bool confirmed,int verificationBudget){
  if(!confirmed)throw new IOException("Изменение служебного доступа не разрешено пользователем.");if(adb.Executable==null)return adb.Problem;HelperProcessRunner client=AdbHelper.ClientRunner(adb,run,available);
  try{string serial=AdbHelper.SelectUsbCandidate(AdbHelper.CheckedCall(adb.Executable,client,alive,5000,"devices","-l").Output);string physical=AdbHelper.CheckedCall(adb.Executable,client,alive,4000,"-d","get-serialno").Output.Trim();if(physical!=serial)throw new IOException("Оставьте один телефон, разрешённый для USB-отладки.");
   string previous=AdbHelper.CheckedCall(adb.Executable,client,alive,4000,"-s",serial,"shell","getprop","service.adb.tcp.port").Output.Trim();
   string persistent=AdbHelper.CheckedCall(adb.Executable,client,alive,4000,"-s",serial,"shell","getprop","persist.adb.tcp.port").Output.Trim(),addresses=AdbHelper.CheckedCall(adb.Executable,client,alive,4000,"-s",serial,"shell","getprop","service.adb.listen_addrs").Output.Trim();
   if((persistent!=""&&persistent!="0"&&persistent!="-1")||addresses!="")return "На телефоне уже настроен другой способ сетевой отладки. Он не изменён; настройки служебного доступа не менялись.";
   if(enable){string secure=AdbHelper.CheckedCall(adb.Executable,client,alive,4000,"-s",serial,"shell","getprop","ro.adb.secure").Output.Trim();if(secure!="1")return "На этой сборке Android защита сетевой отладки ключом не подтверждена. Режим не включён.";}
   if(previous!=""&&previous!="0"&&previous!="-1"&&previous!="5555")return "На телефоне уже настроен другой порт сетевой отладки. Он не изменён.";
   if((enable&&previous=="5555")||(!enable&&(previous==""||previous=="0"||previous=="-1")))return enable?"Служебный доступ уже включён. Нажмите «Подключить». Связь с другим ПК ещё нужно проверить.":"Наш режим порта 5555 не включён. Другие настройки отладки не менялись.";
   AdbHelper.CheckedCall(adb.Executable,client,alive,6000,enable?new string[]{"-s",serial,"tcpip","5555"}:new string[]{"-s",serial,"usb"});
   Stopwatch wait=Stopwatch.StartNew();while(alive()&&wait.ElapsedMilliseconds<Math.Min(12000,Math.Max(0,verificationBudget))){Thread.Sleep(250);HelperProcessResult state=client(adb.Executable,new string[]{"-s",serial,"get-state"},1000);if(state==null||state.ExitCode!=0||state.Output.Trim()!="device")continue;HelperProcessResult check=client(adb.Executable,new string[]{"-s",serial,"shell","getprop","service.adb.tcp.port"},1000);if(check==null||check.ExitCode!=0)continue;string actual=check.Output.Trim();if(enable?actual=="5555":(actual==""||actual=="0"||actual=="-1"))return enable?"Настройка Android подтверждена. Теперь на рабочем ПК достаточно нажать «Подключить» через USB-модем. Связь с рабочим ПК ещё нужно проверить. Android может отключить доступ после перезагрузки телефона.":"Наш режим порта 5555 выключен и проверен. Другие способы отладки не изменялись.";}
   return "Команда отправлена, но результат пока не подтверждён. Разблокируйте телефон, разрешите отладку и повторите проверку.";
  }catch(IOException error){return error.Message;}catch(Exception){return "Служебный доступ не подтверждён. Подключите телефон к ПК с работающей USB-отладкой и повторите проверку.";}
 }
 internal static string SetPreparedMode(bool enable,HelperSessionAlive alive){return SetPreparedMode(AdbHelper.LocateAdb(),AdbHelper.RunProcess,delegate{return AdbHelper.CheckAdbPort(AdbHelper.BundledServerPort);},alive,enable,true);}
}
}
