using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using UsbBridge;

// Real secure cable + SOCKS requests. Fixtures never change Windows DNS/routes.
class RelayDnsTests {
 static int passed;
 static void Check(bool value,string message){if(!value)throw new Exception(message);passed++;Console.WriteLine("PASS "+message);}
 static byte[] Query(int id,int type){return new byte[]{(byte)(id>>8),(byte)id,1,0,0,1,0,0,0,0,0,0,4,116,101,115,116,0,0,(byte)type,0,1};}
 static byte[] Answer(byte[] q){byte[] r=(byte[])q.Clone();r[2]=129;r[3]=128;return r;}
 sealed class Cable:IDisposable {
  readonly TcpClient client=new TcpClient(),server;readonly SecureWire phone;readonly Mux mux;readonly MemoryStream pending=new MemoryStream();int offset;
  public Cable(Relay relay){TcpListener listener=new TcpListener(IPAddress.Loopback,0);listener.Start();client.Connect((IPEndPoint)listener.LocalEndpoint);server=listener.AcceptTcpClient();listener.Stop();client.NoDelay=server.NoDelay=true;client.ReceiveTimeout=server.ReceiveTimeout=5000;SecureWire pc=null;Exception error=null;using(ManualResetEvent ready=new ManualResetEvent(false)){Wire.Run(delegate{try{pc=new SecureWire(server.GetStream(),"DNS-fixture",false);}catch(Exception e){error=e;}finally{ready.Set();}});phone=new SecureWire(client.GetStream(),"DNS-fixture",true);if(!ready.WaitOne(5000,false)||error!=null)throw new Exception("handshake",error);}mux=new Mux(pc);mux.Accepted=relay.Serve;mux.Start();Send(1,new byte[0]);Data(new byte[]{5,1,0});Check(Wire.Equal(Read(2),new byte[]{5,0}),"DNS fixture SOCKS handshake");}
  public void Send(int type,byte[] data){byte[] f=new byte[data.Length+5];f[0]=(byte)type;Wire.Put(f,1,1);Buffer.BlockCopy(data,0,f,5,data.Length);phone.Send(f);}
  public void Data(byte[] data){Send(2,data);}
  public byte[] Read(int count){while(pending.Length-offset<count){byte[] f=phone.Receive();if(f[0]==7||f[0]==6)continue;if(f[0]==5){Send(6,new byte[0]);continue;}if(f[0]!=2)throw new IOException("Unexpected Mux frame "+f[0]);pending.Write(f,5,f.Length-5);byte[] credit=new byte[4];Wire.Put(credit,0,f.Length-5);Send(7,credit);}byte[] value=new byte[count];Buffer.BlockCopy(pending.GetBuffer(),offset,value,0,count);offset+=count;return value;}
  public void Dispose(){mux.Dispose();phone.Dispose();client.Close();server.Close();pending.Close();}
 }
 static byte[] Packet(IPAddress host,int port,byte[] payload){byte[] packet=new byte[10+payload.Length];packet[0]=(byte)(payload.Length>>8);packet[1]=(byte)payload.Length;packet[2]=10;packet[3]=1;Buffer.BlockCopy(host.GetAddressBytes(),0,packet,4,4);packet[8]=(byte)(port>>8);packet[9]=(byte)port;Buffer.BlockCopy(payload,0,packet,10,payload.Length);return packet;}
 static void TcpDns(){using(UdpClient dns=new UdpClient(new IPEndPoint(IPAddress.Loopback,0))){dns.Client.ReceiveTimeout=4000;int port=((IPEndPoint)dns.Client.LocalEndPoint).Port;Exception error=null;IPAddress origin=null;using(ManualResetEvent done=new ManualResetEvent(false)){
  Wire.Run(delegate{try{for(int i=0;i<2;i++){IPEndPoint from=null;byte[] q=dns.Receive(ref from);origin=from.Address;byte[] r=Answer(q);dns.Send(r,r.Length,from);}}catch(Exception e){error=e;}finally{done.Set();}});
  using(Cable cable=new Cable(new Relay(IPAddress.Parse("127.0.0.2"),0,IPAddress.Loopback,port,600))){cable.Data(new byte[]{5,1,0,1,198,18,7,2,0,53});Check(cable.Read(10)[1]==0,"TCP DNS client can connect when selected PC DNS only supports UDP");for(int i=0;i<2;i++){byte[] q=Query(100+i,i==0?28:1);byte[] framed=new byte[q.Length+2];framed[1]=(byte)q.Length;Buffer.BlockCopy(q,0,framed,2,q.Length);for(int p=0;p<framed.Length;p++)cable.Data(new byte[]{framed[p]});byte[] length=cable.Read(2),r=cable.Read(length[0]*256+length[1]);Check(Wire.Equal(r,Answer(q)),"TCP DNS framing preserves "+(i==0?"AAAA":"A")+" query and reply");}Check(done.WaitOne(1500,false)&&error==null,"persistent TCP DNS processes both questions");Check(origin.Equals(IPAddress.Parse("127.0.0.2")),"TCP-to-UDP DNS keeps the chosen PC source address");}
  if(!done.WaitOne(4000,false))throw new Exception("DNS fixture did not finish");
 }}}
 static void NoDatagramStall(){using(UdpClient dns=new UdpClient(new IPEndPoint(IPAddress.Loopback,0)))using(UdpClient echo=new UdpClient(new IPEndPoint(IPAddress.Loopback,0)))using(ManualResetEvent release=new ManualResetEvent(false))using(ManualResetEvent dnsSeen=new ManualResetEvent(false))using(ManualResetEvent echoSeen=new ManualResetEvent(false))using(ManualResetEvent done=new ManualResetEvent(false)){
  dns.Client.ReceiveTimeout=echo.Client.ReceiveTimeout=5000;int dnsPort=((IPEndPoint)dns.Client.LocalEndPoint).Port,echoPort=((IPEndPoint)echo.Client.LocalEndPoint).Port;Exception error=null;
  Wire.Run(delegate{try{IPEndPoint from=null;byte[] q=dns.Receive(ref from);dnsSeen.Set();release.WaitOne(3500,false);byte[] r=Answer(q);dns.Send(r,r.Length,from);}catch(Exception e){error=e;}finally{done.Set();}});
  Wire.Run(delegate{try{IPEndPoint from=null;byte[] payload=echo.Receive(ref from);echo.Send(payload,payload.Length,from);echoSeen.Set();}catch{}});
  try{using(Cable cable=new Cable(new Relay(IPAddress.Parse("127.0.0.2"),0,IPAddress.Loopback,dnsPort,4000))){cable.Data(new byte[]{5,5,0,1,0,0,0,0,0,0});Check(cable.Read(10)[1]==0,"UDP association opened");byte[] q=Query(123,1);cable.Data(Packet(IPAddress.Parse("198.18.7.2"),53,q));Check(dnsSeen.WaitOne(2500,false),"slow DNS fixture received request");byte[] payload={4,0,0,0,5,6,7};cable.Data(Packet(IPAddress.Loopback,echoPort,payload));Check(echoSeen.WaitOne(1000,false),"pending DNS does not block another UDP destination in the same association");byte[] h=cable.Read(10),body=cable.Read(h[0]*256+h[1]);Check(h[8]*256+h[9]==echoPort&&Wire.Equal(body,payload),"unrelated datagram returns before DNS is released");release.Set();h=cable.Read(10);body=cable.Read(h[0]*256+h[1]);Check(h[8]*256+h[9]==53&&Wire.Equal(body,Answer(q)),"slow DNS reply is still delivered with its original transaction");Check(done.WaitOne(1000,false)&&error==null,"slow DNS fixture finished");}}
  finally{release.Set();done.WaitOne(4500,false);}
 }}
 static int Main(string[] args){try{if(args.Length==0||args[0]=="tcp")TcpDns();if(args.Length==0||args[0]=="stall")NoDatagramStall();Console.WriteLine("TOTAL "+passed+" passed");return 0;}catch(Exception e){Console.Error.WriteLine(e);return 1;}}
}
