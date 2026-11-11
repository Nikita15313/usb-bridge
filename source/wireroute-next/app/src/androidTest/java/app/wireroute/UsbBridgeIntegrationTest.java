package app.wireroute;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;

/** Isolated package + ephemeral local WireGuard server. Never accesses a real profile. */
public final class UsbBridgeIntegrationTest {
 @Test public void wireguardUsesUsbAndPreservesSavedSettings() throws Exception {
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  assertTrue(context.getPackageName().endsWith(".usbtest"));
  assertTrue(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"));
  if(Build.VERSION.SDK_INT<31)throw new IllegalStateException("This fixture requires the Android 12+ emulator");
  WireRouteApplication app=(WireRouteApplication)context.getApplicationContext();
  assertTrue(!app.getConfigStore().hasConfig()||"usb-emulator-fixture.conf".equals(app.getConfigStore().getDisplayName()));
  assertNull(VpnService.prepare(context));
  Bundle args=InstrumentationRegistry.getArguments();
  String config="[Interface]\nPrivateKey = "+args.getString("clientKey")+"\nAddress = 10.88.0.2/32\nDNS = 10.88.0.1\n[Peer]\nPublicKey = "+args.getString("serverKey")+"\nEndpoint = "+args.getString("host")+":"+args.getString("port")+"\nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 5\n";
  app.getConfigStore().save(config,"usb-emulator-fixture.conf");
  app.getRoutingPreferences().setMode(RoutingMode.ONLY_SELECTED);
  app.getRoutingPreferences().setSelectedPackages(Collections.singleton("local.usbbridge.probe"));
  android.app.Activity activity=InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  TunnelController controller=app.getTunnelController();
  try {
   assertTrue("Bridge relay must be available without Shizuku",UsbBridgeUnderlay.offeredRelaySession(context)>0);
   context.sendBroadcast(new Intent(UsbBootstrapReceiver.ACTION).setPackage(context.getPackageName())
           .putExtra("session",UsbBridgeUnderlay.offeredRelaySession(context)));
   long plainEnd=SystemClock.elapsedRealtime()+10000;
   while(!UsbPlainVpnService.running&&SystemClock.elapsedRealtime()<plainEnd)SystemClock.sleep(100);
   assertTrue("USB internet works under the WireRoute package before VPN",UsbPlainVpnService.running);
   plainEnd=SystemClock.elapsedRealtime()+10000;
   Network plainNetwork;
   while((plainNetwork=UsbBridgeUnderlay.ownVpnNetwork(context))==null&&SystemClock.elapsedRealtime()<plainEnd)SystemClock.sleep(100);
   assertNotNull("Plain USB TUN is published before switching encryption",plainNetwork);
   assertNull("Switching encryption must not ask Android consent again",VpnService.prepare(context));
   long initialPlainHold=Long.parseLong(args.getString("initialPlainHoldMillis","0"));
   if(initialPlainHold<0||initialPlainHold>300000)throw new IllegalArgumentException("initialPlainHoldMillis must be 0..300000");
   android.util.Log.i("USBBridgeProbe","READY initial plain USB before encryption");
   long initialPlainUntil=SystemClock.elapsedRealtime()+initialPlainHold;
   while(SystemClock.elapsedRealtime()<initialPlainUntil){
    assertTrue("Initial plain USB stays active without first enabling encryption",UsbPlainVpnService.running);
    assertEquals("Initial plain does not activate WireGuard",TunnelController.State.DOWN,controller.getSnapshot().state);
    assertNull("Initial plain retains Android VPN consent",VpnService.prepare(context));
    SystemClock.sleep(Math.max(1,Math.min(500,initialPlainUntil-SystemClock.elapsedRealtime())));
   }
   InstrumentationRegistry.getInstrumentation().runOnMainSync(controller::connect);
   long end=SystemClock.elapsedRealtime()+25000;
   while(controller.getSnapshot().state!=TunnelController.State.UP&&SystemClock.elapsedRealtime()<end){assertNotEquals(controller.getSnapshot().failure.toString(),TunnelController.State.ERROR,controller.getSnapshot().state);SystemClock.sleep(100);}
   assertEquals(TunnelController.State.UP,controller.getSnapshot().state);
   assertTrue("Selected probe uses encryption",controller.isPackageUsingVpn("local.usbbridge.probe"));
   assertFalse("Unselected probe retains plain USB",controller.isPackageUsingVpn("local.usbbridge.probe.plain"));
   assertTrue(UsbBridgeUnderlay.relayActive());
   assertNull("No process/socket binding to TestNetwork: packets use direct USB PFD",UsbBridgeUnderlay.current());
   ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
   assertNull("Optional status metadata must not bind the process",cm.getBoundNetworkForProcess());
   end=SystemClock.elapsedRealtime()+10000;
   Network own;
   while((own=UsbBridgeUnderlay.ownVpnNetwork(context))==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
   assertNotNull("Hotspot report identifies the actual owned encrypted VPN",own);
   assertNotEquals("Never report the old plain TUN as encrypted hotspot egress",plainNetwork.getNetworkHandle(),own.getNetworkHandle());
   assertEquals(android.os.Process.myUid(),cm.getNetworkCapabilities(own).getOwnerUid());
   if(Boolean.parseBoolean(args.getString("requireConnectivity","false"))){
    end=SystemClock.elapsedRealtime()+20000;
    boolean declared=false;
    while(SystemClock.elapsedRealtime()<end){
     Network metadata=UsbBridgeUnderlay.relayConnectivityNetwork(context);
     NetworkCapabilities caps=cm.getNetworkCapabilities(own);
     // The real TestNetwork remains available to the Bridge helper, but must
     // not propagate TRANSPORT_TEST (hidden SDK value 7) onto the VPN itself.
     if(metadata!=null&&caps!=null&&!caps.hasTransport(7)){declared=true;break;}
     SystemClock.sleep(250);
    }
    assertTrue("USB helper Network exists but VPN never inherits TEST transport",declared);
    assertNull("Status support never binds the process to the TestNetwork",cm.getBoundNetworkForProcess());
    android.util.Log.i("USBBridgeProbe","PASS VPN excludes TEST transport without process binding");
   }
   assertFalse("Plain TUN stops before encrypted TUN starts",UsbPlainVpnService.running);
   end=SystemClock.elapsedRealtime()+20000;
   while(controller.getTransferSnapshot().latestHandshakeEpochMillis==0&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(250);
   assertTrue("Native WireGuard handshake through USB relay",controller.getTransferSnapshot().latestHandshakeEpochMillis>0);
   assertEquals(config,app.getConfigStore().load());
   assertEquals(RoutingMode.ONLY_SELECTED,app.getRoutingPreferences().getMode());
   assertEquals(Collections.singleton("local.usbbridge.probe"),app.getRoutingPreferences().getSelectedPackages());
   android.util.Log.i("USBBridgeProbe","PASS WireRoute native handshake and saved settings preserved");
   assertDelayedPlainStartCannotStopEncryptedWorker(context,controller);
   // External runner now checks a different UID through this VPN, then cable
   // loss and reconnect. Keep the service alive without blocking its main loop.
   SystemClock.sleep(Long.parseLong(args.getString("holdMillis","80000")));
  } finally {
   InstrumentationRegistry.getInstrumentation().runOnMainSync(controller::disconnect);
   long end=SystemClock.elapsedRealtime()+10000;
   while(controller.getSnapshot().state!=TunnelController.State.DOWN&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
   assertEquals(TunnelController.State.DOWN,controller.getSnapshot().state);
   assertNull(UsbBridgeUnderlay.current());
   assertFalse("Native encrypted underlay is released before OFF is shown",UsbBridgeUnderlay.active());
   if(UsbBridgeUnderlay.offeredRelaySession(context)>0){
    end=SystemClock.elapsedRealtime()+10000;
    while(!UsbPlainVpnService.running&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
    assertTrue("Turning VPN off automatically restores plain USB",UsbPlainVpnService.running);
    assertNull("No repeated Android VPN consent",VpnService.prepare(context));
    android.util.Log.i("USBBridgeProbe","PASS plain-restored without repeated VPN consent");
    long plainHold=Long.parseLong(args.getString("finalPlainHoldMillis","0"));
    if(plainHold<0||plainHold>300000)throw new IllegalArgumentException("finalPlainHoldMillis must be 0..300000");
    long plainUntil=SystemClock.elapsedRealtime()+plainHold;
    while(SystemClock.elapsedRealtime()<plainUntil){
     assertTrue("Restored plain USB stays active",UsbPlainVpnService.running);
     assertNull("Restored plain retains VPN permission",VpnService.prepare(context));
     SystemClock.sleep(Math.max(1,Math.min(500,plainUntil-SystemClock.elapsedRealtime())));
    }
   }
   assertEquals(config,app.getConfigStore().load());
   assertEquals(RoutingMode.ONLY_SELECTED,app.getRoutingPreferences().getMode());
   InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
  }
 }

 /** A delayed Android service command used to call process-global HEV stop
  * while rejecting the plain launch, thereby killing the encrypted split path. */
 private static void assertDelayedPlainStartCannotStopEncryptedWorker(Context context,
         TunnelController controller) throws Exception {
  java.lang.reflect.Field currentField=StableVpnService.class.getDeclaredField("currentService");
  currentField.setAccessible(true);
  StableVpnService service=(StableVpnService)currentField.get(null);
  assertNotNull("Encrypted service exists before delayed bootstrap",service);
  java.lang.reflect.Field splitField=StableVpnService.class.getDeclaredField("usbSplit");
  splitField.setAccessible(true);
  UsbSplitTunnel split=(UsbSplitTunnel)splitField.get(service);
  assertNotNull("Native split worker exists before delayed bootstrap",split);
  assertTrue("Encrypted split owns HEV before rejected plain start",HevDatapath.owns(split));
  assertTrue("Native split worker is running before rejected plain start",HevDatapath.isRunning(split));
  Network before=UsbBridgeUnderlay.ownVpnNetwork(context);
  assertNotNull(before);
  long attempts=UsbPlainVpnService.startAttempts;
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
   Intent delayed=new Intent(context,UsbPlainVpnService.class);
   if(Build.VERSION.SDK_INT>=26)context.startForegroundService(delayed);
   else context.startService(delayed);
  });
  long end=SystemClock.elapsedRealtime()+5000;
  while(UsbPlainVpnService.startAttempts==attempts&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);
  assertTrue("Delayed plain onStartCommand actually executed",UsbPlainVpnService.startAttempts>attempts);
  InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  assertTrue("Delayed bootstrap cannot change desired encrypted mode",controller.isConnectionDesired());
  assertEquals("Rejected plain start leaves controller UP",TunnelController.State.UP,controller.getSnapshot().state);
  assertFalse("Rejected plain service never establishes a second TUN",UsbPlainVpnService.running);
  assertSame("Rejected plain start retains the existing split instance",split,splitField.get(service));
  assertTrue("Rejected plain cleanup cannot release another native owner",HevDatapath.owns(split));
  assertTrue("Rejected plain cleanup cannot stop encrypted native HEV",HevDatapath.isRunning(split));
  Network after=UsbBridgeUnderlay.ownVpnNetwork(context);
  assertNotNull(after);
  assertEquals("Rejected plain start does not revoke or recreate the active TUN",
          before.getNetworkHandle(),after.getNetworkHandle());
  java.lang.reflect.Field backendField=TunnelController.class.getDeclaredField("backend");
  backendField.setAccessible(true);
  com.wireguard.android.backend.GoBackend backend=(com.wireguard.android.backend.GoBackend)backendField.get(controller);
  synchronized(backend){
   assertTrue("Native WireGuard remains UP after rejected plain cleanup",
           backend.getRunningTunnelNames().contains("WireRoute"));
  }
  android.util.Log.i("USBBridgeProbe","PASS delayed plain bootstrap preserves native encrypted worker and TUN");
 }
}
