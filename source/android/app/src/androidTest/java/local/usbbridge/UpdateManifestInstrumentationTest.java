package local.usbbridge;

import android.test.InstrumentationTestCase;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

public final class UpdateManifestInstrumentationTest extends InstrumentationTestCase {
 private static KeyPair keys;
 private static synchronized KeyPair keys()throws Exception {if(keys==null){KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);keys=generator.generateKeyPair();}return keys;}
 private String publicKey()throws Exception {return Base64.encodeToString(keys().getPublic().getEncoded(),Base64.NO_WRAP);}
 private JSONObject release()throws Exception {return new JSONObject().put("id","androidBridge").put("packageName","local.usbbridge").put("versionCode",155).put("versionName","0.15.5").put("url","https://github.com/Nikita15313/usb-bridge/releases/download/v0.15.5/USBBridge-Android-0.15.5.apk").put("sha256",repeat('a',64)).put("signerSha256",repeat('b',64)).put("size",12345);}
 private static String repeat(char c,int count){char[] chars=new char[count];java.util.Arrays.fill(chars,c);return new String(chars);}
 private JSONObject payload(JSONObject... items)throws Exception {JSONArray releases=new JSONArray();for(JSONObject item:items)releases.put(item);return new JSONObject().put("schema",1).put("sequence",155).put("publishedAt","2026-11-11T00:00:00Z").put("releases",releases);}
 private byte[] signed(JSONObject json)throws Exception {byte[] body=json.toString().getBytes(StandardCharsets.UTF_8);Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(keys().getPrivate());signer.update(body);return new JSONObject().put("payload",Base64.encodeToString(body,Base64.NO_WRAP)).put("signature",Base64.encodeToString(signer.sign(),Base64.NO_WRAP)).toString().getBytes(StandardCharsets.UTF_8);}
 private void rejected(JSONObject json)throws Exception {try{UpdateManifest.verify(signed(json),publicKey());fail("Invalid metadata accepted");}catch(IOException expected){}}
 public void testSignedReleaseAndVersionOrdering()throws Exception {UpdateManifest manifest=UpdateManifest.verify(signed(payload(release())),publicKey());assertEquals(155,manifest.sequence);UpdateManifest.Release release=manifest.releases.get(0);assertTrue(release.newerThan(154));assertFalse(release.newerThan(155));assertFalse(release.newerThan(156));}
 public void testPayloadTamperingRejected()throws Exception {JSONObject envelope=new JSONObject(new String(signed(payload(release())),StandardCharsets.UTF_8));envelope.put("payload",Base64.encodeToString(payload(release().put("versionCode",999)).toString().getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP));try{UpdateManifest.verify(envelope.toString().getBytes(StandardCharsets.UTF_8),publicKey());fail("Unsigned modification accepted");}catch(GeneralSecurityException expected){}}
 public void testDifferentPublisherRejected()throws Exception {KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);String other=Base64.encodeToString(gen.generateKeyPair().getPublic().getEncoded(),Base64.NO_WRAP);try{UpdateManifest.verify(signed(payload(release())),other);fail("Wrong publisher accepted");}catch(GeneralSecurityException expected){}}
 public void testPinnedPublisherKeyIsRsa2048OrStronger()throws Exception {java.security.interfaces.RSAPublicKey key=(java.security.interfaces.RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.decode(UpdateTrust.PUBLIC_KEY,Base64.DEFAULT)));assertTrue(key.getModulus().bitLength()>=2048);}
 public void testDuplicateReleaseRejected()throws Exception {rejected(payload(release(),release()));}
 public void testPackageSubstitutionRejected()throws Exception {rejected(payload(release().put("packageName","other.application")));}
 public void testBadHashAndOversizedApkRejected()throws Exception {rejected(payload(release().put("sha256","abc")));rejected(payload(release().put("size",UpdateManifest.MAX_APK+1)));}
 public void testForeignHttpTraversalAndUserInfoUrlsRejected()throws Exception {for(String url:new String[]{"http://github.com/Nikita15313/usb-bridge/releases/download/v1/app.apk","https://evil.example/app.apk","https://github.com/Nikita15313/other/releases/download/v1/app.apk","https://github.com/Nikita15313/usb-bridge/releases/download/../app.apk","https://me@github.com/Nikita15313/usb-bridge/releases/download/v1/app.apk","https://github.com/Nikita15313/usb-bridge/releases/download/v1/app.apk?redirect=evil"})rejected(payload(release().put("url",url)));}
 public void testUnknownSchemaAndSequenceRejected()throws Exception {rejected(payload(release()).put("schema",2));rejected(payload(release()).put("sequence",0));}
 public void testEnvelopeSizeBounded()throws Exception {try{UpdateManifest.verify(new byte[UpdateManifest.MAX_ENVELOPE+1],publicKey());fail();}catch(IOException expected){}}
 public void testOnlyGithubTlsRedirectsAllowed(){assertTrue(UpdateManifest.redirectUrl("https://release-assets.githubusercontent.com/asset?temporary=1"));assertTrue(UpdateManifest.redirectUrl("https://objects.githubusercontent.com/asset"));assertFalse(UpdateManifest.redirectUrl("http://objects.githubusercontent.com/asset"));assertFalse(UpdateManifest.redirectUrl("https://github.com.evil.example/asset"));assertFalse(UpdateManifest.redirectUrl("https://github.com:444/asset"));}
 public void testInstalledSignerPackageAndVersionMustMatch()throws Exception {UpdateManifest.Release release=UpdateManifest.verify(signed(payload(release())),publicKey()).releases.get(0);String signer=repeat('b',64);UpdateManifest.verifyArchiveIdentity(release,"local.usbbridge",155,signer,signer,154);for(int mode=0;mode<5;mode++){try{UpdateManifest.verifyArchiveIdentity(release,mode==0?"wrong.package":"local.usbbridge",mode==1?154:155,mode==2?repeat('c',64):signer,mode==3?repeat('c',64):signer,mode==4?155:154);fail("APK substitution or downgrade accepted "+mode);}catch(IOException expected){}}}
 public void testUsbHttpsUsesSocksRemoteDnsWithoutPhysicalNetwork()throws Exception {
  ExecutorService worker=Executors.newSingleThreadExecutor();
  try(ServerSocket proxy=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){proxy.setSoTimeout(6000);
   Future<String> remote=worker.submit(()->{try(Socket peer=proxy.accept()){
    peer.setSoTimeout(6000);InputStream in=peer.getInputStream();OutputStream out=peer.getOutputStream();byte[] hello=Wire.read(in,2);assertEquals(5,hello[0]);Wire.read(in,hello[1]&255);out.write(new byte[]{5,0});
    byte[] connect=Wire.read(in,4);assertEquals(5,connect[0]);assertEquals(1,connect[1]);assertEquals("DNS must be resolved on the PC",3,connect[3]);String hostname=new String(Wire.read(in,in.read()),StandardCharsets.US_ASCII);byte[] port=Wire.read(in,2);assertEquals(443,((port[0]&255)<<8)|(port[1]&255));out.write(new byte[]{5,0,0,1,127,0,0,1,1,1});return hostname;
   }});
   HttpsURLConnection request=UpdateManager.connection("https://usb-bridge-update.invalid/test",true,proxy.getLocalPort());try{request.getResponseCode();fail("Fixture does not provide TLS");}catch(IOException expected){}finally{request.disconnect();}
   assertEquals("usb-bridge-update.invalid",remote.get(7,TimeUnit.SECONDS));
  }finally{worker.shutdownNow();}
 }
}
