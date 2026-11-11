using System;
using System.IO;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;
using System.Diagnostics;
namespace UsbBridge {
public sealed class MidiPipe:Stream {
 readonly Action<byte[]> send; readonly ThreadStart cleanup; readonly object tx=new object(),state=new object();readonly Queue<byte> bytes=new Queue<byte>();readonly List<byte> frame=new List<byte>();bool sysex,ready;volatile bool closed;int disposeStarted;int session,seq=1,expected=1,acked=-1;public long Retries;
 public MidiPipe(Action<byte[]> send,ThreadStart cleanup){this.send=send;this.cleanup=cleanup;session=Wire.Int(Wire.Random(4),0);}
 internal long RetryCount {get{return Interlocked.Read(ref Retries);}}
 public static uint Crc(byte[] b,int n){uint c=0xffffffff;for(int i=0;i<n;i++){c^=b[i];for(int j=0;j<8;j++)c=(c>>1)^((c&1)!=0?0xedb88320u:0);}return ~c;}
 public static byte[] Encode(byte[] b){List<byte> o=new List<byte>();o.Add(0xf0);o.Add(0x7d);o.Add(0x55);o.Add(0x42);for(int p=0;p<b.Length;){int pos=o.Count;o.Add(0);byte mask=0;for(int j=0;j<7&&p<b.Length;j++,p++){mask|=(byte)((b[p]>>7)<<j);o.Add((byte)(b[p]&127));}o[pos]=mask;}o.Add(0xf7);return o.ToArray();}
 void SendPacket(int kind,int sn,byte[] payload){byte[] raw=new byte[13+payload.Length];raw[0]=(byte)kind;Wire.Put(raw,1,session);Wire.Put(raw,5,sn);Buffer.BlockCopy(payload,0,raw,9,payload.Length);Wire.Put(raw,raw.Length-4,(int)Crc(raw,raw.Length-4));send(Encode(raw));}
 void Packet(int kind,int sn,byte[] payload){if(closed)throw new IOException("MIDI closed");SendPacket(kind,sn,payload);}
 public void Connect(){for(int i=0;i<10;i++){Packet(2,0,new byte[0]);lock(state){if(!ready&&!closed)Monitor.Wait(state,1000);if(closed)throw new IOException("MIDI closed");if(ready)return;}}throw new IOException("MIDI найден, но телефон не ответил. Запустите USB Bridge на телефоне в режиме MIDI.");}
 public void Feed(byte[] input){if(closed)return;foreach(byte v in input){if(v>=0xf8)continue;if(v==0xf0){frame.Clear();sysex=true;continue;}if(!sysex)continue;if(v==0xf7){sysex=false;Decode(frame.ToArray());continue;}if(v>=128||frame.Count>1200){sysex=false;continue;}frame.Add(v);}}
 void Decode(byte[] b){if(b.Length<4||b[0]!=0x7d||b[1]!=0x55||b[2]!=0x42)return;List<byte> l=new List<byte>();for(int p=3;p<b.Length;){int mask=b[p++];for(int j=0;j<7&&p<b.Length;j++)l.Add((byte)(b[p++]|((mask>>j&1)<<7)));}byte[] r=l.ToArray();if(r.Length<13||unchecked((uint)Wire.Int(r,r.Length-4))!=Crc(r,r.Length-4)||Wire.Int(r,1)!=session)return;int type=r[0],sn=Wire.Int(r,5);if(type==4){if(sn==0&&r.Length==13)Abort();return;}if(type==3){lock(state){if(closed)return;ready=true;Monitor.PulseAll(state);}return;}if(type==1){lock(state){if(closed)return;acked=sn;Monitor.PulseAll(state);}return;}if(type!=0)return;bool accept=false;lock(state){if(closed||!ready)return;if(sn==expected){if(bytes.Count+r.Length-13>262144){closed=true;Monitor.PulseAll(state);return;}for(int p=9;p<r.Length-4;p++)bytes.Enqueue(r[p]);expected++;accept=true;Monitor.PulseAll(state);}else if(sn==expected-1)accept=true;}if(accept)Packet(1,sn,new byte[0]);}
 public override void Write(byte[] b,int o,int n){lock(tx){while(n>0){int k=Math.Min(768,n);byte[] p=new byte[k];Buffer.BlockCopy(b,o,p,0,k);bool ok=false;for(int attempt=0;attempt<6;attempt++){Packet(0,seq,p);lock(state){DateTime until=DateTime.UtcNow.AddSeconds(2);while(acked!=seq&&!closed&&DateTime.UtcNow<until)Monitor.Wait(state,100);if(closed)throw new IOException("MIDI closed");if(acked==seq){ok=true;break;}}Interlocked.Increment(ref Retries);}if(!ok)throw new IOException("MIDI: потеря связи или кабель отключён");seq++;o+=k;n-=k;}}}
 public override int Read(byte[] b,int o,int n){if(n==0)return 0;lock(state){while(bytes.Count==0&&!closed)Monitor.Wait(state,1000);if(closed)throw new IOException("MIDI closed");int k=Math.Min(n,bytes.Count);for(int i=0;i<k;i++)b[o+i]=bytes.Dequeue();return k;}}
 // Wake stream users without invoking the owner's cleanup callback recursively.
 public void Abort(){lock(state){closed=true;Monitor.PulseAll(state);}}
 protected override void Dispose(bool disposing){
  if(Interlocked.Exchange(ref disposeStarted,1)!=0){Abort();base.Dispose(disposing);return;}
  bool notify;lock(state){notify=ready&&!closed;closed=true;Monitor.PulseAll(state);}
  // CLOSE is scoped to this MIDI session. Wake waiters first; never hold state
  // while sending or invoking device cleanup. Direct device Abort sends nothing.
  try{if(notify)SendPacket(4,0,new byte[0]);}catch{}
  finally{Abort();try{if(cleanup!=null)cleanup();}finally{base.Dispose(disposing);}}
 }
 public override bool CanRead{get{return true;}}public override bool CanWrite{get{return true;}}public override bool CanSeek{get{return false;}}public override long Length{get{throw new NotSupportedException();}}public override long Position{get{throw new NotSupportedException();}set{throw new NotSupportedException();}}public override void Flush(){}public override long Seek(long o,SeekOrigin s){throw new NotSupportedException();}public override void SetLength(long l){throw new NotSupportedException();}
}
public sealed class MidiDevice:IDisposable {
 [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]struct InCaps{public ushort mid,pid;public uint version;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=32)]public string name;public uint support;}
 [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]struct OutCaps{public ushort mid,pid;public uint version;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=32)]public string name;public ushort technology,voices,notes,mask;public uint support;}
 [StructLayout(LayoutKind.Sequential)]struct Header{public IntPtr data;public uint length,recorded;public UIntPtr user;public uint flags;public IntPtr next;public UIntPtr reserved;public uint offset;[MarshalAs(UnmanagedType.ByValArray,SizeConst=8)]public UIntPtr[] more;}
 delegate void Callback(IntPtr h,uint msg,UIntPtr instance,IntPtr p1,IntPtr p2);
 [DllImport("winmm.dll")]static extern uint midiInGetNumDevs();[DllImport("winmm.dll")]static extern uint midiOutGetNumDevs();
 [DllImport("winmm.dll",CharSet=CharSet.Unicode)]static extern uint midiInGetDevCapsW(UIntPtr id,out InCaps c,uint n);[DllImport("winmm.dll",CharSet=CharSet.Unicode)]static extern uint midiOutGetDevCapsW(UIntPtr id,out OutCaps c,uint n);
 [DllImport("winmm.dll")]static extern uint midiInOpen(out IntPtr h,uint id,Callback cb,UIntPtr x,uint flags);[DllImport("winmm.dll")]static extern uint midiOutOpen(out IntPtr h,uint id,IntPtr cb,UIntPtr x,uint flags);
 [DllImport("winmm.dll")]static extern uint midiInPrepareHeader(IntPtr h,IntPtr p,uint n);[DllImport("winmm.dll")]static extern uint midiInUnprepareHeader(IntPtr h,IntPtr p,uint n);[DllImport("winmm.dll")]static extern uint midiInAddBuffer(IntPtr h,IntPtr p,uint n);
 [DllImport("winmm.dll")]static extern uint midiInStart(IntPtr h);[DllImport("winmm.dll")]static extern uint midiInStop(IntPtr h);[DllImport("winmm.dll")]static extern uint midiInReset(IntPtr h);[DllImport("winmm.dll")]static extern uint midiInClose(IntPtr h);
 [DllImport("winmm.dll")]static extern uint midiOutPrepareHeader(IntPtr h,IntPtr p,uint n);[DllImport("winmm.dll")]static extern uint midiOutUnprepareHeader(IntPtr h,IntPtr p,uint n);[DllImport("winmm.dll")]static extern uint midiOutLongMsg(IntPtr h,IntPtr p,uint n);[DllImport("winmm.dll")]static extern uint midiOutReset(IntPtr h);[DllImport("winmm.dll")]static extern uint midiOutClose(IntPtr h);
 IntPtr input,output;Callback callback;readonly List<IntPtr> buffers=new List<IntPtr>(),deferredOutput=new List<IntPtr>();readonly Queue<IntPtr> incoming=new Queue<IntPtr>();readonly object writeLock=new object(),readLock=new object();readonly AutoResetEvent outputReady=new AutoResetEvent(false);bool outputEventPinned;volatile bool disposed;int disposeStarted;public MidiPipe Pipe;static readonly uint size=(uint)Marshal.SizeOf(typeof(Header));
 public static string[] Inputs(){List<string> s=new List<string>();for(uint i=0;i<midiInGetNumDevs();i++){InCaps c;if(midiInGetDevCapsW((UIntPtr)i,out c,(uint)Marshal.SizeOf(typeof(InCaps)))==0)s.Add(i+" · "+c.name);}return s.ToArray();}
 public static string[] Outputs(){List<string> s=new List<string>();for(uint i=0;i<midiOutGetNumDevs();i++){OutCaps c;if(midiOutGetDevCapsW((UIntPtr)i,out c,(uint)Marshal.SizeOf(typeof(OutCaps)))==0&&c.technology==1)s.Add(i+" · "+c.name);}return s.ToArray();}
 static void Check(uint e){if(e!=0)throw new IOException("Ошибка Windows MIDI: "+e);}
 static IntPtr Allocate(byte[] data,int capacity){Header h=new Header();h.data=Marshal.AllocHGlobal(capacity);h.length=(uint)capacity;h.more=new UIntPtr[8];if(data!=null)Marshal.Copy(data,0,h.data,data.Length);IntPtr p=Marshal.AllocHGlobal((int)size);Marshal.StructureToPtr(h,p,false);return p;}
 static void Free(IntPtr p){Header h=(Header)Marshal.PtrToStructure(p,typeof(Header));Marshal.FreeHGlobal(h.data);Marshal.FreeHGlobal(p);}
 public MidiDevice(uint inId,uint outId){try{callback=OnMidi;outputReady.SafeWaitHandle.DangerousAddRef(ref outputEventPinned);Check(midiOutOpen(out output,outId,outputReady.SafeWaitHandle.DangerousGetHandle(),UIntPtr.Zero,0x50000));Check(midiInOpen(out input,inId,callback,UIntPtr.Zero,0x30000));Pipe=new MidiPipe(Send,Dispose);for(int i=0;i<16;i++){IntPtr p=Allocate(null,2048);buffers.Add(p);Check(midiInPrepareHeader(input,p,size));Check(midiInAddBuffer(input,p,size));}Wire.Run(ReceiveLoop);Check(midiInStart(input));}catch{Dispose();throw;}}
 // A zero-handle transport lets lifecycle tests exercise the real disposal path.
 internal MidiDevice(MidiPipe pipe){Pipe=pipe;}
 void OnMidi(IntPtr h,uint msg,UIntPtr instance,IntPtr p1,IntPtr p2){if(!disposed&&(msg==0x3c4||msg==0x3c6))lock(incoming){incoming.Enqueue(p1);Monitor.PulseAll(incoming);}}
 void ReceiveLoop(){try{while(!disposed){IntPtr p;lock(incoming){while(incoming.Count==0&&!disposed)Monitor.Wait(incoming);if(disposed)return;p=incoming.Dequeue();}byte[] b;lock(readLock){if(disposed)return;Header h=(Header)Marshal.PtrToStructure(p,typeof(Header));if(h.recorded>h.length)throw new IOException("Invalid MIDI buffer length");b=new byte[h.recorded];Marshal.Copy(h.data,b,0,b.Length);Check(midiInAddBuffer(input,p,size));}Pipe.Feed(b);}}catch{if(Pipe!=null)Pipe.Close();}}
 internal delegate bool CompletionState();
 internal static void AwaitOutput(WaitHandle signal,CompletionState complete,CompletionState canceled,int timeoutMs){
  Stopwatch elapsed=Stopwatch.StartNew();
  for(;;){if(canceled())throw new IOException("MIDI device closed");if(complete())return;long remaining=timeoutMs-elapsed.ElapsedMilliseconds;if(remaining<=0)throw new IOException("MIDI driver timed out");signal.WaitOne((int)remaining,false);}
 }
 void Send(byte[] data){lock(writeLock){if(disposed)throw new IOException("MIDI device closed");IntPtr p=Allocate(data,data.Length);try{
   Check(midiOutPrepareHeader(output,p,size));outputReady.Reset();Check(midiOutLongMsg(output,p,size));
   // CALLBACK_EVENT wakes immediately on driver completion. Sleep(1) polling
   // used to add a Windows timer quantum to asynchronous USB MIDI packets.
   // OPEN/CLOSE/spurious events are not completion: MHDR_DONE remains decisive.
   try{AwaitOutput(outputReady,delegate{return (((Header)Marshal.PtrToStructure(p,typeof(Header))).flags&1)!=0;},delegate{return disposed;},4000);}catch{midiOutReset(output);throw;}
  }finally{uint result=midiOutUnprepareHeader(output,p,size);if(result==0||(((Header)Marshal.PtrToStructure(p,typeof(Header))).flags&2)==0)Free(p);else deferredOutput.Add(p);}}}
 public void Dispose(){
  // Deadlines/Stop call the device directly, not necessarily Pipe.Close(). Abort
  // readers and ACK waiters before waiting for any native MIDI operation lock.
  MidiPipe pipe=Pipe;if(pipe!=null)pipe.Abort();
  if(Interlocked.Exchange(ref disposeStarted,1)!=0)return;disposed=true;outputReady.Set();
  lock(incoming)Monitor.PulseAll(incoming);
  lock(writeLock)lock(readLock){if(input!=IntPtr.Zero){midiInStop(input);midiInReset(input);}if(output!=IntPtr.Zero){midiOutReset(output);foreach(IntPtr p in deferredOutput){if(midiOutUnprepareHeader(output,p,size)==0)Free(p);}deferredOutput.Clear();if(midiOutClose(output)==0)output=IntPtr.Zero;}if(input!=IntPtr.Zero){foreach(IntPtr p in buffers){uint result=midiInUnprepareHeader(input,p,size);if(result==0||(((Header)Marshal.PtrToStructure(p,typeof(Header))).flags&2)==0)Free(p);}buffers.Clear();midiInClose(input);input=IntPtr.Zero;}
   // Never release a callback handle if a faulty driver refused to close.
   // The extra native reference intentionally survives until process exit then.
   if(output==IntPtr.Zero&&outputEventPinned){outputReady.SafeWaitHandle.DangerousRelease();outputEventPinned=false;}outputReady.Close();}
 }
}
}


