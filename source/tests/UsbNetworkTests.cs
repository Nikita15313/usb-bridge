using System;
using System.IO;
using System.Text;
using System.Net;
using System.Net.Sockets;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Diagnostics;
using System.Collections.Generic;
using System.Threading;
using UsbBridge;
class UsbNetworkTests {
 static int count;
 static void Check(bool value,string name){if(!value)throw new Exception(name);Console.WriteLine("PASS "+name);count++;}
 sealed class RejectingTls:SslStream {
  readonly Exception failure;public SslProtocols Requested;public int Calls;
  public RejectingTls(Exception error):base(new MemoryStream(),false){failure=error;}
  public override void AuthenticateAsClient(string host,X509CertificateCollection certificates,SslProtocols protocols,bool revocation){Requested=protocols;Calls++;throw failure;}
 }
 static void LegacyTlsFailure(){
  Exception[] errors={new ArgumentException("Unsupported value","sslProtocolType"),new ArgumentException("Unsupported value","enabledSslProtocols"),new AuthenticationException("Certificate rejected"),new IOException("Connection timed out"),new NotSupportedException("Other platform feature"),new ArgumentException("Invalid host","targetHost")};
  foreach(Exception error in errors)using(RejectingTls tls=new RejectingTls(error)){
   Exception actual=null;try{UsbNetwork.AuthenticateProbe(tls,"example.test");}catch(Exception caught){actual=caught;}
   ArgumentException argument=error as ArgumentException;bool legacy=argument!=null&&(argument.ParamName=="sslProtocolType"||argument.ParamName=="enabledSslProtocols");
   Check(legacy?actual is Tls12UnavailableException:Object.ReferenceEquals(actual,error),"TLS failure classification retains exact origin: "+error.GetType().Name+(argument==null?"":" "+argument.ParamName));
   Check(tls.Calls==1&&(int)tls.Requested==3072,"TLS probe never retries with a legacy protocol after "+error.GetType().Name);
  }
  Adapter source=new Adapter{Name="Ethernet",Address=IPAddress.Parse("192.168.0.2"),Dns=IPAddress.Parse("192.168.0.1")};int attempts=0;string reason;bool propagated=false;
  try{UsbNetwork.VerifyUplink(source,8000,delegate{return true;},out reason,delegate(Adapter a,string host,int ms,CableAlive running,out string why){attempts++;why="";using(RejectingTls tls=new RejectingTls(errors[0]))UsbNetwork.AuthenticateProbe(tls,host);return true;});}catch(Tls12UnavailableException){propagated=true;}
  Check(propagated&&attempts==1&&!source.UplinkVerified&&!source.UplinkCheckDeferred,"HTTPS verifier preserves explicit unsupported protocol for source selection without marking success");
  attempts=0;bool verified=UsbNetwork.VerifyUplink(source,8000,delegate{return true;},out reason,delegate(Adapter a,string host,int ms,CableAlive running,out string why){attempts++;why="";using(RejectingTls tls=new RejectingTls(errors[2]))UsbNetwork.AuthenticateProbe(tls,host);return true;});
  Check(!verified&&attempts==3&&!source.UplinkCheckDeferred,"certificate failure uses bounded HTTPS alternatives and never defers as legacy runtime");
 }
 static Adapter Phone(string address,string gateway){return new Adapter{Name="phone",Address=IPAddress.Parse(address),Mask=IPAddress.Parse("255.255.255.0"),Gateway=IPAddress.Parse(gateway),UsbPhone=true};}
 static void LocalHttpsProbe(bool cancel){
  TcpListener dns=new TcpListener(IPAddress.Loopback,0),https=new TcpListener(IPAddress.Loopback,0);dns.Start();https.Start();int dnsPort=((IPEndPoint)dns.LocalEndpoint).Port,httpsPort=((IPEndPoint)https.LocalEndpoint).Port;
  ManualResetEvent dnsDone=new ManualResetEvent(false),httpsDone=new ManualResetEvent(false),connected=new ManualResetEvent(false);Exception dnsError=null;bool active=true,sawTls=false;IPAddress dnsOrigin=null,httpsOrigin=null;
  Wire.Run(delegate{try{using(TcpClient request=dns.AcceptTcpClient()){request.ReceiveTimeout=2000;dnsOrigin=((IPEndPoint)request.Client.RemoteEndPoint).Address;Stream stream=request.GetStream();byte[] size=Wire.Read(stream,2);byte[] question=Wire.Read(stream,size[0]*256+size[1]);byte[] answer=new byte[question.Length+16];Buffer.BlockCopy(question,0,answer,0,question.Length);answer[2]=129;answer[3]=128;answer[7]=1;byte[] record={192,12,0,1,0,1,0,0,0,60,0,4,127,0,0,1};Buffer.BlockCopy(record,0,answer,question.Length,record.Length);stream.Write(new byte[]{(byte)(answer.Length>>8),(byte)answer.Length},0,2);stream.Write(answer,0,answer.Length);}}catch(Exception error){dnsError=error;}finally{dnsDone.Set();}});
  Wire.Run(delegate{try{using(TcpClient request=https.AcceptTcpClient()){request.ReceiveTimeout=3000;httpsOrigin=((IPEndPoint)request.Client.RemoteEndPoint).Address;sawTls=true;connected.Set();if(cancel){while(request.GetStream().ReadByte()!=-1){}}else{X509Certificate2 cert=new X509Certificate2(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"untrusted-probe-fixture.pfx"),"");try{using(SslStream tls=new SslStream(request.GetStream(),false)){tls.AuthenticateAsServer(cert,false,(SslProtocols)3072,false);byte[] content=Encoding.ASCII.GetBytes("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n");tls.Write(content,0,content.Length);}}finally{cert.Reset();}}}}catch(IOException){}catch(AuthenticationException){}finally{httpsDone.Set();}});
  if(cancel)Wire.Run(delegate{if(connected.WaitOne(2000,false))active=false;});
  Adapter source=new Adapter{Name="local source fixture",Address=IPAddress.Parse("127.0.0.2"),Dns=IPAddress.Loopback};Stopwatch elapsed=Stopwatch.StartNew();string reason;bool good=UsbNetwork.ProbeHttpsAtPorts(source,"localhost",dnsPort,httpsPort,3000,delegate{return active;},out reason);
  bool finished=dnsDone.WaitOne(3000,false)&&httpsDone.WaitOne(3000,false);dns.Stop();https.Stop();
  Check(!good&&sawTls&&dnsError==null&&finished,cancel?"canceling an actual stalled TLS probe closes its sockets":"self-signed HTTPS certificate cannot validate an uplink despite HTTP 204");
  Check(source.Address.Equals(dnsOrigin)&&source.Address.Equals(httpsOrigin),cancel?"canceled TLS probe and its DNS stayed on selected source":"actual DNS and TLS connections both use the selected source address");
  if(cancel)Check(elapsed.ElapsedMilliseconds<2000,"canceling TLS does not wait for the full network budget");dnsDone.Close();httpsDone.Close();connected.Close();
 }
 static void UplinkVerification(){
  Adapter source=new Adapter{Name="Ethernet",Address=IPAddress.Parse("192.168.0.2"),Dns=IPAddress.Parse("192.168.0.1"),Index=21};List<string> hosts=new List<string>();bool active=true;string reason;
  bool verified=UsbNetwork.VerifyUplink(source,8000,delegate{return active;},out reason,delegate(Adapter a,string host,int ms,CableAlive running,out string why){hosts.Add(host);if(ms<1||ms>8000||a!=source)throw new Exception("Unbounded or changed source");why="first site unavailable";return hosts.Count==2;});
  Check(verified&&hosts.Count==2&&hosts[0]!=hosts[1],"uplink verification tolerates failure of the first HTTPS endpoint");
  hosts.Clear();verified=UsbNetwork.VerifyUplink(source,8000,delegate{return true;},out reason,delegate(Adapter a,string host,int ms,CableAlive running,out string why){hosts.Add(host);why="blocked";return hosts.Count==3;});
  Check(verified&&hosts.Count==3&&hosts[2].Contains("cloudflare.com"),"second fallback reaches a different HTTPS operator");
  hosts.Clear();active=false;verified=UsbNetwork.VerifyUplink(source,8000,delegate{return active;},out reason,delegate(Adapter a,string host,int ms,CableAlive running,out string why){hosts.Add(host);why="";return true;});
  Check(!verified&&hosts.Count==0,"canceled uplink verification makes no network attempts");active=true;
  verified=UsbNetwork.VerifyUplink(source,8000,delegate{return active;},out reason,delegate(Adapter a,string host,int ms,CableAlive running,out string why){active=false;why="late result";return true;});
  Check(!verified,"cancellation discards a successful HTTPS result");active=true;
  int attempts=0;verified=UsbNetwork.VerifyUplink(source,30,delegate{return true;},out reason,delegate(Adapter a,string host,int ms,CableAlive running,out string why){attempts++;Thread.Sleep(ms+30);why="late result";return true;});
  Check(!verified&&attempts==1,"HTTPS fallback respects the total verification deadline");
  source.Dns=null;attempts=0;verified=UsbNetwork.VerifyUplink(source,8000,delegate{return true;},out reason,delegate(Adapter a,string host,int ms,CableAlive running,out string why){attempts++;why="";return true;});
  Check(!verified&&attempts==0,"missing configured DNS never invokes an HTTPS probe or public DNS fallback");source.Dns=IPAddress.Parse("192.168.0.1");source.UsbPhone=true;
  verified=UsbNetwork.VerifyUplink(source,8000,delegate{return true;},out reason,delegate(Adapter a,string host,int ms,CableAlive running,out string why){attempts++;why="";return true;});Check(!verified&&attempts==0,"known phone interface cannot pass as PC uplink");
  Check(UsbNetwork.IsHttp204("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n")&&UsbNetwork.IsHttp204("HTTP/1.0 204\r\n\r\n"),"only expected no-content HTTP success is accepted");
  Check(!UsbNetwork.IsHttp204("HTTP/1.1 200 OK\r\n\r\n")&&!UsbNetwork.IsHttp204("HTTP/1.1 302 Found\r\nLocation: /login\r\n\r\n")&&!UsbNetwork.IsHttp204("HTTP/1.1 2040 Bad\r\n\r\n"),"captive portal and prefix status codes are rejected");
  Check(!UsbNetwork.IsHttp204("HTTP/1.1 204 No Content\r\nContent-Length: 100\r\n\r\n")&&!UsbNetwork.IsHttp204("HTTP/1.1 204 No Content\r\nLocation: /login\r\n\r\n"),"unexpected body or redirect cannot validate an uplink");
  Exception legacy=new ArgumentException("Unsupported value","sslProtocolType"),unavailable=new NotSupportedException("TLS is unavailable");
  Check(UsbNetwork.ExplainTlsFailure(legacy) is Tls12UnavailableException&&UsbNetwork.ExplainTlsFailure(legacy).Message.Contains("телефон")&&Object.ReferenceEquals(UsbNetwork.ExplainTlsFailure(unavailable),unavailable),"only explicit TLS enum rejection defers proof to Android; generic unsupported errors stay failures");
  Exception certificateError=new AuthenticationException("Untrusted certificate"),badArgument=new ArgumentException("Invalid host","targetHost");Check(Object.ReferenceEquals(UsbNetwork.ExplainTlsFailure(certificateError),certificateError)&&Object.ReferenceEquals(UsbNetwork.ExplainTlsFailure(badArgument),badArgument),"certificate failures are not misreported as an obsolete Windows version");
  byte[] dns={0,1,128,0,0,0,0,1,0,0,0,0,0,0,1,0,1,0,0,0,60,0,4,192,0,2,1};
  Check(UsbNetwork.ProbeAddress(dns).Equals(IPAddress.Parse("192.0.2.1")),"probe uses the IPv4 answer returned by its selected DNS");
  bool rejected=false;try{UsbNetwork.ProbeAddress(new byte[]{0,0});}catch(IOException){rejected=true;}Check(rejected,"truncated DNS is rejected before endpoint connection");
  dns[3]=3;rejected=false;try{UsbNetwork.ProbeAddress(dns);}catch(IOException){rejected=true;}Check(rejected,"DNS negative result is not treated as a working uplink");
  int loopIndex=0;foreach(NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())if(ni.NetworkInterfaceType==NetworkInterfaceType.Loopback)loopIndex=ni.GetIPProperties().GetIPv4Properties().Index;
  Adapter loop=new Adapter{Address=IPAddress.Parse("127.0.0.2"),Index=loopIndex};
  using(Socket tcp=UsbNetwork.ProbeSocket(loop,SocketType.Stream,500))using(Socket udp=UsbNetwork.ProbeSocket(loop,SocketType.Dgram,500)){
   Check(((IPEndPoint)tcp.LocalEndPoint).Address.Equals(loop.Address)&&((IPEndPoint)udp.LocalEndPoint).Address.Equals(loop.Address),"both TCP and UDP probe sockets bind the exact source address");
   Check(loopIndex>0&&(int)tcp.GetSocketOption(SocketOptionLevel.IP,(SocketOptionName)31)==loopIndex&&(int)udp.GetSocketOption(SocketOptionLevel.IP,(SocketOptionName)31)==loopIndex,"both probe transports pin IP_UNICAST_IF to the selected interface");
  }
 }
 static int Main(){try{
  Adapter first=Phone("192.168.40.2","192.168.40.1"),second=Phone("192.168.50.2","192.168.50.1");first.DhcpServer=IPAddress.Parse("192.168.40.129");
  List<string> attempts=new List<string>();bool alive=true;
  UsbOpenAttempt open=delegate(Adapter a,IPAddress peer,int ms,CableAlive valid,Action<IDisposable> opening){attempts.Add(peer.ToString());if(ms<=0||ms>3000)throw new Exception("Unbounded attempt");if(peer.Equals(first.Gateway))throw new IOException("No bridge at gateway");return new UsbNetworkLink{Adapter=a,Peer=peer};};
  UsbNetworkLink link=UsbNetwork.TryOpen(new List<Adapter>(new Adapter[]{first,second}),delegate{return alive;},null,null,open);
  Check(link!=null&&link.Adapter==first&&link.Peer.Equals(first.DhcpServer)&&attempts.Count==2,"unresponsive gateway falls through to on-link DHCP peer in same cycle");link.Dispose();
  attempts.Clear();UsbNetworkLink missing=UsbNetwork.TryOpen(new List<Adapter>(new Adapter[]{second}),delegate{return true;},null,null,delegate(Adapter a,IPAddress p,int ms,CableAlive valid,Action<IDisposable> opening){attempts.Add(p.ToString());throw new IOException("Not running");});
  Check(missing==null&&attempts.Count==1,"failed USB handshake returns control for immediate MIDI fallback");
  attempts.Clear();alive=false;link=UsbNetwork.TryOpen(new List<Adapter>(new Adapter[]{first}),delegate{return alive;},null,null,open);Check(link==null&&attempts.Count==0,"stopped session does not probe USB peers");
  alive=true;link=UsbNetwork.TryOpen(new List<Adapter>(new Adapter[]{first}),delegate{return alive;},null,null,delegate(Adapter a,IPAddress p,int ms,CableAlive valid,Action<IDisposable> opening){alive=false;return new UsbNetworkLink{Adapter=a,Peer=p};});Check(link==null,"session canceled during successful probe is not published");
  List<Adapter> many=new List<Adapter>();for(int n=1;n<12;n++)many.Add(Phone("192.168."+n+".2","192.168."+n+".1"));int tries=0;
  link=UsbNetwork.TryOpen(many,delegate{return true;},null,null,delegate(Adapter a,IPAddress p,int ms,CableAlive valid,Action<IDisposable> opening){tries++;throw new IOException();});Check(link==null&&tries==6,"discovery work is capped even with many USB adapters");
  TcpListener listener=new TcpListener(IPAddress.Loopback,0);listener.Start();int port=((IPEndPoint)listener.LocalEndpoint).Port;Exception serverError=null;ManualResetEvent done=new ManualResetEvent(false);IPAddress origin=null;
  Wire.Run(delegate{try{using(TcpClient phone=listener.AcceptTcpClient()){phone.ReceiveTimeout=5000;phone.NoDelay=true;origin=((IPEndPoint)phone.Client.RemoteEndPoint).Address;Stream stream=phone.GetStream();if(Encoding.ASCII.GetString(Wire.Read(stream,8))!="USBBR05!")throw new IOException("Invalid preface");byte[] key=Wire.Random(32);stream.Write(key,0,key.Length);using(SecureWire secure=new SecureWire(stream,BitConverter.ToString(key).Replace("-",""),false)){byte[] data=secure.Receive();secure.Send(data);}}}catch(Exception error){serverError=error;}finally{done.Set();}});
  Adapter local=new Adapter{Address=IPAddress.Parse("127.0.0.2"),Name="loopback fixture"};
  using(UsbNetworkLink real=UsbNetwork.OpenCandidateAtPort(local,IPAddress.Loopback,port,4000,delegate{return true;},null)){byte[] message=Encoding.ASCII.GetBytes("USB-authenticated-payload");real.Wire.Send(message);Check(Wire.Equal(real.Wire.Receive(),message),"actual socket completes USB preface authentication and encrypted payload");}
  Check(done.WaitOne(5000,false)&&serverError==null&&origin.Equals(local.Address),"USB transport stays bound to detected cable address");listener.Stop();done.Close();
  TcpListener silent=new TcpListener(IPAddress.Loopback,0);silent.Start();int silentPort=((IPEndPoint)silent.LocalEndpoint).Port;TcpClient accepted=null;ManualResetEvent joined=new ManualResetEvent(false);
  Wire.Run(delegate{try{accepted=silent.AcceptTcpClient();}finally{joined.Set();}});Stopwatch elapsed=Stopwatch.StartNew();bool rejected=false;
  try{UsbNetwork.OpenCandidateAtPort(local,IPAddress.Loopback,silentPort,350,delegate{return true;},null);}catch(Exception){rejected=true;}
  Check(rejected&&elapsed.ElapsedMilliseconds<2000,"silent non-Bridge endpoint cannot stall auto selection indefinitely");joined.WaitOne(2000,false);if(accepted!=null)accepted.Close();silent.Stop();joined.Close();
  UplinkVerification();
  LegacyTlsFailure();
  LocalHttpsProbe(false);LocalHttpsProbe(true);
  Console.WriteLine("TOTAL "+count+" passed");return 0;
 }catch(Exception error){Console.Error.WriteLine(error);return 1;}}
}
