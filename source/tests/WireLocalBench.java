package local.usbbridge;
import java.net.*;import java.util.*;import java.util.concurrent.*;
/** Loopback crypto/frame benchmark only. Not a physical USB speed claim. */
public final class WireLocalBench {
 public static void main(String[] args)throws Exception{
  for(int trial=0;trial<4;trial++)try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
   final int count=8192;final byte[] frame=new byte[4096];Arrays.fill(frame,(byte)91);
   FutureTask<Void> server=new FutureTask<>(()->{try(Socket socket=listener.accept()){
    socket.setTcpNoDelay(true);socket.setSoTimeout(20000);
    Wire.Secure wire=new Wire.Secure(socket.getInputStream(),socket.getOutputStream(),socket,"LOCALBENCH",false);
    for(int i=0;i<count;i++)if(!Arrays.equals(frame,wire.receive()))throw new AssertionError("payload corruption");
    wire.send(new byte[]{1});return null;
   }});Wire.run(server);
   try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort())){
    socket.setTcpNoDelay(true);socket.setSoTimeout(20000);
    Wire.Secure wire=new Wire.Secure(socket.getInputStream(),socket.getOutputStream(),socket,"LOCALBENCH",true);
    long start=System.nanoTime();for(int i=0;i<count;i++)wire.send(frame);
    if(!Arrays.equals(wire.receive(),new byte[]{1}))throw new AssertionError("ack");server.get(25,TimeUnit.SECONDS);
    double seconds=(System.nanoTime()-start)/1e9;System.out.printf(Locale.ROOT,"%s %.2f Mbps, %.3f s, verified %d bytes%n",trial==0?"warmup":"sample",count*frame.length*8/seconds/1e6,seconds,count*frame.length);
   }
  }
 }
}
