package local.usbbridge;

import org.json.*;
import java.util.*;

/** Presents observed Android state, never turns a request into a success. */
final class HotspotDisplay {
 boolean enabled,owned;
 String summary="Wi-Fi выключен",clients="";
 static HotspotDisplay parse(String state,boolean wanted){
  HotspotDisplay view=new HotspotDisplay();
  String[] parts=state.split("\\|",7);
  boolean terminal="STOPPING".equals(parts[0])||"ERROR".equals(parts[0]);
  view.summary=(wanted||terminal)&&parts.length>1?parts[1]:"Wi-Fi выключен";
  try{
   if(parts.length<7)return view;
   JSONObject data=new JSONObject(parts[6]);view.enabled=data.optBoolean("enabled");view.owned=view.enabled&&data.optBoolean("owned");
   if(!view.enabled){if(!wanted&&!terminal)view.summary="Wi-Fi выключен";return view;}
   if(!terminal)view.summary=!view.owned?"Wi-Fi включён в настройках телефона":data.optBoolean("routing")?("vpn".equals(data.optString("source"))?"Wi-Fi включён · через VPN":"Wi-Fi включён · интернет компьютера"):data.optBoolean("waitingForVpn")?"Wi-Fi включён · ждём VPN":"Wi-Fi включён · настраиваем интернет";
   if(!data.optBoolean("clientsKnown")){view.clients="Телефон не передал список устройств";return view;}
   JSONArray list=data.optJSONArray("clients");
   if(list==null||list.length()==0){view.clients="Ждём подключения устройств";return view;}
   List<String> labels=new ArrayList<>();
   for(int i=0;i<list.length();i++){
    JSONObject client=list.optJSONObject(i);if(client==null)continue;
    String name=client.optString("name","Устройство").replace('\n',' ').replace('\r',' ');
    if(name.length()>60)name=name.substring(0,60);
    labels.add("• "+name);
   }
   view.clients=android.text.TextUtils.join("\n",labels);
  }catch(JSONException ignored){}
  return view;
 }
}
