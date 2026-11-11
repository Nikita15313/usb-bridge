package local.usbbridge;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import javax.net.ssl.HttpsURLConnection;

/**
 * Explicit opt-in release verification; never contacted by the regular fixture suite.
 * In a disposable ASCII build stage set defaultConfig.testInstrumentationRunner to
 * local.usbbridge.UpdatePublishedFeedProbe, then assembleDebugAndroidTest/install its APK.
 * AGP does not register an extra runner declared only in src/androidTest/AndroidManifest.xml.
 * Run: adb -s OWNED_EMULATOR shell am instrument -w local.usbbridge.test/local.usbbridge.UpdatePublishedFeedProbe
 * Optional: -e socksPort PORT exercises the actual updater's USB/SOCKS HTTPS connection.
 * Use an owned localhost SOCKS relay restricted to raw.githubusercontent.com:443, and remove
 * its adb reverse mapping/stop that relay afterwards. No certificate or signature checks are bypassed.
 * Restore the stage's normal testInstrumentationRunner afterwards; production APKs are unchanged.
 */
public final class UpdatePublishedFeedProbe extends Instrumentation {
 private long sequence;private int socksPort;
 @Override public void onCreate(Bundle arguments){super.onCreate(arguments);sequence=arguments==null?155:arguments.getLong("sequence",155);socksPort=arguments==null?0:Integer.parseInt(arguments.getString("socksPort","0"));start();}
 @Override public void onStart(){Bundle result=new Bundle();HttpsURLConnection request=null;try{
  request=UpdateManager.connection(UpdateTrust.MANIFEST_URL+"?probe="+System.currentTimeMillis(),socksPort>0,socksPort);request.setRequestProperty("Cache-Control","no-cache");
  if(request.getResponseCode()!=200)throw new IOException("Feed HTTP "+request.getResponseCode());
  ByteArrayOutputStream data=new ByteArrayOutputStream();try(InputStream in=request.getInputStream()){byte[] buffer=new byte[4096];for(int count;(count=in.read(buffer))!=-1;){if(data.size()+count>UpdateManifest.MAX_ENVELOPE)throw new IOException("Feed too large");data.write(buffer,0,count);}}
  UpdateManifest manifest=UpdateManifest.verify(data.toByteArray(),UpdateTrust.PUBLIC_KEY);if(manifest.sequence!=sequence)throw new IOException("Unexpected release sequence "+manifest.sequence);
  boolean bridge=false,wire=false,windows=false;StringBuilder versions=new StringBuilder();for(UpdateManifest.Release release:manifest.releases){if(release.id.equals("androidBridge"))bridge=true;if(release.id.equals("androidWireRoute"))wire=true;if(release.id.equals("windows"))windows=true;versions.append(release.id).append('=').append(release.versionCode).append(' ');}
  if(!bridge||!wire||!windows)throw new IOException("Incomplete release feed");result.putString("result","PASS published HTTPS feed; pinned RSA signature valid; sequence="+manifest.sequence+"; "+versions);finish(Activity.RESULT_OK,result);
 }catch(Exception error){result.putString("result","FAIL "+error.getClass().getSimpleName()+": "+error.getMessage());finish(Activity.RESULT_CANCELED,result);}finally{if(request!=null)request.disconnect();}}
}
