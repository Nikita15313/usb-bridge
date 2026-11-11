package local.usbbridge;

import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Publisher-signed release metadata. No response field is trusted before signature verification. */
final class UpdateManifest {
 static final int MAX_ENVELOPE=256*1024,MAX_PAYLOAD=128*1024;
 static final long MAX_APK=64L*1024*1024;
 static final String REPOSITORY="/Nikita15313/usb-bridge/releases/download/";
 static final class Release {
  final String id,packageName,versionName,url,sha256,signerSha256; final long versionCode,size;
  Release(JSONObject item)throws Exception {
   id=item.getString("id");packageName=item.optString("packageName","");
   versionName=item.getString("versionName");url=item.getString("url");sha256=item.getString("sha256").toLowerCase(Locale.ROOT);
   signerSha256=item.optString("signerSha256","").toLowerCase(Locale.ROOT);versionCode=item.getLong("versionCode");size=item.getLong("size");
   if(!id.matches("[A-Za-z][A-Za-z0-9]{0,39}")||versionCode<=0||versionCode>Integer.MAX_VALUE||versionName.length()>50||versionName.isEmpty()
      ||!sha256.matches("[0-9a-f]{64}")||size<=0||size>256L*1024*1024||!assetUrl(url))throw new IOException("Invalid release metadata");
   if(id.equals("androidBridge")||id.equals("androidWireRoute")){
    String expected=id.equals("androidBridge")?"local.usbbridge":"app.wireroute.release263";
    if(!packageName.equals(expected)||size>MAX_APK||!signerSha256.matches("[0-9a-f]{64}"))throw new IOException("Invalid Android release identity");
   }
  }
  boolean newerThan(long installed){return versionCode>installed;}
 }
 final List<Release> releases;final long sequence;
 private UpdateManifest(List<Release> releases,long sequence){this.releases=Collections.unmodifiableList(releases);this.sequence=sequence;}
 static UpdateManifest verify(byte[] envelope,String publicKey)throws Exception {
  if(envelope.length==0||envelope.length>MAX_ENVELOPE)throw new IOException("Manifest size");
  JSONObject signed=new JSONObject(new String(envelope,StandardCharsets.UTF_8));
  byte[] payload=Base64.decode(signed.getString("payload"),Base64.DEFAULT),signature=Base64.decode(signed.getString("signature"),Base64.DEFAULT);
  if(payload.length==0||payload.length>MAX_PAYLOAD||signature.length<128||signature.length>1024)throw new IOException("Signed payload size");
  String encoded=publicKey.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
  PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.decode(encoded,Base64.DEFAULT)));
  if(((java.security.interfaces.RSAPublicKey)key).getModulus().bitLength()<2048)throw new GeneralSecurityException("Weak update key");
  Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update(payload);
  if(!verifier.verify(signature))throw new GeneralSecurityException("Invalid update signature");
  JSONObject root=new JSONObject(new String(payload,StandardCharsets.UTF_8));if(root.getInt("schema")!=1)throw new IOException("Unknown manifest schema");long sequence=root.getLong("sequence");if(sequence<=0)throw new IOException("Manifest sequence");
  JSONArray items=root.getJSONArray("releases");if(items.length()>12)throw new IOException("Too many releases");
  ArrayList<Release> releases=new ArrayList<>();HashSet<String> ids=new HashSet<>();
  for(int i=0;i<items.length();i++){Release release=new Release(items.getJSONObject(i));if(!ids.add(release.id))throw new IOException("Duplicate release");releases.add(release);}
  return new UpdateManifest(releases,sequence);
 }
 static boolean assetUrl(String value){try{URI uri=new URI(value);String path=uri.getRawPath();return "https".equals(uri.getScheme())&&"github.com".equals(uri.getHost())&&uri.getPort()==-1&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null&&path!=null&&path.startsWith(REPOSITORY)&&path.substring(REPOSITORY.length()).matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")&&!path.contains("..");}catch(Exception e){return false;}}
 static boolean redirectUrl(String value){try{URI uri=new URI(value);String host=uri.getHost();return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&("github.com".equals(host)||"release-assets.githubusercontent.com".equals(host)||"objects.githubusercontent.com".equals(host));}catch(Exception e){return false;}}
 static String hex(byte[] value){StringBuilder out=new StringBuilder(value.length*2);for(byte b:value)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
 static void verifyArchiveIdentity(Release release,String actualPackage,long actualVersion,String actualSigner,String installedSigner,long installedVersion)throws IOException {
  if(!release.packageName.equals(actualPackage)||actualVersion!=release.versionCode||!release.signerSha256.equals(actualSigner)||!actualSigner.equals(installedSigner)||actualVersion<=installedVersion)throw new IOException("APK identity, signature or version mismatch");
 }
}
