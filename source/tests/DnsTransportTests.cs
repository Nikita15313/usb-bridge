using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Diagnostics;
using UsbBridge;

class DnsTransportTests {
 static int count;
 static Socket Create(SocketType type){Socket socket=new Socket(AddressFamily.InterNetwork,type,type==SocketType.Stream?ProtocolType.Tcp:ProtocolType.Udp);socket.Bind(new IPEndPoint(IPAddress.Loopback,0));return socket;}
 static byte[] Query(){return new byte[]{0x12,0x34,1,0,0,1,0,0,0,0,0,0,4,116,101,115,116,0,0,28,0,1};}
 static byte[] Answer(byte[] query,int code){byte[] response=(byte[])query.Clone();response[2]=129;response[3]=(byte)(128|code);return response;}
 static void Check(bool value,string name){if(!value)throw new Exception(name);Console.WriteLine("PASS "+name);count++;}
 static bool Same(byte[] a,byte[] b){return Wire.Equal(a,b);}
 // TCP and UDP have independent excluded/dynamic port ranges on Windows.
 // Reserving one protocol's random port does not guarantee the other can bind.
 static UdpClient BindBoth(out TcpListener tcp,out int port){
  Random ports=new Random();
  for(int attempt=0;attempt<12;attempt++){
   // Random selection avoids repeatedly walking the same contiguous exclusion
   // range when Windows allocates ephemeral TCP ports sequentially.
   port=ports.Next(49152,65536);tcp=new TcpListener(IPAddress.Loopback,port);
   try{tcp.Start();return new UdpClient(new IPEndPoint(IPAddress.Loopback,port));}
   catch(SocketException error){tcp.Stop();if(error.SocketErrorCode!=SocketError.AccessDenied&&error.SocketErrorCode!=SocketError.AddressAlreadyInUse)throw;}
  }
  throw new IOException("Cannot reserve loopback TCP/UDP fixture port after 12 attempts");
 }
 static int Main(){try{
  TcpListener tcp=new TcpListener(IPAddress.Loopback,0);tcp.Start();int port=((IPEndPoint)tcp.LocalEndpoint).Port;Exception failure=null;ManualResetEvent done=new ManualResetEvent(false);
  Wire.Run(delegate{try{using(TcpClient connection=tcp.AcceptTcpClient())using(NetworkStream stream=connection.GetStream()){byte[] length=Wire.Read(stream,2);byte[] request=Wire.Read(stream,length[0]*256+length[1]);byte[] response=Answer(request,3);stream.WriteByte((byte)(response.Length>>8));stream.WriteByte((byte)response.Length);for(int i=0;i<response.Length;i++)stream.WriteByte(response[i]);}}catch(Exception e){failure=e;}finally{done.Set();}});
  string diagnostic="";DnsTransport transport=new DnsTransport(Create,IPAddress.Loopback,port,500);transport.Diagnostic=delegate(string line){diagnostic=line;};
  byte[] query=Query();byte[] result=transport.Exchange(query);
  Check(Same(result,Answer(query,3)),"TCP-only DNS preserves NXDOMAIN and the original AAAA question across fragmented replies");
  Check(diagnostic.Contains("protocol=TCP")&&diagnostic.Contains("server=127.0.0.1:"+port)&&diagnostic.Contains("TCP=1")&&!diagnostic.Contains("test"),"DNS diagnostics identify the actual winning TCP resolver without logging names");
  Check(done.WaitOne(1500,false)&&failure==null,"TCP-only DNS fixture completed");tcp.Stop();done.Close();
  using(UdpClient udp=new UdpClient(new IPEndPoint(IPAddress.Loopback,0))){port=((IPEndPoint)udp.Client.LocalEndPoint).Port;ManualResetEvent udpDone=new ManualResetEvent(false);failure=null;
   Wire.Run(delegate{try{IPEndPoint client=null;byte[] request=udp.Receive(ref client);byte[] response=Answer(request,2);udp.Send(response,response.Length,client);}catch(Exception e){failure=e;}finally{udpDone.Set();}});
   transport=new DnsTransport(Create,IPAddress.Loopback,port,500);transport.Diagnostic=delegate(string line){diagnostic=line;throw new Exception("Unavailable diagnostic sink");};result=transport.Exchange(query);
   Check(Same(result,Answer(query,2)),"TCP-unavailable DNS uses UDP at the same configured server and preserves SERVFAIL");
   Check(diagnostic.Contains("protocol=UDP")&&transport.Diagnostics.Contains("UDP=1")&&transport.Diagnostics.Contains("failed=0"),"UDP winner is counted and a failing diagnostic sink cannot break DNS");
   Check(udpDone.WaitOne(1500,false)&&failure==null,"UDP fixture completed");udpDone.Close();
  }
  using(UdpClient bad=new UdpClient(new IPEndPoint(IPAddress.Loopback,0))){port=((IPEndPoint)bad.Client.LocalEndPoint).Port;ManualResetEvent badDone=new ManualResetEvent(false);
   Wire.Run(delegate{try{IPEndPoint client=null;byte[] request=bad.Receive(ref client);byte[] response=Answer(request,0);response[0]^=1;bad.Send(response,response.Length,client);}finally{badDone.Set();}});
   bool rejected=false;try{new DnsTransport(Create,IPAddress.Loopback,port,500).Exchange(query);}catch(IOException){rejected=true;}
   Check(rejected,"mismatched DNS transaction is rejected");badDone.WaitOne(1500,false);badDone.Close();
  }
  int attempts=0;DnsSocketFactory counted=delegate(SocketType type){Interlocked.Increment(ref attempts);return Create(type);};
  TcpListener unused=new TcpListener(IPAddress.Loopback,0);unused.Start();port=((IPEndPoint)unused.LocalEndpoint).Port;unused.Stop();
  transport=new DnsTransport(counted,IPAddress.Loopback,port,150);transport.Diagnostic=delegate(string line){diagnostic=line;};bool failed=false;try{transport.Exchange(query);}catch(IOException){failed=true;}
  Check(failed&&attempts==2,"failed resolver stops after TCP and UDP; no public DNS substitution");
  Check(diagnostic.Contains("protocol=failed")&&transport.Diagnostics.Contains("failed=1")&&transport.Diagnostics.Contains("UDP=0")&&transport.Diagnostics.Contains("TCP=0"),"failed DNS race is counted once and never presented as a successful protocol");
  attempts=0;failed=false;try{new DnsTransport(counted,null,port,150).Exchange(query);}catch(IOException){failed=true;}
  Check(failed&&attempts==0,"missing Windows DNS fails explicitly without contacting an unrelated resolver");
  // A TCP listener that accepts nothing cannot delay an available UDP answer.
  using(UdpClient fast=BindBoth(out tcp,out port))using(ManualResetEvent fastDone=new ManualResetEvent(false)){
   Wire.Run(delegate{try{IPEndPoint from=null;byte[] request=fast.Receive(ref from);byte[] response=Answer(request,0);fast.Send(response,response.Length,from);}finally{fastDone.Set();}});
   Stopwatch elapsed=Stopwatch.StartNew();result=new DnsTransport(Create,IPAddress.Loopback,port,2500).Exchange(query);
   Check(Same(result,Answer(query,0))&&elapsed.ElapsedMilliseconds<1000,"working UDP DNS does not wait for a blackholed TCP query");fastDone.WaitOne(1500,false);
  }tcp.Stop();
  // UDP is silently dropped; TCP must start before the UDP receive timeout.
  using(UdpClient silent=BindBoth(out tcp,out port))using(ManualResetEvent tcpDone=new ManualResetEvent(false)){
   Wire.Run(delegate{try{using(TcpClient connection=tcp.AcceptTcpClient())using(NetworkStream stream=connection.GetStream()){byte[] length=Wire.Read(stream,2);byte[] request=Wire.Read(stream,length[0]*256+length[1]);byte[] response=Answer(request,0);stream.WriteByte((byte)(response.Length>>8));stream.WriteByte((byte)response.Length);stream.Write(response,0,response.Length);}}finally{tcpDone.Set();}});
   Stopwatch elapsed=Stopwatch.StartNew();result=new DnsTransport(Create,IPAddress.Loopback,port,2500).Exchange(query);
   Check(Same(result,Answer(query,0))&&elapsed.ElapsedMilliseconds<1000,"TCP DNS starts promptly when UDP is silently dropped");tcpDone.WaitOne(1500,false);
  }tcp.Stop();
  // Both transports are active: a UDP answer must close a TCP reader that is
  // already waiting, not leave a background socket alive until its timeout.
  using(UdpClient delayed=BindBoth(out tcp,out port))using(ManualResetEvent tcpWaiting=new ManualResetEvent(false))using(ManualResetEvent tcpClosed=new ManualResetEvent(false))using(ManualResetEvent udpFinished=new ManualResetEvent(false)){
   Exception tcpRaceError=null;bool sawEof=false;
   Wire.Run(delegate{try{using(TcpClient connection=tcp.AcceptTcpClient())using(NetworkStream stream=connection.GetStream()){connection.ReceiveTimeout=3000;byte[] length=Wire.Read(stream,2);Wire.Read(stream,length[0]*256+length[1]);tcpWaiting.Set();sawEof=stream.ReadByte()<0;}}catch(Exception e){tcpRaceError=e;}finally{tcpClosed.Set();}});
   Wire.Run(delegate{try{IPEndPoint from=null;byte[] request=delayed.Receive(ref from);if(!tcpWaiting.WaitOne(2000,false))return;byte[] response=Answer(request,0);delayed.Send(response,response.Length,from);}finally{udpFinished.Set();}});
   result=new DnsTransport(Create,IPAddress.Loopback,port,2500).Exchange(query);
   Check(Same(result,Answer(query,0)),"valid UDP answer wins while TCP is already blocked");
   bool closedPromptly=tcpClosed.WaitOne(700,false);SocketException closeError=tcpRaceError==null?null:tcpRaceError.InnerException as SocketException;bool peerClosed=sawEof||(closeError!=null&&closeError.SocketErrorCode==SocketError.ConnectionReset);Check(closedPromptly&&peerClosed,"winning DNS answer promptly closes losing TCP socket");
   Check(udpFinished.WaitOne(1000,false),"both hedged DNS fixture workers finish");
  }tcp.Stop();
  // A truncated UDP reply is never returned as a usable complete DNS answer.
  using(UdpClient truncated=BindBoth(out tcp,out port))using(ManualResetEvent udpFinished=new ManualResetEvent(false))using(ManualResetEvent tcpFinished=new ManualResetEvent(false)){
   Wire.Run(delegate{try{IPEndPoint from=null;byte[] request=truncated.Receive(ref from);byte[] response=Answer(request,0);response[2]|=2;truncated.Send(response,response.Length,from);}finally{udpFinished.Set();}});
   Wire.Run(delegate{try{using(TcpClient connection=tcp.AcceptTcpClient())using(NetworkStream stream=connection.GetStream()){byte[] length=Wire.Read(stream,2);byte[] request=Wire.Read(stream,length[0]*256+length[1]);byte[] response=Answer(request,0);stream.WriteByte((byte)(response.Length>>8));stream.WriteByte((byte)response.Length);stream.Write(response,0,response.Length);}}finally{tcpFinished.Set();}});
   result=new DnsTransport(Create,IPAddress.Loopback,port,2500).Exchange(query);
   Check(Same(result,Answer(query,0)),"truncated UDP response retries TCP at the same selected resolver");
   Check(udpFinished.WaitOne(1000,false)&&tcpFinished.WaitOne(1000,false),"truncation fallback fixture completes both transports");
  }tcp.Stop();
  Console.WriteLine("TOTAL "+count+" passed");return 0;
 }catch(Exception error){Console.Error.WriteLine(error);return 1;}}
}
