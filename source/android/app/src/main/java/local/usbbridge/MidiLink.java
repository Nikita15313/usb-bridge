package local.usbbridge;
import android.content.Context;
import android.media.midi.*;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

final class MidiLink implements Closeable {
 interface Listener{void opened(Pipe pipe);void status(String s);}
 interface PacketSender{void send(byte[] packet)throws IOException;}
 final MidiManager manager;final Listener listener;volatile MidiDevice device;MidiInputPort output;MidiOutputPort input;PacketSender sender;volatile boolean closed;boolean opening;int openingId=-1;long openGeneration;final ExecutorService receiver=Executors.newSingleThreadExecutor();volatile Pipe current;ByteArrayOutputStream sysex=new ByteArrayOutputStream();boolean collecting;
 MidiLink(Context ctx,Listener listener)throws IOException {this.listener=listener;manager=(MidiManager)ctx.getSystemService(Context.MIDI_SERVICE);if(manager==null)throw new IOException("MIDI API отсутствует");manager.registerDeviceCallback(callback,new Handler(Looper.getMainLooper()));scan();}
 // The packet transport seam exercises the real SysEx/session parser without USB hardware.
 MidiLink(Listener listener,PacketSender sender){this.listener=listener;this.sender=sender;manager=null;}
 final MidiManager.DeviceCallback callback=new MidiManager.DeviceCallback(){public void onDeviceAdded(MidiDeviceInfo i){scan();}public void onDeviceRemoved(MidiDeviceInfo i){synchronized(MidiLink.this){if(opening&&openingId==i.getId()){opening=false;openingId=-1;openGeneration++;}if(device!=null&&device.getInfo().getId()==i.getId()){closePorts();listener.status("USB-MIDI отключён");}}}};
 synchronized void scan(){
  if(closed||device!=null||opening||manager==null)return;
  for(MidiDeviceInfo i:manager.getDevices()){
   String name=i.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME,"");
   if(i.getInputPortCount()==0||i.getOutputPortCount()==0||(i.getType()!=MidiDeviceInfo.TYPE_USB&&!name.toLowerCase(Locale.ROOT).contains("peripheral")))continue;
   opening=true;openingId=i.getId();final long generation=++openGeneration;
   manager.openDevice(i,d->{synchronized(MidiLink.this){
    if(closed||generation!=openGeneration||device!=null){if(d!=null)try{d.close();}catch(Exception ignored){}return;}
    opening=false;openingId=-1;
    if(d==null){listener.status("Не удалось открыть MIDI");return;}
    device=d;output=d.openInputPort(0);input=d.openOutputPort(0);
    if(output==null||input==null){closePorts();listener.status("Порты MIDI заняты другим приложением");return;}
    final MidiInputPort port=output;sender=b->port.send(b,0,b.length);
    input.connect(new MidiReceiver(){public void onSend(byte[] data,int offset,int count,long timestamp){
     byte[] copy=Arrays.copyOfRange(data,offset,offset+count);
     try{receiver.execute(()->{synchronized(MidiLink.this){if(!closed&&device==d)feed(copy);}});}catch(RejectedExecutionException ignored){}
    }});
    listener.status("MIDI готов. Нажмите «Подключить» на ПК.");
   }},new Handler(Looper.getMainLooper()));return;
  }
  listener.status("Выберите MIDI в USB-настройках телефона");
 }
 synchronized void feed(byte[] b){if(closed)return;for(byte v:b){int x=v&255;if(x>=248)continue;if(x==240){sysex.reset();collecting=true;continue;}if(!collecting)continue;if(x==247){collecting=false;decode(sysex.toByteArray());continue;}if(x>=128||sysex.size()>1200){collecting=false;continue;}sysex.write(x);}}
 static long crc(byte[] b,int n){CRC32 c=new CRC32();c.update(b,0,n);return c.getValue();}
 static byte[] encode(byte[] b){ByteArrayOutputStream o=new ByteArrayOutputStream();o.write(240);o.write(125);o.write(85);o.write(66);for(int p=0;p<b.length;){int k=Math.min(7,b.length-p),mask=0;for(int j=0;j<k;j++)mask|=((b[p+j]&255)>>7)<<j;o.write(mask);for(int j=0;j<k;j++)o.write(b[p++]&127);}o.write(247);return o.toByteArray();}
 synchronized void packet(int type,int session,int seq,byte[] payload)throws IOException{if(sender==null||closed)throw new IOException("MIDI отключён");byte[] raw=new byte[13+payload.length];raw[0]=(byte)type;Wire.put(raw,1,session);Wire.put(raw,5,seq);System.arraycopy(payload,0,raw,9,payload.length);Wire.put(raw,raw.length-4,(int)crc(raw,raw.length-4));sender.send(encode(raw));}
 void decode(byte[] b){if(b.length<4||b[0]!=125||b[1]!=85||b[2]!=66)return;ByteArrayOutputStream o=new ByteArrayOutputStream();for(int p=3;p<b.length;){int mask=b[p++];for(int j=0;j<7&&p<b.length;j++)o.write((b[p++]&127)|((mask>>j&1)<<7));}byte[] r=o.toByteArray();if(r.length<13||Wire.number(r,r.length-4)!=(int)crc(r,r.length-4))return;int type=r[0],session=Wire.number(r,1),seq=Wire.number(r,5);try{if(type==2){
   // Another PC process can send HELLO on the same MIDI device. It must not
   // destroy the live stream before BridgeService can reject that connection.
   Pipe active=current;
   if(active!=null&&!active.closed&&active.session!=session)return;
   if(active==null||active.closed){current=new Pipe(session);Pipe p=current;Wire.run(()->{if(!closed&&current==p&&!p.closed)listener.opened(p);});}
   packet(3,session,0,new byte[0]);return;
  }Pipe p=current;if(p==null||p.session!=session||p.closed)return;if(type==4){if(seq==0&&r.length==13)p.abort();return;}if(type==1){synchronized(p){p.acked=seq;p.notifyAll();}return;}if(type==0){boolean accepted=false;synchronized(p){if(seq==p.expected){if(p.queue.size()+r.length-13>262144){p.abort();return;}for(int i=9;i<r.length-4;i++)p.queue.add(r[i]);p.expected++;accepted=true;p.notifyAll();}else if(seq==p.expected-1)accepted=true;}if(accepted)packet(1,session,seq,new byte[0]);}}catch(IOException e){if(current!=null)current.abort();listener.status(e.getMessage());}}
 final class Pipe implements Closeable {
  final int session;int seq=1,expected=1,acked=-1;volatile boolean closed;final ArrayDeque<Byte> queue=new ArrayDeque<>();
  Pipe(int session){this.session=session;}
  final InputStream in=new InputStream(){public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}public int read(byte[] b,int o,int n)throws IOException{if(n==0)return 0;synchronized(Pipe.this){while(queue.isEmpty()&&!closed)pause();if(closed)throw new IOException("MIDI closed");int k=Math.min(n,queue.size());for(int i=0;i<k;i++)b[o+i]=queue.remove();return k;}}};
  final OutputStream out=new OutputStream(){public void write(int b)throws IOException{write(new byte[]{(byte)b});}public synchronized void write(byte[] b,int o,int n)throws IOException{while(n>0){int k=Math.min(768,n);byte[] data=Arrays.copyOfRange(b,o,o+k);boolean ok=false;for(int a=0;a<6;a++){packet(0,session,seq,data);synchronized(Pipe.this){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!closed&&acked!=seq&&System.nanoTime()<end)pause();if(closed)throw new IOException("MIDI closed");if(acked==seq){ok=true;break;}}}if(!ok)throw new IOException("MIDI: компьютер перестал отвечать");seq++;o+=k;n-=k;}}};
  void pause()throws IOException{try{wait(100);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
  synchronized boolean abort(){if(closed)return false;closed=true;notifyAll();return true;}
  public void close(){if(!abort())return;try{packet(4,session,0,new byte[0]);}catch(IOException ignored){}}
 }
 synchronized void closePorts(){openGeneration++;opening=false;openingId=-1;sender=null;collecting=false;sysex.reset();if(current!=null)current.abort();try{if(input!=null)input.close();}catch(Exception ignored){}try{if(output!=null)output.close();}catch(Exception ignored){}try{if(device!=null)device.close();}catch(Exception ignored){}device=null;input=null;output=null;}
 public void close(){closed=true;if(manager!=null)manager.unregisterDeviceCallback(callback);closePorts();receiver.shutdownNow();}
}
