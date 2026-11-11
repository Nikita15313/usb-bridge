using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Collections.Generic;
using UsbBridge;

class TcpAdbHelperTests {
 static int passed;
 static void Check(bool condition,string name){if(!condition)throw new Exception(name);passed++;Console.WriteLine("PASS "+name);}
 delegate void TestAction();
 static bool Rejects(TestAction action){try{action();return false;}catch(IOException){return true;}}
 static TcpAdbCableTarget Target(){return TcpAdbCableTarget.FromAuthenticatedLink(IPAddress.Parse("192.0.2.2"),IPAddress.Parse("192.0.2.1"),IPAddress.Parse("255.255.255.0"),777,"owned-test-usb",true);}
 static TcpAdbForwarder FixtureForwarder(TcpAdbCableTarget target,HelperSessionAlive alive){return new TcpAdbForwarder(target,alive,delegate{throw new Exception("Fake ADB must never create a remote connection");});}
 sealed class Fake {
  internal const string UsbSerial="test-phone-156",Apk="/data/app/~~TESTONLY-123/moe.shizuku.privileged.api-TESTONLY-123/base.apk";
  internal readonly List<string[]> Calls=new List<string[]>();internal readonly List<int> Budgets=new List<int>();
  internal string Alias="",PhonePort="0",Persistent="",Listen="",UsbState="device",Debuggable="0",AdbSecure="1";internal bool Alive=true,Network=true,Bundled,Launched,Readable=true,ConnectTimeout;internal int Mutations,Devices,CancelOnDevices=-1,FactoryCount;
  internal string[] States={"device"};
  internal HelperProcessResult Run(string exe,string[] arguments,int timeout){
   if(exe!="fake-adb.exe")throw new Exception("Unknown test executable");Calls.Add(arguments);Budgets.Add(timeout);string[] a=arguments;
   if(a.Length>=2&&a[0]=="-P"){if(a[1]!="5038")throw new Exception("Wrong isolated server");a=new string[arguments.Length-2];Array.Copy(arguments,2,a,0,a.Length);}else if(Bundled)throw new Exception("Bundled call escaped isolated server");
   string output="";
   if(a[0]=="connect"){if(a.Length!=2||!TcpAdbHelper.IsLoopbackSerial(a[1]))throw new Exception("Network ADB escaped loopback");Alias=a[1];if(ConnectTimeout)return new HelperProcessResult{TimedOut=true};}
   else if(a[0]=="disconnect"){if(a.Length!=2||!TcpAdbHelper.IsLoopbackSerial(a[1]))throw new Exception("Non-targeted disconnect");if(Alias=="")Alias=a[1];if(a[1]!=Alias)throw new Exception("Alias changed");}
   else if(a[0]=="devices"){
    Devices++;if(Network){string state=States[Math.Min(Devices-1,States.Length-1)];output="List of devices attached\nother-real-phone\tdevice\n192.0.2.99:5555\tdevice\n"+(state==""?"":Alias+"\t"+state+"\n");}else output="List of devices attached\n"+(UsbState==""?"":UsbSerial+"\t"+UsbState+"\n");
    if(Devices==CancelOnDevices)Alive=false;
   }else if(a[0]=="-d"){if(Network)throw new Exception("Network path used USB-only selector");output=UsbSerial;}
   else if(a[0]=="-s"){
    if(a[1]!=(Network?Alias:UsbSerial))throw new Exception("ADB command targeted another phone");
    if(a[2]=="get-serialno")output=Network?Alias:UsbSerial;
    else if(a[2]=="get-state")return new HelperProcessResult{ExitCode=Readable?0:1,Output=Readable?"device":""};
    else if(a[2]=="tcpip"){if(Network||a.Length!=4||a[3]!="5555")throw new Exception("Unexpected tcpip mutation");Mutations++;PhonePort="5555";}
    else if(a[2]=="usb"){if(Network||a.Length!=3)throw new Exception("Unexpected USB mutation or null argument");Mutations++;PhonePort="0";}
    else if(a[2]=="shell"){
     if(a[3]=="pm")output="package:"+Apk;
     else if(a[3]=="dumpsys")output="versionName=13.6.0.r1086.2650830 versionCode=1086 primaryCpuAbi=arm64-v8a";
     else if(a[3]=="getprop"){
      if(a[4]=="service.adb.tcp.port")output=PhonePort;
      else if(a[4]=="persist.adb.tcp.port")output=Persistent;
      else if(a[4]=="service.adb.listen_addrs")output=Listen;
      else if(a[4]=="ro.debuggable")output=Debuggable;
      else if(a[4]=="ro.adb.secure")output=AdbSecure;
      else if(a[4]=="ro.product.manufacturer")output="Google";
      else if(a[4]=="persist.sys.permission.enable")output="false";
      else throw new Exception("Unexpected property");
     }else if(a[3].EndsWith("/libshizuku.so",StringComparison.Ordinal))Launched=true;
     else throw new Exception("Unexpected shell operation");
    }else throw new Exception("Unexpected device operation");
   }else throw new Exception("Unexpected ADB operation");
   return new HelperProcessResult{Output=output};
  }
  internal TcpAdbForwarder Create(TcpAdbCableTarget target,HelperSessionAlive alive){FactoryCount++;return FixtureForwarder(target,alive);}
  internal string Start(Action<string> progress,int authBudget){return TcpAdbHelper.TryStart(new HelperAdbLocation("fake-adb.exe",null,Bundled),Run,delegate{return true;},delegate{return Alive;},Target(),progress,authBudget,Create);}
  internal string Prepare(bool enable,bool consent,int budget){Network=false;return TcpAdbHelper.SetPreparedMode(new HelperAdbLocation("fake-adb.exe",null,Bundled),Run,delegate{return true;},delegate{return Alive;},enable,consent,budget);}
  internal bool OnlyTargetedCleanup(){string[] last=Calls[Calls.Count-1];int n=last.Length>=2&&last[0]=="-P"?2:0;return last.Length==n+2&&last[n]=="disconnect"&&last[n+1]==Alias;}
 }
 static void SocketRoundTrip(){
  TcpListener origin=new TcpListener(IPAddress.Loopback,0);origin.Start();int port=((IPEndPoint)origin.LocalEndpoint).Port;Exception serverError=null;ManualResetEvent serverDone=new ManualResetEvent(false);int connectors=0;
  Thread server=new Thread(delegate(){try{using(TcpClient peer=origin.AcceptTcpClient()){peer.ReceiveTimeout=peer.SendTimeout=3000;NetworkStream stream=peer.GetStream();MemoryStream received=new MemoryStream();byte[] block=new byte[100];int n;while((n=stream.Read(block,0,block.Length))!=0)received.Write(block,0,n);if(Encoding.ASCII.GetString(received.ToArray())!="hello")throw new Exception("Wrong phone request");byte[] reply=Encoding.ASCII.GetBytes("world");stream.Write(reply,0,reply.Length);}}catch(Exception e){serverError=e;}finally{serverDone.Set();}});server.IsBackground=true;server.Start();
  TcpAdbForwarder forward=null;
  try{forward=new TcpAdbForwarder(Target(),delegate{return true;},delegate(TcpAdbCableTarget t,HelperSessionAlive alive){Interlocked.Increment(ref connectors);if(!Object.ReferenceEquals(t.Source,Target().Source)&&!t.Source.Equals(IPAddress.Parse("192.0.2.2")))throw new Exception("Target changed");Socket socket=new Socket(AddressFamily.InterNetwork,SocketType.Stream,ProtocolType.Tcp);socket.Connect(IPAddress.Loopback,port);return socket;});
   Check(TcpAdbHelper.IsLoopbackSerial(forward.Serial),"forwarder exposes only a canonical ephemeral loopback address");
   using(TcpClient client=new TcpClient()){client.ReceiveTimeout=client.SendTimeout=3000;client.Connect(IPAddress.Loopback,int.Parse(forward.Serial.Substring(10)));NetworkStream clientStream=client.GetStream();byte[] request=Encoding.ASCII.GetBytes("hello");clientStream.Write(request,0,request.Length);client.Client.Shutdown(SocketShutdown.Send);MemoryStream reply=new MemoryStream();byte[] block=new byte[100];int n;while((n=clientStream.Read(block,0,block.Length))!=0)reply.Write(block,0,n);Check(Encoding.ASCII.GetString(reply.ToArray())=="world","forwarder preserves bidirectional bytes after client half-close");}
   Check(serverDone.WaitOne(3000,false)&&serverError==null&&connectors==1,"half-close fixture completes with one explicitly injected connector");
  }finally{if(forward!=null)forward.Dispose();origin.Stop();server.Join(3500);serverDone.Close();}
 }
 static void Cancellation(){
  bool alive=true;ManualResetEvent accepted=new ManualResetEvent(false);TcpListener origin=new TcpListener(IPAddress.Loopback,0);origin.Start();int port=((IPEndPoint)origin.LocalEndpoint).Port;TcpClient originPeer=null;Thread waiter=new Thread(delegate(){try{originPeer=origin.AcceptTcpClient();accepted.Set();}catch{}});waiter.IsBackground=true;waiter.Start();
  TcpAdbForwarder forward=new TcpAdbForwarder(Target(),delegate{return alive;},delegate{Socket socket=new Socket(AddressFamily.InterNetwork,SocketType.Stream,ProtocolType.Tcp);socket.Connect(IPAddress.Loopback,port);return socket;});int proxyPort=int.Parse(forward.Serial.Substring(10));
  try{using(TcpClient peer=new TcpClient()){peer.ReceiveTimeout=3000;peer.Connect(IPAddress.Loopback,proxyPort);NetworkStream peerStream=peer.GetStream();Check(accepted.WaitOne(3000,false),"cancellation fixture has a live owned pair");alive=false;Thread.Sleep(600);Check(!forward.Running,"session cancellation stops the forwarder watcher");bool ended=false;try{ended=peerStream.ReadByte()==-1;}catch(IOException){ended=true;}Check(ended,"cancellation closes accepted proxy socket");originPeer.ReceiveTimeout=3000;ended=false;try{ended=originPeer.GetStream().ReadByte()==-1;}catch(IOException){ended=true;}Check(ended,"cancellation closes outgoing socket too");}
   using(TcpClient late=new TcpClient()){bool refused=false;try{late.Connect(IPAddress.Loopback,proxyPort);}catch(SocketException){refused=true;}Check(refused,"cancellation closes the loopback listener");}
  }finally{forward.Dispose();if(originPeer!=null)originPeer.Close();origin.Stop();accepted.Close();}
 }
 static int Main(){try{
  Console.WriteLine("CLR "+Environment.Version);TcpAdbCableTarget target=Target();Check(target!=null,"authenticated same-subnet USB target accepted");
  IPAddress source=IPAddress.Parse("192.0.2.2"),phone=IPAddress.Parse("192.0.2.1"),mask=IPAddress.Parse("255.255.255.0");
  Check(TcpAdbCableTarget.FromAuthenticatedLink(source,phone,mask,777,"usb",false)==null,"unauthenticated link cannot become an ADB target");
  Check(TcpAdbCableTarget.FromAuthenticatedLink(source,phone,mask,0,"usb",true)==null,"missing USB interface index rejected");
  Check(TcpAdbCableTarget.FromAuthenticatedLink(source,phone,mask,777,"",true)==null,"missing USB interface identity rejected");
  foreach(string peer in new string[]{"192.0.3.1","192.0.2.0","192.0.2.255","127.0.0.1","224.0.0.1","0.0.0.0","192.0.2.2"})Check(TcpAdbCableTarget.FromAuthenticatedLink(source,IPAddress.Parse(peer),mask,777,"usb",true)==null,"unsafe or unrelated phone address rejected: "+peer);
  Check(TcpAdbCableTarget.FromAuthenticatedLink(source,phone,IPAddress.Parse("255.0.255.0"),777,"usb",true)==null,"noncontiguous netmask rejected");
  Check(TcpAdbCableTarget.FromAuthenticatedLink(source,phone,IPAddress.Any,777,"usb",true)==null,"zero mask cannot authorize an arbitrary host");
  Check(TcpAdbCableTarget.FromAuthenticatedLink(source,IPAddress.IPv6Loopback,mask,777,"usb",true)==null,"IPv6 peer rejected by IPv4-only cable mode");
  Check(target.SameCurrentInterface("OWNED-TEST-USB",777,source,true),"current matching interface accepts case-insensitive identity");
  Check(!target.SameCurrentInterface("other",777,source,true)&&!target.SameCurrentInterface("owned-test-usb",778,source,true)&&!target.SameCurrentInterface("owned-test-usb",777,phone,true)&&!target.SameCurrentInterface("owned-test-usb",777,source,false),"changed identity, index, address or link state invalidates target");
  Check(Rejects(delegate{TcpAdbForwarder.ConnectBound(target,delegate{return true;});}),"production connector rejects a nonexistent pinned interface before network access");
  Check(TcpAdbHelper.ShouldFallback(true,target,TcpAdbHelper.MissingUsb+" details"),"missing physical USB device permits opted-in RNDIS fallback");
  Check(!TcpAdbHelper.ShouldFallback(false,target,TcpAdbHelper.MissingUsb)&&!TcpAdbHelper.ShouldFallback(true,null,TcpAdbHelper.MissingUsb),"opt-out and MIDI or missing target never trigger fallback");
  Check(!TcpAdbHelper.ShouldFallback(true,target,"На телефоне разрешите USB-отладку")&&!TcpAdbHelper.ShouldFallback(true,target,"Для настройки оставьте подключённым один телефон"),"unauthorized and ambiguous USB are not reinterpreted as driver failure");
  foreach(string value in new string[]{"127.0.0.1:1","127.0.0.1:65535"})Check(TcpAdbHelper.IsLoopbackSerial(value),"canonical loopback serial accepted: "+value);
  foreach(string value in new string[]{"192.0.2.1:5555","localhost:5555","127.0.0.1:0","127.0.0.1:65536","127.0.0.1:05555","127.0.0.1:5555;reboot","127.0.0.1:5555\n","127.0.0.1:+5555","[::1]:5555"})Check(!TcpAdbHelper.IsLoopbackSerial(value),"unsafe serial rejected: "+value.Replace("\n","newline"));
  Check(TcpAdbHelper.SelectExact("other\tdevice\n127.0.0.1:50000\tdevice\n192.0.2.10:5555\tdevice","127.0.0.1:50000")=="127.0.0.1:50000","only exact proxy transport selected among unrelated devices");
  Check(Rejects(delegate{TcpAdbHelper.SelectExact("127.0.0.1:50000\tdevice\n127.0.0.1:50000\tdevice","127.0.0.1:50000");}),"duplicate exact alias rejected");
  Fake normal=new Fake();string result=normal.Start(null,0);Check(normal.Launched&&result.StartsWith("Команда запуска отправлена"),"targeted TCP transport reuses validated helper starter");Check(normal.OnlyTargetedCleanup()&&normal.FactoryCount==1&&normal.Mutations==0,"automatic fallback only closes its own alias and never enables TCP debugging");
  foreach(string redirect in new string[]{"ADB_SERVER_SOCKET","ANDROID_ADB_SERVER_ADDRESS","ANDROID_ADB_SERVER_PORT","ADB_SERVER_PORT"}){string previous=Environment.GetEnvironmentVariable(redirect);try{Environment.SetEnvironmentVariable(redirect,"tcp:192.0.2.99:5037");Fake redirected=new Fake();result=redirected.Start(null,0);Check(!redirected.Launched&&redirected.Calls.Count==0,"network mode refuses inherited remote ADB redirection: "+redirect);Fake preguard=new Fake();result=AdbHelper.TryStartCable(new HelperAdbLocation("fake-adb.exe",null,false),preguard.Run,delegate{return true;},delegate{return true;},target,true,null);Check(preguard.Calls.Count==0&&result.Contains("локальный ADB"),"opted-in cable mode blocks remote-server environment before USB selection: "+redirect);}finally{Environment.SetEnvironmentVariable(redirect,previous);}}
  Fake bundled=new Fake{Bundled=true};result=bundled.Start(null,0);Check(bundled.Launched&&bundled.OnlyTargetedCleanup(),"bundled TCP helper keeps all commands on isolated server5038");
  Fake auth=new Fake{States=new string[]{"unauthorized","device"}};int notices=0;string noticedAlias="";result=auth.Start(delegate(string text){notices++;noticedAlias=auth.Alias;Check(text.StartsWith("Разрешите USB-отладку на телефоне. После"),"RSA notice preserves Android retry prefix");},2000);Check(auth.Launched&&notices==1&&auth.FactoryCount==1&&auth.Alias==noticedAlias,"authorization waits on same proxy and resumes after device approval");
  Fake transient=new Fake{States=new string[]{"offline","","device"}};result=transient.Start(null,2500);Check(transient.Launched&&transient.FactoryCount==1,"short missing state during approval retains the same transport");
  Fake cancel=new Fake{States=new string[]{"unauthorized","device"},CancelOnDevices=2};result=cancel.Start(null,2000);Check(!cancel.Launched&&cancel.OnlyTargetedCleanup(),"cancelled authorization never launches and disconnects only owned alias");
  Fake denied=new Fake{States=new string[]{"unauthorized"}};result=denied.Start(null,0);Check(!denied.Launched&&denied.OnlyTargetedCleanup()&&result.StartsWith("На телефоне разрешите"),"authorization timeout reports consent and cleans its exact transport");
  Fake timeout=new Fake{ConnectTimeout=true};result=timeout.Start(null,0);Check(!timeout.Launched&&timeout.OnlyTargetedCleanup(),"connect timeout cannot run helper and still releases owned alias");
  Fake consent=new Fake();Check(Rejects(delegate{consent.Prepare(true,false,0);})&&consent.Calls.Count==0,"home preparation cannot execute anything without explicit consent");
  Fake absent=new Fake{UsbState=""};result=absent.Prepare(true,true,0);Check(absent.Mutations==0&&result.StartsWith(TcpAdbHelper.MissingUsb),"home preparation requires an actual authorized USB device");
  Fake untrusted=new Fake{UsbState="unauthorized"};result=untrusted.Prepare(true,true,0);Check(untrusted.Mutations==0&&result.StartsWith("На телефоне разрешите"),"unauthorized USB cannot enable the phone listener");
  Fake custom=new Fake{PhonePort="62000"};result=custom.Prepare(true,true,0);Check(custom.Mutations==0&&result.Contains("другой порт"),"custom existing TCP port preserved");
  Fake persistent=new Fake{Persistent="5555"};result=persistent.Prepare(false,true,0);Check(persistent.Mutations==0&&result.Contains("другой способ"),"persistent existing TCP setup is never overwritten");
  Fake addresses=new Fake{Listen="tcp:5555"};result=addresses.Prepare(true,true,0);Check(addresses.Mutations==0&&result.Contains("другой способ"),"custom listen addresses preserved");
  Fake insecure=new Fake{Debuggable="1",AdbSecure="0"};result=insecure.Prepare(true,true,0);Check(insecure.Mutations==0&&result.Contains("защита сетевой отладки ключом не подтверждена"),"engineering phone without key authorization is never enabled");
  Fake unknownSecurity=new Fake{Debuggable="",AdbSecure=""};result=unknownSecurity.Prepare(true,true,0);Check(unknownSecurity.Mutations==0&&result.Contains("защита сетевой отладки ключом не подтверждена"),"unknown authorization policy fails closed before changing the phone");
  Fake consumerInsecure=new Fake{Debuggable="0",AdbSecure="0"};result=consumerInsecure.Prepare(true,true,0);Check(consumerInsecure.Mutations==0&&result.Contains("защита сетевой отладки ключом не подтверждена"),"consumer label does not override explicitly disabled ADB authentication");
  Fake consumerUnknown=new Fake{Debuggable="0",AdbSecure=""};result=consumerUnknown.Prepare(true,true,0);Check(consumerUnknown.Mutations==0&&result.Contains("защита сетевой отладки ключом не подтверждена"),"consumer label does not manufacture an unknown authentication policy");
  Fake engineeringSecure=new Fake{Debuggable="1",AdbSecure="1"};result=engineeringSecure.Prepare(true,true,0);Check(engineeringSecure.Mutations==1&&result.Contains("результат пока не подтверждён"),"explicit key authentication permits preparation independent of build label");
  Fake already=new Fake{PhonePort="5555"};result=already.Prepare(true,true,0);Check(already.Mutations==0&&result.Contains("ещё нужно проверить"),"existing preparation is idempotent and does not claim work-PC reachability");
  Fake enabled=new Fake();result=enabled.Prepare(true,true,2000);Check(enabled.Mutations==1&&result.StartsWith("Настройка Android подтверждена")&&result.Contains("ещё нужно проверить"),"confirmed home enable checks configuration readback without claiming hardware success");
  Fake disabled=new Fake{PhonePort="5555"};result=disabled.Prepare(false,true,2000);Check(disabled.Mutations==1&&result.StartsWith("Наш режим порта 5555 выключен"),"explicit disable uses USB command and verifies its configuration");
  Fake unread=new Fake{Readable=false};result=unread.Prepare(true,true,0);Check(unread.Mutations==1&&result.Contains("результат пока не подтверждён"),"command output without readback never claims a prepared phone");
  SocketRoundTrip();Cancellation();Console.WriteLine("TOTAL "+passed+" passed");return 0;
 }catch(Exception e){Console.Error.WriteLine(e);return 1;}}
}
