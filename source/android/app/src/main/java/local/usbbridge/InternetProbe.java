package local.usbbridge;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import javax.net.ssl.*;

/** This probe can reach the web only through the authenticated PC relay. */
final class InternetProbe {
 static String check()throws Exception {
  Exception last=null;
  for(String[] target:new String[][]{{"www.gstatic.com","/generate_204","204"},{"example.com","/","200"}}){
   try{checkPhoneDns(target[0]);}catch(Exception dns){last=dns;continue;}
   try(Socket pipe=new Socket()){
    pipe.connect(new InetSocketAddress("127.0.0.1",BridgeService.SOCKS),3000);pipe.setSoTimeout(14000);
    InputStream in=pipe.getInputStream();OutputStream out=pipe.getOutputStream();
    out.write(new byte[]{5,1,0});if(!Arrays.equals(Wire.read(in,2),new byte[]{5,0}))throw new IOException("Канал ПК не отвечает");
    byte[] host=Wire.bytes(target[0]);out.write(Wire.cat(new byte[]{5,1,0,3,(byte)host.length},host,new byte[]{1,(byte)187}));
    if(Wire.read(in,10)[1]!=0)throw new IOException("Компьютер не открыл сайт");
    try(SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(pipe,target[0],443,true)){
     tls.setSoTimeout(14000);SSLParameters params=tls.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(params);tls.startHandshake();
     tls.getOutputStream().write(Wire.bytes("GET "+target[1]+" HTTP/1.1\r\nHost: "+target[0]+"\r\nConnection: close\r\n\r\n"));
     String line=new BufferedReader(new InputStreamReader(tls.getInputStream())).readLine();
     if(line==null||!line.startsWith("HTTP/1.")||!line.contains(" "+target[2]+" "))throw new IOException("Сайт вернул "+line);
     return "HTTPS "+target[0]+" · "+target[2];
    }
   }catch(Exception e){last=e;}
  }
  throw last==null?new IOException("Нет ответа из интернета"):last;
 }
 /** Exercise the synthetic DNS endpoint used by Android, not only SOCKS hostname resolution. */
 static void checkPhoneDns()throws Exception {checkPhoneDns("example.com");}
 static void checkPhoneDns(String hostname)throws Exception {
  int id=new java.security.SecureRandom().nextInt(65536);
  ByteArrayOutputStream query=new ByteArrayOutputStream();
  query.write(new byte[]{(byte)(id>>8),(byte)id,1,0,0,1,0,0,0,0,0,0});
  for(String label:hostname.split("\\.")){byte[] bytes=Wire.bytes(label);query.write(bytes.length);query.write(bytes);}
  query.write(new byte[]{0,0,1,0,1});byte[] body=query.toByteArray();
  try(Socket pipe=new Socket()){
   pipe.connect(new InetSocketAddress("127.0.0.1",BridgeService.SOCKS),3000);pipe.setSoTimeout(14000);
   InputStream in=pipe.getInputStream();OutputStream out=pipe.getOutputStream();
   out.write(new byte[]{5,1,0});if(!Arrays.equals(Wire.read(in,2),new byte[]{5,0}))throw new IOException("DNS: канал ПК не отвечает");
   out.write(new byte[]{5,1,0,1,(byte)198,18,7,2,0,53});
   if(Wire.read(in,10)[1]!=0)throw new IOException("DNS телефона не отвечает через ПК");
   out.write(new byte[]{(byte)(body.length>>8),(byte)body.length});out.write(body);
   byte[] prefix=Wire.read(in,2);int length=((prefix[0]&255)<<8)|(prefix[1]&255);
   if(length<12)throw new IOException("Неполный ответ DNS телефона");
   byte[] response=Wire.read(in,length);
   if(response[0]!=body[0]||response[1]!=body[1]||(response[2]&128)==0||(response[3]&15)!=0||(response[6]==0&&response[7]==0))throw new IOException("DNS телефона не разрешил адрес сайта");
  }
 }
}
