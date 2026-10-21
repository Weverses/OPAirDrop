#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/bin/common.sh"
init_state
: > "$STATE/startup-trace.log"
startup_trace BOOT_SERVICE_ENTER
i=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$i" -lt 90 ];do sleep 2;i=$((i+1));done
[ "$(getprop sys.boot_completed)" = 1 ] || exit 2
startup_trace ANDROID_READY
# Complete a recovery-mode install or an LSPosed configuration deferred at install.
if [ -f "$STATE/oshare-setup-pending" ]; then
    /system/bin/sh "$MODDIR/bin/install-oshare.sh" "$MODDIR" boot >> "$STATE/oshare-install.log" 2>&1 || true
fi
# Installer enables one boot attempt. A failed boot never reloads in a loop.
[ -e "$STATE/autostart" ] && [ ! -e "$STATE/disabled" ] || exit 0
if [ -e "$STATE/startup-incomplete" ];then
    touch "$STATE/disabled"
    echo 'Prior startup did not finish; manual inspection required' > "$STATE/boot-start.log"
    exit 3
fi
if /system/bin/sh "$MODDIR/bin/control.sh" start > "$STATE/boot-start.log" 2>&1;then
    log -t opairshare-quickshare 'r24 resident driver, transfer callbacks and UI lifecycle ready'
else
    touch "$STATE/disabled"
    log -t opairshare-quickshare 'r24 startup failed; no automatic retry'
fi
