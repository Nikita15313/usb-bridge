package local.usbbridge.probe;
import android.app.Activity;import android.os.Bundle;import android.util.Log;import android.widget.TextView;
public class Probe extends Activity {
 public void onCreate(Bundle b){super.onCreate(b);TextView text=new TextView(this);text.setTextSize(20);text.setText("Проверка VPN…");setContentView(text);new Thread(()->{String result;try{java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL("http://www.gstatic.com/generate_204").openConnection();c.setConnectTimeout(15000);c.setReadTimeout(15000);int status=c.getResponseCode();c.disconnect();if(status!=204)throw new Exception("HTTP "+status);result="PASS independent Android app reached HTTP 204 through current Android route";}catch(Exception e){result="FAIL "+e;}Log.i("USBBridgeProbe",result);String r=result;runOnUiThread(()->text.setText(r));}).start();}
}
