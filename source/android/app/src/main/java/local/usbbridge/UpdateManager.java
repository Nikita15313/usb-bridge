package local.usbbridge;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.Toast;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** One low-priority worker shared by visible UI and the existing USB foreground service. */
final class UpdateManager {
 interface Listener {void changed();}
 static final long CHECK_INTERVAL_MS=30_000,MAX_RETRY_MS=300_000;
 private static UpdateManager singleton;
 static synchronized UpdateManager get(Context context){if(singleton==null)singleton=new UpdateManager(context.getApplicationContext());return singleton;}
 private final Context context; private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"USBBridge-updates");t.setPriority(Thread.MIN_PRIORITY);return t;});
 private final File directory; private final android.content.SharedPreferences preferences;
 private WeakReference<Listener> listener=new WeakReference<>(null); private boolean serviceActive,uiActive,busy,manual;
 private long retryMs=CHECK_INTERVAL_MS; private String etag="";
 private UpdateManifest manifest; private UpdateManifest.Release offered; private File ready;
 private String message=""; private boolean downloading;
 private final Runnable tick=()->check(false);
 private UpdateManager(Context context){this.context=context;directory=new File(context.getCacheDir(),"updates");directory.mkdirs();preferences=context.getSharedPreferences("updates",Context.MODE_PRIVATE);}
 void uiActive(Listener value){boolean wasActive=active();listener=new WeakReference<>(value);uiActive=value!=null;if(uiActive){refreshOffer();publish();}if(!active())main.removeCallbacks(tick);else if(!wasActive)schedule(0);}
 void serviceActive(boolean active){boolean wasActive=active();serviceActive=active;if(!active())main.removeCallbacks(tick);else if(!wasActive)schedule(0);}
 private boolean active(){return uiActive||serviceActive;}
 private void schedule(long delay){main.removeCallbacks(tick);if(active())main.postDelayed(tick,delay);}
 private void publish(){Listener target=listener.get();if(target!=null)target.changed();}
 String message(){return message;}
 boolean hasOffer(){return offered!=null;}
 boolean busy(){return busy;}
 String actionLabel(){return offered==null?"Проверить обновления":(ready!=null?"Установить ":"Обновить ")+(offered.id.equals("androidWireRoute")?"WireRoute ":"USB Bridge ")+offered.versionName;}
 void check(boolean requested){
  if(busy){if(requested)Toast.makeText(context,"Проверка уже выполняется",Toast.LENGTH_SHORT).show();return;}
  main.removeCallbacks(tick);busy=true;manual=requested;if(requested){message="Проверяем обновления…";publish();}
  final String conditional=etag;
  worker.execute(()->{
   try{
    byte[] cached=null;File metadata=new File(context.getFilesDir(),"update-manifest.json");
    if(manifest==null&&metadata.isFile()){try{cached=readFile(metadata,UpdateManifest.MAX_ENVELOPE);final UpdateManifest saved=UpdateManifest.verify(cached,UpdateTrust.PUBLIC_KEY);if(saved.sequence<preferences.getLong("sequence",0))throw new IOException("Older cached manifest");main.post(()->{if(manifest==null){manifest=saved;refreshOffer();publish();}});}catch(Exception ignored){cached=null;}}
    HttpResult response=fetchManifest(conditional);
    UpdateManifest parsed;
    if(response.status==304){if(manifest==null){if(cached==null)throw new IOException("304 without cached manifest");parsed=UpdateManifest.verify(cached,UpdateTrust.PUBLIC_KEY);}else parsed=manifest;}
    else{parsed=UpdateManifest.verify(response.data,UpdateTrust.PUBLIC_KEY);if(parsed.sequence<preferences.getLong("sequence",0))throw new IOException("Older update manifest");atomicWrite(metadata,response.data);}
    if(parsed.sequence<preferences.getLong("sequence",0))throw new IOException("Older cached manifest");final UpdateManifest result=parsed;
    main.post(()->{manifest=result;etag=response.etag==null?conditional:response.etag;preferences.edit().putLong("sequence",Math.max(result.sequence,preferences.getLong("sequence",0))).apply();refreshOffer();busy=false;retryMs=CHECK_INTERVAL_MS;message=offered==null?(manual?"Установлены актуальные версии":""):"Доступно обновление";publish();schedule(CHECK_INTERVAL_MS);});
   }catch(Exception error){android.util.Log.i("USBBridgeUpdate","Check deferred: "+error.getClass().getSimpleName()+": "+error.getMessage());main.post(()->{busy=false;message=manual?"Не удалось проверить. Повторим позже.":(offered==null?"":"Доступно обновление");publish();long wait=retryMs;retryMs=Math.min(MAX_RETRY_MS,retryMs*2);schedule(wait);});}
  });
 }
 private void refreshOffer(){
  UpdateManifest.Release previous=offered;offered=null;
  if(manifest!=null)for(String id:new String[]{"androidWireRoute","androidBridge"})for(UpdateManifest.Release item:manifest.releases)if(item.id.equals(id)&&item.newerThan(installedVersion(item.packageName))){offered=item;break;}
  // Install WireRoute first: installing Bridge may restart this process.
  if(manifest!=null)for(UpdateManifest.Release item:manifest.releases)if(item.id.equals("androidWireRoute")&&item.newerThan(installedVersion(item.packageName))){offered=item;break;}
  if(offered==null||previous==null||!offered.sha256.equals(previous.sha256))ready=null;
  if(offered!=null){File file=destination(offered);if(file.isFile()&&file.length()==offered.size)ready=file;}
 }
 void act(Activity activity){
  if(busy)return;refreshOffer();if(offered==null){check(true);return;}
  UpdateManifest.Release release=offered;WeakReference<Activity> owner=new WeakReference<>(activity);busy=true;downloading=true;message=ready==null?"Скачиваем обновление…":"Проверяем установочный файл…";publish();
  worker.execute(()->{try{
   File file=destination(release);if(!file.isFile()||file.length()!=release.size)download(release,file);
   verifyApk(release,file);main.post(()->{busy=false;downloading=false;refreshOffer();if(offered==null||!offered.sha256.equals(release.sha256)){publish();schedule(CHECK_INTERVAL_MS);return;}ready=file;message="Обновление готово к установке";publish();Activity current=owner.get();if(current!=null&&!current.isFinishing()&&!current.isDestroyed()&&uiActive)install(current,file);schedule(CHECK_INTERVAL_MS);});
  }catch(Exception error){File invalid=destination(release);if(invalid.isFile())invalid.delete();android.util.Log.w("USBBridgeUpdate","Update rejected",error);main.post(()->{busy=false;downloading=false;ready=null;message="Обновление не установлено. Попробуйте ещё раз.";publish();schedule(CHECK_INTERVAL_MS);});}});
 }
 private File destination(UpdateManifest.Release release){return new File(directory,release.packageName+"-"+release.versionCode+".apk");}
 private void install(Activity activity,File file){
  if(!context.getPackageManager().canRequestPackageInstalls()){
   Toast.makeText(activity,"Разрешите обновления из USB Bridge, затем вернитесь и нажмите «Установить».",Toast.LENGTH_LONG).show();
   try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.getPackageName())));}catch(Exception error){Toast.makeText(activity,"Откройте настройки установки приложений для USB Bridge",Toast.LENGTH_LONG).show();}return;
  }
  try{Uri uri=new Uri.Builder().scheme("content").authority(context.getPackageName()+".updates").appendPath(file.getName()).build();
   Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
   intent.setClipData(ClipData.newRawUri("USB Bridge update",uri));activity.startActivity(intent);
  }catch(Exception error){Toast.makeText(activity,"Не удалось открыть установку обновления",Toast.LENGTH_LONG).show();}
 }
 private long installedVersion(String packageName){try{return context.getPackageManager().getPackageInfo(packageName,0).getLongVersionCode();}catch(PackageManager.NameNotFoundException missing){return 0;}}
 private static String signer(PackageInfo info)throws IOException {if(info==null||info.signingInfo==null)throw new IOException("APK has no signing identity");android.content.pm.Signature[] signatures=info.signingInfo.getApkContentsSigners();if(signatures==null||signatures.length!=1)throw new IOException("Unexpected APK signers");try{return UpdateManifest.hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));}catch(GeneralSecurityException error){throw new IOException(error);}}
 private void verifyApk(UpdateManifest.Release release,File file)throws Exception {
  if(file.length()!=release.size||!release.sha256.equals(hash(file)))throw new IOException("APK content mismatch");
  PackageManager pm=context.getPackageManager();PackageInfo archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);if(archive==null)throw new IOException("Invalid APK");
  PackageInfo installed=null;try{installed=pm.getPackageInfo(release.packageName,PackageManager.GET_SIGNING_CERTIFICATES);}catch(PackageManager.NameNotFoundException missing){if(!release.id.equals("androidWireRoute"))throw missing;}
  UpdateManifest.verifyArchiveIdentity(release,archive.packageName,archive.getLongVersionCode(),signer(archive),installed==null?release.signerSha256:signer(installed),installed==null?0:installed.getLongVersionCode());
 }
 private void download(UpdateManifest.Release release,File destination)throws Exception {
  if(!UpdateManifest.assetUrl(release.url))throw new IOException("Unexpected asset URL");File temp=new File(directory,destination.getName()+".part");
  try{HttpsURLConnection connection=openAsset(release.url);try{
   long declared=connection.getContentLengthLong();if(declared!=-1&&declared!=release.size)throw new IOException("Asset size mismatch");
   long deadline=SystemClock.elapsedRealtime()+180_000,total=0;byte[] buffer=new byte[32*1024];
   try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(temp)){
    for(int count;(count=in.read(buffer))!=-1;){if(SystemClock.elapsedRealtime()>deadline||total+count>release.size)throw new IOException("Download limit");out.write(buffer,0,count);total+=count;}out.getFD().sync();
   }
   if(total!=release.size||!release.sha256.equals(hash(temp)))throw new IOException("Download integrity mismatch");
   if(destination.exists()&&!destination.delete())throw new IOException("Cannot replace cached update");if(!temp.renameTo(destination))throw new IOException("Cannot commit update");
  }finally{connection.disconnect();}}finally{if(temp.exists())temp.delete();}
 }
 private static HttpsURLConnection openAsset(String url)throws Exception {
  for(int count=0;count<6;count++){
   if(!UpdateManifest.redirectUrl(url))throw new IOException("Unexpected download host");HttpsURLConnection c=connection(url);int status=c.getResponseCode();
   if(status==200)return c;if(status==301||status==302||status==303||status==307||status==308){String target=c.getHeaderField("Location");c.disconnect();if(target==null)throw new IOException("Missing redirect");url=new URL(new URL(url),target).toString();continue;}c.disconnect();throw new IOException("Download HTTP "+status);
  }throw new IOException("Too many redirects");
 }
 private static HttpsURLConnection connection(String url)throws Exception {return connection(url,BridgeService.connected,BridgeService.SOCKS);}
 static HttpsURLConnection connection(String url,boolean usb,int socksPort)throws Exception {HttpsURLConnection c=(HttpsURLConnection)(usb?new URL(url).openConnection(new Proxy(Proxy.Type.SOCKS,new InetSocketAddress("127.0.0.1",socksPort))):new URL(url).openConnection());c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestProperty("User-Agent","USBBridge/"+BuildConfig.VERSION_NAME);c.setRequestProperty("Accept-Encoding","identity");return c;}
 private static final class HttpResult {int status;byte[] data;String etag;}
 private static HttpResult fetchManifest(String etag)throws Exception {
  HttpsURLConnection c=connection(UpdateTrust.MANIFEST_URL+"?poll="+(System.currentTimeMillis()/CHECK_INTERVAL_MS));try{c.setRequestProperty("Cache-Control","no-cache");if(!etag.isEmpty())c.setRequestProperty("If-None-Match",etag);HttpResult r=new HttpResult();r.status=c.getResponseCode();r.etag=c.getHeaderField("ETag");if(r.status==304)return r;if(r.status!=200)throw new IOException("Manifest HTTP "+r.status);if(c.getContentLengthLong()>UpdateManifest.MAX_ENVELOPE)throw new IOException("Manifest too large");try(InputStream in=c.getInputStream()){r.data=read(in,UpdateManifest.MAX_ENVELOPE);}return r;}finally{c.disconnect();}
 }
 private static byte[] readFile(File file,int limit)throws Exception {try(InputStream in=new FileInputStream(file)){return read(in,limit);}}
 private static byte[] read(InputStream in,int limit)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];long deadline=SystemClock.elapsedRealtime()+15000;for(int n;(n=in.read(buffer))!=-1;){if(out.size()+n>limit||SystemClock.elapsedRealtime()>deadline)throw new IOException("Response limit");out.write(buffer,0,n);}return out.toByteArray();}
 private static void atomicWrite(File file,byte[] data)throws Exception {File temp=new File(file.getPath()+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(data);out.getFD().sync();}if(!temp.renameTo(file)){temp.delete();throw new IOException("Cannot cache metadata");}}
 private static String hash(File file)throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[32768];for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}return UpdateManifest.hex(digest.digest());}
}
