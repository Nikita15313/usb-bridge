package local.usbbridge.vpnprobe;
import android.app.*;import android.content.*;import android.net.*;import android.os.*;import android.widget.*;
public class VpnProbe extends Activity {
 void launch(){Intent permission=VpnService.prepare(this);if(permission!=null)startActivityForResult(permission,1);else startForegroundService(new Intent(this,TestVpn.class).putExtra("host",getIntent().getStringExtra("host")));}
 public void onCreate(Bundle b){super.onCreate(b);Button button=new Button(this);button.setText("Start independent test VPN");setContentView(button);button.setOnClickListener(v->launch());if(getIntent().getBooleanExtra("start",false))launch();}
 protected void onActivityResult(int q,int r,Intent data){super.onActivityResult(q,r,data);if(r==RESULT_OK)launch();}
}
