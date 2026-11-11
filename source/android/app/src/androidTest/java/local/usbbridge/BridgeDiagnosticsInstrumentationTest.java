package local.usbbridge;

import android.test.InstrumentationTestCase;

/** Reports must distinguish an alive relay from a prepared Android network. */
@SuppressWarnings("deprecation")
public final class BridgeDiagnosticsInstrumentationTest extends InstrumentationTestCase {
 public void testHelperResultRetainsUsefulFailureButRemovesAddressesAndSecrets(){
  String result=BridgeDiagnostics.helperResult("ADB unavailable; host=192.168.1.10:5555; url=https://example.invalid/private?token=abc; token=credential; path=C:\\Users\\someone private\\adb.exe; IPv6=2001:db8::1234");
  assertTrue(result.startsWith("ADB unavailable"));
  for(String secret:new String[]{"192.168.1.10","example.invalid","credential","someone","private","2001:db8"})assertFalse(secret,result.contains(secret));
 }
 public void testHelperResultCannotInjectLogLinesOrDirectionalText(){
  String result=BridgeDiagnostics.helperResult("Не найден ADB\r\n\t\u0000\u202eПодробности");
  assertEquals("Не найден ADB Подробности",result);
 }
 public void testHelperResultIsBoundedAndKeepsExplanation(){
  StringBuilder raw=new StringBuilder("Нужен драйвер. ");for(int i=0;i<4000;i++)raw.append("ещё ");
  String result=BridgeDiagnostics.helperResult(raw.toString());assertTrue(result.startsWith("Нужен драйвер."));assertEquals(BridgeDiagnostics.HELPER_RESULT_LIMIT,result.length());assertTrue(result.endsWith("…"));
 }
 public void testEmptyResultDoesNotMasqueradeAsSuccess(){assertEquals("Компьютер прислал пустой ответ",BridgeDiagnostics.helperResult(null));assertEquals("Компьютер прислал пустой ответ",BridgeDiagnostics.helperResult(" \n "));assertEquals("Компьютер прислал пустой ответ",BridgeDiagnostics.helperResult("\u202e"));}
 public void testRelayAndStaleDefaultFlagCannotImplyAndroidReadiness(){
  assertFalse(BridgeDiagnostics.systemChannelReady(false,0,true));
  assertFalse(BridgeDiagnostics.systemChannelReady(true,0,true));
  assertFalse(BridgeDiagnostics.systemChannelReady(false,123,true));
  assertFalse(BridgeDiagnostics.systemChannelReady(true,123,false));
  assertTrue(BridgeDiagnostics.systemChannelReady(true,123,true));
 }
 public void testNetworkReportIdentifiesUidScopeAndDoesNotDumpCapabilities(){
  String report=BridgeDiagnostics.networkObservation(getInstrumentation().getTargetContext());
  assertTrue(report.contains("AS OBSERVED BY BRIDGE UID"));assertTrue(report.contains("not Telegram"));assertTrue(report.contains("legacy currentNetworkInfo="));assertTrue(report.contains("modern activeHandle="));
  for(String forbidden:new String[]{"SSID:","LinkAddresses:","DnsAddresses:","ExtraInfo", "SubscriptionIds:"})assertFalse(report.contains(forbidden));
 }
 public void testSelectedVpnVersionComesFromPackageManager(){
  String report=BridgeDiagnostics.selectedVpn(getInstrumentation().getTargetContext(),"local.usbbridge");
  assertTrue(report.contains("version="+BuildConfig.VERSION_NAME));assertTrue(report.contains("code="+BuildConfig.VERSION_CODE));assertTrue(report.contains("relayApi="));
 }
}
