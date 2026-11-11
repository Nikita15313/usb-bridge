using System;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Threading;
using UsbBridge;

// A slow MIDI writer stops the relay's UDP reader while it waits for cable
// credit. Reproduce that pause with real sockets and the production factory.
// No external endpoints, VPN accounts, drivers or route changes are used.
class RelayUdpBurstTests {
 const int Count=160,Size=1200;
 static void Check(bool value,string message){if(!value)throw new Exception(message);Console.WriteLine("PASS "+message);}
 static int Main(){try{
  Relay relay=new Relay(IPAddress.Parse("127.0.0.2"),0,IPAddress.Loopback);
  MethodInfo factory=typeof(Relay).GetMethod("NewSocket",BindingFlags.NonPublic|BindingFlags.Instance);
  using(Socket receiver=(Socket)factory.Invoke(relay,new object[]{SocketType.Dgram}))
  using(UdpClient peer=new UdpClient(new IPEndPoint(IPAddress.Loopback,0))){
   receiver.ReceiveTimeout=700;peer.Client.ReceiveTimeout=1000;
   receiver.Connect(peer.Client.LocalEndPoint);receiver.Send(new byte[]{99});
   IPEndPoint destination=null;peer.Receive(ref destination);
   Check(destination.Address.Equals(IPAddress.Parse("127.0.0.2")),"burst fixture retains the selected Windows source");
   // Receive nothing until the complete 192 KiB burst has arrived. This is a
   // bounded cable stall, not a bandwidth benchmark or UDP reliability claim.
   for(int sequence=0;sequence<Count;sequence++){
    byte[] packet=new byte[Size];Wire.Put(packet,0,sequence);
    for(int offset=4;offset<Size;offset++)packet[offset]=(byte)(sequence*13+offset*29);
    peer.Send(packet,packet.Length,destination);
   }
   Thread.Sleep(80);
   bool[] seen=new bool[Count];int received=0;byte[] data=new byte[Size+1];Stopwatch deadline=Stopwatch.StartNew();
   while(received<Count&&deadline.ElapsedMilliseconds<3000){
    int length;try{length=receiver.Receive(data);}catch(SocketException error){if(error.SocketErrorCode==SocketError.TimedOut)break;throw;}
    if(length!=Size)throw new Exception("truncated burst packet");
    int sequence=Wire.Int(data,0);if(sequence<0||sequence>=Count||seen[sequence])throw new Exception("corrupt or duplicate burst packet");
    for(int offset=4;offset<Size;offset++)if(data[offset]!=(byte)(sequence*13+offset*29))throw new Exception("changed burst payload");
    seen[sequence]=true;received++;
   }
   Console.WriteLine("BURST received="+received+"/"+Count+"; bytes="+(Count*Size)+"; socketReceiveBuffer="+receiver.ReceiveBufferSize+"; CLR="+Environment.Version);
   Check(received==Count,"192 KiB VPN-size UDP burst survives a temporarily stalled cable reader");
   Check(true,"burst packet boundaries and all payload bytes remain unchanged");
  }
  Console.WriteLine("TOTAL 3 passed");return 0;
 }catch(Exception error){Console.Error.WriteLine(error);return 1;}}
}
