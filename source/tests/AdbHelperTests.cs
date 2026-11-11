using System;
using System.IO;
using System.Collections.Generic;
using System.ComponentModel;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Diagnostics;
using UsbBridge;

class AdbHelperTests {
 const string Apk="/data/app/~~zerIcWfdXbYkB-qWbCoXuQ==/moe.shizuku.privileged.api-A6KswmThSlV3fDu2EWLVvw==/base.apk";
 const string Details="Package [moe.shizuku.privileged.api]\n versionName=13.6.0.r1086.2650830\n versionCode=1086 minSdk=23\n primaryCpuAbi=arm64-v8a\n";
 const string Serial="abcdef123456";
 const string BaseDirectory="C:\\USB Bridge test",Bundle=BaseDirectory+"\\platform-tools";
 static int count;
 static void Check(bool value,string name){if(!value)throw new Exception(name);Console.WriteLine("PASS "+name);count++;}
 delegate void TestAction();
 static bool Rejects(TestAction action){try{action();return false;}catch(IOException){return true;}}
 static HelperAdbLocation Find(string searchPath,string localDirectory,params string[] files){return AdbHelper.LocateAdb(BaseDirectory,searchPath,localDirectory,delegate(string path){foreach(string file in files)if(String.Equals(Path.GetFullPath(file),path,StringComparison.OrdinalIgnoreCase))return true;return false;});}
 static bool CheckServer(string reply,out string query){
  TcpListener listener=new TcpListener(IPAddress.Loopback,0);listener.Start();int port=((IPEndPoint)listener.LocalEndpoint).Port;string request="";Exception serverError=null;
  Thread server=new Thread(delegate(){try{using(TcpClient peer=listener.AcceptTcpClient()){peer.ReceiveTimeout=2000;NetworkStream stream=peer.GetStream();byte[] bytes=new byte[16];int offset=0;while(offset<bytes.Length){int read=stream.Read(bytes,offset,bytes.Length-offset);if(read==0)break;offset+=read;}request=Encoding.ASCII.GetString(bytes,0,offset);if(reply==null)Thread.Sleep(700);else{byte[] response=Encoding.ASCII.GetBytes(reply);stream.Write(response,0,response.Length);}}}catch(Exception error){serverError=error;}});server.IsBackground=true;server.Start();
  bool available=false;try{available=AdbHelper.CheckAdbPort(port);}finally{if(!server.Join(2500)){listener.Stop();throw new Exception("Local ADB protocol fixture did not finish");}listener.Stop();}
  if(serverError!=null)throw serverError;query=request;return available;
 }
 sealed class Fake {
  public readonly List<string[]> Calls=new List<string[]>();public readonly List<int> Timeouts=new List<int>();
  public int FailAt=-1,TimeoutAt=-1,NullAt=-1,NativeError,CancelAfter=-1;public bool Alive=true,CancelBeforeStart,BadManagerHash,BadUploadHash,MissingOverlay;
  public string Version=Details,Paths="package:"+Apk,Devices="List of devices attached\n"+Serial+"\tdevice product:OnePlus transport_id:1\n",Usb=Serial,Manufacturer="Google",Optimization="true";
  public HelperProcessResult Run(string exe,string[] arguments,int timeout){int index=Calls.Count;Calls.Add(arguments);Timeouts.Add(timeout);if(exe!="fake-adb.exe")throw new Exception("Unexpected executable");if(NativeError!=0)throw new Win32Exception(NativeError);if(index==FailAt)return new HelperProcessResult{ExitCode=1,Error="untrusted remote message"};if(index==TimeoutAt)return new HelperProcessResult{TimedOut=true};if(index==NullAt)return null;
   string[] results={Devices,Usb,Paths,Version,Manufacturer,Optimization,"starter sent"};string output=index<results.Length?results[index]:"";
   if(AdbHelper.OverlayRequired(Manufacturer,Optimization)){
    if(index==6)output=(BadManagerHash?new string('f',64):AdbHelper.OfficialManagerHash)+"  "+Apk;
    if(index==10)output=(BadUploadHash?new string('f',64):AdbHelper.OverlayJarHash)+"  "+AdbHelper.OverlayDirectory+"/server.jar.upload\n"+AdbHelper.OverlayScriptHash+"  "+AdbHelper.OverlayDirectory+"/start-helper154.sh.upload";
    if(index>14)throw new Exception("Unexpected extra overlay command");
   }else if(index>=results.Length)throw new Exception("Unexpected extra command");
   if((CancelBeforeStart&&index==5)||index==CancelAfter)Alive=false;return new HelperProcessResult{Output=output};}
  public string Start(){return AdbHelper.TryStart("fake-adb.exe",Run,delegate{return Alive;},delegate{if(MissingOverlay)throw new IOException("missing local payload");return new HelperOverlayFiles("C:\\test helper\\server.jar","C:\\test helper\\start-helper154.sh");});}
 }
 static string Args(string[] values){return String.Join("|",values);}
 static int Main(){try{
  Console.WriteLine("CLR "+Environment.Version);
  HelperAdbLocation complete=Find(null,null,Bundle+"\\adb.exe",Bundle+"\\AdbWinApi.dll",Bundle+"\\AdbWinUsbApi.dll");
  Check(complete.Executable==Bundle+"\\adb.exe"&&complete.Problem==null&&complete.Bundled,"complete bundled ADB is available when no existing installation exists");
  HelperAdbLocation missingDll=Find(null,null,Bundle+"\\adb.exe",Bundle+"\\AdbWinApi.dll");
  Check(missingDll.Executable==null&&missingDll.Problem.Contains("AdbWinUsbApi.dll")&&!missingDll.Problem.Contains("не хватает adb.exe"),"partial bundle identifies missing USB DLL before attempting process launch");
  HelperAdbLocation missingExe=Find(null,null,Bundle+"\\AdbWinApi.dll",Bundle+"\\AdbWinUsbApi.dll");
  Check(missingExe.Executable==null&&missingExe.Problem.Contains("не хватает adb.exe"),"DLL-only bundle is reported as incomplete rather than driver failure");
  HelperAdbLocation absentAdb=Find(null,null);
  Check(absentAdb.Executable==null&&absentAdb.Problem.StartsWith("Не найден adb.exe",StringComparison.Ordinal),"missing executable is distinct from absent phone");
  HelperAdbLocation installed=Find("; \"C:\\External tools\" ;",null,"C:\\External tools\\adb.exe");
  Check(installed.Executable=="C:\\External tools\\adb.exe"&&installed.Problem==null,"quoted PATH installation with spaces is accepted without imposing bundled DLL layout");
  HelperAdbLocation sdk=Find(null,"C:\\Local AppData","C:\\Local AppData\\Android\\Sdk\\platform-tools\\adb.exe");
  Check(sdk.Executable=="C:\\Local AppData\\Android\\Sdk\\platform-tools\\adb.exe","SDK fallback works without PATH");
  HelperAdbLocation partialFallback=Find(Bundle+";C:\\External",null,Bundle+"\\adb.exe","C:\\External\\adb.exe");
  Check(partialFallback.Executable=="C:\\External\\adb.exe","incomplete bundled ADB falls back to an existing installation");
  HelperAdbLocation partialPath=Find(Bundle.ToLowerInvariant()+";"+Bundle+"\\.",null,Bundle+"\\adb.exe");
  Check(partialPath.Executable==null&&partialPath.Problem.Contains("AdbWinApi.dll"),"PATH aliases cannot bypass bundled DLL validation");
  HelperAdbLocation modern=AdbHelper.LocateAdb(BaseDirectory,"C:\\SDK",null,delegate(string path){return path=="C:\\SDK\\adb.exe"||path.StartsWith(Bundle+"\\",StringComparison.Ordinal);});
  Check(modern.Executable=="C:\\SDK\\adb.exe"&&!modern.Bundled,"all supported Windows versions prefer existing SDK over bundled legacy client");
  string query;
  Check(CheckServer("OKAY00040028",out query)&&query=="000chost:version","private-port guard reuses matching protocol 40 server with read-only version query");
  Check(!CheckServer("OKAY00040029",out query),"newer ADB server on private port is preserved instead of auto-replaced");
  Check(!CheckServer("HTTP/1.1 200 OK",out query),"unrelated service on private port prevents ADB invocation");
  Check(!CheckServer("OKAY0004",out query),"truncated server version reply is rejected");
  Check(!CheckServer(null,out query),"silent private-port service has bounded timeout");
  TcpListener released=new TcpListener(IPAddress.Loopback,0);released.Start();int freePort=((IPEndPoint)released.LocalEndpoint).Port;released.Stop();Check(AdbHelper.CheckAdbPort(freePort),"connection refusal permits bundled server to start on a free private port");
  string listing="List of devices attached\nemulator-5554\tdevice\n192.168.0.5:5555\tdevice\nadb-ABC-xyz._adb-tls-connect._tcp\tdevice\n"+Serial+"\tdevice product:OnePlus transport_id:1\n";
  Check(AdbHelper.SelectUsbCandidate(listing)==Serial,"ignore emulator and wireless devices without requiring usb label");
  Check(Rejects(delegate{AdbHelper.SelectUsbCandidate("List of devices attached\nemulator-5554\tdevice\n");}),"no physical USB candidate cannot start helper");
  Check(Rejects(delegate{AdbHelper.SelectUsbCandidate(Serial+"\tunauthorized\n");}),"unauthorized phone requires user grant instead of launching");
  Check(Rejects(delegate{AdbHelper.SelectUsbCandidate(Serial+"\toffline\n");}),"offline phone cannot start helper");
  Check(Rejects(delegate{AdbHelper.SelectUsbCandidate(Serial+"\tdevice\nsecond\tdevice\n");}),"multiple physical candidates are rejected");
  Check(Rejects(delegate{AdbHelper.SelectUsbCandidate("evil;reboot\tdevice\n");}),"serial shell metacharacters are rejected");
  string starter=Apk.Substring(0,Apk.Length-"base.apk".Length)+"lib/arm64/libshizuku.so";
  Check(AdbHelper.StarterPath("package:"+Apk,Details)==starter,"13.6 native starter path matches installed arm64 APK");
  Check(AdbHelper.StarterPath("package:"+Apk,Details.Replace("arm64-v8a","x86_64")).EndsWith("/lib/x86_64/libshizuku.so",StringComparison.Ordinal),"native ABI selection supports emulator test architecture");
  Check(Rejects(delegate{AdbHelper.StarterPath("package:"+Apk,Details.Replace("13.6.0","13.5.4"));}),"older Shizuku version is not launched with newer command");
  Check(Rejects(delegate{AdbHelper.StarterPath("package:"+Apk,Details.Replace("13.6.0","13.7.0"));}),"newer Shizuku version is preserved without forced downgrade");
  Check(Rejects(delegate{AdbHelper.StarterPath("package:"+Apk+";reboot",Details);}),"remote APK path cannot inject shell commands");
  Check(Rejects(delegate{AdbHelper.StarterPath("package:"+Apk.Replace("moe.shizuku.privileged.api","other.package"),Details);}),"only fixed Shizuku package location is executable");
  Check(Rejects(delegate{AdbHelper.StarterPath("package:"+Apk+"\npackage:/other.apk",Details);}),"ambiguous split APK paths are rejected");
  Fake happy=new Fake();string result=happy.Start();
  Check(result.StartsWith("Команда запуска отправлена",StringComparison.Ordinal)&&happy.Calls.Count==7,"exit zero confirms command dispatch only");
  Check(Args(happy.Calls[0])=="devices|-l"&&Args(happy.Calls[1])=="-d|get-serialno"&&Args(happy.Calls[2])=="-s|"+Serial+"|shell|pm|path|moe.shizuku.privileged.api"&&Args(happy.Calls[3])=="-s|"+Serial+"|shell|dumpsys|package|moe.shizuku.privileged.api"&&Args(happy.Calls[4])=="-s|"+Serial+"|shell|getprop|ro.product.manufacturer"&&Args(happy.Calls[5])=="-s|"+Serial+"|shell|getprop|persist.sys.permission.enable"&&Args(happy.Calls[6])=="-s|"+Serial+"|shell|"+starter,"unaffected phones use official starter after two fixed read-only property checks");
  Check(happy.Timeouts[0]==6000&&happy.Timeouts[6]==20000,"all process stages have bounded deadlines");
  Fake bundled=new Fake();int guardCalls=0;string bundledResult=AdbHelper.TryStart(new HelperAdbLocation("fake-adb.exe",null,true),bundled.Run,delegate{return true;},delegate{guardCalls++;return true;});
  bool allPrivate=true;foreach(string[] invocation in bundled.Calls)allPrivate&=invocation.Length>=3&&invocation[0]=="-P"&&invocation[1]=="5038";
  Check(bundledResult.StartsWith("Команда запуска отправлена",StringComparison.Ordinal)&&bundled.Calls.Count==7&&guardCalls==7&&allPrivate,"every bundled command checks and uses private port without default-server access");
  Fake external=new Fake();string externalResult=AdbHelper.TryStart(new HelperAdbLocation("fake-adb.exe",null),external.Run,delegate{return true;},delegate{throw new Exception("external ADB must not use bundled port guard");});
  Check(externalResult.StartsWith("Команда запуска отправлена",StringComparison.Ordinal)&&Args(external.Calls[0])=="devices|-l","existing external ADB keeps its original command endpoint");
  Fake occupied=new Fake();string occupiedResult=AdbHelper.TryStart(new HelperAdbLocation("fake-adb.exe",null,true),occupied.Run,delegate{return true;},delegate{return false;});
  Check(occupied.Calls.Count==0&&occupiedResult.Contains("порт USB Bridge занят"),"conflicting private server is not killed or replaced");
  Fake replaced=new Fake();int checks=0;AdbHelper.TryStart(new HelperAdbLocation("fake-adb.exe",null,true),replaced.Run,delegate{return true;},delegate{return ++checks<3;});
  Check(replaced.Calls.Count==2&&checks==3,"private server conflict appearing mid-setup stops before next Android command");
  ProcessStartInfo bundledEnvironment=new ProcessStartInfo();bundledEnvironment.EnvironmentVariables["ADB_SERVER_SOCKET"]="tcp:localhost:5037";bundledEnvironment.EnvironmentVariables["ANDROID_ADB_SERVER_ADDRESS"]="remote-host";bundledEnvironment.EnvironmentVariables["ANDROID_ADB_SERVER_PORT"]="5037";bundledEnvironment.EnvironmentVariables["ADB_SERVER_PORT"]="5037";bundledEnvironment.EnvironmentVariables["ADB_VENDOR_KEYS"]="existing-authorized-user-keys";
  AdbHelper.ConfigureServerEnvironment(bundledEnvironment,new string[]{"-P","5038","devices","-l"});
  Check(!bundledEnvironment.EnvironmentVariables.ContainsKey("ADB_SERVER_SOCKET")&&!bundledEnvironment.EnvironmentVariables.ContainsKey("ANDROID_ADB_SERVER_ADDRESS")&&!bundledEnvironment.EnvironmentVariables.ContainsKey("ANDROID_ADB_SERVER_PORT")&&!bundledEnvironment.EnvironmentVariables.ContainsKey("ADB_SERVER_PORT")&&bundledEnvironment.EnvironmentVariables["ADB_VENDOR_KEYS"]=="existing-authorized-user-keys","bundled child removes endpoint redirection while preserving authorized user keys");
  ProcessStartInfo externalEnvironment=new ProcessStartInfo();externalEnvironment.EnvironmentVariables["ADB_SERVER_SOCKET"]="tcp:localhost:5037";AdbHelper.ConfigureServerEnvironment(externalEnvironment,new string[]{"devices","-l"});
  Check(externalEnvironment.EnvironmentVariables["ADB_SERVER_SOCKET"]=="tcp:localhost:5037","existing external ADB environment remains unchanged");
  Fake mismatch=new Fake();mismatch.Usb="different";mismatch.Start();Check(mismatch.Calls.Count==2,"authoritative USB selector mismatch stops before shell access");
  Fake noPhone=new Fake();noPhone.Devices="List of devices attached\n";string noPhoneText=noPhone.Start();Check(noPhone.Calls.Count==1&&noPhoneText.StartsWith("ADB запущен, но телефон по USB не найден",StringComparison.Ordinal)&&!noPhoneText.Contains("продолжает работать"),"running ADB with no USB phone reports detection issue without promising internet");
  Fake unauthorized=new Fake();unauthorized.Devices=Serial+"\tunauthorized\n";string unauthorizedText=unauthorized.Start();Check(unauthorized.Calls.Count==1&&unauthorizedText.StartsWith("На телефоне разрешите USB-отладку",StringComparison.Ordinal),"phone authorization is actionable and retains desktop automatic retry signal");
  Fake offline=new Fake();offline.Devices=Serial+"\toffline\n";string offlineText=offline.Start();Check(offline.Calls.Count==1&&offlineText.Contains("Телефон найден"),"offline phone is distinguished from missing ADB or driver");
  Fake multipleUsb=new Fake();multipleUsb.FailAt=1;multipleUsb.Start();Check(multipleUsb.Calls.Count==2,"ADB USB ambiguity never falls back to another device");
  Fake wrongVersion=new Fake();wrongVersion.Version=Details.Replace("13.6.0","13.5.4");wrongVersion.Start();Check(wrongVersion.Calls.Count==4,"unsupported version never launches any starter");
  Fake timeout=new Fake();timeout.TimeoutAt=2;string timeoutText=timeout.Start();Check(timeout.Calls.Count==3&&timeoutText.IndexOf("вовремя",StringComparison.Ordinal)>=0,"timed out process aborts helper with local explanation");
  Fake noResult=new Fake();noResult.NullAt=0;string noResultText=noResult.Start();Check(noResult.Calls.Count==1&&noResultText.Contains("результат запуска ADB")&&!noResultText.Contains("вовремя"),"missing process result is not mislabeled as phone timeout");
  Fake listFailed=new Fake();listFailed.FailAt=0;string listFailedText=listFailed.Start();Check(listFailed.Calls.Count==1&&listFailedText.Contains("получить список телефонов")&&listFailedText.Contains("код 1"),"ADB process failure identifies discovery stage before interpreting devices");
  Fake denied=new Fake();denied.FailAt=6;string deniedText=denied.Start();Check(deniedText.IndexOf("untrusted",StringComparison.Ordinal)<0&&!deniedText.StartsWith("Команда",StringComparison.Ordinal)&&deniedText.Contains("запустить помощника")&&!deniedText.Contains("продолжает работать"),"nonzero starter result is not reported as success, internet guarantee, or remote instructions");
  Fake windowsDenied=new Fake();windowsDenied.NativeError=5;Check(windowsDenied.Start().Contains("Windows запретила запуск")&&windowsDenied.Calls.Count==1,"Windows process access denial is distinct from missing Android driver");
  Fake loaderFailed=new Fake();loaderFailed.NativeError=126;Check(loaderFailed.Start().Contains("загрузить библиотеку")&&loaderFailed.Calls.Count==1,"OS loader DLL failure requests full extraction");
  Fake wrongArchitecture=new Fake();wrongArchitecture.NativeError=193;Check(wrongArchitecture.Start().Contains("несовместима с Windows")&&wrongArchitecture.Calls.Count==1,"incompatible executable gets specific Windows explanation");
  Fake vanished=new Fake();vanished.NativeError=2;Check(vanished.Start().Contains("Файл ADB больше не найден")&&vanished.Calls.Count==1,"executable removed after discovery is reported without claiming device failure");
  Fake canceled=new Fake();canceled.CancelBeforeStart=true;canceled.Start();Check(canceled.Calls.Count==6,"cable session cancellation prevents final starter command");
  Fake absent=new Fake();absent.Alive=false;absent.Start();Check(absent.Calls.Count==0,"closed cable session never invokes ADB");
  Check(AdbHelper.OverlayRequired("OnePlus\r\n","true\n")&&AdbHelper.OverlayRequired("oPpO","true")&&!AdbHelper.OverlayRequired("Google","true")&&!AdbHelper.OverlayRequired("OnePlus","false")&&!AdbHelper.OverlayRequired("OnePlus; id","true")&&!AdbHelper.OverlayRequired("OnePlus","true; id"),"compatibility patch is restricted to exact OnePlus or OPPO optimized mode");
  Fake overlay=new Fake();overlay.Manufacturer="OnePlus";string overlayResult=overlay.Start();
  string remote=AdbHelper.OverlayDirectory,lib=starter.Substring(0,starter.LastIndexOf('/'));
  Check(overlayResult.StartsWith("Команда запуска отправлена",StringComparison.Ordinal)&&overlay.Calls.Count==15,"optimized OnePlus receives checked compatibility helper instead of broken official startup");
  Check(Args(overlay.Calls[6])=="-s|"+Serial+"|shell|sha256sum|"+Apk&&Args(overlay.Calls[7])=="-s|"+Serial+"|shell|mkdir|-p|-m|700|"+remote&&Args(overlay.Calls[8])=="-s|"+Serial+"|push|C:\\test helper\\server.jar|"+remote+"/server.jar.upload"&&Args(overlay.Calls[9])=="-s|"+Serial+"|push|C:\\test helper\\start-helper154.sh|"+remote+"/start-helper154.sh.upload","official manager is hash checked before staging only fixed packaged payload files");
  Check(Args(overlay.Calls[10])=="-s|"+Serial+"|shell|sha256sum|"+remote+"/server.jar.upload|"+remote+"/start-helper154.sh.upload"&&Args(overlay.Calls[11])=="-s|"+Serial+"|shell|mv|"+remote+"/server.jar.upload|"+remote+"/server.jar"&&Args(overlay.Calls[12])=="-s|"+Serial+"|shell|mv|"+remote+"/start-helper154.sh.upload|"+remote+"/start-helper154.sh"&&Args(overlay.Calls[13])=="-s|"+Serial+"|shell|chmod|444|"+remote+"/server.jar|"+remote+"/start-helper154.sh"&&Args(overlay.Calls[14])=="-s|"+Serial+"|shell|sh|"+remote+"/start-helper154.sh|"+Apk+"|"+lib,"both uploaded files are verified before readonly atomic replacement and fixed script launch");
  Fake noOptimization=new Fake();noOptimization.Manufacturer="OnePlus";noOptimization.Optimization="false";noOptimization.Start();Check(noOptimization.Calls.Count==7&&Args(noOptimization.Calls[6]).EndsWith("|"+starter,StringComparison.Ordinal),"OnePlus with unaffected system setting retains official native starter");
  Fake wrongCode=new Fake();wrongCode.Manufacturer="OnePlus";wrongCode.Version=Details.Replace("versionCode=1086","versionCode=1087");string wrongCodeText=wrongCode.Start();Check(wrongCode.Calls.Count==6&&wrongCodeText.Contains("не поддерживает исправление"),"unknown manager build is not patched or downgraded despite same version name");
  Fake missingOverlay=new Fake();missingOverlay.Manufacturer="OnePlus";missingOverlay.MissingOverlay=true;string missingOverlayText=missingOverlay.Start();Check(missingOverlay.Calls.Count==6&&missingOverlayText=="missing local payload","absent or corrupt local payload stops before phone file mutations");
  Fake badManager=new Fake();badManager.Manufacturer="OnePlus";badManager.BadManagerHash=true;badManager.Start();Check(badManager.Calls.Count==7,"different installed manager bytes stop before staging or restarting a server");
  Fake badUpload=new Fake();badUpload.Manufacturer="OnePlus";badUpload.BadUploadHash=true;badUpload.Start();Check(badUpload.Calls.Count==11,"corrupt uploaded bytes never replace or execute active helper files");
  Fake failedPush=new Fake();failedPush.Manufacturer="OnePlus";failedPush.FailAt=8;failedPush.Start();Check(failedPush.Calls.Count==9,"failed payload transfer never attempts to restart existing helper");
  Fake canceledOverlay=new Fake();canceledOverlay.Manufacturer="OnePlus";canceledOverlay.CancelAfter=13;canceledOverlay.Start();Check(canceledOverlay.Calls.Count==14,"disconnect after staging still prevents privileged helper restart");
  Fake failedScript=new Fake();failedScript.Manufacturer="OnePlus";failedScript.FailAt=14;string failedScriptText=failedScript.Start();Check(failedScript.Calls.Count==15&&!failedScriptText.StartsWith("Команда запуска отправлена",StringComparison.Ordinal)&&failedScriptText.Contains("запустить помощника"),"script rejection is not reported as successful helper startup");
  Check(Rejects(delegate{AdbHelper.VerifyRemoteHashes(AdbHelper.OfficialManagerHash+"  "+Apk+";id",new string[]{Apk},new string[]{AdbHelper.OfficialManagerHash});})&&Rejects(delegate{AdbHelper.VerifyRemoteHashes(AdbHelper.OfficialManagerHash+"  "+Apk+"\nextra",new string[]{Apk},new string[]{AdbHelper.OfficialManagerHash});}),"remote integrity response must match exact expected file paths and count");
  Check(AdbHelper.QuoteArgument("a b")=="\"a b\""&&AdbHelper.QuoteArgument("path\\")=="\"path\\\\\""&&AdbHelper.QuoteArgument("a\"b")=="\"a\\\"b\"","Windows arguments preserve spaces quotes and trailing backslash");
  HelperRequestGate gate=new HelperRequestGate();DateTime now=new DateTime(2026,1,1);
  Check(gate.TryBegin(now)&&!gate.TryBegin(now.AddSeconds(30)),"parallel helper launch is rejected even after debounce interval");gate.Complete();
  Check(!gate.TryBegin(now.AddSeconds(9))&&gate.TryBegin(now.AddSeconds(10)),"fresh user attempt is accepted after ten seconds");gate.Complete();
  Check(!gate.TryBegin(now.AddSeconds(5)),"backward clock does not permit a launch burst");
  HelperRequestGate queued=new HelperRequestGate(TimeSpan.Zero);ManualResetEvent firstStarted=new ManualResetEvent(false),releaseFirst=new ManualResetEvent(false),repairDone=new ManualResetEvent(false);int runningJobs=0,parallelJobs=0,discardedJobs=0,repairJobs=0;
  queued.Enqueue(delegate{return true;},delegate{Interlocked.Increment(ref runningJobs);firstStarted.Set();if(!releaseFirst.WaitOne(3000,false))throw new Exception("test release timed out");Interlocked.Decrement(ref runningJobs);});
  Check(firstStarted.WaitOne(2000,false),"queued helper begins its initial request");
  queued.Enqueue(delegate{return true;},delegate{Interlocked.Increment(ref discardedJobs);});
  queued.Enqueue(delegate{return true;},delegate{if(Interlocked.Increment(ref runningJobs)>1)Interlocked.Increment(ref parallelJobs);Interlocked.Increment(ref repairJobs);Interlocked.Decrement(ref runningJobs);repairDone.Set();});
  Check(!repairDone.WaitOne(60,false),"repair waits while previous helper startup is active");releaseFirst.Set();
  Check(repairDone.WaitOne(2000,false)&&repairJobs==1&&discardedJobs==0&&parallelJobs==0,"busy repair is eventually executed once and superseded queued work is coalesced without parallel starts");
  HelperRequestGate throttled=new HelperRequestGate(TimeSpan.FromMilliseconds(180));ManualResetEvent firstFast=new ManualResetEvent(false),secondFast=new ManualResetEvent(false);Stopwatch cooldown=Stopwatch.StartNew();
  throttled.Enqueue(delegate{return true;},delegate{cooldown.Reset();cooldown.Start();firstFast.Set();});Check(firstFast.WaitOne(2000,false),"cooldown fixture starts");
  throttled.Enqueue(delegate{return true;},delegate{secondFast.Set();});Check(!secondFast.WaitOne(60,false)&&secondFast.WaitOne(2000,false)&&cooldown.ElapsedMilliseconds>=150,"request received during cooldown waits then runs instead of being silently dropped");
  HelperRequestGate canceledQueue=new HelperRequestGate(TimeSpan.Zero);ManualResetEvent held=new ManualResetEvent(false),releaseHeld=new ManualResetEvent(false),canceledRan=new ManualResetEvent(false),replacementRan=new ManualResetEvent(false);int pendingAlive=1;
  canceledQueue.Enqueue(delegate{return true;},delegate{held.Set();releaseHeld.WaitOne(3000,false);});Check(held.WaitOne(2000,false),"cancellation fixture starts");
  canceledQueue.Enqueue(delegate{return Thread.VolatileRead(ref pendingAlive)==1;},delegate{canceledRan.Set();});Interlocked.Exchange(ref pendingAlive,0);releaseHeld.Set();
  Check(!canceledRan.WaitOne(250,false),"queued helper for disconnected USB session is never executed");
  canceledQueue.Enqueue(delegate{return true;},delegate{replacementRan.Set();});Check(replacementRan.WaitOne(2000,false),"later replacement USB session remains eligible after canceled pending startup");
  HelperRequestGate thrownQueue=new HelperRequestGate(TimeSpan.Zero);ManualResetEvent thrownStarted=new ManualResetEvent(false),afterFailure=new ManualResetEvent(false);
  thrownQueue.Enqueue(delegate{return true;},delegate{thrownStarted.Set();throw new IOException("fixture failure");});Check(thrownStarted.WaitOne(2000,false),"failure fixture starts");
  thrownQueue.Enqueue(delegate{return true;},delegate{afterFailure.Set();});Check(afterFailure.WaitOne(2000,false),"failed queued startup does not strand a later repair");
  Console.WriteLine("TOTAL "+count+" passed");return 0;
 }catch(Exception error){Console.Error.WriteLine(error);return 1;}}
}
