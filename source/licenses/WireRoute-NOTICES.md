# Third-party notices — WireRoute 3.0.2-usb

WireRoute uses the official WireGuard Android tunnel library:

- Artifact: `com.wireguard.android:tunnel:1.0.20260102`
- [Source](https://github.com/WireGuard/wireguard-android/tree/1.0.20260102).
- [Project license: Apache License 2.0](https://raw.githubusercontent.com/WireGuard/wireguard-android/1.0.20260102/COPYING).
- Native libraries are distributed by that Maven artifact. The packaged WireGuard
  Go and WireGuard tools components retain their respective upstream licenses:
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

The ByeDPI and hev-socks5-tunnel engines from earlier WireRoute versions have been
removed from the application source, native inputs and dependency graph in 3.0.0.
