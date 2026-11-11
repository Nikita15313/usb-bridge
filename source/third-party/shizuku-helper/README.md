# Modified Shizuku compatibility payload

This is USB Bridge's source-built helper payload157, not an official Shizuku release. The installed official Shizuku manager APK remains unchanged. Upstream source locations, exact commits and archive SHA256 values are in [UPSTREAM.json](UPSTREAM.json). Shizuku uses Apache2.0 and Shizuku-API uses MIT; both licenses are included here.

Only `server/src/main/java/rikka/shizuku/server/ShizukuService.java` has runtime changes. [combined-runtime.patch](combined-runtime.patch) applies against the pinned Shizuku source and contains both changes:

- An optional manager `WRITE_SECURE_SETTINGS` convenience grant can throw an OEM `SecurityException`. Log that denial and finish manager attachment. The denied permission is not granted or bypassed.
- During startup, explicitly deliver Binder to the exact installed package `local.usbbridge`, user0, if it declares the Shizuku API permission. This uses the upstream provider/attachment path with existing permission checks. Its no-retry overload avoids force-stopping this running client.

The startup path handles a client that was already foreground before the helper started and did not receive the initial enumeration-based Binder delivery. Duplicate delivery of an already living Binder is ignored by the upstream provider. Normal client permission confirmation, package-to-UID checks and service authorization remain unchanged. No listener, system permission, USB setting, system optimization, VPN profile or Windows networking setting is added or changed.

## Rebuild

1. Obtain the pinned Shizuku and Shizuku-API archives from `UPSTREAM.json`, verify their hashes, extract Shizuku, then place the pinned API source under its `api` directory.
2. Apply `combined-runtime.patch` at the Shizuku root. Copy the contents of `build-support` over the corresponding project files. These build adaptations omit the manager UI/native compilation, include provider runtime classes, and produce an unminified standalone DEX container.
3. Use an ASCII project path on Windows because AIDL does not handle all non-ASCII paths. Use JDK21, Gradle8.13, AndroidSDK36 and build-tools36.1.0. Run `gradle :payload:assembleRelease --no-daemon --console=plain`. The unsigned payload APK is only a build container; do not install it.
4. Run `python package-payload.py --apk <payload-release-unsigned.apk> --output <owned-output-directory>`. The script checks actual required class definitions and unresolved non-platform dependencies, creates `server.jar`, and writes its hash into the launcher script. Expected released artifact values are in `ARTIFACT.json`.

`start-helper157.sh` is the fixed launcher for `/data/local/tmp/usbbridge-helper157`. It requires the user-authorized Android shell UID2000, verifies the installed official manager APK and payload hashes, validates its native library path, and replaces only an exact shell-owned `shizuku_server`. Root-owned servers are left alone. The native library is provided by the unchanged official manager. The launcher never enables network debugging or grants Android permissions.

The helper remains subject to reboot, adbd lifetime and OEM process termination. Network-debugging access, where needed, must already have been enabled by an explicit user action. Payload verification does not establish compatibility with every device or network.
