package local.usbbridge;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Small local diagnostic journal; never records payloads or VPN keys. */
final class BridgeLog {
 static synchronized void add(Context context,String event){
  android.util.Log.i("USBBridge",event);
  try{
   File file=new File(context.getFilesDir(),"bridge.log");
   if(file.length()>262144){File previous=new File(context.getFilesDir(),"bridge.previous.log");if(previous.exists())previous.delete();file.renameTo(previous);}
   try(FileOutputStream out=new FileOutputStream(file,true)){
    String line=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).format(new Date())+" "+event.replace('\n',' ')+'\n';
    out.write(line.getBytes(StandardCharsets.UTF_8));
   }
  }catch(Exception ignored){}
 }
 static synchronized String tail(Context context){
  ArrayDeque<String> lines=new ArrayDeque<>();
  try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(new File(context.getFilesDir(),"bridge.log")),StandardCharsets.UTF_8))){
   String line;while((line=in.readLine())!=null){if(lines.size()==80)lines.removeFirst();lines.addLast(line);}
  }catch(Exception ignored){}
  return android.text.TextUtils.join("\n",lines);
 }
}
