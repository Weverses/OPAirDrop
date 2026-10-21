#!/system/bin/sh
# Authenticate the signed APK before exposing the callback-patched APK.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
AUTH="$STATE/callback-auth"
startup_trace AUTH_BEGIN
mkdir -p "$AUTH"
if [ -e "$STATE/callback-off" ]; then
    /system/bin/sh "$MODDIR/bin/app-check.sh" original
    exit 0
fi
gms_pid=$(pidof com.google.android.gms || true)
if same_boot "$AUTH/boot" && [ -n "$gms_pid" ] &&
   [ "$(cat "$AUTH/gms-pid" 2>/dev/null || true)" = "$gms_pid" ]; then
    /system/bin/sh "$MODDIR/bin/callback.sh" ensure
    exit 0
fi
/system/bin/sh "$MODDIR/bin/transfer-idle.sh"
# control.sh's passive baseline has already stopped the old provider/radio.
/system/bin/sh "$MODDIR/bin/callback.sh" off
/system/bin/sh "$MODDIR/bin/provider.sh" ensure
n=0;registered=0
while [ "$n" -lt 20 ]; do
    dump=$(framework timeout 6 dumpsys activity service com.google.android.gms/.chimera.PersistentApiServiceNoInstantApps) || dump=
    if printf '%s\n' "$dump" | grep -Eq '[|] com\.google\.android\.mosey [|] Version:'; then
        registered=1;break
    fi
    sleep 1;n=$((n+1))
done
[ "$registered" != 1 ] || startup_trace AUTH_REGISTERED
/system/bin/sh "$MODDIR/bin/provider.sh" release
reply=$(framework timeout 15 service call "$SERVICE" 2 i32 1 i32 4)
printf '%s\n' "$reply" > "$AUTH/native-stop.txt"
if [ "$registered" != 1 ]; then
    echo 'CALLBACK_AUTH_NOT_READY: original signed APK retained'
    exit 6
fi
/system/bin/sh "$MODDIR/bin/callback.sh" on
gms_pid=$(pidof com.google.android.gms)
printf '%s\n' "$gms_pid" > "$AUTH/gms-pid"
boot_id > "$AUTH/boot"
startup_trace PATCH_AUTH_READY
echo "CALLBACK_AUTH_PRIMED gms_pid=$gms_pid"
