from pathlib import Path
import argparse,os,subprocess,time,json,sys
from EmulatorUi import adb,ui,tap,until,open_app
parser=argparse.ArgumentParser();parser.add_argument('--work',type=Path,required=True,help='Folder containing USBBridge.exe and wgfixture.exe');parser.add_argument('--source',required=True);parser.add_argument('--index',required=True);args=parser.parse_args()
work=args.work.resolve();out=work/'v05-wireroute-tests';out.mkdir(exist_ok=True)
flags=subprocess.CREATE_NO_WINDOW;processes=[];handles=[];relay=None;report=[]
def start(command,name):
 log=open(out/(name+'.log'),'ab');handles.append(log)
 p=subprocess.Popen([str(x) for x in command],stdout=log,stderr=log,creationflags=flags);processes.append(p);return p
def logs():return adb('logcat','-d','-s','USBBridgeProbe:I','AndroidRuntime:E')
def passed(s):
 report.append('PASS '+s);print(report[-1],flush=True);(out/'results.txt').write_text('\n'.join(report)+'\n',encoding='utf-8')
def connect():
 global relay
 adb('logcat','-c');relay=start([work/'USBBridge.exe','--test-relay','127.0.0.1','auto','39473',args.source,'1.1.1.1',args.index],'relay')
 until(lambda:'PASS PC-only' in logs(),'PC HTTPS',40)
def client(expect=True):
 adb('logcat','-c');adb('shell','am','force-stop','local.usbbridge.probe');adb('shell','am','start','-n','local.usbbridge.probe/.Probe')
 until(lambda:('PASS independent Android app' if expect else 'FAIL ') in logs(),'independent client',35)
 if not expect:assert 'PASS independent Android app' not in logs()
 assert 'FATAL EXCEPTION' not in logs()
 return logs()
assert adb('shell','getprop','ro.kernel.qemu').strip()=='1'
for name in ['server.log','relay.log','instrumentation.log','results.txt','failure.txt']:(out/name).unlink(missing_ok=True)
oldwifi=adb('shell','settings','get','global','wifi_on').strip();olddata=adb('shell','settings','get','global','mobile_data').strip()
try:
 adb('shell','am','force-stop','local.usbbridge.vpnprobe')
 (out/'fixture-private.json').unlink(missing_ok=True)
 server=start([work/'wgfixture.exe',out/'fixture-private.json'],'server')
 until(lambda:(out/'fixture-private.json').exists(),'test server',10)
 keys=json.loads((out/'fixture-private.json').read_text())
 open_app()
 if any(n.get('text')=='Остановить' for n in ui().iter('node')):
  tap('Остановить',top=True);time.sleep(2)
 tap('Выбрать мой VPN');tap('WireRoute');tap('Подключить',top=True)
 adb('shell','svc','wifi','disable');adb('shell','svc','data','disable');adb('forward','tcp:39473','tcp:38473');connect()
 until(lambda:'testtun' in adb('shell','ls','/sys/class/net'),'explicit USB underlay')
 adb('logcat','-c')
 adbexe=Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/platform-tools/adb.exe'
 instrument=start([adbexe,'-s','emulator-5554','shell','am','instrument','-w','-e','class','app.wireroute.UsbBridgeIntegrationTest','-e','host',args.source,'-e','port',keys['port'],'-e','clientKey',keys['clientKey'],'-e','serverKey',keys['serverKey'],'app.wireroute.release263.usbtest.test/androidx.test.runner.AndroidJUnitRunner'],'instrumentation')
 until(lambda:'PASS WireRoute native handshake' in logs() or instrument.poll() is not None,'native handshake',40)
 assert 'PASS WireRoute native handshake' in logs(),(out/'instrumentation.log').read_text(errors='replace')+logs()
 (out/'native-proof.txt').write_text(logs(),encoding='utf-8')
 passed('Actual WireGuard native handshake travels through explicit PC source with emulator Wi-Fi/data disabled')
 (out/'networks-active.txt').write_text(adb('shell','dumpsys','connectivity'),encoding='utf-8')
 text=client();assert 'HTTP www.gstatic.com /generate_204' in (out/'server.log').read_text(errors='replace')
 (out/'native-proof.txt').write_text((out/'native-proof.txt').read_text(encoding='utf-8')+text,encoding='utf-8')
 passed('Independent Android app reaches HTTP 204 served inside real encrypted WireGuard tunnel')
 relay.terminate();relay.wait(5);adb('shell','svc','wifi','enable');time.sleep(3);client(False)
 passed('Native WireGuard does not fall back to available emulator Wi-Fi after cable transport disappears')
 connect()
 client()
 assert (out/'server.log').read_text().count('HTTP www.gstatic.com /generate_204')>=2
 assert 'isForeground=true' in adb('shell','dumpsys','activity','services','app.wireroute.release263.usbtest')
 passed('Actual WireGuard recovers automatically after PC transport reconnects; encrypted HTTP reaches the test server again')
 until(lambda:instrument.poll() is not None,'instrumentation cleanup',260)
 result=(out/'instrumentation.log').read_text(errors='replace');assert 'OK (1 test)' in result,result
 passed('WireRoute disconnect clears process binding and preserves original encrypted profile and app selection')
 open_app();tap('Остановить',top=True)
 until(lambda:'testtun' not in adb('shell','ls','/sys/class/net'),'Bridge cleanup')
 passed('Bridge and actual VPN stop without Android crash or remaining test interface')
 print('TOTAL',len(report),'passed',flush=True)
except Exception as e:
 (out/'failure.txt').write_text(str(e)+'\n'+logs(),encoding='utf-8');raise
finally:
 for p in processes:
  if p.poll() is None:p.terminate();p.wait(5)
 for f in handles:f.close()
 adb('shell','am','force-stop','app.wireroute.release263.usbtest',check=False)
 adb('shell','svc','wifi','enable' if oldwifi=='1' else 'disable',check=False)
 adb('shell','svc','data','enable' if olddata=='1' else 'disable',check=False)
