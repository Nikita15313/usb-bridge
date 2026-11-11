package local.usbbridge;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class Wire {
 static byte[] read(InputStream s,int n)throws IOException {byte[] b=new byte[n];int p=0;while(p<n){int k=s.read(b,p,n-p);if(k<0)throw new EOFException();p+=k;}return b;}
 static int number(byte[] b,int p){return (b[p]&255)<<24|(b[p+1]&255)<<16|(b[p+2]&255)<<8|(b[p+3]&255);}
 static void put(byte[] b,int p,int v){b[p]=(byte)(v>>24);b[p+1]=(byte)(v>>16);b[p+2]=(byte)(v>>8);b[p+3]=(byte)v;}
 static byte[] cat(byte[]... parts){int n=0;for(byte[] b:parts)n+=b.length;byte[] r=new byte[n];n=0;for(byte[] b:parts){System.arraycopy(b,0,r,n,b.length);n+=b.length;}return r;}
 static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 static byte[] random(int n){byte[] b=new byte[n];new SecureRandom().nextBytes(b);return b;}
 static byte[] mac(byte[] k,byte[] b)throws GeneralSecurityException {Mac h=Mac.getInstance("HmacSHA256");h.init(new SecretKeySpec(k,"HmacSHA256"));return h.doFinal(b);}
 static void run(Runnable r){Thread t=new Thread(r);t.setDaemon(true);t.start();}
 public static class Secure implements Closeable {
  final InputStream in;final OutputStream out;final Closeable owner;byte[] txKey,rxKey,txMac,rxMac;int tx,rx;
  final SecureRandom entropy=new SecureRandom();Cipher encrypt,decrypt;Mac sendMac,receiveMac;
  Secure(InputStream in,OutputStream out,Closeable owner,String code,boolean client)throws Exception {
   this.in=in;this.out=out;this.owner=owner;byte[] key=bytes(code.replace(" ","").replace("-","").toUpperCase(Locale.ROOT)),c,s;
   if(client){c=random(32);out.write(c);s=read(in,32);if(!MessageDigest.isEqual(read(in,32),mac(key,cat(bytes("phone"),c,s))))throw new IOException("Неверный код");out.write(mac(key,cat(bytes("pc"),c,s)));}
   else{c=read(in,32);s=random(32);out.write(cat(s,mac(key,cat(bytes("phone"),c,s))));if(!MessageDigest.isEqual(read(in,32),mac(key,cat(bytes("pc"),c,s))))throw new IOException("Неверный код на компьютере");}
   byte[] master=mac(key,cat(c,s));String send=client?"pc":"phone",recv=client?"phone":"pc";txKey=mac(master,bytes(send+"-enc"));rxKey=mac(master,bytes(recv+"-enc"));txMac=mac(master,bytes(send+"-mac"));rxMac=mac(master,bytes(recv+"-mac"));
   encrypt=Cipher.getInstance("AES/CBC/PKCS5Padding");decrypt=Cipher.getInstance("AES/CBC/PKCS5Padding");sendMac=Mac.getInstance("HmacSHA256");receiveMac=Mac.getInstance("HmacSHA256");sendMac.init(new SecretKeySpec(txMac,"HmacSHA256"));receiveMac.init(new SecretKeySpec(rxMac,"HmacSHA256"));
  }
  // Sender and receiver have separate crypto state; only the sender is locked.
  // A fresh random IV and authentication tag are still generated for every frame.
  synchronized void send(byte[] p)throws Exception {if(p.length>8192)throw new IOException("Frame too big");byte[] iv=new byte[16];entropy.nextBytes(iv);encrypt.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(txKey,"AES"),new IvParameterSpec(iv));byte[] seq=new byte[4];put(seq,0,tx++);byte[] body=cat(seq,iv,encrypt.doFinal(p)),len=new byte[4];put(len,0,body.length+32);out.write(cat(len,body,sendMac.doFinal(body)));out.flush();}
  byte[] receive()throws Exception {int n=number(read(in,4),0);if(n<68||n>8300)throw new IOException("Invalid frame");byte[] packet=read(in,n),body=Arrays.copyOf(packet,n-32);if(!MessageDigest.isEqual(Arrays.copyOfRange(packet,n-32,n),receiveMac.doFinal(body))||number(body,0)!=rx++)throw new IOException("Frame authentication failed");decrypt.init(Cipher.DECRYPT_MODE,new SecretKeySpec(rxKey,"AES"),new IvParameterSpec(Arrays.copyOfRange(body,4,20)));return decrypt.doFinal(body,20,body.length-20);}
  public void close()throws IOException{owner.close();}
 }
 public interface Events{void status(String text);void closed(String why);}
 public static class Mux implements Closeable {
  static final int MAX_CHANNELS=192;
  final Object admission=new Object();volatile int peerLimit=96;
  final Secure wire;final ConcurrentHashMap<Integer,Channel> channels=new ConcurrentHashMap<>();final ArrayBlockingQueue<byte[]> sends=new ArrayBlockingQueue<>(128);final AtomicInteger ids=new AtomicInteger(1);volatile boolean closed;volatile long last=System.nanoTime();final Events events;final AtomicLong sent=new AtomicLong(),received=new AtomicLong();
  Mux(Secure wire,Events events){this.wire=wire;this.events=events;}
  void start(){run(()->{try{while(!closed){byte[] b=sends.poll(1,TimeUnit.SECONDS);if(b!=null){wire.send(b);sent.addAndGet(b.length);}}}catch(Exception e){fail(e);}});run(()->{try{while(!closed){byte[] b=wire.receive();last=System.nanoTime();received.addAndGet(b.length);if(b.length<5)throw new IOException("Short frame");int t=b[0],id=number(b,1);byte[] d=Arrays.copyOfRange(b,5,b.length);if(t==5){pong(d);continue;}if(t==6)continue;if(t==8){String text=new String(d,StandardCharsets.UTF_8);if(!capacityStatus(text))events.status(text);continue;}Channel ch=channels.get(id);if(ch!=null)ch.feed(t,d);}}catch(Exception e){fail(e);}});run(()->{try{while(!closed){Thread.sleep(3000);if(System.nanoTime()-last>TimeUnit.SECONDS.toNanos(20))throw new IOException("Компьютер перестал отвечать");send(5,0,new byte[0]);}}catch(Exception e){fail(e);}});}
  boolean capacityStatus(String text){if(!text.startsWith("LIMIT|"))return false;try{int value=Integer.parseInt(text.substring(6));if(value>0&&value<=MAX_CHANNELS)synchronized(admission){peerLimit=value;}}catch(NumberFormatException ignored){}return true;}
  void send(int type,int id,byte[] d)throws IOException{byte[] b=new byte[d.length+5];b[0]=(byte)type;put(b,1,id);System.arraycopy(d,0,b,5,d.length);try{while(!closed){if(sends.offer(b,1,TimeUnit.SECONDS))return;}throw new IOException("Канал закрыт");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
  // A pong must never stop the receive thread from draining data and credit.
  // During congestion any authenticated incoming frame already proves liveness.
  void pong(byte[] d){if(closed)return;byte[] b=new byte[d.length+5];b[0]=6;System.arraycopy(d,0,b,5,d.length);sends.offer(b);}
  Channel reserve()throws IOException{synchronized(admission){if(closed||channels.size()>=peerLimit)throw new IOException("Канал недоступен или занят");int id=ids.getAndIncrement();if(id<=0)throw new IOException("Исчерпаны номера каналов");Channel ch=new Channel(this,id);channels.put(id,ch);return ch;}}
  Channel open()throws IOException{Channel ch=reserve();try{send(1,ch.id,new byte[0]);return ch;}catch(IOException e){channels.remove(ch.id);ch.abort();throw e;}}
  // Provider admission holds the service lifecycle lock: never wait for a slow USB queue here.
  Channel openNow()throws IOException{Channel ch=reserve();byte[] frame=new byte[5];frame[0]=1;put(frame,1,ch.id);if(closed||!sends.offer(frame)){channels.remove(ch.id);ch.abort();throw new IOException("Канал USB занят");}return ch;}
  void fail(Exception e){if(closed)return;close();events.closed(e.getMessage());}
  public void close(){synchronized(admission){closed=true;}try{wire.close();}catch(Exception ignored){}for(Channel c:channels.values())c.abort();channels.clear();}
 }
 public static class Channel implements Closeable {
  final Mux mux;final int id;final ArrayDeque<byte[]> queue=new ArrayDeque<>();int offset,queued,credit=65536;boolean dead,remoteFin,localFin;
  Channel(Mux mux,int id){this.mux=mux;this.id=id;}
  synchronized void abort(){dead=true;notifyAll();}
  synchronized void feed(int type,byte[] d)throws IOException{if(type==2){if(remoteFin||d.length==0||d.length>4096||queued+d.length>65536)throw new IOException("Stream overflow");queue.add(d);queued+=d.length;}else if(type==3)remoteFin=true;else if(type==4)dead=true;else if(type==7){if(d.length!=4)throw new IOException("Bad credit");int n=number(d,0);if(n<=0||credit+n>65536)throw new IOException("Bad credit");credit+=n;}else throw new IOException("Unknown frame");notifyAll();}
  final InputStream in=new InputStream(){public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}public int read(byte[] b,int o,int n)throws IOException{if(b==null)throw new NullPointerException();if(o<0||n<0||o>b.length-n)throw new IndexOutOfBoundsException();if(n==0)return 0;int k=0;synchronized(Channel.this){while(queue.isEmpty()&&!remoteFin&&!dead&&!mux.closed)pause();if(dead||mux.closed)throw new IOException("Stream closed");if(queue.isEmpty())return -1;while(k<n&&!queue.isEmpty()){byte[] top=queue.peek();int take=Math.min(n-k,top.length-offset);System.arraycopy(top,offset,b,o+k,take);offset+=take;queued-=take;k+=take;if(offset==top.length){queue.remove();offset=0;}}}byte[] ack=new byte[4];put(ack,0,k);mux.send(7,id,ack);return k;}};
  final OutputStream out=new OutputStream(){public void write(int b)throws IOException{write(new byte[]{(byte)b});}public void write(byte[] b,int o,int n)throws IOException{while(n>0){int k;synchronized(Channel.this){while(credit==0&&!dead&&!mux.closed)pause();if(dead||mux.closed||localFin)throw new IOException("Stream closed");k=Math.min(Math.min(n,4096),credit);credit-=k;}mux.send(2,id,Arrays.copyOfRange(b,o,o+k));o+=k;n-=k;}}};
  void pause()throws IOException{try{wait(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
  void finish()throws IOException{if(localFin)return;localFin=true;mux.send(3,id,new byte[0]);}
  public void close(){boolean was;synchronized(this){was=dead;dead=true;notifyAll();}if(!was&&!mux.closed)try{mux.send(4,id,new byte[0]);}catch(Exception ignored){}mux.channels.remove(id);}
 }
}
