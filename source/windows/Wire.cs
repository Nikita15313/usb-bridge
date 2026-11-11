using System;
using System.IO;
using System.Text;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Threading;

namespace UsbBridge {
public static class Wire {
    public static byte[] Read(Stream s,int n) { byte[] b=new byte[n]; int p=0; while(p<n){int k=s.Read(b,p,n-p);if(k<=0)throw new EndOfStreamException();p+=k;}return b; }
    public static int Int(byte[] b,int p){return (b[p]<<24)|(b[p+1]<<16)|(b[p+2]<<8)|b[p+3];}
    public static void Put(byte[] b,int p,int v){b[p]=(byte)(v>>24);b[p+1]=(byte)(v>>16);b[p+2]=(byte)(v>>8);b[p+3]=(byte)v;}
    public static byte[] Cat(params byte[][] a){int n=0;foreach(byte[] b in a)n+=b.Length;byte[] r=new byte[n];n=0;foreach(byte[] b in a){Buffer.BlockCopy(b,0,r,n,b.Length);n+=b.Length;}return r;}
    static readonly RandomNumberGenerator rng=RandomNumberGenerator.Create(); public static byte[] Random(int n){byte[] b=new byte[n];lock(rng)rng.GetBytes(b);return b;}
    public static byte[] Mac(byte[] k,byte[] b){using(HMACSHA256 h=new HMACSHA256(k))return h.ComputeHash(b);}
    public static bool Equal(byte[] a,byte[] b){if(a.Length!=b.Length)return false;int x=0;for(int i=0;i<a.Length;i++)x|=a[i]^b[i];return x==0;}
    public static void Run(ThreadStart a){Thread t=new Thread(a);t.IsBackground=true;t.Start();}
}
public sealed class SecureWire : IDisposable {
    readonly Stream stream; byte[] txKey,rxKey,txMac,rxMac; int tx,rx,disposed; readonly object gate=new object(),readGate=new object();RijndaelManaged txCipher,rxCipher;HMACSHA256 txHasher,rxHasher;
    public SecureWire(Stream stream,string code,bool client){
        this.stream=stream;byte[] secret=Encoding.UTF8.GetBytes(code.Replace(" ","").Replace("-","").ToUpperInvariant());
        byte[] c,s;
        if(client){c=Wire.Random(32);stream.Write(c,0,c.Length);s=Wire.Read(stream,32);byte[] proof=Wire.Read(stream,32);if(!Wire.Equal(proof,Wire.Mac(secret,Wire.Cat(Encoding.ASCII.GetBytes("phone"),c,s))))throw new IOException("Неверный код телефона");byte[] p=Wire.Mac(secret,Wire.Cat(Encoding.ASCII.GetBytes("pc"),c,s));stream.Write(p,0,p.Length);}
        else{c=Wire.Read(stream,32);s=Wire.Random(32);byte[] p=Wire.Mac(secret,Wire.Cat(Encoding.ASCII.GetBytes("phone"),c,s));byte[] hello=Wire.Cat(s,p);stream.Write(hello,0,hello.Length);if(!Wire.Equal(Wire.Read(stream,32),Wire.Mac(secret,Wire.Cat(Encoding.ASCII.GetBytes("pc"),c,s))))throw new IOException("Bad code");}
        byte[] master=Wire.Mac(secret,Wire.Cat(c,s));string send=client?"pc":"phone",recv=client?"phone":"pc";
        txKey=Wire.Mac(master,Encoding.ASCII.GetBytes(send+"-enc"));rxKey=Wire.Mac(master,Encoding.ASCII.GetBytes(recv+"-enc"));txMac=Wire.Mac(master,Encoding.ASCII.GetBytes(send+"-mac"));rxMac=Wire.Mac(master,Encoding.ASCII.GetBytes(recv+"-mac"));
        txCipher=new RijndaelManaged();txCipher.Key=txKey;rxCipher=new RijndaelManaged();rxCipher.Key=rxKey;txHasher=new HMACSHA256(txMac);rxHasher=new HMACSHA256(rxMac);
    }
    public void Send(byte[] plain){lock(gate){if(disposed!=0)throw new IOException("Closed");if(plain.Length>8192)throw new IOException("Frame too large");byte[] iv=Wire.Random(16),cipher;txCipher.IV=iv;using(ICryptoTransform t=txCipher.CreateEncryptor())cipher=t.TransformFinalBlock(plain,0,plain.Length);byte[] seq=new byte[4];Wire.Put(seq,0,tx++);byte[] body=Wire.Cat(seq,iv,cipher);byte[] len=new byte[4];Wire.Put(len,0,body.Length+32);byte[] packet=Wire.Cat(len,body,txHasher.ComputeHash(body));stream.Write(packet,0,packet.Length);stream.Flush();}}
    public byte[] Receive(){lock(readGate){if(disposed!=0)throw new IOException("Closed");int n=Wire.Int(Wire.Read(stream,4),0);if(n<68||n>8300)throw new IOException("Invalid frame");byte[] b=Wire.Read(stream,n),body=new byte[n-32],mac=new byte[32];Buffer.BlockCopy(b,0,body,0,body.Length);Buffer.BlockCopy(b,body.Length,mac,0,32);if(!Wire.Equal(mac,rxHasher.ComputeHash(body))||Wire.Int(body,0)!=rx++)throw new IOException("Frame authentication failed");byte[] iv=new byte[16];Buffer.BlockCopy(body,4,iv,0,16);rxCipher.IV=iv;using(ICryptoTransform t=rxCipher.CreateDecryptor())return t.TransformFinalBlock(body,20,body.Length-20);}}
    public void Dispose(){if(Interlocked.Exchange(ref disposed,1)!=0)return;try{stream.Close();}finally{lock(gate){if(txCipher!=null)txCipher.Clear();if(txHasher!=null)txHasher.Clear();}lock(readGate){if(rxCipher!=null)rxCipher.Clear();if(rxHasher!=null)rxHasher.Clear();}}}
}
public sealed class Mux : IDisposable {
    public const int MaximumChannels=192;
    readonly SecureWire wire; readonly Dictionary<int,Channel> channels=new Dictionary<int,Channel>(); readonly Queue<byte[]> sends=new Queue<byte[]>(),controls=new Queue<byte[]>();
    public volatile bool Closed; public DateTime LastReceive=DateTime.UtcNow; public long Sent,Received; public Action<Channel> Accepted; public Action<string> Status;
    public Mux(SecureWire wire){this.wire=wire;}
    public void Start(){Wire.Run(ReadLoop);Wire.Run(WriteLoop);Wire.Run(delegate{try{while(!Closed){Thread.Sleep(3000);if((DateTime.UtcNow-LastReceive).TotalSeconds>20)throw new IOException("Телефон перестал отвечать");Send(5,0,new byte[0]);}}catch(Exception e){Fail(e.Message);}});}
    public void Send(int type,int id,byte[] data){byte[] b=new byte[data.Length+5];b[0]=(byte)type;Wire.Put(b,1,id);Buffer.BlockCopy(data,0,b,5,data.Length);lock(sends){while(sends.Count>=128&&!Closed)Monitor.Wait(sends,1000);if(Closed)throw new IOException("Канал закрыт");sends.Enqueue(b);Monitor.PulseAll(sends);}}
    // The reader must never wait behind its own writer queue: incoming credit
    // can be required to drain that queue. Capacity resets and pongs therefore
    // use a separate, bounded, nonblocking queue. A peer flooding more than 256
    // pending control replies may lose an excess reply; existing streams live.
    void Control(int type,int id,byte[] data){byte[] b=new byte[data.Length+5];b[0]=(byte)type;Wire.Put(b,1,id);Buffer.BlockCopy(data,0,b,5,data.Length);lock(sends){if(Closed||controls.Count>=256)return;controls.Enqueue(b);Monitor.PulseAll(sends);}}
    void WriteLoop(){int controlBurst=0;try{while(!Closed){byte[] b;lock(sends){while(sends.Count==0&&controls.Count==0&&!Closed)Monitor.Wait(sends);if(Closed)return;if(controls.Count>0&&(sends.Count==0||controlBurst<8)){b=controls.Dequeue();controlBurst++;}else{b=sends.Dequeue();controlBurst=0;}Monitor.PulseAll(sends);}wire.Send(b);Interlocked.Add(ref Sent,b.Length);}}catch(Exception e){Fail(e.Message);}}
    void ReadLoop(){try{while(!Closed){byte[] b=wire.Receive();LastReceive=DateTime.UtcNow;Interlocked.Add(ref Received,b.Length);if(b.Length<5)throw new IOException("Short frame");int type=b[0],id=Wire.Int(b,1);byte[] d=new byte[b.Length-5];Buffer.BlockCopy(b,5,d,0,d.Length);if(type==5){Control(6,0,d);continue;}if(type==6)continue;if(type==8){if(Status!=null)Status(Encoding.UTF8.GetString(d));continue;}Channel ch=null;bool rejected=false;lock(channels){if(type==1){if(id<=0||channels.ContainsKey(id))throw new IOException("Invalid stream");if(channels.Count>=MaximumChannels)rejected=true;else{ch=new Channel(this,id);channels.Add(id,ch);}}else if(!channels.TryGetValue(id,out ch))continue;}if(rejected){Control(4,id,new byte[0]);continue;}if(type==1){Channel captured=ch;Wire.Run(delegate{try{if(Accepted!=null)Accepted(captured);}catch{}finally{captured.Close();}});}else ch.Feed(type,d);}}catch(Exception e){Fail(e.Message);}}
    internal void Remove(int id){lock(channels)channels.Remove(id);}
    void Fail(string message){Dispose();if(Status!=null)Status("Отключено: "+message);}
    public void Dispose(){if(Closed)return;Closed=true;try{wire.Dispose();}catch{}lock(sends)Monitor.PulseAll(sends);lock(channels){foreach(Channel c in channels.Values)c.Abort();channels.Clear();}}
}
public sealed class Channel : Stream {
    readonly Mux mux;readonly int id;readonly Queue<byte[]> queue=new Queue<byte[]>();int queued,offset,credit=65536;bool remoteFin,localFin,dead;
    public Channel(Mux mux,int id){this.mux=mux;this.id=id;}
    public void Abort(){lock(queue){dead=true;Monitor.PulseAll(queue);}}
    public void Feed(int type,byte[] d){lock(queue){if(type==2){if(remoteFin||d.Length==0||d.Length>4096||queued+d.Length>65536)throw new IOException("Stream overflow");queue.Enqueue(d);queued+=d.Length;}else if(type==3)remoteFin=true;else if(type==4)dead=true;else if(type==7){if(d.Length!=4)throw new IOException("Bad credit");int n=Wire.Int(d,0);if(n<=0||credit+n>65536)throw new IOException("Bad credit");credit+=n;}else throw new IOException("Unknown frame");Monitor.PulseAll(queue);}}
    public override int Read(byte[] b,int o,int n){
        if(b==null)throw new ArgumentNullException("b");if(o<0||n<0||o>b.Length-n)throw new ArgumentOutOfRangeException();if(n==0)return 0;
        int consumed=0;lock(queue){
            while(queue.Count==0&&!remoteFin&&!dead&&!mux.Closed)Monitor.Wait(queue,1000);
            if(dead||mux.Closed)throw new IOException("Stream closed");
            // Drain available records in one socket write/credit reply, but never
            // wait for more data just to fill the caller's buffer. This preserves
            // request/response latency and the existing 64 KiB flow-control bound.
            while(queue.Count>0&&consumed<n){byte[] top=queue.Peek();int k=Math.Min(n-consumed,top.Length-offset);Buffer.BlockCopy(top,offset,b,o+consumed,k);offset+=k;queued-=k;consumed+=k;if(offset==top.Length){queue.Dequeue();offset=0;}}
        }
        if(consumed>0){byte[] ack=new byte[4];Wire.Put(ack,0,consumed);mux.Send(7,id,ack);}return consumed;
    }
    public override void Write(byte[] b,int o,int n){while(n>0){int k;lock(queue){while(credit==0&&!dead&&!mux.Closed)Monitor.Wait(queue,1000);if(dead||mux.Closed||localFin)throw new IOException("Stream closed");k=Math.Min(Math.Min(n,4096),credit);credit-=k;}byte[] d=new byte[k];Buffer.BlockCopy(b,o,d,0,k);mux.Send(2,id,d);o+=k;n-=k;}}
    public void Finish(){if(localFin)return;localFin=true;mux.Send(3,id,new byte[0]);}
    protected override void Dispose(bool disposing){bool was;lock(queue){was=dead;dead=true;Monitor.PulseAll(queue);}if(!was&&!mux.Closed){try{mux.Send(4,id,new byte[0]);}catch{}}mux.Remove(id);base.Dispose(disposing);}
    public override bool CanRead{get{return true;}}public override bool CanWrite{get{return true;}}public override bool CanSeek{get{return false;}}public override long Length{get{throw new NotSupportedException();}}public override long Position{get{throw new NotSupportedException();}set{throw new NotSupportedException();}}public override void Flush(){}public override long Seek(long o,SeekOrigin s){throw new NotSupportedException();}public override void SetLength(long l){throw new NotSupportedException();}
}
}

