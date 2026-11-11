"""Sign a release feed locally. The signing key is never uploaded or distributed."""
from pathlib import Path
import argparse, base64, hashlib, json, re, subprocess
from datetime import datetime, timezone
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--key', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--sequence', type=int, required=True)
    parser.add_argument('--bridge-version', required=True)
    parser.add_argument('--wire-version', required=True)
    parser.add_argument('--wire-code', type=int, required=True)
    parser.add_argument('--aapt', type=Path, required=True)
    parser.add_argument('--apksigner-jar', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    args = parser.parse_args()
    if args.sequence <= 0 or args.wire_code <= 0:
        parser.error('Version codes must be positive')
    releases = []
    entries = [('windows', f'USBBridge-Windows-{args.bridge_version}.zip', args.sequence, args.bridge_version, None),
               ('androidBridge', f'USBBridge-Android-{args.bridge_version}.apk', args.sequence, args.bridge_version, 'local.usbbridge'),
               ('androidWireRoute', f'WireRoute-Android-{args.wire_version}.apk', args.wire_code, args.wire_version, 'app.wireroute.release263')]
    for identity, filename, code, version, package in entries:
        path = args.assets / filename
        data = path.read_bytes()
        release = dict(id=identity, versionCode=code, versionName=version,
                       url=f'https://github.com/Nikita15313/usb-bridge/releases/download/v{args.bridge_version}/{filename}',
                       sha256=hashlib.sha256(data).hexdigest(), size=len(data))
        if package:
            badging = subprocess.check_output([str(args.aapt), 'dump', 'badging', str(path)], text=True, encoding='utf-8')
            actual = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
            if not actual or actual.groups() != (package, str(code), version):
                raise ValueError('Unexpected APK identity: ' + filename)
            certs = subprocess.check_output([str(args.java), '-jar', str(args.apksigner_jar), 'verify', '--print-certs', str(path)], text=True, encoding='utf-8')
            certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})', certs)
            if len(certificates) != 1:
                raise ValueError('Expected one APK signer: ' + filename)
            release.update(packageName=package, signerSha256=certificates[0].lower())
        releases.append(release)
    payload = json.dumps(dict(schema=1, sequence=args.sequence,
                              publishedAt=datetime.now(timezone.utc).isoformat(), releases=releases),
                         ensure_ascii=True, separators=(',', ':')).encode('utf-8')
    private = serialization.load_pem_private_key(args.key.read_bytes(), password=None)
    signature = private.sign(payload, padding.PKCS1v15(), hashes.SHA256())
    private.public_key().verify(signature, payload, padding.PKCS1v15(), hashes.SHA256())
    envelope = dict(payload=base64.b64encode(payload).decode('ascii'), signature=base64.b64encode(signature).decode('ascii'))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(envelope, separators=(',', ':')) + '\n', encoding='utf-8')
    print('Signed feed:', args.output, 'sequence', args.sequence)

if __name__ == '__main__':
    main()
