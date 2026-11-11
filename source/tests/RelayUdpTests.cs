using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using UsbBridge;

// Real encrypted Mux -> SOCKS cmd 5 -> UDP. No external network or route changes.
class RelayUdpTests {
 static void Check(bool value,string message){if(!value)throw new Exception(message);Console.WriteLine("PASS "+message);}
 static void Send(SecureWire phone,int type,byte[] payload){byte[] frame=new byte[payload.Length+5];frame[0]=(byte)type;Wire.Put(frame,1,1);Buffer.BlockCopy(payload,0,frame,5,payload.Length);phone.Send(frame);}
 static byte[] Read(SecureWire phone,int count){MemoryStream bytes=new MemoryStream();DateTime deadline=DateTime.UtcNow.AddSeconds(5);while(bytes.Length<count){if(DateTime.UtcNow>deadline)throw new IOException("Relay response deadline exceeded");byte[] frame=phone.Receive();if(frame[0]==7||frame[0]==6)continue;if(frame[0]==5){Send(phone,6,new byte[0]);continue;}if(frame[0]!=2)throw new IOException("Unexpected Mux record "+frame[0]);bytes.Write(frame,5,frame.Length-5);byte[] credit=new byte[4];Wire.Put(credit,0,frame.Length-5);Send(phone,7,credit);}if(bytes.Length!=count)throw new IOException("Unexpected stream length");return bytes.ToArray();}
 static byte[] Packet(int port,byte[] payload){byte[] packet=new byte[10+payload.Length];packet[0]=(byte)(payload.Length>>8);packet[1]=(byte)payload.Length;packet[2]=10;packet[3]=1;packet[4]=127;packet[7]=1;packet[8]=(byte)(port>>8);packet[9]=(byte)port;Buffer.BlockCopy(payload,0,packet,10,payload.Length);return packet;}
 static int Main(){try{
  using(UdpClient echo=new UdpClient(new IPEndPoint(IPAddress.Loopback,0))){
   echo.Client.ReceiveTimeout=5000;int port=((IPEndPoint)echo.Client.LocalEndPoint).Port;IPAddress[] origins=new IPAddress[2];Exception serverError=null;ManualResetEvent echoed=new ManualResetEvent(false);
   Wire.Run(delegate{try{for(int i=0;i<2;i++){IPEndPoint sender=null;byte[] data=echo.Receive(ref sender);origins[i]=sender.Address;echo.Send(data,data.Length,sender);}}catch(Exception error){serverError=error;}finally{echoed.Set();}});
   TcpListener listener=new TcpListener(IPAddress.Loopback,0);listener.Start();
   using(TcpClient client=new TcpClient()){
    client.Connect((IPEndPoint)listener.LocalEndpoint);using(TcpClient server=listener.AcceptTcpClient()){
     listener.Stop();client.ReceiveTimeout=server.ReceiveTimeout=5000;client.NoDelay=server.NoDelay=true;SecureWire pc=null;Exception handshakeError=null;ManualResetEvent ready=new ManualResetEvent(false);
     Wire.Run(delegate{try{pc=new SecureWire(server.GetStream(),"test",false);}catch(Exception error){handshakeError=error;}finally{ready.Set();}});
     using(SecureWire phone=new SecureWire(client.GetStream(),"test",true)){
      if(!ready.WaitOne(5000,false)||handshakeError!=null)throw new Exception("Handshake failed",handshakeError);ready.Close();
      Relay relay=new Relay(IPAddress.Parse("127.0.0.2"),0,IPAddress.Loopback,IPAddress.Parse("127.0.0.3"),0);
      using(Mux mux=new Mux(pc)){
       mux.Accepted=relay.Serve;mux.Start();Send(phone,1,new byte[0]);Send(phone,2,new byte[]{5,1,0});Check(Wire.Equal(Read(phone,2),new byte[]{5,0}),"SOCKS handshake works over encrypted transport");
       Send(phone,2,new byte[]{5,5,0,1,0,0,0,0,0,0});Check(Read(phone,10)[1]==0,"backwards-compatible SOCKS command 5 accepted");
       byte[] wireGuard=new byte[148];wireGuard[0]=1;for(int i=4;i<wireGuard.Length;i++)wireGuard[i]=(byte)i;
       byte[] expected=Packet(port,wireGuard);Send(phone,2,expected);Check(Wire.Equal(Read(phone,expected.Length),expected),"WireGuard initiation payload crosses UDP relay unchanged");
       byte[] ordinary=Packet(port,new byte[]{4,0,0,0,7,8,9});Send(phone,2,ordinary);Check(Wire.Equal(Read(phone,ordinary.Length),ordinary),"subsequent UDP packet crosses the same association unchanged");
       Check(echoed.WaitOne(5000,false)&&serverError==null,"UDP echo fixture completes");
       Check(origins[0].Equals(IPAddress.Parse("127.0.0.2"))&&origins[1].Equals(origins[0]),"WireGuard handshake and following packets honor selected source without physical bypass");
       int recoveringPort;using(UdpClient reservation=new UdpClient(new IPEndPoint(IPAddress.Loopback,0)))recoveringPort=((IPEndPoint)reservation.Client.LocalEndPoint).Port;
       // The first datagram receives ICMP port-unreachable. The same peer then
       // becomes available; one old ICMP must not permanently kill its reader.
       Send(phone,2,Packet(recoveringPort,new byte[]{1}));Thread.Sleep(300);
       using(UdpClient recovering=new UdpClient(new IPEndPoint(IPAddress.Loopback,recoveringPort)))using(ManualResetEvent recovered=new ManualResetEvent(false)){
        recovering.Client.ReceiveTimeout=4000;Exception recoverError=null;
        Wire.Run(delegate{try{IPEndPoint sender=null;byte[] payload=recovering.Receive(ref sender);recovering.Send(payload,payload.Length,sender);}catch(Exception e){recoverError=e;}finally{recovered.Set();}});
        byte[] recovery=Packet(recoveringPort,new byte[]{4,0,0,0,10,11});Send(phone,2,recovery);
        Check(Wire.Equal(Read(phone,recovery.Length),recovery),"UDP destination recovers after an earlier ICMP port-unreachable");
        Check(recovered.WaitOne(1000,false)&&recoverError==null,"recovered UDP fixture completes without another association");
       }
      }
     }
    }
   }
   echoed.Close();
  }
  Console.WriteLine("TOTAL 8 passed");return 0;
 }catch(Exception error){Console.Error.WriteLine(error);return 1;}}
}
