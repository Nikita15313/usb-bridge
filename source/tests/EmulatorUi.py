from pathlib import Path
import os, subprocess, time, re, xml.etree.ElementTree as ET

work = Path(__file__).resolve().parent
adb_path = Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/platform-tools/adb.exe'
report = []
relay = None
handles = []

def adb(*args, check=True):
    p = subprocess.run([str(adb_path), '-s', 'emulator-5554', *args], capture_output=True,
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
