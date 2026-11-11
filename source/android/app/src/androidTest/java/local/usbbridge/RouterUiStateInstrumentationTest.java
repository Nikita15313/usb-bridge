package local.usbbridge;

import android.test.InstrumentationTestCase;
import java.util.concurrent.atomic.AtomicReference;

public final class RouterUiStateInstrumentationTest extends InstrumentationTestCase {
 private String active(){return "ACTIVE|Работает|testtun1|testtun1||PC|"+HotspotClients.json(true,true,false,HotspotClients.EMPTY,true);}
 public void testCurrentCleanupPublishesAfterServiceInstanceIsGone(){
  RouterUiState gate=new RouterUiState();long owner=gate.begin();AtomicReference<String> text=new AtomicReference<>(active());
  Runnable queued=()->gate.publish(owner,()->text.set("STOPPED|Раздача выключена"));
  queued.run();assertEquals("Wi-Fi выключен",HotspotDisplay.parse(text.get(),false).summary);
 }
 public void testOldQueuedCleanupCannotOverwriteNewService(){
  RouterUiState gate=new RouterUiState();long old=gate.begin();AtomicReference<String> text=new AtomicReference<>("new active");
  Runnable queued=()->gate.publish(old,()->text.set("old stopped"));gate.begin();queued.run();assertEquals("new active",text.get());
 }
 public void testOldCleanupCannotOverwriteNewerAlreadyStoppedService(){
  RouterUiState gate=new RouterUiState();long old=gate.begin(),newer=gate.begin();AtomicReference<String> text=new AtomicReference<>();
  assertTrue(gate.publish(newer,()->text.set("new cleanup error")));
  assertFalse(gate.publish(old,()->text.set("old success")));assertEquals("new cleanup error",text.get());
 }
 public void testStoppingPreservesObservedApButDoesNotClaimFinished(){
  HotspotDisplay view=HotspotDisplay.parse(RouterUiState.stopping(active()),false);
  assertTrue(view.enabled);assertTrue(view.owned);assertEquals("Завершаем отключение…",view.summary);
 }
 public void testRemainingOwnedApDisplaysActualCleanupFailure(){
  String result=RouterUiState.stage(active(),"READY","Выключите точку доступа в настройках");
  HotspotDisplay view=HotspotDisplay.parse(RouterUiState.completed(result),false);
  assertTrue(view.enabled);assertTrue(view.owned);assertEquals("Выключите точку доступа в настройках",view.summary);
 }
 public void testCleanupExceptionRetainsObservedApAndShowsErrorWithoutSharingRequest(){
  HotspotDisplay view=HotspotDisplay.parse(RouterUiState.failed(active()),false);
  assertTrue(view.enabled);assertTrue(view.owned);assertTrue(view.summary.startsWith("Не удалось подтвердить"));
  assertTrue(HotspotDisplay.parse(RouterUiState.failed("READY|Нет раздачи"),false).summary.startsWith("Не удалось подтвердить"));
 }
}
