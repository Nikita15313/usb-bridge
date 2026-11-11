# Third-party notices — WireRoute 3.1.5

WireRoute uses the official WireGuard Android tunnel library:

- Artifact: `com.wireguard.android:tunnel:1.0.20260102`
- [Source](https://github.com/WireGuard/wireguard-android/tree/1.0.20260102).
- [Project license: Apache License 2.0](https://raw.githubusercontent.com/WireGuard/wireguard-android/1.0.20260102/COPYING).
- The Java library and WireGuard tools come from that Maven artifact. The
  WireGuard Go library is rebuilt from the same pinned upstream wrapper/core
  with the local packet-descriptor adapter in `native/libwg-go/packet-tun.go`.
  This adapter changes local I/O only; the cryptographic implementation is unchanged.
  The components retain their respective upstream licenses:
  [wireguard-go](https://github.com/WireGuard/wireguard-go/blob/master/LICENSE),
  [wireguard-tools](https://github.com/WireGuard/wireguard-tools/blob/master/COPYING).

WireGuard is a registered trademark of Jason A. Donenfeld. WireRoute is an independent personal client and is not endorsed by the WireGuard project.

The app UI uses `androidx.drawerlayout:drawerlayout:1.2.0` and its AndroidX dependencies,
under the [AndroidX Apache License 2.0](https://raw.githubusercontent.com/androidx/androidx/androidx-main/LICENSE.txt).

Core library desugaring uses `com.android.tools:desugar_jdk_libs:2.1.5`. Its upstream
[license](https://raw.githubusercontent.com/google/desugar_jdk_libs/master/LICENSE)
contains GPL version 2 terms with the Classpath exception for designated code.

JUnit and the AndroidX test runner are test dependencies. The release application
does not include the separate instrumentation APK.

The optional USB internet mode uses hev-socks5-tunnel from the existing USB Bridge
distribution, including hev-task-system, lwIP and libyaml. Their notices are in
`licenses/HEV-Tunnel-LICENSE.txt`, `licenses/HEV-Core-LICENSE.txt`,
`licenses/HEV-Task-System-LICENSE.txt`, `licenses/lwIP-LICENSE.txt`, and
`licenses/yaml-LICENSE.txt`. The engine is used only while the WireGuard switch
is off and USB Bridge is explicitly selected, and for the plain side of USB
per-app routing while WireGuard is on. `native/build-native.ps1` reproduces the
two Android native libraries and retains Android's BOOTTIME timer behavior.
