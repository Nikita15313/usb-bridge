USB Bridge helper compatibility payload 154

Purpose
The official Shizuku 13.6.0 r1086 server performs an optional WRITE_SECURE_SETTINGS grant while attaching its own manager. On this OxygenOS device that call throws SecurityException (GRANT_RUNTIME_PERMISSIONS denied), before manager bindApplication completes. The manager then reports stale server version 13.0 and user services time out waiting for its Binder readiness.

Narrow runtime patch
server/src/main/java/rikka/shizuku/server/ShizukuService.java: catch RemoteException OR SecurityException around that optional grant; log the actual denial and continue manager attachment. No permission is manufactured, granted, bypassed or suppressed from diagnostic state. All other runtime source files from the upstream server/API modules are unchanged. See optional-grant.patch and runtime-source-differences.json.

Provenance
Shizuku commit 2650830c5b099ae0dd34fedf614d4f592ca05d65
https://github.com/RikkaApps/Shizuku/tree/2650830c5b099ae0dd34fedf614d4f592ca05d65
Pinned Shizuku-API submodule commit 510fc988c02c3475d8c25db170f96792f105bdf8
https://github.com/RikkaApps/Shizuku-API/tree/510fc988c02c3475d8c25db170f96792f105bdf8
Original archives and their SHA-256 values are retained in provenance.json. Both projects are Apache-2.0 licensed; see licenses/. This modified standalone payload is not an official Shizuku release. The installed signed manager APK is never replaced.

Build
Sources/build adaptations are in upstream/. Source was staged under ASCII D:/Codex/Builds/USBBridgeHelper154 because Windows AIDL fails in Cyrillic paths.
JBR21; Gradle8.13; AGP8.13.2; Kotlin2.1.21; AndroidSDK36; build-tools36.1.0.
Build command: gradle :payload:assembleRelease --no-daemon --console=plain
Build adaptations: omit manager UI and native compilation; use the original installed manager's librish.so at runtime; assemble unminified server/API classes in a standalone DEX container. The payload directly includes the provider module, which upstream server/starter declare compileOnly and normally receive through the manager APK. This supplies BinderContainer and its Parcelable creator at runtime. The application APK build is packaging-only and is not installed. server.jar contains the built classes.dex only. Runtime flags explicitly set shizuku.library.path to the validated original manager native library directory.
After the Gradle build, run package-payload.py. It reads actual DEX class definitions (not merely reference strings), requires the server, starter, provider/BinderContainer and Parcelable creators, generated Binder API stubs and runtime library classes, then packages a deterministic server.jar and pins its hash into start-helper154.sh. artifact.json and verified-dex-classes.txt record these checks.

Launch
Fixed destination /data/local/tmp/usbbridge-helper154/server.jar and start-helper154.sh.
Windows validates supported OnePlus/Oppo policy state, exact installed manager APK identity and artifact hashes, stages temporary uploads, verifies then renames and makes payload readonly.
Run the fixed script through authorized adb shell, passing validated installed base.apk and nativeLibraryDir as two separately quoted arguments.
The script requires UID2000, validates installed manager path/hash, payload hash and native files, then enumerates candidates with pidof and independently validates each exact cmdline and UID before stopping only shell-owned shizuku_server processes. It waits up to five seconds for each exact PID to exit before launching a replacement. A root-owned server is not touched and causes an abort. It starts app_process through setsid. No network listeners are created. It does not alter USB settings, system optimization, VPN profiles, Android permissions or the official APK. Java diagnostic messages primarily go to Android logcat; server.log only captures standard output/error and may be empty.

Limits
This fixes the manager-attachment exception. It does not install Windows drivers, does not give the app system privilege without authorized startup, and does not survive reboot. Like official shell-started Shizuku it remains subject to the adbd process cgroup and OEM termination. A USB/adbd mode transition may therefore kill it. Physical execution and Bridge end-to-end tests must be reported separately from the successful source build.
