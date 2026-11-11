using System;
using System.IO;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using UsbBridge;

// Loopback-only measurement of current framing/crypto/relay. This is not a claim
// about USB cable, Android device, MIDI hardware, ISP, or VPN throughput.
class LocalThroughput {
 const int Total=8*1024*1024;
 static void Frame(SecureWire phone,int type,int id,byte[] data){byte[] frame=new byte[data.Length+5];frame[0]=(byte)type;Wire.Put(frame,1,id);Buffer.BlockCopy(data,0,frame,5,data.Length);phone.Send(frame);}
 static byte[] Response(SecureWire phone,int expected){MemoryStream result=new MemoryStream();while(result.Length<expected){byte[] frame=phone.Receive();int type=frame[0];if(type==5){Frame(phone,6,0,new byte[0]);continue;}if(type==7||type==6)continue;if(type!=2)throw new IOException("Unexpected stream record "+type);int n=frame.Length-5;result.Write(frame,5,n);byte[] credit=new byte[4];Wire.Put(credit,0,n);Frame(phone,7,1,credit);}if(result.Length!=expected)throw new IOException("Response size mismatch");return result.ToArray();}
 static TcpListener Origin(out ManualResetEvent done,Action<Exception> failed){TcpListener listener=new TcpListener(IPAddress.Loopback,0);listener.Start();ManualResetEvent finished=new ManualResetEvent(false);done=finished;
  Wire.Run(delegate{try{using(TcpClient server=listener.AcceptTcpClient()){server.NoDelay=true;server.SendTimeout=15000;Stream stream=server.GetStream();if(stream.ReadByte()!=42)throw new IOException("Missing start marker");byte[] block=new byte[32768];for(int i=0;i<block.Length;i++)block[i]=0x5a;for(int n=0;n<Total;n+=block.Length)stream.Write(block,0,Math.Min(block.Length,Total-n));}}catch(Exception error){failed(error);}finally{listener.Stop();finished.Set();}});return listener;
 }
 static double Measure(bool bridge){Exception serverError=null;ManualResetEvent originDone;TcpListener origin=Origin(out originDone,delegate(Exception e){serverError=e;});int port=((IPEndPoint)origin.LocalEndpoint).Port;Stopwatch watch=new Stopwatch();
  if(!bridge){using(TcpClient direct=new TcpClient()){direct.NoDelay=true;direct.ReceiveTimeout=15000;direct.Connect(IPAddress.Loopback,port);Stream stream=direct.GetStream();watch.Start();stream.WriteByte(42);byte[] bytes=new byte[32768];int n,total=0;while((n=stream.Read(bytes,0,Math.Min(bytes.Length,Total-total)))>0){if(bytes[0]!=0x5a||bytes[n-1]!=0x5a)throw new IOException("Data mismatch");total+=n;if(total==Total)break;}watch.Stop();if(total!=Total)throw new IOException("Truncated direct transfer");}}
  else{
   TcpListener cable=new TcpListener(IPAddress.Loopback,0);cable.Start();using(TcpClient phoneSocket=new TcpClient()){
    phoneSocket.NoDelay=true;phoneSocket.ReceiveTimeout=15000;phoneSocket.Connect((IPEndPoint)cable.LocalEndpoint);using(TcpClient pcSocket=cable.AcceptTcpClient()){
     cable.Stop();pcSocket.NoDelay=true;pcSocket.ReceiveTimeout=15000;SecureWire pc=null;Exception handshakeError=null;ManualResetEvent ready=new ManualResetEvent(false);
     Wire.Run(delegate{try{pc=new SecureWire(pcSocket.GetStream(),"benchmark",false);}catch(Exception e){handshakeError=e;}finally{ready.Set();}});
     using(SecureWire phone=new SecureWire(phoneSocket.GetStream(),"benchmark",true)){
      if(!ready.WaitOne(5000,false)||handshakeError!=null)throw new Exception("Handshake",handshakeError);ready.Close();using(Mux mux=new Mux(pc)){
       Relay relay=new Relay(IPAddress.Loopback,0,IPAddress.Loopback);mux.Accepted=relay.Serve;mux.Start();Frame(phone,1,1,new byte[0]);Frame(phone,2,1,new byte[]{5,1,0});Response(phone,2);
       Frame(phone,2,1,new byte[]{5,1,0,1,127,0,0,1,(byte)(port>>8),(byte)port});if(Response(phone,10)[1]!=0)throw new IOException("Connect refused");
       watch.Start();Frame(phone,2,1,new byte[]{42});int total=0;while(total<Total){byte[] frame=phone.Receive();if(frame[0]==5){Frame(phone,6,0,new byte[0]);continue;}if(frame[0]==7||frame[0]==6)continue;if(frame[0]!=2)throw new IOException("Truncated bridge transfer");int n=frame.Length-5;if(n<1||frame[5]!=0x5a||frame[frame.Length-1]!=0x5a)throw new IOException("Data mismatch");total+=n;byte[] credit=new byte[4];Wire.Put(credit,0,n);Frame(phone,7,1,credit);}watch.Stop();if(total!=Total)throw new IOException("Wrong byte count");
      }
     }
    }
   }
  }
  if(!originDone.WaitOne(5000,false)||serverError!=null)throw new Exception("Origin failed",serverError);originDone.Close();return Total*8.0/watch.Elapsed.TotalSeconds/1000000.0;
 }
 static int Main(){try{Console.WriteLine("LOCAL LOOPBACK ONLY; 8 MiB each; Mbps; framing4096/window65536 unchanged");for(int trial=1;trial<=3;trial++){double direct=Measure(false),bridge=Measure(true);Console.WriteLine("trial="+trial+" rawTCP="+direct.ToString("F1",System.Globalization.CultureInfo.InvariantCulture)+" encryptedMuxRelay="+bridge.ToString("F1",System.Globalization.CultureInfo.InvariantCulture));}return 0;}catch(Exception error){Console.Error.WriteLine(error);return 1;}}
}
