#!/system/bin/sh
# USB Bridge 0.15.7: start a source-built Shizuku compatibility server.
# The installed, signed Shizuku manager and all permission checks are retained.
set -eu
fail() { echo "USB Bridge helper: $*" >&2; exit 1; }
[ "$(id -u)" = "2000" ] || fail "requires the user-authorized Android shell"
[ "$#" = "2" ] || fail "requires the installed manager APK and native library paths"
PAYLOAD=/data/local/tmp/usbbridge-helper157/server.jar
PAYLOAD_SHA=afdb2fe6f2e3631afc7ed9932affd830abc491805215759df577b762bb0c4a61
MANAGER_SHA=6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f
APK="$1"
LIB="$2"
CURRENT_APK="$(pm path --user 0 moe.shizuku.privileged.api | sed -n 's/^package://p')"
[ "$APK" = "$CURRENT_APK" ] || fail "manager path changed; retry from the PC"
APK_DIR="${APK%/base.apk}"
case "$LIB" in
 "$APK_DIR/lib/arm64"|"$APK_DIR/lib/arm"|"$APK_DIR/lib/x86_64"|"$APK_DIR/lib/x86") ;;
 *) fail "unexpected native library path" ;;
esac
[ -r "$APK" ] && [ -r "$LIB/librish.so" ] || fail "installed manager files unavailable"
[ -x /system/bin/setsid ] && [ -x /system/bin/app_process ] || fail "required Android launcher unavailable"
[ "$(sha256sum "$APK" | cut -d ' ' -f1)" = "$MANAGER_SHA" ] || fail "unsupported manager APK"
[ "$(sha256sum "$PAYLOAD" | cut -d ' ' -f1)" = "$PAYLOAD_SHA" ] || fail "payload integrity check failed"
# Stop only the exact shell-owned Shizuku server. Root-owned servers are untouched.
for PID in $(pidof shizuku_server 2>/dev/null || true); do
 case "$PID" in ''|*[!0-9]*) fail "invalid process identifier" ;; esac
 PROCESS="/proc/$PID"
 [ -r "$PROCESS/status" ] && [ -r "$PROCESS/cmdline" ] || continue
 NAME="$(tr '\000' '\n' < "$PROCESS/cmdline" 2>/dev/null | head -n 1)" || continue
 [ "$NAME" = "shizuku_server" ] || continue
 OWNER="$(sed -n 's/^Uid:[[:space:]]*\([0-9]*\).*/\1/p' "$PROCESS/status" 2>/dev/null)" || continue
 [ "$OWNER" = "2000" ] || fail "a non-shell Shizuku server is already running"
 kill "$PID" 2>/dev/null || fail "could not stop the previous shell server"
 # A terminating JVM can still own Binder endpoints. Do not race a second server.
 TRIES=0
 while kill -0 "$PID" 2>/dev/null; do
  [ -r "$PROCESS/cmdline" ] && [ -r "$PROCESS/status" ] || break
  CURRENT_NAME="$(tr '\000' '\n' < "$PROCESS/cmdline" 2>/dev/null | head -n 1)" || break
  [ "$CURRENT_NAME" = "shizuku_server" ] || break
  CURRENT_OWNER="$(sed -n 's/^Uid:[[:space:]]*\([0-9]*\).*/\1/p' "$PROCESS/status" 2>/dev/null)" || break
  [ "$CURRENT_OWNER" = "2000" ] || break
  grep -q '^State:.*Z' "$PROCESS/status" 2>/dev/null && break
  [ "$TRIES" -lt 50 ] || fail "previous shell server did not stop in time"
  TRIES=$((TRIES + 1))
  sleep 0.1
 done
done
/system/bin/setsid /system/bin/app_process "-Djava.class.path=$PAYLOAD" "-Dshizuku.library.path=$LIB" /system/bin --nice-name=shizuku_server rikka.shizuku.server.ShizukuService > /data/local/tmp/usbbridge-helper157/server.log 2>&1 < /dev/null &
CHILD=$!
sleep 1
kill -0 "$CHILD" 2>/dev/null || fail "server exited; see the helper diagnostic log"
echo "USB Bridge helper: compatibility server started (pid $CHILD); phone checks access"
