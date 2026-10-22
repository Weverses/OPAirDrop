#!/system/bin/sh
# Restart only the idle provider/native service, preserving resident WLAN.
set -eu
mode=${1:?original|trace|20|freq20|rate20}
tag=${2:?unique log tag}
case "$mode" in original|trace|20|freq20|rate20) ;; *) exit 2;; esac
case "$tag" in ''|*[!A-Za-z0-9_-]*) exit 2;; esac
. /data/local/tmp/opairshare-width-control-functions.sh
kernel_matches
/system/bin/sh "$MODDIR/bin/transfer-idle.sh"
lock_stack
stop_supervisor
passive_ui_baseline
/system/bin/sh "$MODDIR/bin/coex-session.sh" restore
rm -f "$STATE/ui-session"
stop_native
if [ "$mode" = original ]; then
    start_native
else
    OPAIRSHARE_ENABLE_HOPPING=1 OPAIRSHARE_SOCIAL_CHANNELS=149 \
    OPAIRSHARE_CONFIG_TRACE="$STATE/config-trace.log" \
    OPAIRSHARE_WIDTH_MODE="$mode" OPAIRSHARE_WIDTH_IFACE=wonder0 \
    OPAIRSHARE_WIDTH_LOG="/data/local/tmp/opairshare-width-$tag.txt" \
    LD_PRELOAD="$MODDIR/payload/native/libopairshare_capability.so:$MODDIR/payload/native/libopairshare_config.so:/data/local/tmp/opairshare-width-shim.so" \
    /system_ext/bin/mosey_server >> "$STATE/server.log" 2>&1 < /dev/null 9>&- &
    server=$!
    echo "$server" > "$STATE/server.pid"
    boot_id > "$STATE/server-boot"
    n=0
    while [ "$n" -lt 15 ]; do
        if framework service check "$SERVICE" | grep -q ': found'; then break; fi
        pid_live "$server" || exit 5
        sleep 1; n=$((n+1))
    done
    framework service check "$SERVICE" | grep -q ': found'
fi
# activate_provider uses the existing safe Stop/prepare/start lifecycle.
activate_provider
echo "WIDTH_SESSION_READY mode=$mode server=$(cat "$STATE/server.pid")"
