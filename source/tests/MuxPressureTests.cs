using System;
using System.IO;
using System.Text;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using UsbBridge;
class MuxPressureTests {
 sealed class GateStream:Stream {
  readonly Stream inner;public volatile bool Block;public readonly ManualResetEvent Entered=new ManualResetEvent(false),Release=new ManualResetEvent(false);
  public GateStream(Stream stream){inner=stream;}
  public override int Read(byte[] b,int o,int n){return inner.Read(b,o,n);}public override void Write(byte[] b,int o,int n){if(Block){Entered.Set();if(!Release.WaitOne(5000,false))throw new IOException("Test writer deadline");}inner.Write(b,o,n);}
  protected override void Dispose(bool disposing){Release.Set();inner.Close();base.Dispose(disposing);}public override void Flush(){inner.Flush();}public override bool CanRead{get{return true;}}public override bool CanWrite{get{return true;}}public override bool CanSeek{get{return false;}}public override long Length{get{throw new NotSupportedException();}}public override long Position{get{throw new NotSupportedException();}set{throw new NotSupportedException();}}public override long Seek(long offset,SeekOrigin origin){throw new NotSupportedException();}public override void SetLength(long value){throw new NotSupportedException();}
 }
 static int Main(){try{
  TcpListener listener=new TcpListener(IPAddress.Loopback,0);listener.Start();using(TcpClient client=new TcpClient()){
   client.Connect((IPEndPoint)listener.LocalEndpoint);using(TcpClient server=listener.AcceptTcpClient()){
    listener.Stop();client.NoDelay=server.NoDelay=true;client.ReceiveTimeout=server.ReceiveTimeout=5000;GateStream blocked=new GateStream(server.GetStream());SecureWire pc=null;Exception error=null;ManualResetEvent ready=new ManualResetEvent(false),received=new ManualResetEvent(false);
    Wire.Run(delegate{try{pc=new SecureWire(blocked,"fixture",false);}catch(Exception e){error=e;}finally{ready.Set();}});
    using(SecureWire phone=new SecureWire(client.GetStream(),"fixture",true)){
     if(!ready.WaitOne(5000,false)||error!=null)throw new Exception("Handshake failed",error);blocked.Block=true;
     using(Mux mux=new Mux(pc)){
      mux.Status=delegate(string text){if(text=="reader-progress")received.Set();};mux.Start();mux.Send(8,0,new byte[1]);if(!blocked.Entered.WaitOne(2000,false))throw new Exception("Writer not blocked");for(int i=0;i<128;i++)mux.Send(8,0,new byte[1]);
      phone.Send(new byte[]{5,0,0,0,0});byte[] data=Encoding.ASCII.GetBytes("reader-progress"),frame=new byte[data.Length+5];frame[0]=8;Buffer.BlockCopy(data,0,frame,5,data.Length);phone.Send(frame);
      if(!received.WaitOne(2000,false))throw new Exception("Reader stalled behind pong and full writer queue");Console.WriteLine("PASS reader continues past PING when writer queue is full");
      if(mux.Closed)throw new Exception("Congestion closed session");Console.WriteLine("PASS control congestion does not tear down authenticated session");
     }
    }
   }
  }Console.WriteLine("TOTAL 2 passed");return 0;
 }catch(Exception error){Console.Error.WriteLine(error);return 1;}}
}
