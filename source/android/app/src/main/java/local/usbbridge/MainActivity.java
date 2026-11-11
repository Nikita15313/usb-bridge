package local.usbbridge;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
 final int ink=Color.rgb(22,43,39),green=Color.rgb(20,112,91),muted=Color.rgb(88,107,102),bg=Color.rgb(244,247,242),pale=Color.rgb(229,243,236),good=Color.rgb(22,124,91),warn=Color.rgb(171,86,19);
 LinearLayout root;TextView status,detail,proof,traffic,wifi,wifiClients,helper,vpnDetail,sharingHint,updateMessage;Button updateButton;
 final UpdateManager.Listener updateListener=()->refreshUpdate();
 final TrafficRate trafficRate=new TrafficRate();Button start,checkNow,share,setup,openVpn,shareDirect,shareVpn;Handler timer=new Handler(Looper.getMainLooper());boolean consentToStart,pendingShare,pendingPlain,helperPermissionInFlight,resumed,helperHintShown,compatibilityPermissionAsked;

 int dp(int value){return (int)(getResources().getDisplayMetrics().density*value+.5f);}
 TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setLineSpacing(0,1.08f);if(bold)view.setTypeface(null,Typeface.BOLD);view.setPadding(0,dp(4),0,dp(4));return view;}
 GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
 GradientDrawable outlined(int color,int radius,int stroke){GradientDrawable d=background(color,radius);d.setStroke(dp(1),stroke);return d;}
 LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(20),dp(17),dp(20),dp(18));c.setBackground(background(Color.WHITE,22));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);root.addView(c,p);return c;}
 TextView pill(LinearLayout parent,String value){TextView view=text(value,14,green,true);view.setGravity(Gravity.CENTER_VERTICAL);view.setPadding(dp(13),dp(9),dp(13),dp(9));view.setBackground(background(pale,12));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);parent.addView(view,p);return view;}
 Button button(LinearLayout parent,String value,boolean primary,Runnable action){Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setTextSize(16);b.setTypeface(null,primary?Typeface.BOLD:Typeface.NORMAL);b.setTextColor(primary?Color.WHITE:green);b.setGravity(Gravity.CENTER);b.setPadding(dp(12),0,dp(12),0);b.setMinHeight(0);b.setMinWidth(0);b.setStateListAnimator(null);b.setBackground(primary?background(green,15):outlined(Color.rgb(238,247,242),15,Color.rgb(216,232,224)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(56));p.topMargin=dp(10);parent.addView(b,p);b.setOnClickListener(v->action.run());return b;}

 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);HelperAccess.observe(this);
  pendingShare=saved!=null&&saved.getBoolean("pendingShare")&&BridgeService.instance!=null;
  pendingPlain=saved!=null&&saved.getBoolean("pendingPlain")&&BridgeService.instance!=null;
  consentToStart=saved!=null&&saved.getBoolean("consentToStart");
  getWindow().setDecorFitsSystemWindows(false);getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);getWindow().setStatusBarContrastEnforced(false);getWindow().setNavigationBarContrastEnforced(false);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setBackgroundColor(bg);
  root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);scroll.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(scroll);
  scroll.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());root.setPadding(dp(20),bars.top+dp(24),dp(20),bars.bottom+dp(24));return insets;});scroll.requestApplyInsets();

  root.addView(text("USB Bridge",32,ink,true));root.addView(text("Компьютер  →  телефон  →  Wi-Fi",16,muted,false));

  LinearLayout internet=card();internet.addView(text("Интернет с компьютера",14,muted,true));status=text("Подключите телефон кабелем",24,ink,true);internet.addView(status);proof=pill(internet,"○ Источник ещё не проверен");proof.setOnClickListener(v->sourceInfo());detail=text("1. Включите USB-модем; если он недоступен — MIDI.\n2. Откройте программу на компьютере.\n3. Нажмите «Подключить» на обоих устройствах.",15,muted,false);LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(-1,-2);detailParams.topMargin=dp(7);internet.addView(detail,detailParams);traffic=text("",17,green,true);internet.addView(traffic);
  start=button(internet,"Подключить",true,this::connect);checkNow=button(internet,"Проверить интернет",false,this::verifyNow);

  LinearLayout hotspot=card();hotspot.addView(text("Раздать Wi-Fi",21,ink,true));wifi=text("После подключения другие устройства увидят имя сети и введут пароль. Устанавливать на них APK не нужно.",15,muted,false);hotspot.addView(wifi);wifiClients=text("",14,muted,false);hotspot.addView(wifiClients);helper=pill(hotspot,"Системный доступ проверяется…");
  share=button(hotspot,"Включить точку Wi-Fi",true,this::requestShare);
  hotspot.addView(text("Интернет для подключённых устройств",14,muted,true));
  LinearLayout modes=new LinearLayout(this);modes.setOrientation(LinearLayout.HORIZONTAL);hotspot.addView(modes,new LinearLayout.LayoutParams(-1,-2));
  shareDirect=modeButton(modes,"Без VPN",()->selectSharingMode(SharePolicy.DIRECT),false);
  shareVpn=modeButton(modes,"Через VPN",()->selectSharingMode(SharePolicy.VPN),true);
  sharingHint=text("",13,muted,false);hotspot.addView(sharingHint);
  button(hotspot,"Изменить имя сети и пароль",false,this::wifiSettings);setup=button(hotspot,"Настроить системный доступ",false,this::prepareHelper);

  LinearLayout vpn=card();vpn.addView(text("VPN на телефоне",21,ink,true));vpnDetail=text("",14,muted,false);vpn.addView(vpnDetail);button(vpn,"Выбрать VPN",false,this::chooseVpn);openVpn=button(vpn,"Открыть WireRoute",false,this::launchVpn);

  button(root,"Как пользоваться",false,this::help);button(root,"Диагностика",false,this::diagnostics);
  updateMessage=text("",14,green,false);root.addView(updateMessage);updateButton=button(root,"Проверить обновления",false,()->UpdateManager.get(this).act(this));
  root.addView(text("Версия "+BuildConfig.VERSION_NAME,12,muted,false));
  Shizuku.addRequestPermissionResultListener(permissionResult);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1);
  traffic.setFontFeatureSettings("tnum");traffic.setMinLines(2);
 }

 Button modeButton(LinearLayout parent,String label,Runnable action,boolean gap){
  Button b=new Button(this);b.setAllCaps(false);b.setTextSize(15);b.setText(label);b.setMinWidth(0);b.setMinHeight(0);b.setPadding(dp(6),0,dp(6),0);b.setStateListAnimator(null);
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(50),1);p.topMargin=dp(6);if(gap)p.leftMargin=dp(8);parent.addView(b,p);b.setOnClickListener(v->action.run());return b;
 }
 void modeStyle(Button b,String label,boolean selected,boolean enabled){
  int key=(selected?1:0)+(enabled?2:0);if(b.getTag() instanceof Integer&&((Integer)b.getTag())==key)return;b.setTag(key);
  b.setText((selected?"✓ ":"")+label);b.setSelected(selected);b.setEnabled(enabled);b.setAlpha(enabled?1f:.45f);b.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);b.setTextColor(selected?Color.WHITE:green);b.setBackground(selected?background(green,14):outlined(pale,14,Color.rgb(207,225,217)));b.setContentDescription(label+(selected?", выбран":"")+(!enabled?", включите VPN на телефоне":""));
 }
 void selectSharingMode(String mode){
  if(SharePolicy.VPN.equals(mode)&&!BridgeService.encryptedReady())return;
  getSharedPreferences("bridge",0).edit().putString("sharingMode",mode).apply();BridgeService service=BridgeService.instance;if(service!=null)service.sharingModeChanged();refreshSharingModes();
 }
 void refreshSharingModes(){
  boolean ready=BridgeService.connected&&BridgeService.encryptedReady();boolean requested=BridgeService.connected&&SharePolicy.requiresVpn(BridgeService.sharingMode(this),BridgeService.wifiVpnRequired);
  modeStyle(shareDirect,"Без VPN",!requested,true);modeStyle(shareVpn,"Через VPN",requested,ready);
  value(sharingHint,requested&&!ready?"Ждём готовности VPN для раздачи.":ready?(requested?"Выбран VPN для всех подключённых устройств.":"Выбран интернет компьютера. VPN телефона работает отдельно."):"Чтобы раздавать через VPN, включите его в WireRoute.");
 }

 void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
 void value(TextView view,String text){if(!android.text.TextUtils.equals(view.getText(),text))view.setText(text);}
 void visible(View view,int state){if(view.getVisibility()!=state)view.setVisibility(state);}
 void pillStyle(TextView view,int key,int color,int fill){Object old=view.getTag();if(old instanceof Integer&&((Integer)old)==key)return;view.setTag(key);view.setTextColor(color);view.setBackground(background(fill,12));}
 void connect(){
  if(BridgeService.instance!=null){pendingShare=false;pendingPlain=false;stopService(new Intent(this,BridgeService.class));return;}
  if(supportsUsbRelay(BridgeService.selectedVpn(this))){pendingPlain=true;startBridge();return;}
  if(BridgeService.externalVpn(this)){new AlertDialog.Builder(this).setTitle("На телефоне уже включён VPN").setMessage("Сначала подключим интернет компьютера. Android временно остановит текущий VPN. После подключения откройте обновлённый WireRoute и включите VPN снова.").setPositiveButton("Подключить интернет ПК",(d,w)->requestInternet(true)).setNegativeButton("Отмена",null).show();return;}
  requestInternet(true);
 }
 void requestInternet(boolean startAfter){consentToStart=startAfter;Intent permission=VpnService.prepare(this);if(permission!=null){new AlertDialog.Builder(this).setTitle("Разрешить канал USB Bridge").setMessage("Android покажет значок VPN, потому что через этот системный канал телефон получает интернет компьютера. Это не подписка и не отдельный VPN-сервер.").setPositiveButton("Разрешить",(d,w)->startActivityForResult(permission,7)).setNegativeButton("Отмена",null).show();}else if(startAfter)startBridge();else if(BridgeService.instance!=null)BridgeService.instance.activate();}
 void startBridge(){startForegroundService(new Intent(this,BridgeService.class));}
 void verifyNow(){BridgeService service=BridgeService.instance;if(service==null||!BridgeService.connected){toast("Сначала подключите компьютер");return;}service.verifyNow();toast("Проверяем интернет компьютера");}
 void sourceInfo(){
  String message=BridgeService.verified?"Проверочный сайт ответил через компьютер. Это подтверждает канал, но не работу всех приложений.\n\nПодключение: "+BridgeService.channelLabel()+"\nИсточник на ПК: "+(BridgeService.source.isEmpty()?"текущее подключение Windows":BridgeService.source)+"\n\n"+(BridgeService.appNetworkReady()?"Системный канал Android подготовлен.":"Системный канал Android пока не подготовлен. Некоторые приложения могут считать интернет отключённым.")+"\n\nЕсли на самом компьютере включён VPN, телефон тоже получает этот интернет. Кнопка VPN на телефоне управляет только VPN телефона.":"Дождитесь подключения и успешной проверки интернета компьютера.";
  new AlertDialog.Builder(this).setTitle("Откуда интернет").setMessage(message).setPositiveButton("Проверить",(d,w)->verifyNow()).setNeutralButton("Диагностика",(d,w)->diagnostics()).setNegativeButton("Закрыть",null).show();
 }
 void diagnostics(){new AlertDialog.Builder(this).setTitle("Диагностика").setMessage(report()).setPositiveButton("Скопировать",(d,w)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("USB Bridge",report()));toast("Диагностика скопирована");}).setNegativeButton("Закрыть",null).show();}
 void wifiSettings(){try{startActivity(new Intent().setClassName("com.android.settings","com.android.settings.TetherSettings"));}catch(Exception e){startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));}}
 void developerSettings(){try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(Exception e){startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));}}
 void openShizuku(){Intent open=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");if(open!=null)startActivity(open);else toast("Установите Shizuku-13.6.0.apk из папки Android");}
 void requestShare(){
  BridgeService service=BridgeService.instance;
  if(pendingShare||BridgeService.shareWanted||HotspotDisplay.parse(BridgeService.routerState,BridgeService.shareWanted).owned){pendingShare=false;if(service!=null)service.share(false);return;}
  if(service==null||!BridgeService.verified){toast("Сначала подключите интернет компьютера");return;}
  pendingShare=true;helperHintShown=false;service.share(true);
  int helperState=BridgeService.helperState(this);
  if(helperState==BridgeService.HELPER_READY)continuePendingShare();else if(helperState!=BridgeService.HELPER_UNAVAILABLE&&helperState!=BridgeService.HELPER_ERROR)prepareHelper();
 }
 void cancelPendingShare(){if(!pendingShare)return;pendingShare=false;BridgeService service=BridgeService.instance;if(service!=null)service.share(false);}
 void continuePendingShare(){
  if(!pendingShare)return;
  BridgeService service=BridgeService.instance;
  if(service==null||!BridgeService.connected||!BridgeService.shareWanted){pendingShare=false;return;}
  if(!BridgeService.verified)return;
  int state=BridgeService.helperState(this);
  if(state==BridgeService.HELPER_READY){pendingShare=false;BridgeService.helperIssue="";service.share(true);}
  else if(resumed&&state==BridgeService.HELPER_PERMISSION&&!helperPermissionInFlight)requestHelperPermission();
  else if(resumed&&state==BridgeService.HELPER_LIMITED&&!helperHintShown){helperHintShown=true;prepareHelper();}
  else if(resumed&&state==BridgeService.HELPER_UNAVAILABLE&&!helperHintShown&&BridgeService.helperRequestAt!=0&&SystemClock.elapsedRealtime()-BridgeService.helperRequestAt>55000){helperHintShown=true;prepareHelper();}
 }
 void requestHelperPermission(){if(helperPermissionInFlight)return;helperPermissionInFlight=true;try{Shizuku.requestPermission(8);}catch(Exception e){helperPermissionInFlight=false;cancelPendingShare();toast("Не удалось запросить доступ. Проверьте, запущен ли Shizuku.");}}
 final Shizuku.OnRequestPermissionResultListener permissionResult=(request,grant)->{if(request!=8)return;helperPermissionInFlight=false;if(grant==PackageManager.PERMISSION_GRANTED){BridgeService.helperIssue="";toast(pendingShare?"Доступ разрешён. Включаем точку Wi-Fi.":"Системный доступ разрешён");continuePendingShare();if(BridgeService.instance!=null)BridgeService.instance.activate();}else{cancelPendingShare();toast("Точка Wi-Fi не включена: доступ не разрешён");}};

 void prepareHelper(){
  int state=BridgeService.helperState(this);
  if(state==BridgeService.HELPER_PERMISSION){requestHelperPermission();return;}
  if(state==BridgeService.HELPER_READY){BridgeService.helperIssue="";continuePendingShare();if(BridgeService.instance!=null)BridgeService.instance.activate();return;}
  AlertDialog.Builder dialog=new AlertDialog.Builder(this);
  if(state==BridgeService.HELPER_MISSING){dialog.setTitle("Для точки Wi-Fi нужен системный доступ").setMessage("Установите Shizuku из папки Android в комплекте. Он нужен для раздачи и подготовки системной сети Android. Передача данных может идти и без него, но некоторые приложения, включая Telegram, могут считать сеть отключённой.\n\nДля первого запуска Shizuku потребуется рабочая USB-отладка на компьютере или временное подключение телефона к Wi-Fi.").setPositiveButton("Понятно",(d,w)->cancelPendingShare());}
  else if(state==BridgeService.HELPER_LIMITED){dialog.setTitle("Телефон ограничил доступ к раздаче").setMessage("Shizuku запущен, но нужные сетевые права не получены. Раздача пока недоступна.\n\nНе отключайте оптимизацию системы наугад: на некоторых прошивках OnePlus это нарушает настройки экрана и не решает эту проблему. В диагностике записано, какого разрешения не хватает.").setPositiveButton("Диагностика",(d,w)->diagnostics()).setNeutralButton("Открыть Shizuku",(d,w)->openShizuku()).setNegativeButton("Отмена",(d,w)->cancelPendingShare());}
  else if(state==BridgeService.HELPER_UNAVAILABLE){dialog.setTitle("Точка Wi-Fi ждёт системного доступа").setMessage((BridgeService.helperAutoMessage.isEmpty()?"":BridgeService.helperAutoMessage+"\n\n")+"USB-отладка включена и системный помощник запущен — это разные состояния. Сейчас USB Bridge не получает от помощника нужный доступ.\n\nПрограмма на ПК пытается запустить его через кабель. Если Windows не видит телефон для отладки, одна галочка на телефоне этого не исправляет. Другой способ запуска — беспроводная отладка при подключении телефона к сети Wi-Fi.\n\nБез этого доступа раздача недоступна, а часть приложений может не распознавать интернет USB. Работающий канал USB сохраняется. Если в Shizuku уже написано «запущен», вернитесь сюда: проверка повторяется автоматически.").setPositiveButton("Открыть Shizuku",(d,w)->openShizuku()).setNegativeButton("Отмена",(d,w)->cancelPendingShare());}
  else{String issue=BridgeService.helperIssue.isEmpty()?"Системный канал не запустился":BridgeService.helperIssue;dialog.setTitle("Раздача пока не готова").setMessage(issue+"\n\nРаздача не включена. Проверка интернета ПК не подтверждает работу каждого приложения телефона.").setPositiveButton("Закрыть",(d,w)->cancelPendingShare());}
  dialog.setOnCancelListener(d->cancelPendingShare()).show();
 }

 void chooseVpn(){
  List<String> names=new ArrayList<>(),packages=new ArrayList<>();names.add("Без VPN на телефоне");packages.add("");Set<String> seen=new HashSet<>();
  for(ResolveInfo info:getPackageManager().queryIntentServices(new Intent(VpnService.SERVICE_INTERFACE),0)){ServiceInfo service=info.serviceInfo;if(service==null||service.packageName.equals(getPackageName())||!"android.permission.BIND_VPN_SERVICE".equals(service.permission)||!seen.add(service.packageName))continue;try{ApplicationInfo app=getPackageManager().getApplicationInfo(service.packageName,PackageManager.GET_META_DATA);if(app.metaData==null||(app.metaData.getInt("local.usbbridge.UNDERLAY_API",0)<1&&app.metaData.getInt("local.usbbridge.UDP_RELAY_API",0)<1))continue;}catch(Exception ignored){continue;}names.add(service.applicationInfo.loadLabel(getPackageManager()).toString());packages.add(service.packageName);}
  for(String known:new String[]{"app.wireroute.release263","app.wireroute.release263.usbtest"})try{ApplicationInfo app=getPackageManager().getApplicationInfo(known,PackageManager.GET_META_DATA);if(!packages.contains(known)&&app.metaData!=null&&(app.metaData.getInt("local.usbbridge.UNDERLAY_API",0)>=1||app.metaData.getInt("local.usbbridge.UDP_RELAY_API",0)>=1)){names.add(app.loadLabel(getPackageManager()).toString());packages.add(known);}}catch(Exception ignored){}
  String current=BridgeService.selectedVpn(this);int checked=packages.indexOf(current);
  new AlertDialog.Builder(this).setTitle("VPN на телефоне").setSingleChoiceItems(names.toArray(new String[0]),Math.max(0,checked),(dialog,index)->{String selected=packages.get(index);saveVpnSelection(selected,names.get(index));dialog.dismiss();pendingPlain=BridgeService.instance!=null&&supportsUsbRelay(selected);if(selected.isEmpty()&&BridgeService.instance!=null)requestInternet(false);continuePendingPlain();}).setNegativeButton("Закрыть",null).show();
 }
 boolean supportsUsbRelay(String name){try{ApplicationInfo app=getPackageManager().getApplicationInfo(name,PackageManager.GET_META_DATA);return app.metaData!=null&&app.metaData.getInt("local.usbbridge.UDP_RELAY_API",0)>=1;}catch(Exception e){return false;}}
 void saveVpnSelection(String selected,String label){BridgeService service=BridgeService.instance;
  if(service!=null){synchronized(service){getSharedPreferences("bridge",0).edit().putString("vpnPackage",selected).putString("vpnLabel",label).apply();service.vpnChanged();}}
  else getSharedPreferences("bridge",0).edit().putString("vpnPackage",selected).putString("vpnLabel",label).apply();
 }
 void continuePendingPlain(){
  if(!pendingPlain||!resumed||!BridgeService.connected||!BridgeService.verified)return;
  if(BridgeService.phoneReady(this)){pendingPlain=false;return;}
  if("starting".equals(BridgeService.ownVpnMode))return;
  BridgeService service=BridgeService.instance;
  if(!"plain_permission".equals(BridgeService.phoneIssue)&&(service==null||service.plainRequestAt==0||SystemClock.elapsedRealtime()-service.plainRequestAt<6000))return;
  pendingPlain=false;launchVpn();
 }
 void launchVpn(){
  String selected=BridgeService.selectedVpn(this);
  if(selected.isEmpty()){chooseVpn();return;}
  if(!supportsUsbRelay(selected)){new AlertDialog.Builder(this).setTitle("Обновите WireRoute на телефоне").setMessage("Эта версия VPN использует старый способ подключения. Установите WireRoute APK из нового комплекта поверх прежней версии. После обновления VPN через USB будет включаться в самом WireRoute без переходов в Shizuku.").setPositiveButton("Понятно",null).show();return;}
  Intent intent=getPackageManager().getLaunchIntentForPackage(selected);
  if(intent!=null){pendingPlain=false;if(BridgeService.connected&&BridgeService.verified)intent.putExtra("local.usbbridge.START_PLAIN",true);startActivity(intent);}else toast("Установите WireRoute APK из комплекта");
 }
 String vpnState(){
  if(!supportsUsbRelay(BridgeService.selectedVpn(this)))return "Установите WireRoute из нового комплекта.";
  if("plain_permission".equals(BridgeService.phoneIssue))return "Откройте WireRoute и подтвердите разрешение Android.";
  if(!BridgeService.connected)return "Включайте и выключайте VPN в WireRoute.";
  if(!BridgeService.phoneReady(this))return "Подготавливаем интернет телефона…";
  return "vpn".equals(BridgeService.ownVpnMode)?"VPN включён. Выбор приложений действует на этом телефоне.":"VPN выключен. Используется интернет компьютера.";
 }
 String report(){return "USB Bridge "+BuildConfig.VERSION_NAME+"\n"+Build.MANUFACTURER+" "+Build.MODEL+" / "+Build.DISPLAY+"\n"+BridgeService.headline(this)+"\nКанал: "+BridgeService.linkName+"\nИсточник ПК: "+BridgeService.source+"\nДоказательство: "+BridgeService.check+"\nСистемный доступ: "+BridgeService.helperDetails(this)+"\nМаршрут: "+BridgeService.routerState+"\nVPN телефона: "+BridgeService.selectedVpn(this)+" "+BridgeService.vpnMessage+"\n"+BridgeService.addresses()+"\n"+BridgeService.routingDiagnostics(this)+"\n\nЖурнал:\n"+BridgeLog.tail(this);}
 void help(){new AlertDialog.Builder(this).setTitle("Как пользоваться").setMessage("1. Соедините телефон и компьютер кабелем.\n2. Включите «USB-модем». Если Windows его не видит — выберите MIDI. Программа сама определит доступный способ.\n3. Нажмите «Подключить» в обоих приложениях.\n4. Дождитесь надписи «Интернет идёт через компьютер».\n\nVPN: выберите обновлённый WireRoute в этом приложении, затем включайте и выключайте профиль в WireRoute. Системное разрешение VPN подтверждается один раз. Настроенный системный доступ позволяет приложениям, включая Telegram, правильно распознать интернет по кабелю. Программа использует его автоматически.\n\nРаздача: нажмите «Включить Wi-Fi» здесь. При запущенном Shizuku приложение само подготовит раздачу. Если связь с Shizuku потеряна, откройте его и проверьте состояние. Для запуска действительно остановленного Shizuku нужен рабочий ADB на ПК или временная сеть Wi-Fi. Без системного доступа обычную раздачу интернета ПК включить нельзя.").setPositiveButton("Готово",null).setNeutralButton("Скопировать диагностику",(d,w)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("USB Bridge",report()));toast("Диагностика скопирована");}).show();}

 String helperText(int state){if(!BridgeService.helperIssue.isEmpty())return "! "+BridgeService.helperIssue;if(state==BridgeService.HELPER_READY)return BridgeService.routerState.startsWith("ACTIVE|")?"✓ Wi-Fi включён":"✓ Раздача доступна";if(state==BridgeService.HELPER_PERMISSION)return "Осталось разрешить доступ USB Bridge";if(state==BridgeService.HELPER_LIMITED)return "! Нужна настройка телефона";if(state==BridgeService.HELPER_UNAVAILABLE)return "Нет связи с системным доступом";if(state==BridgeService.HELPER_ERROR)return "Повторяем проверку системного доступа";return "Нужно установить помощник раздачи";}
 final Runnable refresh=new Runnable(){public void run(){
  continuePendingShare();continuePendingPlain();if(resumed&&!compatibilityPermissionAsked&&BridgeService.connected&&BridgeService.relayVpn(MainActivity.this)&&BridgeService.helperState(MainActivity.this)==BridgeService.HELPER_PERMISSION){compatibilityPermissionAsked=true;requestHelperPermission();}
  value(status,BridgeService.headline(MainActivity.this));value(start,BridgeService.instance==null?"Подключить":"Остановить");
  if(BridgeService.instance==null){value(proof,"○ Источник ещё не проверен");pillStyle(proof,0,muted,Color.rgb(238,242,238));value(detail,"1. Включите USB-модем; если он недоступен — MIDI.\n2. Откройте программу на компьютере.\n3. Нажмите «Подключить» на обоих устройствах.");}
  else if(!BridgeService.connected){value(proof,"○ Ждём компьютер по кабелю");pillStyle(proof,0,muted,Color.rgb(238,242,238));value(detail,"На компьютере нажмите «Подключить». USB-модем используется первым; MIDI — если он недоступен.");}
  else if(BridgeService.verified){boolean fresh=BridgeService.probeFailures==0;value(proof,fresh?"✓ Проверочный сайт ответил через ПК":"● Повторяем проверку интернета");pillStyle(proof,fresh?1:4,fresh?good:green,fresh?Color.rgb(225,244,235):pale);String readiness=BridgeService.appNetworkReady()?"":"\nСистемная сеть Android пока не готова. Часть приложений может не видеть интернет.";value(detail,"Подключение: "+BridgeService.channelLabel()+"\nИсточник: "+(BridgeService.source.isEmpty()?"текущее подключение компьютера":BridgeService.source)+readiness);}
  else{value(proof,BridgeService.checking?"● Проверяем источник…":"! Интернет ПК не подтверждён");pillStyle(proof,BridgeService.checking?2:3,BridgeService.checking?green:warn,BridgeService.checking?pale:Color.rgb(255,239,222));value(detail,BridgeService.checking?"Проверяем доступ к интернету…":"Откройте сайт на компьютере и нажмите «Проверить интернет».");}
  visible(checkNow,BridgeService.connected?View.VISIBLE:View.GONE);
  HotspotDisplay hotspot=HotspotDisplay.parse(BridgeService.routerState,BridgeService.shareWanted);
  value(wifi,pendingShare?(BridgeService.helperAutoMessage.isEmpty()?"Включаем раздачу…":BridgeService.helperAutoMessage):hotspot.summary);
  value(wifiClients,hotspot.clients);visible(wifiClients,hotspot.clients.isEmpty()?View.GONE:View.VISIBLE);
  value(share,pendingShare?"Отменить включение":BridgeService.shareWanted||hotspot.owned?"Выключить Wi-Fi":"Включить Wi-Fi");
  refreshSharingModes();
  int helperState=BridgeService.helperState(MainActivity.this);value(helper,helperText(helperState));boolean helperGood=helperState==BridgeService.HELPER_READY&&BridgeService.helperIssue.isEmpty();boolean helperBad=helperState==BridgeService.HELPER_LIMITED||helperState==BridgeService.HELPER_ERROR||!BridgeService.helperIssue.isEmpty();pillStyle(helper,helperGood?10:helperBad?11:12,helperGood?good:helperBad?warn:green,helperGood?Color.rgb(225,244,235):helperBad?Color.rgb(255,239,222):pale);visible(setup,helperGood?View.GONE:View.VISIBLE);value(setup,helperState==BridgeService.HELPER_ERROR?"Повторить проверку":helperBad?"Проверить права Android":helperState==BridgeService.HELPER_UNAVAILABLE?"Проверить системный доступ":"Настроить системный доступ");
  boolean own=!BridgeService.selectedVpn(MainActivity.this).isEmpty();String label=getSharedPreferences("bridge",0).getString("vpnLabel","WireRoute");if(own){value(vpnDetail,vpnState());value(openVpn,"Открыть "+label);}else value(vpnDetail,"VPN выключен.");visible(openVpn,own?View.VISIBLE:View.GONE);
  timer.postDelayed(this,1000);
 }};
 final Runnable refreshTraffic=new Runnable(){public void run(){
  refreshSharingModes();
  BridgeService live=BridgeService.instance;Wire.Mux link=live!=null&&BridgeService.connected?live.mux:null;
  trafficRate.sample(link,SystemClock.elapsedRealtime(),link==null?0:link.sent.get(),link==null?0:link.received.get());
  value(traffic,"↓ "+BridgeService.formatBytes(trafficRate.smoothDown)+"/с    ↑ "+BridgeService.formatBytes(trafficRate.smoothUp)+"/с\nПо кабелю: "+BridgeService.formatBytes(trafficRate.total));
  visible(traffic,BridgeService.instance!=null?View.VISIBLE:View.GONE);
  timer.postDelayed(this,200);
 }};
 void refreshUpdate(){if(updateMessage==null)return;UpdateManager updates=UpdateManager.get(this);value(updateMessage,updates.message());visible(updateMessage,updates.message().isEmpty()?View.GONE:View.VISIBLE);value(updateButton,updates.actionLabel());updateButton.setEnabled(!updates.busy());}
 @Override public void onResume(){super.onResume();resumed=true;UpdateManager.get(this).uiActive(updateListener);timer.removeCallbacks(refresh);timer.removeCallbacks(refreshTraffic);timer.post(refresh);timer.post(refreshTraffic);if(BridgeService.instance!=null&&BridgeService.shareWanted&&BridgeService.helperState(this)==BridgeService.HELPER_READY)BridgeService.instance.activate();}
 @Override public void onPause(){resumed=false;UpdateManager.get(this).uiActive(null);timer.removeCallbacks(refresh);timer.removeCallbacks(refreshTraffic);trafficRate.reset();super.onPause();}
 @Override public void onSaveInstanceState(Bundle out){out.putBoolean("pendingShare",pendingShare);out.putBoolean("pendingPlain",pendingPlain);out.putBoolean("consentToStart",consentToStart);super.onSaveInstanceState(out);}
 @Override public void onDestroy(){Shizuku.removeRequestPermissionResultListener(permissionResult);super.onDestroy();}
 @Override protected void onActivityResult(int request,int result,Intent intent){super.onActivityResult(request,result,intent);if(request==7&&result==RESULT_OK){if(consentToStart)startBridge();else if(BridgeService.instance!=null)BridgeService.instance.activate();}else if(request==7)toast("Android не разрешил канал USB Bridge");}
}
