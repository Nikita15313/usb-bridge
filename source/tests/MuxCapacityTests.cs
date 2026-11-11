using System;
using System.IO;
using System.Text;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Diagnostics;
using UsbBridge;

class MuxCapacityTests {
 static int checks;
 static void Check(bool value,string message){if(!value)throw new Exception(message);checks++;Console.WriteLine("PASS "+message);}
 sealed class GateStream:Stream {
  readonly Stream inner;internal volatile bool Block;internal readonly ManualResetEvent Entered=new ManualResetEvent(false),Release=new ManualResetEvent(false);
  internal GateStream(Stream stream){inner=stream;}
  public override int Read(byte[] b,int o,int n){return inner.Read(b,o,n);}
  public override void Write(byte[] b,int o,int n){if(Block){Entered.Set();if(!Release.WaitOne(5000,false))throw new IOException("Fixture write deadline");}inner.Write(b,o,n);}
  protected override void Dispose(bool disposing){Release.Set();inner.Close();base.Dispose(disposing);}
  public override void Flush(){inner.Flush();}public override bool CanRead{get{return true;}}public override bool CanWrite{get{return true;}}public override bool CanSeek{get{return false;}}
  public override long Length{get{throw new NotSupportedException();}}public override long Position{get{throw new NotSupportedException();}set{throw new NotSupportedException();}}
  public override long Seek(long o,SeekOrigin s){throw new NotSupportedException();}public override void SetLength(long n){throw new NotSupportedException();}
 }
 sealed class Fixture:IDisposable {
  readonly TcpClient client=new TcpClient(),server;readonly SecureWire phone;readonly ManualResetEvent release=new ManualResetEvent(false);
  internal readonly GateStream Gate;internal readonly Mux Mux;internal int Accepted,Finished;internal volatile bool Progress;
  internal Fixture(){
   TcpListener listener=new TcpListener(IPAddress.Loopback,0);listener.Start();client.Connect((IPEndPoint)listener.LocalEndpoint);server=listener.AcceptTcpClient();listener.Stop();client.NoDelay=server.NoDelay=true;client.ReceiveTimeout=server.ReceiveTimeout=5000;Gate=new GateStream(server.GetStream());SecureWire pc=null;Exception error=null;
   using(ManualResetEvent ready=new ManualResetEvent(false)){Wire.Run(delegate{try{pc=new SecureWire(Gate,"capacity",false);}catch(Exception e){error=e;}finally{ready.Set();}});phone=new SecureWire(client.GetStream(),"capacity",true);if(!ready.WaitOne(3000,false)||error!=null)throw new Exception("Fixture handshake",error);}
   Mux=new Mux(pc);Mux.Status=delegate(string message){if(message=="progress")Progress=true;};
   Mux.Accepted=delegate(Channel channel){Interlocked.Increment(ref Accepted);try{int value=channel.ReadByte();if(value>=0)channel.WriteByte((byte)value);release.WaitOne(10000,false);}catch(IOException){}finally{channel.Close();Interlocked.Increment(ref Finished);}};Mux.Start();
  }
  internal void Send(int type,int id,byte[] data){byte[] frame=new byte[data.Length+5];frame[0]=(byte)type;Wire.Put(frame,1,id);Buffer.BlockCopy(data,0,frame,5,data.Length);phone.Send(frame);}
  internal void Open(int id){Send(1,id,new byte[0]);}
  internal byte[] Read(){while(true){byte[] frame=phone.Receive();if(frame[0]==5){Send(6,0,new byte[0]);continue;}if(frame[0]==6)continue;return frame;}}
  internal byte[] Until(int type,int id){Stopwatch elapsed=Stopwatch.StartNew();while(elapsed.ElapsedMilliseconds<4000){byte[] frame=Read();if(frame[0]==type&&Wire.Int(frame,1)==id)return frame;}throw new IOException("Fixture response deadline");}
  internal void WaitAccepted(int count){Stopwatch elapsed=Stopwatch.StartNew();while(Interlocked.CompareExchange(ref Accepted,0,0)<count&&elapsed.ElapsedMilliseconds<3000&&!Mux.Closed)Thread.Sleep(5);if(Accepted!=count)throw new Exception("Expected "+count+" accepted streams; got "+Accepted);}
  internal void WaitFinished(int count){Stopwatch elapsed=Stopwatch.StartNew();while(Interlocked.CompareExchange(ref Finished,0,0)<count&&elapsed.ElapsedMilliseconds<3000)Thread.Sleep(5);if(Finished<count)throw new Exception("Stream slot did not close");}
  internal void Echo(int id,byte value){Send(2,id,new byte[]{value});byte[] response=Until(2,id);if(response.Length!=6||response[5]!=value)throw new IOException("Old stream payload changed");}
  public void Dispose(){release.Set();Mux.Dispose();phone.Dispose();client.Close();server.Close();Gate.Release.Set();}
 }
 static void Capacity(){using(Fixture fixture=new Fixture()){
  for(int id=1;id<=96;id++)fixture.Open(id);fixture.WaitAccepted(96);
  Check(!fixture.Mux.Closed,"legacy peer can retain 96 simultaneous streams without capability negotiation");
  for(int id=97;id<=192;id++)fixture.Open(id);fixture.WaitAccepted(192);
  Check(Mux.MaximumChannels==192&&!fixture.Mux.Closed,"negotiated capacity admits exactly 192 simultaneous streams");
  fixture.Open(193);byte[] reset=fixture.Until(4,193);
  Check(reset.Length==5&&fixture.Accepted==192&&!fixture.Mux.Closed,"193rd stream alone is rejected while the authenticated connection remains open");
  fixture.Echo(1,17);Check(true,"existing stream still transfers after capacity rejection");
  fixture.Send(4,2,new byte[0]);fixture.WaitFinished(1);fixture.Open(194);fixture.WaitAccepted(193);fixture.Echo(194,29);
  Check(!fixture.Mux.Closed,"freed stream capacity is reused without reconnecting the cable");

  fixture.Gate.Block=true;fixture.Mux.Send(8,0,new byte[]{41});if(!fixture.Gate.Entered.WaitOne(2000,false))throw new Exception("Writer did not block");
  for(int n=0;n<128;n++)fixture.Mux.Send(8,0,new byte[]{42});
  fixture.Open(195);for(int id=200;id<500;id++)fixture.Open(id);fixture.Send(8,0,Encoding.ASCII.GetBytes("progress"));
  Stopwatch elapsed=Stopwatch.StartNew();while(!fixture.Progress&&elapsed.ElapsedMilliseconds<2000)Thread.Sleep(5);
  Check(fixture.Progress&&!fixture.Mux.Closed,"capacity overflow and control-queue saturation cannot block the reader behind a full data queue");
  fixture.Gate.Release.Set();byte[] first=fixture.Read();if(first[0]!=8||first[5]!=41)throw new Exception("Unexpected blocked writer record");
  int controls=0;bool rejected=false;while(true){byte[] frame=fixture.Read();if(frame[0]==4){controls++;if(Wire.Int(frame,1)==195)rejected=true;continue;}if(frame[0]==8&&frame.Length==6&&frame[5]==42)break;throw new Exception("Unexpected congestion frame");}
  Check(rejected&&controls>0&&controls<=8,"capacity reset is prioritized but control flooding cannot starve queued data");
  fixture.Echo(3,31);Check(!fixture.Mux.Closed,"an established stream remains usable after a flood of excess open requests");
 }}
 static void Invalid(bool duplicate){using(Fixture fixture=new Fixture()){
  fixture.Open(1);fixture.WaitAccepted(1);fixture.Open(duplicate?1:0);Stopwatch elapsed=Stopwatch.StartNew();while(!fixture.Mux.Closed&&elapsed.ElapsedMilliseconds<2000)Thread.Sleep(5);
  Check(fixture.Mux.Closed,duplicate?"duplicate stream ID still fails protocol validation":"nonpositive stream ID still fails protocol validation");
 }}
 static int Main(){try{Capacity();Invalid(true);Invalid(false);Console.WriteLine("TOTAL "+checks+" passed");return 0;}catch(Exception error){Console.Error.WriteLine(error);return 1;}}
}
