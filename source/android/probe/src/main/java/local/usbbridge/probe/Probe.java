package local.usbbridge.probe;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;
import java.net.InetAddress;
import java.net.URL;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import javax.net.ssl.HttpsURLConnection;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

public final class Probe extends Activity {
  private String requestUrl(String url)throws Exception {
    long expectedBytes=getIntent().getLongExtra("expectedBytes",-1);
    String expectedSha=getIntent().getStringExtra("expectedSha256"),expectedRoute=getIntent().getStringExtra("expectedRoute");
    boolean verifyPayload=expectedBytes>0||expectedSha!=null;
    boolean readBody=getIntent().getBooleanExtra("readBody",false);
    if(readBody&&verifyPayload)throw new Exception("readBody cannot be combined with payload verification");
    if(verifyPayload&&(expectedBytes<=0||expectedBytes>32L*1024*1024||expectedSha==null||!expectedSha.matches("(?i)[0-9a-f]{64}")))throw new Exception("Payload check requires expectedBytes and expectedSha256");
    // Keep normal URLConnection routing and TLS certificate/hostname checks.
    // Cleartext endpoints are isolated fixtures, never a substitute for HTTPS QA.
    URL endpoint=new URL(url);
    if(readBody&&(!("http".equals(endpoint.getProtocol())||"https".equals(endpoint.getProtocol()))||endpoint.getUserInfo()!=null))throw new Exception("readBody requires HTTP(S) without URL credentials");
    java.net.HttpURLConnection request=(java.net.HttpURLConnection)endpoint.openConnection();
    long started=SystemClock.elapsedRealtime();
    try {
      request.setConnectTimeout(10000);request.setReadTimeout(10000);request.setUseCaches(false);request.setRequestProperty("Cache-Control","no-cache");request.setRequestProperty("Accept-Encoding","identity");
      if(readBody)request.setInstanceFollowRedirects(false);
      int code=request.getResponseCode();
      if(readBody){
        if(code!=200)throw new Exception("readBody expected HTTP 200, received "+code);
        if(request.getContentLengthLong()>ProbeBody.LIMIT)throw new Exception("Response body exceeds 16 KiB");
        byte[] body;try(InputStream input=request.getInputStream()){body=ProbeBody.read(input);}
        String safe=ProbeBody.escaped(ProbeBody.text(body));
        // Short chunks avoid Android logcat's per-entry size limit, including UTF-8.
        int parts=Math.max(1,(safe.length()+599)/600);
        for(int part=0;part<parts;part++)Log.i("USBBridgeEgressBody",getPackageName()+" part="+(part+1)+"/"+parts+" "+safe.substring(part*600,Math.min(safe.length(),(part+1)*600)));
        String tls=request instanceof HttpsURLConnection?((HttpsURLConnection)request).getCipherSuite():"cleartext";
        return "PASS readBody HTTP=200 bytes="+body.length+" elapsedMs="+(SystemClock.elapsedRealtime()-started)+" TLS="+tls+" body="+safe;
      }
      if(!verifyPayload){if(code!=204)throw new Exception("HTTP "+code);return "PASS independent Android app reached HTTP 204 through current Android route";}
      if(code!=200)throw new Exception("HTTP "+code);
      String route=request.getHeaderField("X-Fixture-Route");
      if(expectedRoute!=null&&!expectedRoute.equals(route))throw new Exception("Fixture route mismatch: "+route);
      long declared=request.getContentLengthLong();if(declared!=expectedBytes)throw new Exception("Content-Length mismatch: "+declared);
      MessageDigest digest=MessageDigest.getInstance("SHA-256");long received=0;
      try(InputStream input=request.getInputStream()){
        byte[] buffer=new byte[32768];int count;
        while((count=input.read(buffer))!=-1){received+=count;if(received>expectedBytes)throw new Exception("Payload exceeded expected length");digest.update(buffer,0,count);if(SystemClock.elapsedRealtime()-started>120000)throw new Exception("Payload deadline exceeded");}
      }
      StringBuilder actual=new StringBuilder();for(byte value:digest.digest())actual.append(String.format(Locale.US,"%02x",value&255));
      if(received!=expectedBytes||!actual.toString().equalsIgnoreCase(expectedSha))throw new Exception("Payload integrity mismatch: bytes="+received+" sha256="+actual);
      long elapsed=Math.max(1,SystemClock.elapsedRealtime()-started);double rate=received*1000.0/(1024*1024*elapsed);
      return String.format(Locale.US,"PASS payload bytes=%d sha256=%s elapsedMs=%d MiBps=%.3f route=%s",received,actual,elapsed,rate,route);
    } finally {request.disconnect();}
  }
  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    TextView text=new TextView(this);text.setText("Проверка сети…");setContentView(text);
    new Thread(()->{
      ConnectivityManager stateManager=getSystemService(ConnectivityManager.class);
      android.net.NetworkInfo legacy=stateManager.getActiveNetworkInfo();
      Network activeState=stateManager.getActiveNetwork();
      android.net.LinkProperties activeProperties=activeState==null?null:stateManager.getLinkProperties(activeState);
      String state="uid="+android.os.Process.myUid()+" legacyConnected="+(legacy!=null&&legacy.isConnected())+" legacy="+legacy+" modern="+activeState+" activeIface="+(activeProperties==null?null:activeProperties.getInterfaceName())+" capabilities="+(activeState==null?null:stateManager.getNetworkCapabilities(activeState));
      Log.i("USBBridgeNetProbe",state);
      if(getIntent().getBooleanExtra("networkOnly",false)){runOnUiThread(()->text.setText(state));return;}
      String url=getIntent().getStringExtra("url");
      if(url!=null){long started=SystemClock.elapsedRealtime();String message;try{message=requestUrl(url);}catch(Exception e){message="FAIL independent Android app: "+e;}Log.i("USBBridgeProbe",getPackageName()+" "+url+" "+message+" requestElapsedMs="+(SystemClock.elapsedRealtime()-started));String shown=message;runOnUiThread(()->text.setText(shown));return;}
      StringBuilder result=new StringBuilder();
      ConnectivityManager cm=getSystemService(ConnectivityManager.class);
      Network active=cm.getActiveNetwork();
      result.append("active=").append(active).append("\n");
      NetworkCapabilities caps=active==null?null:cm.getNetworkCapabilities(active);
      result.append("caps=").append(caps).append("\n");
      long handle=getIntent().getLongExtra("network",0);
      if(handle!=0){
        try {
          Network underlay=Network.fromNetworkHandle(handle);
          byte[] query=new byte[]{0x12,0x34,1,0,0,1,0,0,0,0,0,0,7,'e','x','a','m','p','l','e',3,'c','o','m',0,0,1,0,1};
          try(DatagramSocket socket=new DatagramSocket()){
            underlay.bindSocket(socket);socket.setSoTimeout(5000);
            socket.send(new DatagramPacket(query,query.length,InetAddress.getByName("198.18.7.2"),53));
            byte[] reply=new byte[1500];DatagramPacket packet=new DatagramPacket(reply,reply.length);socket.receive(packet);
            result.append("underlay UDP DNS=OK bytes=").append(packet.getLength()).append("\n");
          }
        }catch(Exception error){result.append("underlay UDP DNS_ERROR=").append(error).append("\n");}
      }
      for(String host:new String[]{"example.com","www.youtube.com","youtubei.googleapis.com","web.telegram.org"}) {
        try { result.append(host).append(" DNS=").append(InetAddress.getByName(host)).append("\n"); }
        catch(Exception error){result.append(host).append(" DNS_ERROR=").append(error).append("\n");}
      }
      for(String host:new String[]{"https://example.com","https://www.youtube.com","https://web.telegram.org"}) {
        try {
          HttpsURLConnection conn=(HttpsURLConnection)new URL(host).openConnection();
          conn.setConnectTimeout(7000);conn.setReadTimeout(7000);
          result.append(host).append(" HTTP=").append(conn.getResponseCode()).append("\n");conn.disconnect();
        } catch(Exception error){result.append(host).append(" HTTP_ERROR=").append(error).append("\n");}
      }
      for(String host:new String[]{"149.154.167.51","149.154.166.110"}){
        try(Socket socket=new Socket()){
          socket.connect(new InetSocketAddress(host,443),7000);
          result.append(host).append(":443 TCP=OK\n");
        }catch(Exception error){result.append(host).append(":443 TCP_ERROR=").append(error).append("\n");}
      }
      Log.i("USBBridgeNetProbe",result.toString());runOnUiThread(()->text.setText(result.toString()));
    }).start();
  }
}
