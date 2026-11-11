# Third-party components

USB Bridge 0.15.8, November 2026. Upstream copyright notices and full licenses are retained in `licenses/` and in the vendored source directories.

| Component | Version / commit | License / upstream |
|---|---|---|
| HEV SOCKS5 Tunnel | `e802f02bae0fc55cbf681466a60e89c2e6773401` | MIT, Copyright (c) 2022 hev; https://github.com/heiher/hev-socks5-tunnel |
| HEV SOCKS5 Core | `162dd996299fc2d2bff2dd63728f8a2cd71ed31a` | MIT; https://github.com/heiher/hev-socks5-core |
| HEV Task System | `328f35d903221b51811b3d02b277d665dfbdc75f` | MIT; https://github.com/heiher/hev-task-system |
| lwIP, HEV fork | `e22c9d2873cd5a8aade9deb2e518b75dab05170e` | BSD; https://github.com/heiher/lwip |
| yaml, HEV fork | `efa36117a8646d26d12b58e05bac472d7854a70d` | MIT; https://github.com/heiher/yaml |
| Shizuku API, provider, aidl, shared | 13.1.5 | MIT, Copyright (c) 2021 RikkaW; https://github.com/RikkaApps/Shizuku-API |
| AndroidX Annotation | 1.3.0 | Apache 2.0, Android Open Source Project; https://android.googlesource.com/platform/frameworks/support/ |
| Shizuku application, separate APK | v13.6.0.r1086.2650830c | Apache 2.0; https://github.com/RikkaApps/Shizuku |
| Gradle wrapper, source archive only | 8.13 | Apache 2.0; https://github.com/gradle/gradle |

The Shizuku application APK is unmodified and was downloaded from the official release:

https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk

HEV's tracked symbolic links were materialized for building on Windows. No upstream C source functionality was changed. Android arm64-v8a and x86_64 libraries were compiled with Android NDK 29.0.14206865; LOAD segments use 16 KiB alignment. The unused Wintun Windows driver is not included in the package or source archive and is not used by the Windows EXE.

The rootless tethering approach was researched using Android framework APIs and the public v2rayNG discussion at https://github.com/2dust/v2rayNG/pull/5903. Research repositories, SDK binaries and private signing keys are not included.

The Android APK uses the local Android debug signing certificate. The separate official Shizuku APK retains its original signature. SHA256SUMS files identify the exact distributed artifacts.

The WireRoute 3.1.13 APK uses the official WireGuard Android tunnel library 1.0.20260102 and AndroidX. Its full component notices are in licenses/WireRoute-NOTICES.md and the accompanying source/wireroute-next/THIRD_PARTY_NOTICES.md. The WireRoute APK retains the original WireRoute 3.0.0 signing identity. Private keys, saved VPN profiles and settings are not distributed.

WireRoute 3.1.13 retains the pinned WireGuard native wrapper with a local packet-descriptor adapter. Cryptographic code is unchanged. See licenses/WireRoute-NOTICES.md.

Portable ADB: unmodified adb.exe, AdbWinApi.dll and AdbWinUsbApi.dll from Google
Platform-tools28.0.1. Full upstream NOTICE.txt is retained alongside the files.
Official archive: https://dl.google.com/android/repository/platform-tools_r28.0.1-windows.zip
Archive SHA256: db78f726d5dc653706dcd15a462ab1b946c643f598df76906c4c1858411c54df
Selected for legacy Windows compatibility without the UCRT dependency introduced
in platform-tools28.0.2. The bundle does not install drivers or change Windows
security settings. It uses a separate local ADB server endpoint. Existing ADB
installations are preferred on all Windows versions; their files are never replaced.

USB Bridge helper157 is a modified source-built Shizuku compatibility server, not an official Shizuku release. The installed official manager APK remains unchanged. An optional OEM-denied WRITE_SECURE_SETTINGS convenience grant no longer aborts manager attachment. Startup also delivers Binder to the declared USB Bridge client through the unchanged upstream access checks; no denied permission is granted or bypassed. Pinned sources, patches, build support and Apache-2.0/MIT licenses are in source/third-party/shizuku-helper and, in the Windows package, licenses/helper157/. Runtime files are helper/server.jar and helper/start-helper157.sh.
