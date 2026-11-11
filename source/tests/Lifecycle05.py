"""Android emulator only: end-to-end routing and failure tests for 0.6.
Requires the app, probe and vpnprobe APKs installed, Shizuku started and
USB Bridge authorized. No physical USB driver or Wi-Fi radio is simulated.
"""
from pathlib import Path
import argparse, os, re, subprocess, sys, time, xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument('--adb',default=str(Path(os.environ.get('LOCALAPPDATA',''))/'Android/Sdk/platform-tools/adb.exe'))
parser.add_argument('--serial',default='emulator-5554')
parser.add_argument('--relay',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--source',required=True,help='Local PC IPv4 for both internet and test VPN proxy')
parser.add_argument('--index',required=True)
parser.add_argument('--dns',default='1.1.1.1')
args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
if hasattr(sys.stdout,'reconfigure'):sys.stdout.reconfigure(encoding='utf-8')
creation=getattr(subprocess,'CREATE_NO_WINDOW',0);report=[];handles=[];relay=None;proxy=None

def adb(*parts,check=True):
    p=subprocess.run([args.adb,'-s',args.serial,*parts],capture_output=True,encoding='utf-8',errors='replace',timeout=30,creationflags=creation)
    if check and p.returncode:raise RuntimeError(p.stdout+p.stderr)
    return p.stdout+p.stderr

def until(predicate,name,timeout=25):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if predicate():return
        time.sleep(.4)
    raise RuntimeError('Timed out: '+name)

def ui():
    adb('shell','rm','-f','/sdcard/bridge-test.xml',check=False)
    result=adb('shell','uiautomator','dump','--compressed','/sdcard/bridge-test.xml')
    if 'ERROR:' in result:raise RuntimeError(result.strip())
    return ET.fromstring(adb('exec-out','cat','/sdcard/bridge-test.xml'))

def tap(label,top=False,optional=False):
    for _ in range(6 if not optional else 1):
        for node in ui().iter('node'):
            if node.get('text')!=label:continue
            x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
            if y2-y1>=40 and 80<(y1+y2)//2<2300:
                adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.4);return True
        if not optional:adb('shell','input','swipe','540','500' if top else '2100','540','2100' if top else '600','300')
    if optional:return False
    raise RuntimeError('Button not found: '+label)

def open_app():adb('shell','am','start','-n','local.usbbridge/.MainActivity');time.sleep(.3)
def logs():return adb('logcat','-d','-s','USBBridgeProbe:I','AndroidRuntime:E')
def active(service):return 'isForeground=true' in adb('shell','dumpsys','activity','services',service)
def hotspot():return re.search(r'Current upstream interface\(s\): \[testtun\d+\]',adb('shell','dumpsys','tethering')) is not None
def prefs():return '\n'.join(x.strip() for x in adb('shell','dumpsys','connectivity').splitlines() if x.strip().startswith('OemNetworkPreferences{'))
def passed(name):
    report.append('PASS '+name);print(report[-1],flush=True)
    (args.output/'lifecycle-results.txt').write_text('\n'.join(report)+'\n',encoding='utf-8')

def launch_relay():
    global relay
    adb('logcat','-c')
    out=open(args.output/'relay.log','ab');err=open(args.output/'relay.err','ab');handles.extend([out,err])
    relay=subprocess.Popen([str(args.relay.resolve()),'--test-relay','127.0.0.1','auto','39473',args.source,args.dns,args.index],stdout=out,stderr=err,creationflags=creation)
    until(lambda:'PASS PC-only' in logs(),'PC-only HTTPS proof',40)

def disconnect():
    global relay
    if relay and relay.poll() is None:relay.terminate();relay.wait(8)
    relay=None

def client(expect=True):
    adb('logcat','-c');adb('shell','am','force-stop','local.usbbridge.probe');adb('shell','am','start','-n','local.usbbridge.probe/.Probe')
    until(lambda:('PASS independent Android app' if expect else 'FAIL ') in logs(),'client '+('online' if expect else 'blocked'),35)
    text=logs()
    if not expect:assert 'PASS independent Android app' not in text
    assert 'FATAL EXCEPTION' not in text
    return text

def connect_phone():
    open_app();tap('Подключить',top=True)
    if tap('Разрешить',optional=True):tap('OK')
    launch_relay()

assert adb('shell','getprop','ro.kernel.qemu').strip()=='1','These tests must never modify a physical phone'
old_wifi=adb('shell','settings','get','global','wifi_on').strip()
old_data=adb('shell','settings','get','global','mobile_data').strip()
try:
    adb('forward','tcp:39473','tcp:38473');adb('shell','am','force-stop','local.usbbridge.vpnprobe')
    open_app();tap('Остановить',top=True,optional=True);time.sleep(1)
    tap('Выбрать WireRoute');tap('Без VPN на телефоне')
    adb('shell','svc','wifi','disable');adb('shell','svc','data','disable')
    connect_phone();until(lambda:active('local.usbbridge.PhoneVpn'),'automatic phone tunnel')
    passed('Code-free pairing and automatic TLS-validated internet probe through explicitly bound PC adapter')
    (args.output/'phone-proof.txt').write_text(client(),encoding='utf-8')
    passed('Independent phone app reaches internet with ordinary Wi-Fi and mobile data disabled')
    open_app();tap('Включить точку Wi-Fi');until(hotspot,'system hotspot',40)
    until(lambda:'PASS native test TUN' in logs(),'native hotspot upstream probe')
    passed('System hotspot selects PC test TUN and native source probe passes')
    disconnect();until(lambda:not hotspot(),'hotspot stopped on cable loss')
    assert active('local.usbbridge.PhoneVpn')
    adb('shell','svc','wifi','enable');time.sleep(3);client(False)
    passed('Cable loss blocks phone traffic even with Wi-Fi available; hotspot stops')
    launch_relay();client();until(hotspot,'hotspot restored',40)
    passed('PC reconnection restores phone internet and requested hotspot without extra buttons')
    open_app();tap('Остановить',top=True);disconnect()
    until(lambda:not hotspot() and not active('local.usbbridge.PhoneVpn'),'explicit stop cleanup')
    assert 'tun0' not in adb('shell','ls','/sys/class/net').split()
    passed('Explicit Stop closes phone TUN and both foreground services')

    adb('shell','svc','wifi','disable');connect_phone();until(lambda:active('local.usbbridge.PhoneVpn'),'fresh phone tunnel')
    open_app();tap('Выбрать WireRoute');tap('Bridge VPN Test')
    until(lambda:'testtun' in adb('shell','ls','/sys/class/net'),'explicit PC underlay')
    until(lambda:not active('local.usbbridge.PhoneVpn'),'free VPN slot')
    passed('Selecting another VPN releases Android VPN slot and offers an explicit PC network to compatible client')
    out=open(args.output/'vpn-proxy.log','wb');err=open(args.output/'vpn-proxy.err','wb');handles.extend([out,err])
    proxy=subprocess.Popen([sys.executable,str(Path(__file__).with_name('vpn_proxy.py')),'--bind',args.source],stdout=out,stderr=err,creationflags=creation)
    time.sleep(.5);assert proxy.poll() is None,'VPN fixture failed to bind'
    adb('logcat','-c');adb('shell','am','force-stop','local.usbbridge.vpnprobe')
    adb('shell','am','start','-n','local.usbbridge.vpnprobe/.VpnProbe','--es','host',args.source,'--ez','start','true');tap('OK',optional=True)
    until(lambda:'PASS independent VPN started; underlay=' in logs(),'independent native VPN')
    proof=logs();assert 'Transports: TEST' in proof
    proof+=client();assert active('local.usbbridge.vpnprobe') and not active('local.usbbridge.PhoneVpn')
    assert 'TCP ' in (args.output/'vpn-proxy.log').read_text(encoding='utf-8')
    (args.output/'own-vpn-proof.txt').write_text('Emulator Wi-Fi client and mobile data are disabled.\n'+proof,encoding='utf-8')
    passed('Independent VPN and independent client app work over PC internet with Wi-Fi/data off')
    open_app();tap('Включить точку Wi-Fi');until(hotspot,'hotspot with external VPN',40)
    passed('Ordinary hotspot can run while the separate phone VPN occupies the VPN slot')
    disconnect();until(lambda:not hotspot(),'external mode cable loss');adb('shell','svc','wifi','enable');time.sleep(3);client(False)
    assert 'local.usbbridge.vpnprobe=-2' not in prefs()
    passed('External VPN remains restricted to PC after disconnect; no Wi-Fi fallback in tested VPN')
    launch_relay();client();until(hotspot,'hotspot after external reconnect',40)
    passed('External VPN and hotspot recover after PC reconnection')
    open_app();tap('Остановить',top=True);disconnect()
    until(lambda:not hotspot(),'external mode cleanup')
    assert 'local.usbbridge.vpnprobe=-2' not in prefs()
    passed('Explicit Stop closes explicit underlay and hotspot without changing OEM preferences')
    until(lambda:not re.search(r'\btesttun\d+\b',adb('shell','ls','/sys/class/net')),'test interface removal')
    passed('No test TUN remains after normal shutdown')
    print('TOTAL',len(report),'passed',flush=True)
except Exception as e:
    (args.output/'failure.txt').write_text(str(e)+'\n'+logs(),encoding='utf-8')
    raise
finally:
    disconnect()
    if proxy and proxy.poll() is None:proxy.terminate();proxy.wait(5)
    adb('shell','am','force-stop','local.usbbridge.vpnprobe',check=False)
    adb('shell','svc','wifi','enable' if old_wifi=='1' else 'disable',check=False)
    adb('shell','svc','data','enable' if old_data=='1' else 'disable',check=False)
    for handle in handles:handle.close()
