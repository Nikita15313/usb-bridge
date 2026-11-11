using System;
using System.IO;
using System.Collections.Generic;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Text;
using System.Net.Security;
using System.Security.Authentication;

namespace UsbBridge {
// Only AuthenticateProbe may translate an explicit unsupported TLS enum into
// this outcome. Certificate, network and general platform errors are failures.
public sealed class Tls12UnavailableException:IOException {
 internal Tls12UnavailableException(Exception inner):base("Windows/.NET не поддерживает проверку TLS 1.2; защищённую проверку через выбранное подключение ПК выполнит телефон. Интернет пока не подтверждён.",inner){}
}
public delegate UsbNetworkLink UsbOpenAttempt(Adapter adapter,IPAddress peer,int budgetMs,CableAlive alive,Action<IDisposable> opening);
public sealed class UsbNetworkLink:IDisposable {
 public Adapter Adapter;public IPAddress Peer;public SecureWire Wire;public TcpClient Client;
 public void Dispose(){if(Wire!=null)try{Wire.Dispose();}catch{}if(Client!=null)try{Client.Close();}catch{}}
}
public static class UsbNetwork {
 static readonly string[] ProbeHosts={"www.gstatic.com","connectivitycheck.gstatic.com","cp.cloudflare.com"};
 public delegate bool HttpsUplinkAttempt(Adapter adapter,string host,int budgetMs,CableAlive alive,out string reason);
 public static bool VerifyUplink(Adapter adapter,int budgetMs,CableAlive alive,out string reason){return VerifyUplink(adapter,budgetMs,alive,out reason,ProbeHttps);}
 public static bool VerifyUplink(Adapter adapter,int budgetMs,CableAlive alive,out string reason,HttpsUplinkAttempt attempt){
  reason="Интернет выбранного подключения ПК не подтверждён";if(adapter==null||adapter.UsbPhone||adapter.Address==null||adapter.Dns==null){reason="Для проверки нужен источник ПК с собственным DNS";return false;}
  Stopwatch elapsed=Stopwatch.StartNew();int limit=Math.Max(1,Math.Min(8000,budgetMs));for(int i=0;i<ProbeHosts.Length;i++){if(!alive()){reason="Подключение отменено";return false;}int remaining=limit-(int)elapsed.ElapsedMilliseconds;if(remaining<=0)break;int slice=Math.Min(remaining,Math.Max(300,remaining/(ProbeHosts.Length-i)));string failure="";bool good=false;try{good=attempt(adapter,ProbeHosts[i],slice,alive,out failure);}catch(Tls12UnavailableException){throw;}catch(Exception error){failure=error.Message;}if(!alive()){reason="Подключение отменено";return false;}if(good&&elapsed.ElapsedMilliseconds<=limit){reason="HTTPS 204 через "+adapter.Name+" (DNS "+adapter.Dns+")";return true;}if(!String.IsNullOrEmpty(failure))reason=failure;}return false;
 }
 internal static Socket ProbeSocket(Adapter adapter,SocketType type,int timeout){Socket socket=new Socket(AddressFamily.InterNetwork,type,type==SocketType.Stream?ProtocolType.Tcp:ProtocolType.Udp);try{if(adapter.Index>0)socket.SetSocketOption(SocketOptionLevel.IP,(SocketOptionName)31,IPAddress.HostToNetworkOrder(adapter.Index));socket.Bind(new IPEndPoint(adapter.Address,0));socket.SendTimeout=socket.ReceiveTimeout=Math.Max(1,timeout);if(type==SocketType.Stream)socket.NoDelay=true;return socket;}catch{socket.Close();throw;}}
 sealed class ProbeScope:IDisposable {
  readonly Adapter adapter;readonly int budget;readonly CableAlive alive;readonly Stopwatch elapsed=Stopwatch.StartNew();readonly List<Socket> sockets=new List<Socket>();readonly object gate=new object();readonly Timer timer;bool closed;
  public ProbeScope(Adapter value,int timeout,CableAlive active){adapter=value;budget=timeout;alive=active;timer=new Timer(delegate(object ignored){try{if(!alive()||elapsed.ElapsedMilliseconds>=budget)Close();}catch{Close();}},null,50,50);}
  public int Remaining{get{return Math.Max(1,budget-(int)elapsed.ElapsedMilliseconds);}}
  public Socket Create(SocketType type){lock(gate){if(closed||!alive()||elapsed.ElapsedMilliseconds>=budget)throw new IOException("Проверка отменена или истекло время ожидания");Socket socket=ProbeSocket(adapter,type,Remaining);sockets.Add(socket);return socket;}}
  void Close(){lock(gate){closed=true;foreach(Socket socket in sockets)try{socket.Close();}catch{}}}
  public void Dispose(){timer.Dispose();Close();}
 }
 static byte[] DnsQuestion(string host){MemoryStream bytes=new MemoryStream();byte[] id=Wire.Random(2);bytes.Write(id,0,2);bytes.Write(new byte[]{1,0,0,1,0,0,0,0,0,0},0,10);foreach(string label in host.Split('.')){byte[] data=Encoding.ASCII.GetBytes(label);bytes.WriteByte((byte)data.Length);bytes.Write(data,0,data.Length);}bytes.Write(new byte[]{0,0,1,0,1},0,5);return bytes.ToArray();}
 static void SkipName(byte[] bytes,ref int offset){for(int i=0;i<128;i++){if(offset>=bytes.Length)throw new IOException("Короткий ответ DNS");int length=bytes[offset++];if(length==0)return;if((length&192)==192){if(offset>=bytes.Length)throw new IOException("Короткий ответ DNS");offset++;return;}if(length>63||offset+length>bytes.Length)throw new IOException("Повреждённый ответ DNS");offset+=length;}throw new IOException("Повреждённый ответ DNS");}
 internal static IPAddress ProbeAddress(byte[] answer){if(answer.Length<12||(answer[3]&15)!=0)throw new IOException("DNS выбранной сети не разрешил адрес проверки");int offset=12,questions=answer[4]*256+answer[5],records=answer[6]*256+answer[7];for(int i=0;i<questions;i++){SkipName(answer,ref offset);offset+=4;if(offset>answer.Length)throw new IOException("Короткий ответ DNS");}for(int i=0;i<records;i++){SkipName(answer,ref offset);if(offset+10>answer.Length)throw new IOException("Короткий ответ DNS");int type=answer[offset]*256+answer[offset+1],kind=answer[offset+2]*256+answer[offset+3],size=answer[offset+8]*256+answer[offset+9];offset+=10;if(offset+size>answer.Length)throw new IOException("Короткий ответ DNS");if(type==1&&kind==1&&size==4)return new IPAddress(new byte[]{answer[offset],answer[offset+1],answer[offset+2],answer[offset+3]});offset+=size;}throw new IOException("DNS выбранной сети не вернул IPv4");}
 internal static bool IsHttp204(string headers){if(headers==null)return false;string[] lines=headers.Split('\n');string line=lines[0].TrimEnd('\r');if(!line.StartsWith("HTTP/1.1 204",StringComparison.Ordinal)&&!line.StartsWith("HTTP/1.0 204",StringComparison.Ordinal))return false;if(line.Length>12&&line[12]!=' ')return false;foreach(string raw in lines){string header=raw.Trim();if(header.StartsWith("Content-Length:",StringComparison.OrdinalIgnoreCase)){int size;if(!Int32.TryParse(header.Substring(15).Trim(),out size)||size!=0)return false;}if(header.StartsWith("Location:",StringComparison.OrdinalIgnoreCase))return false;}return true;}
 internal static Exception ExplainTlsFailure(Exception error){ArgumentException argument=error as ArgumentException;if(argument!=null&&(argument.ParamName=="sslProtocolType"||argument.ParamName=="enabledSslProtocols"))return new Tls12UnavailableException(error);return error;}
 internal static void AuthenticateProbe(SslStream tls,string host){try{tls.AuthenticateAsClient(host,null,(SslProtocols)3072,false);}catch(Exception error){Exception explained=ExplainTlsFailure(error);if(Object.ReferenceEquals(error,explained))throw;throw explained;}}
 static bool ProbeHttps(Adapter adapter,string host,int budgetMs,CableAlive alive,out string reason){return ProbeHttpsAtPorts(adapter,host,53,443,budgetMs,alive,out reason);}
 internal static bool ProbeHttpsAtPorts(Adapter adapter,string host,int dnsPort,int httpsPort,int budgetMs,CableAlive alive,out string reason){try{using(ProbeScope scope=new ProbeScope(adapter,budgetMs,alive)){
   DnsTransport dns=new DnsTransport(scope.Create,adapter.Dns,dnsPort,Math.Max(100,Math.Min(600,budgetMs/3)));IPAddress address=ProbeAddress(dns.Exchange(DnsQuestion(host)));
   using(Socket socket=scope.Create(SocketType.Stream)){IAsyncResult connect=socket.BeginConnect(new IPEndPoint(address,httpsPort),null,null);using(WaitHandle wait=connect.AsyncWaitHandle){if(!wait.WaitOne(scope.Remaining,false))throw new IOException("HTTPS выбранной сети не отвечает");socket.EndConnect(connect);}if(!((IPEndPoint)socket.LocalEndPoint).Address.Equals(adapter.Address))throw new IOException("Источник проверки изменился");
    using(NetworkStream network=new NetworkStream(socket,false))using(SslStream tls=new SslStream(network,false)){tls.ReadTimeout=tls.WriteTimeout=scope.Remaining;AuthenticateProbe(tls,host);byte[] request=Encoding.ASCII.GetBytes("GET /generate_204 HTTP/1.1\r\nHost: "+host+"\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n");tls.Write(request,0,request.Length);StringBuilder headers=new StringBuilder();for(int i=0;i<8192;i++){int b=tls.ReadByte();if(b<0)break;headers.Append((char)b);int n=headers.Length;if(n>=4&&headers[n-4]=='\r'&&headers[n-3]=='\n'&&headers[n-2]=='\r'&&headers[n-1]=='\n'){if(!IsHttp204(headers.ToString()))throw new IOException("Сеть вернула страницу входа или другой ответ вместо HTTPS 204");reason="";return true;}}throw new IOException("Нет полного ответа HTTPS 204");}
   }
  }}catch(Tls12UnavailableException){throw;}catch(Exception error){reason="Проверка "+host+": "+error.Message;return false;}}
 public static UsbNetworkLink TryOpen(List<Adapter> choices,CableAlive alive,Action<IDisposable> opening,Action<string> log){return TryOpen(choices,alive,opening,log,OpenCandidate);}
 public static UsbNetworkLink TryOpen(List<Adapter> choices,CableAlive alive,Action<IDisposable> opening,Action<string> log,UsbOpenAttempt attempt){
  Stopwatch elapsed=Stopwatch.StartNew();int attempts=0;
  foreach(Adapter adapter in NetworkCatalog.UsbNetworks(choices))foreach(IPAddress peer in NetworkCatalog.Peers(adapter)){
   if(!alive()||elapsed.ElapsedMilliseconds>=8000||attempts++>=6)return null;
   try{UsbNetworkLink link=attempt(adapter,peer,Math.Min(3000,8000-(int)elapsed.ElapsedMilliseconds),alive,opening);if(link!=null){if(alive())return link;link.Dispose();return null;}}
   catch(Exception error){if(log!=null)log("USB-сеть "+adapter.Name+": "+error.Message);}
  }return null;
 }
 public static UsbNetworkLink OpenCandidate(Adapter adapter,IPAddress peer,int budgetMs,CableAlive alive,Action<IDisposable> opening){
  return OpenCandidateAtPort(adapter,peer,38473,budgetMs,alive,opening);
 }
 internal static UsbNetworkLink OpenCandidateAtPort(Adapter adapter,IPAddress peer,int port,int budgetMs,CableAlive alive,Action<IDisposable> opening){
  if(!alive())return null;TcpClient tcp=new TcpClient(new IPEndPoint(adapter.Address,0));SecureWire wire=null;Stopwatch elapsed=Stopwatch.StartNew();
  try{
   if(adapter.Index>0)tcp.Client.SetSocketOption(SocketOptionLevel.IP,(SocketOptionName)31,IPAddress.HostToNetworkOrder(adapter.Index));
   tcp.NoDelay=true;tcp.ReceiveTimeout=Math.Max(1,budgetMs);tcp.SendTimeout=Math.Max(1,budgetMs);if(opening!=null)opening(tcp);
   using(Timer timeout=new Timer(delegate(object ignored){if(!alive()||elapsed.ElapsedMilliseconds>=budgetMs)try{tcp.Close();}catch{}},null,100,100)){
    IAsyncResult pending=tcp.BeginConnect(peer,port,null,null);using(WaitHandle wait=pending.AsyncWaitHandle){if(!wait.WaitOne(Math.Min(1000,budgetMs),false))throw new IOException("телефон не отвечает");tcp.EndConnect(pending);}
    if(!alive())throw new IOException("подключение отменено");
    wire=new SecureWire(tcp.GetStream(),UsbPair.Connect(tcp.GetStream()),true);
    if(!alive()||elapsed.ElapsedMilliseconds>=budgetMs)throw new IOException("проверка соединения не завершена вовремя");
   }
   tcp.ReceiveTimeout=25000;tcp.SendTimeout=20000;
   return new UsbNetworkLink{Adapter=adapter,Peer=peer,Wire=wire,Client=tcp};
  }catch{if(wire!=null)try{wire.Dispose();}catch{}tcp.Close();throw;}
 }
}
}
