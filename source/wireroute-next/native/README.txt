WireRoute packet dispatcher native adapter

Upstream wrapper files api-android.go, jni.c, go.mod/go.sum, Makefile:
https://github.com/WireGuard/wireguard-android/tree/1.0.20260102/tunnel/tools/libwg-go
Tag commit 3831cab2da844319291459308a6e535d36dde4b3.
Apache-2.0 notices are retained. WireGuard core dependency remains the exact
upstream go.mod version f333402bd9cb; cryptographic code is not modified.

Local changes: packet-tun.go adds an AF_UNIX SOCK_DGRAM tun.Device adapter.
api-android.go calls openAndroidTun; real TUN descriptors still use upstream
CreateUnmonitoredTUNFromFD. This permits one Android VPN interface to route
selected app packets through WireGuard and other app packets through plain USB.
JNI symbols and the official Java GoBackend remain unchanged.

Rebuild from the WireRoute source directory in PowerShell:
  ./native/build-native.ps1 -Go C:/path/to/go/bin/go.exe -Ndk C:/path/to/android-ndk
Recorded toolchain: Go 1.27.1 windows/amd64, Android NDK 29.0.14206865.
The script builds arm64-v8a and x86_64 with Android API 24, -trimpath,
-buildvcs=false and an empty build ID. go.mod/go.sum pin dependencies;
the first build needs these modules downloaded into the Go module cache.
It writes app/src/main/jniLibs/<ABI>/libwg-go.so. A Go runtime overlay
under native/.build preserves Android BOOTTIME behavior without editing Go.
Do not package native/.build, signing material or generated test profiles.

Recorded SHA256 values for this release's native output:
  arm64-v8a F07533243069D1A8F4F9BF45AAE19A402DB87FF675EC992BB9BDDBBC486F3876
  x86_64   642F2E8D75481DFBE300E2FDA4CF578499E6627E0FA2BF89AE427F29EBF41C65
Verify the APK's lib/<ABI>/libwg-go.so matches these source binaries: the
Gradle pickFirsts rule must choose the local adapter, not the dependency AAR.
The official Java classes remain supplied by tunnel:1.0.20260102.

StandaloneNativeIntegrationTest, in an isolated .usbtest emulator package,
tests the original real-TUN path with a fresh fixture peer and actual HTTP.
Stop USB Bridge and enable the emulator's ordinary Wi-Fi first. The method
uses clientKey/serverKey/host/port arguments from disposable fixture keys;
it refuses a real device/package or a preexisting non-fixture profile.
