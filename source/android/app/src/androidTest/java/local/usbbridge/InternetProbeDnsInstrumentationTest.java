package local.usbbridge;

import android.test.InstrumentationTestCase;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public final class InternetProbeDnsInstrumentationTest extends InstrumentationTestCase {
 private void check(int failure)throws Exception {
  assertNull("Stop Bridge before DNS fixture",BridgeService.instance);
  ExecutorService worker=Executors.newSingleThreadExecutor();
  try(ServerSocket server=new ServerSocket(BridgeService.SOCKS,1,InetAddress.getByName("127.0.0.1"))){
   server.setSoTimeout(4000);
   Future<?> peer=worker.submit(()->{try(Socket socket=server.accept()){
    socket.setSoTimeout(4000);InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();
    assertEquals(5,Wire.read(in,3)[0]);out.write(new byte[]{5,0});
    byte[] command=Wire.read(in,10);
    assertEquals(1,command[1]);assertEquals(1,command[3]);assertEquals(198,command[4]&255);assertEquals(53,command[9]);
    out.write(new byte[]{5,(byte)(failure==1?5:0),0,1,0,0,0,0,0,0});if(failure==1)return;
    byte[] prefix=Wire.read(in,2);byte[] query=Wire.read(in,((prefix[0]&255)<<8)|(prefix[1]&255));
    query[2]=(byte)0x81;query[3]=(byte)(failure==2?0x83:0x80);query[7]=(byte)(failure==4?0:1);
    if(failure==3)query[0]++;
    byte[] answer=Wire.cat(query,new byte[]{(byte)0xc0,12,0,1,0,1,0,0,0,30,0,4,93,(byte)184,(byte)216,34});
    out.write(new byte[]{(byte)(answer.length>>8),(byte)answer.length});out.write(answer);
   }catch(IOException error){throw new RuntimeException(error);}});
   boolean failed=false;try{InternetProbe.checkPhoneDns();}catch(IOException expected){failed=true;}
   assertEquals("Phone DNS must be part of source verification",failure!=0,failed);
   peer.get(5,TimeUnit.SECONDS);
  }finally{worker.shutdownNow();}
 }
 public void testDnsUsesPhoneSyntheticEndpoint()throws Exception{check(0);}
 public void testDnsRefusalCannotReportInternetSuccess()throws Exception{check(1);}
 public void testNxDomainCannotReportInternetSuccess()throws Exception{check(2);}
 public void testWrongTransactionCannotReportInternetSuccess()throws Exception{check(3);}
 public void testEmptyAnswerCannotReportInternetSuccess()throws Exception{check(4);}
}
