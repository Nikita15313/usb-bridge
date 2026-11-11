using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Collections.Generic;
using System.Diagnostics;
using System.Security.Cryptography;
using UsbBridge;

// One bad destination must not tear down healthy traffic sharing a UDP stream.
class RelayUdpIsolationTests {
 static int passed;
 static void Check(bool value,string message){if(!value)throw new Exception(message);passed++;Console.WriteLine("PASS "+message);}
 sealed class Cable:IDisposable {
  readonly TcpClient client=new TcpClient(),server;readonly SecureWire phone;readonly Mux mux;readonly MemoryStream pending=new MemoryStream();int offset;
  internal Cable(Relay relay){TcpListener listener=new TcpListener(IPAddress.Loopback,0);listener.Start();client.Connect((IPEndPoint)listener.LocalEndpoint);server=listener.AcceptTcpClient();listener.Stop();client.NoDelay=server.NoDelay=true;client.ReceiveTimeout=server.ReceiveTimeout=3000;SecureWire pc=null;Exception error=null;using(ManualResetEvent ready=new ManualResetEvent(false)){Wire.Run(delegate{try{pc=new SecureWire(server.GetStream(),"UDP-isolation",false);}catch(Exception e){error=e;}finally{ready.Set();}});phone=new SecureWire(client.GetStream(),"UDP-isolation",true);if(!ready.WaitOne(3000,false)||error!=null)throw new Exception("handshake",error);}mux=new Mux(pc);mux.Accepted=relay.Serve;mux.Start();Send(1,new byte[0]);Data(new byte[]{5,1,0});Check(Wire.Equal(Read(2),new byte[]{5,0}),"isolation fixture SOCKS handshake");Data(new byte[]{5,5,0,1,0,0,0,0,0,0});Check(Read(10)[1]==0,"isolation fixture UDP association");}
  void Send(int type,byte[] data){byte[] f=new byte[data.Length+5];f[0]=(byte)type;Wire.Put(f,1,1);Buffer.BlockCopy(data,0,f,5,data.Length);phone.Send(f);}
  internal void Data(byte[] data){for(int p=0;p<data.Length;){int count=Math.Min(4096,data.Length-p);byte[] part=new byte[count];Buffer.BlockCopy(data,p,part,0,count);Send(2,part);p+=count;}}
  internal byte[] Read(int count){DateTime until=DateTime.UtcNow.AddSeconds(4);while(pending.Length-offset<count){if(DateTime.UtcNow>until)throw new IOException("UDP response deadline");byte[] f=phone.Receive();if(f[0]==7||f[0]==6)continue;if(f[0]==5){Send(6,new byte[0]);continue;}if(f[0]!=2)throw new IOException("UDP association was closed: frame "+f[0]);pending.Write(f,5,f.Length-5);byte[] credit=new byte[4];Wire.Put(credit,0,f.Length-5);Send(7,credit);}byte[] value=new byte[count];Buffer.BlockCopy(pending.GetBuffer(),offset,value,0,count);offset+=count;return value;}
  public void Dispose(){mux.Dispose();phone.Dispose();client.Close();server.Close();pending.Close();}
 }
 static byte[] Packet(IPAddress address,int port,byte[] payload){byte[] host=address.GetAddressBytes();int header=host.Length+6;byte[] frame=new byte[header+payload.Length];frame[0]=(byte)(payload.Length>>8);frame[1]=(byte)payload.Length;frame[2]=(byte)header;frame[3]=host.Length==4?(byte)1:(byte)4;Buffer.BlockCopy(host,0,frame,4,host.Length);frame[header-2]=(byte)(port>>8);frame[header-1]=(byte)port;Buffer.BlockCopy(payload,0,frame,header,payload.Length);return frame;}
 static void Scenario(bool capacity){
  List<UdpClient> quiet=new List<UdpClient>();
  using(UdpClient echo=new UdpClient(new IPEndPoint(IPAddress.Loopback,0)))using(ManualResetEvent done=new ManualResetEvent(false)){
   echo.Client.ReceiveTimeout=5000;int port=((IPEndPoint)echo.Client.LocalEndPoint).Port;Exception error=null;
   Wire.Run(delegate{try{for(int i=0;i<2;i++){IPEndPoint from=null;byte[] payload=echo.Receive(ref from);echo.Send(payload,payload.Length,from);}}catch(Exception e){error=e;}finally{done.Set();}});
   try{using(Cable cable=new Cable(new Relay(IPAddress.Parse("127.0.0.2"),0,IPAddress.Loopback))){byte[] payload=Packet(IPAddress.Loopback,port,new byte[]{1,2,3,4});cable.Data(payload);Check(Wire.Equal(cable.Read(payload.Length),payload),"healthy IPv4 destination is established first");
    if(capacity){for(int i=0;i<70;i++){UdpClient sink=new UdpClient(new IPEndPoint(IPAddress.Loopback,0));quiet.Add(sink);cable.Data(Packet(IPAddress.Loopback,((IPEndPoint)sink.Client.LocalEndPoint).Port,new byte[]{7}));}}
    else cable.Data(Packet(IPAddress.IPv6Loopback,12345,new byte[]{9}));
    cable.Data(payload);Check(Wire.Equal(cable.Read(payload.Length),payload),capacity?"destination capacity does not close an existing healthy UDP flow":"unsupported IPv6 destination does not close healthy IPv4 traffic");
    Check(done.WaitOne(1000,false)&&error==null,"healthy peer receives both datagrams on the same association");
   }}finally{foreach(UdpClient sink in quiet)sink.Close();echo.Close();done.WaitOne(5500,false);}
  }
 }
 static void Expiry(){
  Check(!Relay.UdpIdle(1000,120999)&&Relay.UdpIdle(1000,121000),"UDP inactivity expires at two minutes, not while recently active");
  int before=int.MaxValue-60000,after=unchecked(before+120000);
  Check(Relay.UdpIdle(before,after)&&!Relay.UdpIdle(before,unchecked(before+119999)),"UDP idle expiry handles the Windows tick-count rollover");
  Dictionary<string,Relay.UdpFlow> flows=new Dictionary<string,Relay.UdpFlow>();
  Relay.UdpFlow idle=new Relay.UdpFlow(new Socket(AddressFamily.InterNetwork,SocketType.Dgram,ProtocolType.Udp)),active=new Relay.UdpFlow(new Socket(AddressFamily.InterNetwork,SocketType.Dgram,ProtocolType.Udp)),closed=new Relay.UdpFlow(new Socket(AddressFamily.InterNetwork,SocketType.Dgram,ProtocolType.Udp));
  try{idle.LastActivity=1000;active.LastActivity=120999;closed.LastActivity=120999;closed.Close();flows.Add("idle",idle);flows.Add("active",active);flows.Add("closed",closed);Relay.PruneUdpFlows(flows,121000);
   Check(flows.Count==1&&flows.ContainsKey("active"),"cache pruning frees idle and failed destinations but keeps active ones");
   bool socketClosed=false;try{idle.Socket.SendTo(new byte[]{1},new IPEndPoint(IPAddress.Loopback,9));}catch(ObjectDisposedException){socketClosed=true;}
   Check(idle.Closed&&socketClosed&&!active.Closed,"pruning closes expired sockets and leaves the active socket open");
  }finally{idle.Close();active.Close();closed.Close();}
 }
 sealed class EchoPeer:IDisposable {
  readonly UdpClient socket=new UdpClient(new IPEndPoint(IPAddress.Loopback,0));readonly ManualResetEvent done=new ManualResetEvent(false);
  internal readonly int Port;internal readonly int[] SourcePorts;Exception failure;
  internal EchoPeer(int count){Port=((IPEndPoint)socket.Client.LocalEndPoint).Port;SourcePorts=new int[count];socket.Client.ReceiveTimeout=5000;Wire.Run(delegate{try{for(int n=0;n<count;n++){IPEndPoint from=null;byte[] data=socket.Receive(ref from);SourcePorts[n]=from.Port;socket.Send(data,data.Length,from);}}catch(Exception e){failure=e;}finally{done.Set();}});}
  internal void Complete(){if(!done.WaitOne(1000,false)||failure!=null)throw new Exception("LRU peer did not complete",failure);}
  public void Dispose(){socket.Close();done.WaitOne(5500,false);done.Close();}
 }
 static void RoundTrip(Cable cable,int port,byte value){byte[] packet=Packet(IPAddress.Loopback,port,new byte[]{value});cable.Data(packet);if(!Wire.Equal(cable.Read(packet.Length),packet))throw new Exception("LRU datagram mismatch");}
 static void LruAdmission(){
  List<UdpClient> quiet=new List<UdpClient>();
  using(EchoPeer cold=new EchoPeer(2))using(EchoPeer hot=new EchoPeer(3))using(EchoPeer fresh=new EchoPeer(1))using(Cable cable=new Cable(new Relay(IPAddress.Parse("127.0.0.2"),0,IPAddress.Loopback))){
   try{
    RoundTrip(cable,cold.Port,1);RoundTrip(cable,hot.Port,2);
    for(int n=0;n<62;n++){UdpClient sink=new UdpClient(new IPEndPoint(IPAddress.Loopback,0));quiet.Add(sink);cable.Data(Packet(IPAddress.Loopback,((IPEndPoint)sink.Client.LocalEndPoint).Port,new byte[]{7}));}
    cable.Data(Packet(IPAddress.IPv6Loopback,12345,new byte[]{9}));RoundTrip(cable,hot.Port,3);
    bool retained=false;using(Socket probe=new Socket(AddressFamily.InterNetwork,SocketType.Dgram,ProtocolType.Udp)){try{probe.Bind(new IPEndPoint(IPAddress.Parse("127.0.0.2"),cold.SourcePorts[0]));}catch(SocketException e){retained=e.SocketErrorCode==SocketError.AddressAlreadyInUse;}}
    Check(retained,"invalid new destination at capacity does not evict a usable cached socket");
    RoundTrip(cable,fresh.Port,4);
    Check(true,"new 65th UDP destination gets an immediate reply on the existing association");
    // Reserving the old local port proves eviction really closed the least-used
    // socket and prevents a coincidental OS port reuse from hiding recreation.
    using(UdpClient reserved=new UdpClient(new IPEndPoint(IPAddress.Parse("127.0.0.2"),cold.SourcePorts[0]))){
     Check(true,"LRU admission closes the oldest socket rather than an active destination");
     RoundTrip(cable,hot.Port,5);RoundTrip(cable,cold.Port,6);hot.Complete();cold.Complete();fresh.Complete();
     Check(hot.SourcePorts[0]==hot.SourcePorts[1]&&hot.SourcePorts[1]==hot.SourcePorts[2],"recently active UDP flow keeps its socket when another destination is admitted");
     Check(cold.SourcePorts[1]!=cold.SourcePorts[0],"evicted destination recreates its socket and receives a reply without reconnecting the association");
    }
   }finally{foreach(UdpClient sink in quiet)sink.Close();}
  }
 }
 static void MixedLoad(){
  const int packets=256,size=8192;List<UdpClient> quiet=new List<UdpClient>();
  using(UdpClient echo=new UdpClient(new IPEndPoint(IPAddress.Loopback,0)))using(UdpClient dns=new UdpClient(new IPEndPoint(IPAddress.Loopback,0)))using(ManualResetEvent echoDone=new ManualResetEvent(false))using(ManualResetEvent dnsDone=new ManualResetEvent(false))using(MemoryStream received=new MemoryStream())using(MemoryStream expected=new MemoryStream()){
   echo.Client.ReceiveTimeout=dns.Client.ReceiveTimeout=8000;int echoPort=((IPEndPoint)echo.Client.LocalEndPoint).Port,dnsPort=((IPEndPoint)dns.Client.LocalEndPoint).Port;Exception echoError=null,dnsError=null;bool pinned=true;
   Wire.Run(delegate{try{for(int n=0;n<=packets;n++){IPEndPoint from=null;byte[] body=echo.Receive(ref from);if(!from.Address.Equals(IPAddress.Parse("127.0.0.2")))pinned=false;echo.Send(body,body.Length,from);}}catch(Exception e){echoError=e;}finally{echoDone.Set();}});
   Wire.Run(delegate{try{for(int n=0;n<16;n++){IPEndPoint from=null;byte[] body=dns.Receive(ref from);if(!from.Address.Equals(IPAddress.Parse("127.0.0.2")))pinned=false;body[2]=129;body[3]=128;dns.Send(body,body.Length,from);}}catch(Exception e){dnsError=e;}finally{dnsDone.Set();}});
   try{Relay relay=new Relay(IPAddress.Parse("127.0.0.2"),0,IPAddress.Loopback,dnsPort,2000);using(Cable cable=new Cable(relay)){
    byte[] first=Packet(IPAddress.Loopback,echoPort,new byte[]{1});cable.Data(first);Check(Wire.Equal(cable.Read(first.Length),first),"mixed-load healthy flow is ready before saturation");
    for(int n=0;n<70;n++){UdpClient sink=new UdpClient(new IPEndPoint(IPAddress.Loopback,0));quiet.Add(sink);cable.Data(Packet(IPAddress.Loopback,((IPEndPoint)sink.Client.LocalEndPoint).Port,new byte[]{7}));}
    bool[] answers=new bool[16];int answerCount=0;Stopwatch elapsed=Stopwatch.StartNew();
    for(int n=0;n<packets;n++){
     if(n%16==0){int id=n/16;byte[] query={0,(byte)id,1,0,0,1,0,0,0,0,0,0,4,116,101,115,116,0,0,1,0,1};cable.Data(Packet(IPAddress.Parse("198.18.7.2"),53,query));cable.Data(Packet(IPAddress.IPv6Loopback,12345,new byte[]{9}));}
     byte[] payload=new byte[size];for(int p=0;p<size;p++)payload[p]=(byte)(n*13+p*29);expected.Write(payload,0,payload.Length);cable.Data(Packet(IPAddress.Loopback,echoPort,payload));
     while(true){byte[] header=cable.Read(10),body=cable.Read(header[0]*256+header[1]);int port=header[8]*256+header[9];if(port==53){int id=body[0]*256+body[1];if(body.Length!=22||body[2]!=129||body[3]!=128||id>=16||answers[id])throw new Exception("Corrupt/duplicate mixed DNS response");answers[id]=true;answerCount++;continue;}if(port!=echoPort||!Wire.Equal(body,payload))throw new Exception("Corrupt mixed UDP payload");received.Write(body,0,body.Length);break;}
    }
    while(answerCount<16){byte[] header=cable.Read(10),body=cable.Read(header[0]*256+header[1]);int id=body[0]*256+body[1];if(header[8]*256+header[9]!=53||body.Length!=22||body[2]!=129||body[3]!=128||id>=16||answers[id])throw new Exception("Corrupt final mixed DNS response");answers[id]=true;answerCount++;}
    using(SHA256 sha=SHA256.Create()){byte[] want=sha.ComputeHash(expected.ToArray()),got=sha.ComputeHash(received.ToArray());Check(received.Length==2097152&&Wire.Equal(want,got),"2 MiB UDP payload survives DNS/unsupported-address/cache-capacity traffic on one association");Console.WriteLine("MIXED bytes="+received.Length+" sha256="+BitConverter.ToString(got).Replace("-","").ToLowerInvariant()+" elapsedMs="+elapsed.ElapsedMilliseconds);}
    Check(answerCount==16,"all synthetic DNS replies return despite saturated destination cache");
    Check(echoDone.WaitOne(1000,false)&&dnsDone.WaitOne(1000,false)&&echoError==null&&dnsError==null&&pinned,"mixed-load peers finish using the pinned source for data and DNS");
   }}finally{foreach(UdpClient sink in quiet)sink.Close();echo.Close();dns.Close();echoDone.WaitOne(8500,false);dnsDone.WaitOne(8500,false);}
  }
 }
 static int Main(string[] args){try{if(args.Length==0||args[0]=="ipv6")Scenario(false);if(args.Length==0||args[0]=="capacity")Scenario(true);if(args.Length==0||args[0]=="expiry")Expiry();if(args.Length==0||args[0]=="lru")LruAdmission();if(args.Length==0||args[0]=="mixed")MixedLoad();Console.WriteLine("TOTAL "+passed+" passed");return 0;}catch(Exception e){Console.Error.WriteLine(e);return 1;}}
}
