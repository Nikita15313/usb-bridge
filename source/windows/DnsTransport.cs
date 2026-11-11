using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Diagnostics;
using System.Collections.Generic;

namespace UsbBridge {
public delegate Socket DnsSocketFactory(SocketType type);

// Use the chosen Windows connection's DNS, including UDP-only and TCP-only routers.
// Never substitute a public resolver: it may return different answers or lose LAN names.
public sealed class DnsTransport {
 readonly DnsSocketFactory createSocket;readonly IPAddress server;readonly int port,timeout;
 long udpAnswers,tcpAnswers,failedQueries,completedQueries;
 public Action<string> Diagnostic;
 public string Diagnostics {get{return "DNS server="+server+":"+port+"; UDP="+Interlocked.Read(ref udpAnswers)+"; TCP="+Interlocked.Read(ref tcpAnswers)+"; failed="+Interlocked.Read(ref failedQueries);}}
 public DnsTransport(DnsSocketFactory createSocket,IPAddress server):this(createSocket,server,53,2500){}
 public DnsTransport(DnsSocketFactory createSocket,IPAddress server,int port,int timeout){this.createSocket=createSocket;this.server=server;this.port=port;this.timeout=timeout;}
 static void Validate(byte[] query,byte[] response){
  if(response.Length<12||response[0]!=query[0]||response[1]!=query[1]||(response[2]&128)==0||(response[2]&120)!=(query[2]&120))throw new IOException("DNS response does not match the request");
  if((response[2]&2)!=0)throw new IOException("DNS response was truncated");
 }
 byte[] ExchangeOne(Socket socket,SocketType type,byte[] query){
  using(socket){
    socket.SendTimeout=socket.ReceiveTimeout=timeout;
    if(type==SocketType.Dgram){socket.Connect(new IPEndPoint(server,port));socket.Send(query);byte[] buffer=new byte[65535];int count=socket.Receive(buffer);byte[] answer=new byte[count];Buffer.BlockCopy(buffer,0,answer,0,count);Validate(query,answer);return answer;}
    IAsyncResult connecting=socket.BeginConnect(new IPEndPoint(server,port),null,null);
    using(WaitHandle wait=connecting.AsyncWaitHandle){if(!wait.WaitOne(timeout,false))throw new IOException("DNS TCP connection timed out");socket.EndConnect(connecting);}
    using(NetworkStream stream=new NetworkStream(socket,false)){
     byte[] length={(byte)(query.Length>>8),(byte)query.Length};stream.Write(length,0,2);stream.Write(query,0,query.Length);
     length=Wire.Read(stream,2);int size=length[0]*256+length[1];if(size<12)throw new IOException("Short DNS TCP response");
     byte[] response=Wire.Read(stream,size);Validate(query,response);return response;
    }
  }
 }
 // A dropped UDP packet must not spend the complete timeout before trying TCP;
 // a blocked TCP listener must not delay a working UDP resolver at all. Both
 // attempts retain the same socket factory (source IP/interface) and DNS server.
 sealed class QueryRace:IDisposable {
  readonly DnsTransport owner;readonly byte[] query;readonly object gate=new object();readonly List<Socket> sockets=new List<Socket>();readonly Stopwatch elapsed=Stopwatch.StartNew();
  byte[] answer;Exception error;int started,finished;bool closed;public SocketType Winner;
  public QueryRace(DnsTransport owner,byte[] query){this.owner=owner;this.query=query;}
  void Start(SocketType type){started++;Wire.Run(delegate{Socket socket=null;try{socket=owner.createSocket(type);lock(gate){if(closed){socket.Close();return;}sockets.Add(socket);}byte[] result=owner.ExchangeOne(socket,type,query);lock(gate){if(answer==null&&!closed){answer=result;Winner=type;}}}catch(Exception e){lock(gate){if(error==null)error=e;}}finally{lock(gate){finished++;Monitor.PulseAll(gate);}}});}
  public byte[] Run(){lock(gate){Start(SocketType.Dgram);int hedge=Math.Min(150,Math.Max(1,owner.timeout/3));while(answer==null&&finished<started&&elapsed.ElapsedMilliseconds<hedge)Monitor.Wait(gate,Math.Max(1,hedge-(int)elapsed.ElapsedMilliseconds));if(answer==null){Start(SocketType.Stream);int budget=owner.timeout+hedge;while(answer==null&&finished<started&&elapsed.ElapsedMilliseconds<budget)Monitor.Wait(gate,Math.Max(1,budget-(int)elapsed.ElapsedMilliseconds));}if(answer!=null)return answer;throw new IOException("DNS выбранной сети Windows не отвечает: "+owner.server,error);}}
  public void Dispose(){Socket[] closing;lock(gate){closed=true;closing=sockets.ToArray();}foreach(Socket socket in closing)try{socket.Close();}catch{}}
 }
 public byte[] Exchange(byte[] query){
  if(query==null||query.Length<12||query.Length>65535||(query[2]&128)!=0)throw new IOException("Invalid DNS request");
  if(server==null)throw new IOException("У выбранной сети Windows не указан DNS");
  Stopwatch elapsed=Stopwatch.StartNew();string protocol="failed";
  try{using(QueryRace race=new QueryRace(this,query)){byte[] answer=race.Run();protocol=race.Winner==SocketType.Dgram?"UDP":"TCP";if(race.Winner==SocketType.Dgram)Interlocked.Increment(ref udpAnswers);else Interlocked.Increment(ref tcpAnswers);return answer;}}
  catch{Interlocked.Increment(ref failedQueries);throw;}
  finally{
   long sequence=Interlocked.Increment(ref completedQueries);
   // Names and answer addresses are deliberately omitted. A few initial samples,
   // slow queries and periodic totals are enough to diagnose transport/source
   // differences without filling the log for each DNS request.
   if(Diagnostic!=null&&(sequence<=4||elapsed.ElapsedMilliseconds>=500||protocol=="failed"||(sequence&63)==0))try{Diagnostic(Diagnostics+"; protocol="+protocol+"; elapsedMs="+elapsed.ElapsedMilliseconds);}catch{}
  }
 }
}
}
