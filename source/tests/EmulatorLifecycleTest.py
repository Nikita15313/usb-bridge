from pathlib import Path
import subprocess, time, re, os, argparse, xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description='Run USB Bridge lifecycle tests on a prepared Android emulator. Install both test APKs, start Shizuku and grant the app permission first.')
parser.add_argument('--adb', type=Path, default=Path(os.environ.get('LOCALAPPDATA',''))/'Android/Sdk/platform-tools/adb.exe')
parser.add_argument('--relay', type=Path, required=True, help='Built USBBridge.exe')
parser.add_argument('--code', required=True, help='Pairing code from the emulator app')
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--port', default='39473')
parser.add_argument('--output', type=Path, default=Path('.build/lifecycle'))
args = parser.parse_args()
work = args.output.resolve()
work.mkdir(parents=True, exist_ok=True)
adb_path = args.adb.resolve()
report = []
relay = None
handles = []

def adb(*args, check=True):
    p = subprocess.run([str(adb_path), '-s', args.serial, *args], capture_output=True,
                       encoding='utf-8', errors='replace', timeout=25,
                       creationflags=subprocess.CREATE_NO_WINDOW)
    if check and p.returncode: raise RuntimeError(p.stdout + p.stderr)
    return p.stdout + p.stderr

def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/bridge-lifecycle.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/bridge-lifecycle.xml'))

def open_app():
    adb('shell', 'am', 'start', '-n', 'local.usbbridge/.MainActivity')
    time.sleep(.5)

def tap(label, top=False):
    for _ in range(6):
        for n in ui().iter('node'):
            if n.get('text') != label: continue
            x1,y1,x2,y2 = map(int, re.findall(r'\d+', n.get('bounds')))
            if y2-y1 >= 50 and 90 < (y1+y2)//2 < 2250:
                adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
                time.sleep(.5)
                return
        adb('shell','input','swipe','540','600' if top else '2050','540','2050' if top else '650','350')
    raise RuntimeError('Button not reachable: '+label)

def until(predicate, description, timeout=20):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if predicate(): return
        time.sleep(.4)
    raise RuntimeError('Timed out: '+description)

def vpn_on():
    return any(x.strip().startswith('NetworkAgentInfo{') and 'ni{VPN CONNECTED' in x
               for x in adb('shell','dumpsys','connectivity').splitlines())

def hotspot_on():
    text=adb('shell','dumpsys','tethering')
    return re.search(r'Current upstream interface\(s\): \[testtun\d+\]',text) is not None

def no_vpn():
    if vpn_on(): return False
    links=adb('shell','ls','/sys/class/net').split()
    service=adb('shell','dumpsys','activity','services','local.usbbridge.PhoneVpn')
    return 'tun0' not in links and 'isForeground=true' not in service

def connect():
    global relay
    out=open(work/'resume-relay.log','ab'); err=open(work/'resume-relay.err','ab'); handles.extend([out,err])
    relay=subprocess.Popen([str(args.relay.resolve()),'--test-relay','127.0.0.1',args.code,args.port],
                           stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW)
    time.sleep(1)
    if relay.poll() is not None: raise RuntimeError('Relay failed: '+(work/'resume-relay.err').read_text(errors='replace'))
    until(lambda: any('Интернет ПК проверен' in n.get('text','') for n in ui().iter('node')),
          'Phone/PC authenticated connection', 25)

def disconnect():
    global relay
    if relay is not None and relay.poll() is None:
        relay.terminate(); relay.wait(5)
    relay=None

def proof(name):
    adb('logcat','-c')
    adb('shell','am','force-stop','local.usbbridge.probe')
    adb('shell','am','start','-n','local.usbbridge.probe/.Probe')
    until(lambda:'PASS independent Android app' in adb('logcat','-d','-s','USBBridgeProbe:I','AndroidRuntime:E'),name,25)
    passed(name)
    open_app()

def passed(name):
    report.append('PASS '+name)
    print(report[-1],flush=True)
    (work/'resume-lifecycle-results.txt').write_text('\n'.join(report)+'\n',encoding='utf-8')

try:
    adb('forward', 'tcp:'+args.port, 'tcp:38473')
    open_app(); tap('Ждать компьютер',top=True); connect()
    tap('Включить интернет телефона')
    until(vpn_on,'initial VPN start')
    proof('fresh Android app has internet before manual stop')

    tap('Выключить интернет телефона')
    until(no_vpn,'manual VPN stop closes TUN and notification')
    passed('manual VPN stop removes VPN network, tun0 and foreground notification')
    tap('Включить интернет телефона')
    until(vpn_on,'manual VPN restart')
    proof('VPN restarts and independent app has internet again')

    tap('Включить раздачу')
    until(hotspot_on,'system hotspot test upstream',35)
    until(lambda:'PASS native test TUN' in adb('logcat','-d','-s','USBBridgeProbe:I','AndroidRuntime:E'),
          'test TUN native internet probe',25)
    passed('system Wi-Fi selects test TUN and native internet probe passes')
    disconnect()
    until(lambda:no_vpn() and not hotspot_on(),'disconnect cleanup of VPN and hotspot',25)
    passed('relay disconnect removes VPN network, tun0, VPN notification and hotspot upstream')

    open_app(); connect(); tap('Включить интернет телефона')
    until(vpn_on,'VPN after transport reconnect')
    proof('reconnecting PC and starting VPN restores real app traffic')
    tap('Включить раздачу')
    until(hotspot_on,'hotspot after reconnect',35)
    passed('hotspot restarts after transport reconnect')
    tap('Остановить соединение',top=True)
    until(lambda:no_vpn() and not hotspot_on(),'full stop cleanup',25)
    until(lambda:'isForeground=true' not in adb('shell','dumpsys','activity','services','local.usbbridge.BridgeService'),
          'main service foreground stopped')
    passed('full stop removes both foreground services, VPN and hotspot upstream')
    print('TOTAL '+str(len(report))+' passed',flush=True)
except Exception as e:
    report.append('FAIL '+str(e)); print(report[-1],flush=True)
    (work/'resume-lifecycle-results.txt').write_text('\n'.join(report)+'\n',encoding='utf-8')
    raise
finally:
    disconnect()
    for h in handles: h.close()
    adb('forward', '--remove', 'tcp:'+args.port, check=False)
