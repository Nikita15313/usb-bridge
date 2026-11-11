"""Package and verify the complete standalone server DEX; never installs anything."""
from pathlib import Path
import hashlib
import json
import struct
import zipfile

ROOT = Path(__file__).resolve().parent
APK = Path('D:/Codex/Builds/USBBridgeHelper154/payload/build/outputs/apk/release/payload-release-unsigned.apk')
REQUIRED = {
    'Lrikka/shizuku/server/ShizukuService;',
    'Lrikka/shizuku/server/ShizukuUserServiceManager;',
    'Lrikka/shizuku/server/UserServiceRecord;',
    'Lmoe/shizuku/starter/ServiceStarter;',
    'Lmoe/shizuku/api/BinderContainer;',
    'Lmoe/shizuku/api/BinderContainer$1;',
    'Lrikka/shizuku/ShizukuProvider;',
    'Lrikka/parcelablelist/ParcelableListSlice;',
    'Lrikka/parcelablelist/ParcelableListSlice$1;',
    'Lmoe/shizuku/server/IShizukuApplication$Stub;',
    'Lmoe/shizuku/server/IShizukuService$Stub;',
    'Lmoe/shizuku/server/IShizukuServiceConnection$Stub;',
    'Lmoe/shizuku/server/IRemoteProcess$Stub;',
    'Lrikka/rish/RishConfig;',
    'Lrikka/hidden/compat/PermissionManagerApis;',
}

def u32(data, offset):
    return struct.unpack_from('<I', data, offset)[0]

def defined_classes(data):
    if not data.startswith(b'dex\n'):
        raise ValueError('Not an Android DEX file')
    string_count, string_offset = struct.unpack_from('<II', data, 0x38)
    strings = []
    for index in range(string_count):
        cursor = u32(data, string_offset + index * 4)
        while data[cursor] & 0x80:
            cursor += 1
        cursor += 1
        strings.append(data[cursor:data.index(0, cursor)])
    type_count, type_offset = struct.unpack_from('<II', data, 0x40)
    types = [strings[u32(data, type_offset + i * 4)] for i in range(type_count)]
    class_count, class_offset = struct.unpack_from('<II', data, 0x60)
    definitions = {types[u32(data, class_offset + i * 32)].decode('ascii') for i in range(class_count)}
    references = {name.decode('ascii').lstrip('[') for name in types}
    return definitions, references

with zipfile.ZipFile(APK) as source:
    names = sorted(n for n in source.namelist() if n.startswith('classes') and n.endswith('.dex'))
    if not names:
        raise SystemExit('No DEX payload found')
    dex = {name: source.read(name) for name in names}
parsed = [defined_classes(data) for data in dex.values()]
definitions = set().union(*(item[0] for item in parsed))
references = set().union(*(item[1] for item in parsed))
missing = REQUIRED - definitions
if missing:
    raise SystemExit('Missing runtime class definitions: ' + ', '.join(sorted(missing)))
platform_prefixes = ('Landroid/', 'Lcom/android/', 'Ljava/', 'Ljavax/', 'Ldalvik/',
                     'Lorg/json/', 'Lorg/xml/', 'Lorg/w3c/', 'Lorg/xmlpull/',
                     'Lsun/', 'Llibcore/')
unresolved = {name for name in references - definitions
              if name.startswith('L') and not name.startswith(platform_prefixes)}
if unresolved:
    raise SystemExit('Unresolved non-platform DEX types: ' + ', '.join(sorted(unresolved)))

payload = ROOT / 'server.jar'
with zipfile.ZipFile(payload, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
    for name, data in dex.items():
        entry = zipfile.ZipInfo(name, (2026, 10, 22, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        output.writestr(entry, data)
payload_sha = hashlib.sha256(payload.read_bytes()).hexdigest()
script = ROOT / 'start-helper154.sh'
script.write_text((ROOT / 'start-helper154.sh.in').read_text(encoding='utf-8').replace('PAYLOAD_SHA_PLACEHOLDER', payload_sha), encoding='utf-8', newline='\n')
artifact = {
    'payload': str(payload),
    'sha256': payload_sha,
    'bytes': payload.stat().st_size,
    'dex': names,
    'script_sha256': hashlib.sha256(script.read_bytes()).hexdigest(),
    'script_bytes': script.stat().st_size,
    'runtime_class_definitions': len(definitions),
    'verified_runtime_classes': sorted(REQUIRED),
    'unresolved_nonplatform_type_references': sorted(unresolved),
}
(ROOT / 'artifact.json').write_text(json.dumps(artifact, indent=2) + '\n', encoding='utf-8')
(ROOT / 'verified-dex-classes.txt').write_text('\n'.join(sorted(definitions)) + '\n', encoding='utf-8')
print(json.dumps(artifact, ensure_ascii=False, indent=2))
