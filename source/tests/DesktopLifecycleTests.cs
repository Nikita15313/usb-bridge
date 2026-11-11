using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Diagnostics;
using System.Threading;
using System.Windows.Forms;
using System.Collections.Generic;
using UsbBridge;

class DesktopLifecycleTests {
 static int passed;
 static readonly BindingFlags Private=BindingFlags.Instance|BindingFlags.NonPublic;
 static void Set(object target,string name,object value){target.GetType().GetField(name,Private).SetValue(target,value);}
 static object Get(object target,string name){return target.GetType().GetField(name,Private).GetValue(target);}
 static void Call(object target,string name,params object[] args){target.GetType().GetMethod(name,Private).Invoke(target,args);}
 static void Check(bool ok,string name){if(!ok)throw new Exception(name);passed++;Console.WriteLine("PASS "+name);}
 sealed class Pair:IDisposable {
  readonly TcpClient pc=new TcpClient();readonly TcpClient phone;readonly SecureWire peer;public readonly Mux mux;
  public Pair(){TcpListener listener=new TcpListener(IPAddress.Loopback,0);listener.Start();pc.Connect((IPEndPoint)listener.LocalEndpoint);phone=listener.AcceptTcpClient();listener.Stop();pc.ReceiveTimeout=phone.ReceiveTimeout=3000;SecureWire incoming=null;Exception error=null;ManualResetEvent ready=new ManualResetEvent(false);Wire.Run(delegate{try{incoming=new SecureWire(phone.GetStream(),"ui-regression",false);}catch(Exception e){error=e;}finally{ready.Set();}});SecureWire outgoing=new SecureWire(pc.GetStream(),"ui-regression",true);if(!ready.WaitOne(3000,false)||error!=null)throw new Exception("Encrypted fixture handshake",error);peer=incoming;mux=new Mux(outgoing);}
  public void Dispose(){mux.Dispose();peer.Dispose();pc.Close();phone.Close();}
 }
 static void SetSession(MainForm form,Pair pair,int gen,int attempt){Set(form,"stopping",false);Set(form,"generation",gen);Set(form,"attemptEpoch",attempt);Set(form,"mux",pair.mux);}
 static void State(MainForm form,Pair pair,int gen,int attempt,string label){Call(form,"SessionStatus",gen,attempt,pair.mux,"MIDI","STATE|1|"+label+"|Wi-Fi test|HTTPS test|MIDI");}
 static string Status(MainForm form){return ((Label)Get(form,"status")).Text;}
 static int ChildMutex(string name){using(SingleInstance value=new SingleInstance(name))return value.IsOwner?0:7;}
 static int ProbeMutex(string name){ProcessStartInfo start=new ProcessStartInfo(Assembly.GetExecutingAssembly().Location,"--mutex "+name);start.UseShellExecute=false;start.CreateNoWindow=true;using(Process p=Process.Start(start)){if(!p.WaitForExit(4000)){p.Kill();throw new Exception("Mutex child stuck");}return p.ExitCode;}}
 [STAThread]static int Main(string[] args){try{
  if(args.Length==2&&args[0]=="--mutex")return ChildMutex(args[1]);
  Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
  using(MainForm form=new MainForm(false))using(Pair first=new Pair())using(Pair second=new Pair()){
   form.Show();Application.DoEvents();SetSession(form,first,1,1);
   Check(((System.Windows.Forms.Timer)Get(form,"ticker")).Interval==200,"desktop transfer display samples every 200 ms");
   Label transfer=(Label)Get(form,"stats");Call(form,"UpdateTransferRates");int changed=0;transfer.TextChanged+=delegate{changed++;};Call(form,"UpdateTransferRates");Call(form,"UpdateTransferRates");Check(changed==0,"unchanged idle counter text is not repainted repeatedly");
   State(form,first,1,1,"fresh");Application.DoEvents();Check(Status(form)=="✓ fresh","current authenticated session updates proof");
   State(form,first,1,1,"stale closed");first.mux.Dispose();Application.DoEvents();Check(!Status(form).StartsWith("✓"),"queued proof cannot turn green after cable closes");
   SetSession(form,second,1,2);State(form,second,1,2,"next attempt");Application.DoEvents();
   State(form,first,1,1,"old attempt");Application.DoEvents();Check(Status(form)=="✓ next attempt","late frame from old attempt cannot overwrite current session");
   State(form,second,1,2,"queued before retry");Set(form,"attemptEpoch",3);((Label)Get(form,"status")).Text="waiting for retry";Application.DoEvents();Check(Status(form)=="waiting for retry","already queued old-attempt frame discarded after retry starts");
   SetSession(form,second,2,4);State(form,second,2,4,"queued before stop");Call(form,"Stop");Application.DoEvents();Check(Status(form)=="Отключено","Stop invalidates queued success without reviving connection");
   Check(transfer.Text=="","Stop immediately clears live speed text");
   State(form,second,2,4,"after stop");Application.DoEvents();Check(Status(form)=="Отключено","late callback after Stop is ignored");
   Set(form,"stopping",false);Set(form,"generation",3);Set(form,"attemptEpoch",5);((Label)Get(form,"status")).Text="new run";
   Call(form,"AttemptUi",2,4,(MethodInvoker)delegate{((Label)Get(form,"status")).Text="old failure";});Application.DoEvents();Check(Status(form)=="new run","old failure callback cannot clear a replacement run");
   Call(form,"Stop");ComboBox sources=(ComboBox)Get(form,"adapters");Adapter missing=new Adapter{Name="Manually chosen unavailable source",Address=IPAddress.Parse("192.0.2.93"),Index=12345};sources.Items.Add(missing);sources.SelectedItem=missing;Call(form,"RefreshDevices");Check(sources.SelectedItem==missing,"missing manually chosen source does not silently become automatic");
   SetSession(form,second,4,6);((ModernButton)Get(form,"connect")).Text="Остановить";Call(form,"StopForCableLoss",4,6);Application.DoEvents();Check((bool)Get(form,"stopping")&&((ModernButton)Get(form,"connect")).Text=="Подключить"&&sources.Enabled,"device removal ends the search and restores Connect");
   Check(Status(form)=="Телефон отключён","removed device has a clear disconnected status");
   SetSession(form,second,5,7);((Label)Get(form,"status")).Text="new cable";Call(form,"StopForCableLoss",4,6);Application.DoEvents();Check(!(bool)Get(form,"stopping")&&Status(form)=="new cable","stale removal cannot stop a replacement cable session");
   Call(form,"StopForCableLoss",5,7);SetSession(form,second,7,8);((Label)Get(form,"status")).Text="replacement started";Application.DoEvents();Check(Status(form)=="replacement started","queued removal UI cannot overwrite a newer connection");Call(form,"Stop");
   Set(form,"preparingPhone",true);int beforePreparationGeneration=(int)Get(form,"generation");Call(form,"Start");Check((bool)Get(form,"stopping")&&(int)Get(form,"generation")==beforePreparationGeneration,"home preparation prevents a new cable session after its dialog closes");
   Set(form,"readyUpdate",new UpdateRelease{Code=157,Name="0.15.7"});Set(form,"updateFolder","C:\\not-an-installation");Call(form,"UpdateUpdateControls");Check(!((ModernButton)Get(form,"updateButton")).Enabled,"update installation stays disabled during phone preparation");Call(form,"InstallUpdate");Check((bool)Get(form,"preparingPhone"),"pending update cannot launch or close the application during phone preparation");Set(form,"preparingPhone",false);Set(form,"readyUpdate",null);Set(form,"updateFolder","");
   TcpAdbCableTarget captured=TcpAdbCableTarget.FromAuthenticatedLink(IPAddress.Parse("192.0.2.2"),IPAddress.Parse("192.0.2.1"),IPAddress.Parse("255.255.255.0"),44,"test-usb",true);Set(form,"tcpAdbTarget",captured);Set(form,"tcpAdbSession",second.mux);Call(form,"Stop");Check(Get(form,"tcpAdbTarget")==null&&Get(form,"tcpAdbSession")==null,"Stop discards the authenticated cable target instead of retaining an old phone");
   form.Close();
  }
  List<Adapter> empty=new List<Adapter>();string[] noPorts=new string[0];Adapter cableAdapter=new Adapter{Id="phone-interface",Index=55,Address=IPAddress.Parse("192.0.2.2")};CablePresence cable=new CablePresence(cableAdapter,"0 · MIDI function","1 · MIDI function");
  Check(cable.Present(new List<Adapter>{new Adapter{Id="phone-interface",Index=55,Address=IPAddress.Parse("192.0.2.3")}},noPorts,noPorts),"USB address renewal on the same interface is not an unplug");
  Check(cable.Present(empty,new string[]{"4 · MIDI function"},new string[]{"7 · MIDI function"}),"MIDI device index changes do not look like an unplug");
  Check(cable.Present(new List<Adapter>{new Adapter{UsbPhone=true}},noPorts,noPorts),"MIDI to USB-modem change remains eligible for reconnect");
  Check(!cable.Present(new List<Adapter>{new Adapter{UsbCandidate=true,Id="dock"}},new string[]{"0 · Piano"},new string[]{"0 · Piano"}),"unrelated USB dock and MIDI instrument do not hide phone removal");
  Check(!cable.Removed(false,100)&&!cable.Removed(false,1000),"brief USB enumeration gap does not stop reconnect");
  Check(!cable.Removed(true,1100)&&!cable.Removed(false,1300)&&!cable.Removed(false,2000)&&cable.Removed(false,2500),"presence recovery resets removal debounce; sustained absence ends session");
  cable=new CablePresence(null,"","");Check(!cable.Removed(false,Int32.MaxValue-100)&&cable.Removed(false,unchecked(Int32.MaxValue+1200)),"removal timer survives Windows tick counter wraparound");
  string name="Local\\USBBridge.Test."+Guid.NewGuid().ToString("N");
  using(SingleInstance owner=new SingleInstance(name)){Check(owner.IsOwner,"first desktop process owns mutex");Check(ProbeMutex(name)==7,"second process cannot own same bridge session");}
  Check(ProbeMutex(name)==0,"new process can start after previous owner exits");
  Console.WriteLine("TOTAL "+passed+" passed");return 0;
 }catch(Exception e){Console.Error.WriteLine(e);return 1;}}
}
