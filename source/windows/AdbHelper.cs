using System;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading;
using System.ComponentModel;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace UsbBridge {
public sealed class HelperProcessResult {
 public int ExitCode;public bool TimedOut;public string Output="",Error="";
}
public delegate HelperProcessResult HelperProcessRunner(string executable,string[] arguments,int timeoutMs);
public delegate bool HelperSessionAlive();
public delegate bool HelperFileExists(string path);
public delegate bool HelperAdbPortAvailable();
public sealed class HelperAdbLocation {
 public string Executable,Problem;public bool Bundled;
 public HelperAdbLocation(string executable,string problem):this(executable,problem,false){}
 public HelperAdbLocation(string executable,string problem,bool bundled){Executable=executable;Problem=problem;Bundled=bundled;}
}
public sealed class HelperRequestGate {
 readonly object gate=new object();readonly double cooldownSeconds;bool running,worker;DateTime last=DateTime.MinValue;Request pending;
 sealed class Request {internal readonly HelperSessionAlive Alive;internal readonly ThreadStart Work;internal Request(HelperSessionAlive alive,ThreadStart work){Alive=alive;Work=work;}internal bool IsAlive(){try{return Alive();}catch{return false;}}}
 public HelperRequestGate():this(TimeSpan.FromSeconds(10)){}
 internal HelperRequestGate(TimeSpan cooldown){if(cooldown<TimeSpan.Zero)throw new ArgumentOutOfRangeException("cooldown");cooldownSeconds=cooldown.TotalSeconds;}
 public bool TryBegin(DateTime now){lock(gate){if(running||(now-last).TotalSeconds<cooldownSeconds)return false;running=true;last=now;return true;}}
 public void Complete(){lock(gate)running=false;}
 internal void Enqueue(HelperSessionAlive alive,ThreadStart work){if(alive==null||work==null)throw new ArgumentNullException();Request request=new Request(alive,work);if(!request.IsAlive())return;bool start=false;lock(gate){
   // One queued request is enough: the latest authenticated USB session wins.
   // A repair received while startup/debounce is active must not disappear.
   if(!request.IsAlive())return;pending=request;if(!worker){worker=true;start=true;}
  }if(start){Thread thread=new Thread(Pump);thread.IsBackground=true;try{thread.Start();}catch{lock(gate)worker=false;throw;}}}
 void Pump(){for(;;){Request next=null;lock(gate){
   if(pending==null){worker=false;return;}
   if(!pending.IsAlive()){pending=null;continue;}
   DateTime now=DateTime.UtcNow;if(now<last)last=now;
   if(!running&&(now-last).TotalSeconds>=cooldownSeconds){next=pending;pending=null;running=true;last=now;}
  }if(next==null){Thread.Sleep(100);continue;}
  try{if(next.IsAlive())next.Work();}catch{/* The caller reports bootstrap failures; keep a later repair eligible. */}finally{Complete();}
 }}
}
internal sealed class HelperOverlayFiles {
 internal readonly string Jar,Script;
 internal HelperOverlayFiles(string jar,string script){Jar=jar;Script=script;}
}
internal delegate HelperOverlayFiles HelperOverlayLoader();

// The cable transport does not use ADB. System routing and hotspot setup can
// require the helper. Commands and package are fixed here; no command text
// supplied by a phone, web page or process output runs.
public static class AdbHelper {
 const string Package="moe.shizuku.privileged.api";
 internal const string OverlayDirectory="/data/local/tmp/usbbridge-helper154";
 internal const string OverlayJarHash="6afeaf913e4fd1687af9d47b72c4a23c57497d5a2de25487edc2fd4a96639a20";
 internal const string OverlayScriptHash="fdb03f69f939866b985e3bd87c4b90a35a79c719f2a1e13ee2cbd44ed8bbddbc";
 internal const string OfficialManagerHash="6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f";
 public const int BundledServerPort=5038;
 static readonly Regex SerialPattern=new Regex("^[A-Za-z0-9_.-]{1,128}$");
 static readonly Regex ApkPattern=new Regex("^package:(/data/app/(?:~~[A-Za-z0-9_-]+={0,2}/)?moe\\.shizuku\\.privileged\\.api-[A-Za-z0-9_-]+={0,2}/base\\.apk)$");
 public static HelperAdbLocation LocateAdb(string baseDirectory,string searchPath,string localDirectory,HelperFileExists exists){
  string bundled=Path.GetFullPath(Path.Combine(baseDirectory,"platform-tools\\adb.exe"));
  string bundledDirectory=Path.GetDirectoryName(bundled);string[] bundledFiles={"adb.exe","AdbWinApi.dll","AdbWinUsbApi.dll"};
  bool anyBundled=false;List<string> missing=new List<string>();
  foreach(string name in bundledFiles){if(exists(Path.Combine(bundledDirectory,name)))anyBundled=true;else missing.Add(name);}
  List<string> choices=new List<string>();
  foreach(string folder in (searchPath??"").Split(Path.PathSeparator))try{string clean=folder.Trim().Trim('"');if(clean.Length>0)choices.Add(Path.Combine(clean,"adb.exe"));}catch{}
  if(!String.IsNullOrEmpty(localDirectory))choices.Add(Path.Combine(localDirectory,"Android\\Sdk\\platform-tools\\adb.exe"));
  // Prefer an existing installation on every Windows version: its running
  // server may already own the phone. Only our bundled build requires these
  // exact DLLs; external installations may package dependencies differently.
  foreach(string path in choices)try{string full=Path.GetFullPath(path);if(!String.Equals(full,bundled,StringComparison.OrdinalIgnoreCase)&&exists(full))return new HelperAdbLocation(full,null);}catch{}
  if(missing.Count==0)return new HelperAdbLocation(bundled,null,true);
  return new HelperAdbLocation(null,anyBundled
   ? "Комплект ADB распакован не полностью: в папке platform-tools не хватает "+String.Join(", ",missing.ToArray())+". Распакуйте весь архив USB Bridge заново."
   : "Не найден adb.exe. Распакуйте весь комплект USB Bridge вместе с папкой platform-tools. Для запуска помощника также нужен уже установленный ADB-драйвер телефона.");
 }
 public static HelperAdbLocation LocateAdb(){return LocateAdb(AppDomain.CurrentDomain.BaseDirectory,Environment.GetEnvironmentVariable("PATH"),Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),File.Exists);}
 public static string FindAdb(){return LocateAdb().Executable;}
 public static string SelectUsbCandidate(string listing){
  List<string[]> candidates=new List<string[]>();
  foreach(string raw in (listing??"").Split('\n')){
   string line=raw.Trim();if(line.Length==0||line.StartsWith("List of devices attached",StringComparison.Ordinal)||line.StartsWith("*",StringComparison.Ordinal))continue;
   string[] fields=Regex.Split(line,"\\s+");if(fields.Length<2)throw new IOException("Не удалось прочитать список телефонов ADB.");string serial=fields[0];
   if(serial.StartsWith("emulator-",StringComparison.OrdinalIgnoreCase)||serial.IndexOf(':')>=0||serial.IndexOf("._adb-",StringComparison.OrdinalIgnoreCase)>=0)continue;
   if(!SerialPattern.IsMatch(serial))throw new IOException("Не удалось однозначно определить телефон по USB.");candidates.Add(fields);
  }
  if(candidates.Count==0)throw new IOException("ADB запущен, но телефон по USB не найден. Проверьте кабель и USB-отладку. На этом ПК также нужен уже установленный ADB-драйвер телефона; без него помощник по USB не запустить.");
  if(candidates.Count!=1)throw new IOException("Для настройки оставьте подключённым один телефон по USB.");
  if(candidates[0][1]=="unauthorized")throw new IOException("На телефоне разрешите USB-отладку для этого компьютера. Если запроса не видно, разблокируйте телефон и переподключите кабель. Затем повторите запуск в USB Bridge.");
  if(candidates[0][1]!="device")throw new IOException("Телефон найден через ADB, но пока не готов к настройке. Разблокируйте его, переподключите кабель и повторите попытку.");
  return candidates[0][0];
 }
 public static string StarterPath(string packagePaths,string packageDetails){
  string[] lines=(packagePaths??"").Trim().Split('\n');if(lines.Length!=1)throw new IOException("Установите Shizuku 13.6.0 из комплекта и откройте его один раз.");
  Match package=ApkPattern.Match(lines[0].Trim());if(!package.Success)throw new IOException("Не найден установленный Shizuku 13.6.0. Установите его из комплекта и откройте один раз.");
  Match version=Regex.Match(packageDetails??"","(?:^|\\s)versionName=(13\\.6\\.0(?:[.][A-Za-z0-9._-]+)?)(?:\\s|$)");
  if(!version.Success)throw new IOException("Установлена другая версия Shizuku. Её автоматический запуск пока не поддержан; приложение не будет заменено.");
  Match abi=Regex.Match(packageDetails??"","(?:^|\\s)primaryCpuAbi=(arm64-v8a|armeabi-v7a|armeabi|x86_64|x86)(?:\\s|$)");
  if(!abi.Success)throw new IOException("Не удалось определить версию системного помощника для этого телефона.");
  string architecture=abi.Groups[1].Value;architecture=architecture=="arm64-v8a"?"arm64":architecture.StartsWith("armeabi",StringComparison.Ordinal)?"arm":architecture;
  string apk=package.Groups[1].Value;return apk.Substring(0,apk.Length-"base.apk".Length)+"lib/"+architecture+"/libshizuku.so";
 }
 internal static bool OverlayRequired(string manufacturer,string optimization){string name=(manufacturer??"").Trim();return (String.Equals(name,"OnePlus",StringComparison.OrdinalIgnoreCase)||String.Equals(name,"OPPO",StringComparison.OrdinalIgnoreCase))&&String.Equals((optimization??"").Trim(),"true",StringComparison.Ordinal);}
 internal static bool HashMatches(byte[] bytes,string expected){using(SHA256 hash=SHA256.Create()){string actual=BitConverter.ToString(hash.ComputeHash(bytes)).Replace("-","").ToLowerInvariant();return String.Equals(actual,expected,StringComparison.OrdinalIgnoreCase);}}
 static HelperOverlayFiles LoadOverlay(){string folder=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"helper");string jar=Path.Combine(folder,"server.jar"),script=Path.Combine(folder,"start-helper154.sh");
  if(!File.Exists(jar)||!File.Exists(script))throw new IOException("В комплекте не хватает файлов помощника OnePlus. Распакуйте весь архив USB Bridge 0.15.4 вместе с папкой helper.");
  if(new FileInfo(jar).Length>4194304||new FileInfo(script).Length>65536||!HashMatches(File.ReadAllBytes(jar),OverlayJarHash)||!HashMatches(File.ReadAllBytes(script),OverlayScriptHash))throw new IOException("Файлы помощника OnePlus повреждены или относятся к другой версии. Распакуйте полный комплект USB Bridge 0.15.4 заново.");
  return new HelperOverlayFiles(jar,script);
 }
 internal static void VerifyRemoteHashes(string output,string[] paths,string[] hashes){string[] lines=(output??"").Trim().Split('\n');if(lines.Length!=paths.Length||paths.Length!=hashes.Length)throw new IOException("Не удалось проверить файлы помощника на телефоне. Запуск остановлен до изменения системного доступа.");
  for(int i=0;i<paths.Length;i++){Match line=Regex.Match(lines[i].Trim(),"^([0-9A-Fa-f]{64})[ \\t]+\\*?(.+)$");if(!line.Success||!String.Equals(line.Groups[1].Value,hashes[i],StringComparison.OrdinalIgnoreCase)||line.Groups[2].Value!=paths[i])throw new IOException("Файлы помощника на телефоне не совпадают с проверенным комплектом. Существующий помощник не остановлен.");}
 }
 static void StartOverlay(string adb,HelperProcessRunner run,HelperSessionAlive alive,HelperOverlayLoader load,string serial,string paths,string details,string starter){
  if(!Regex.IsMatch(details??"","(?:^|\\s)versionCode=1086(?:\\s|$)"))throw new IOException("Эта версия Shizuku не поддерживает исправление OnePlus из комплекта. Установленное приложение не будет заменено.");
  HelperOverlayFiles files=load();if(files==null)throw new IOException("Файлы помощника OnePlus недоступны. Распакуйте полный комплект USB Bridge.");
  string apk=ApkPattern.Match(paths.Trim()).Groups[1].Value,lib=starter.Substring(0,starter.LastIndexOf('/'));
  // Verify the exact official manager before staging anything. The patch only
  // tolerates its optional settings grant failing; it grants no permission.
  VerifyRemoteHashes(Call(adb,run,alive,6000,"-s",serial,"shell","sha256sum",apk).Output,new string[]{apk},new string[]{OfficialManagerHash});
  Call(adb,run,alive,6000,"-s",serial,"shell","mkdir","-p","-m","700",OverlayDirectory);
  string jar=OverlayDirectory+"/server.jar",script=OverlayDirectory+"/start-helper154.sh";
  // Upload to new inodes, so a retry never truncates a read-only/running DEX.
  Call(adb,run,alive,15000,"-s",serial,"push",files.Jar,jar+".upload");
  Call(adb,run,alive,6000,"-s",serial,"push",files.Script,script+".upload");
  VerifyRemoteHashes(Call(adb,run,alive,6000,"-s",serial,"shell","sha256sum",jar+".upload",script+".upload").Output,new string[]{jar+".upload",script+".upload"},new string[]{OverlayJarHash,OverlayScriptHash});
  Call(adb,run,alive,6000,"-s",serial,"shell","mv",jar+".upload",jar);
  Call(adb,run,alive,6000,"-s",serial,"shell","mv",script+".upload",script);
  Call(adb,run,alive,6000,"-s",serial,"shell","chmod","444",jar,script);
  Call(adb,run,alive,20000,"-s",serial,"shell","sh",script,apk,lib);
 }
 static HelperProcessResult Call(string executable,HelperProcessRunner run,HelperSessionAlive alive,int timeout,params string[] args){
  if(!alive())throw new IOException("Кабель отключён. Подключите телефон и повторите настройку.");
  HelperProcessResult result=run(executable,args,timeout);if(result==null)throw new IOException("Не удалось получить результат запуска ADB. Повторите настройку.");
  if(result.TimedOut)throw new IOException("ADB не ответил вовремя. Разблокируйте телефон и повторите настройку.");
  if(result.ExitCode!=0){string stage=args[0]=="devices"?"получить список телефонов":args[0]=="-d"?"выбрать единственный телефон по USB":args.Length>2&&args[2]=="push"?"передать файлы помощника":args.Length>3&&args[3]=="sha256sum"?"проверить файлы помощника":args.Length>3&&args[3]=="sh"?"запустить помощника":args.Length>4?"подготовить системный помощник":"запустить помощника";throw new IOException("ADB не смог "+stage+" (код "+result.ExitCode+"). Проверьте USB-отладку и разрешение на телефоне; системный доступ пока не подтверждён.");}
  return result;
 }
 public static string TryStart(HelperSessionAlive alive){return TryStart(LocateAdb(),Run,alive,delegate{return CheckAdbPort(BundledServerPort);});}
 public static string TryStart(HelperAdbLocation adb,HelperProcessRunner run,HelperSessionAlive alive,HelperAdbPortAvailable available){
  if(adb.Executable==null)return adb.Problem;
  if(!adb.Bundled)return TryStart(adb.Executable,run,alive);
  // Bundled ADB 28.0.1 uses protocol 40. It must never touch a newer SDK's
  // default server or auto-replace an incompatible server on our private port.
  HelperProcessRunner isolated=delegate(string exe,string[] args,int timeout){
   if(!available())throw new IOException("Запуск помощника остановлен: служебный порт USB Bridge занят другой программой. Другие ADB-подключения не изменены.");
   string[] prefixed=new string[args.Length+2];prefixed[0]="-P";prefixed[1]=BundledServerPort.ToString(System.Globalization.CultureInfo.InvariantCulture);Array.Copy(args,0,prefixed,2,args.Length);return run(exe,prefixed,timeout);
  };
  return TryStart(adb.Executable,isolated,alive);
 }
 public static bool CheckAdbPort(int port){
  using(TcpClient client=new TcpClient()){
   try{
    IAsyncResult connecting=client.BeginConnect(IPAddress.Loopback,port,null,null);
    // Windows may spend about one second retrying loopback SYN before reporting
    // WSAECONNREFUSED. A shorter deadline mistakes a free port for a conflict.
    using(WaitHandle wait=connecting.AsyncWaitHandle){if(!wait.WaitOne(2500,false))return false;client.EndConnect(connecting);}
   }catch(SocketException error){return error.SocketErrorCode==SocketError.ConnectionRefused;}catch{return false;}
   try{
    client.SendTimeout=500;client.ReceiveTimeout=500;NetworkStream stream=client.GetStream();byte[] query=Encoding.ASCII.GetBytes("000chost:version");stream.Write(query,0,query.Length);
    byte[] reply=new byte[12];int offset=0;Stopwatch deadline=Stopwatch.StartNew();while(offset<reply.Length){if(deadline.ElapsedMilliseconds>=1000)return false;int read=stream.Read(reply,offset,reply.Length-offset);if(read<=0)return false;offset+=read;}
    return String.Equals(Encoding.ASCII.GetString(reply),"OKAY00040028",StringComparison.OrdinalIgnoreCase);
   }catch{return false;}
  }
 }
 public static string TryStart(string adb,HelperProcessRunner run,HelperSessionAlive alive){return TryStart(adb,run,alive,LoadOverlay);}
 internal static string TryStart(string adb,HelperProcessRunner run,HelperSessionAlive alive,HelperOverlayLoader load){try{
  string serial=SelectUsbCandidate(Call(adb,run,alive,6000,"devices","-l").Output);
  // -d is ADB's authoritative USB-only selector. This also rejects a second USB
  // phone and never targets a TCP/wireless connection or an emulator.
  string physical=Call(adb,run,alive,6000,"-d","get-serialno").Output.Trim();if(physical!=serial)throw new IOException("Не удалось однозначно определить телефон по USB. Оставьте подключённым один телефон.");
  string paths=Call(adb,run,alive,6000,"-s",serial,"shell","pm","path",Package).Output;
  string details=Call(adb,run,alive,6000,"-s",serial,"shell","dumpsys","package",Package).Output;
  string starter=StarterPath(paths,details);
  string manufacturer=Call(adb,run,alive,6000,"-s",serial,"shell","getprop","ro.product.manufacturer").Output;
  string optimization=Call(adb,run,alive,6000,"-s",serial,"shell","getprop","persist.sys.permission.enable").Output;
  if(OverlayRequired(manufacturer,optimization))StartOverlay(adb,run,alive,load,serial,paths,details,starter);
  else Call(adb,run,alive,20000,"-s",serial,"shell",starter);
  return "Команда запуска отправлена. Телефон проверяет системный доступ; если появится запрос USB Bridge, разрешите его.";
 }catch(IOException error){return error.Message;}catch(Win32Exception error){
  if(error.NativeErrorCode==2||error.NativeErrorCode==3)return "Файл ADB больше не найден. Распакуйте весь комплект USB Bridge заново.";
  if(error.NativeErrorCode==5)return "Windows запретила запуск ADB. Системный доступ не запущен; проверьте ограничения запуска программ на этом ПК.";
  if(error.NativeErrorCode==126)return "Windows не смогла загрузить библиотеку ADB. Распакуйте весь комплект, включая папку platform-tools.";
  if(error.NativeErrorCode==193||error.NativeErrorCode==216)return "Эта версия ADB несовместима с Windows на этом ПК. Используйте полный комплект USB Bridge для Windows 7.";
  return "Windows не смогла запустить ADB (код "+error.NativeErrorCode+"). Системный доступ не подтверждён.";
 }catch(Exception){return "Не удалось запустить ADB на этом компьютере. Системный доступ не подтверждён. Используйте полный комплект USB Bridge для вашей Windows.";}}
 public static string QuoteArgument(string value){
  StringBuilder result=new StringBuilder("\"");int slashes=0;foreach(char c in value){if(c=='\\'){slashes++;continue;}if(c=='"'){result.Append('\\',slashes*2+1);result.Append(c);}else{result.Append('\\',slashes);result.Append(c);}slashes=0;}result.Append('\\',slashes*2);result.Append('"');return result.ToString();
 }
 internal static void ConfigureServerEnvironment(ProcessStartInfo info,string[] arguments){
  if(arguments.Length<2||arguments[0]!="-P"||arguments[1]!=BundledServerPort.ToString(System.Globalization.CultureInfo.InvariantCulture))return;
  // ADB_SERVER_SOCKET overrides host/port selection in some ADB versions.
  // Remove redirection only from this child's environment; retain user keys.
  foreach(string name in new string[]{"ADB_SERVER_SOCKET","ANDROID_ADB_SERVER_ADDRESS","ANDROID_ADB_SERVER_PORT","ADB_SERVER_PORT"})info.EnvironmentVariables.Remove(name);
 }
 static HelperProcessResult Run(string executable,string[] arguments,int timeoutMs){
  StringBuilder command=new StringBuilder();foreach(string value in arguments){if(command.Length>0)command.Append(' ');command.Append(QuoteArgument(value));}
  using(Process process=new Process()){
   process.StartInfo=new ProcessStartInfo(executable,command.ToString()){UseShellExecute=false,CreateNoWindow=true,WindowStyle=ProcessWindowStyle.Hidden,RedirectStandardOutput=true,RedirectStandardError=true};
   ConfigureServerEnvironment(process.StartInfo,arguments);
   StringBuilder output=new StringBuilder(),error=new StringBuilder();object gate=new object();bool outputDone=false,errorDone=false;
   process.OutputDataReceived+=delegate(object sender,DataReceivedEventArgs e){lock(gate){if(e.Data==null){outputDone=true;Monitor.PulseAll(gate);}else if(output.Length<131072)output.AppendLine(e.Data);}};
   process.ErrorDataReceived+=delegate(object sender,DataReceivedEventArgs e){lock(gate){if(e.Data==null){errorDone=true;Monitor.PulseAll(gate);}else if(error.Length<4096)error.AppendLine(e.Data);}};
   process.Start();process.BeginOutputReadLine();process.BeginErrorReadLine();
   bool exited=process.WaitForExit(timeoutMs);if(!exited){try{process.Kill();}catch{}process.WaitForExit(1000);}
   // Bounded wait for async readers. Never WaitForExit() without a timeout: a
   // spawned adb daemon may retain redirected handles on some old builds.
   Stopwatch drain=Stopwatch.StartNew();lock(gate){while((!outputDone||!errorDone)&&drain.ElapsedMilliseconds<1000)Monitor.Wait(gate,Math.Max(1,1000-(int)drain.ElapsedMilliseconds));return new HelperProcessResult{ExitCode=exited?process.ExitCode:-1,TimedOut=!exited,Output=output.ToString(),Error=error.ToString()};}
  }
 }
}
}
