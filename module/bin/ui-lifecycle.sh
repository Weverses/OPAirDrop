#!/system/bin/sh
# Keep the driver/native server resident; share provider ownership between UI
# and continuous module-owned background reception.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
. "$MODDIR/bin/kernel-identity.sh"
init_state
exec 8> "$STATE/provider.flock"
flock -n 8 8>&8 || exit 0
echo "$$" > "$STATE/supervisor.pid"
cleanup() {
    /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&- || echo 'Cannot clear AirDrop receive readiness' >&2
    [ "$(cat "$STATE/supervisor.pid" 2>/dev/null || true)" != "$$" ] || rm -f "$STATE/supervisor.pid"
    [ "$(cat "$STATE/supervisor-ready" 2>/dev/null || true)" != "$(boot_id) $$" ] || rm -f "$STATE/supervisor-ready"
    rm -f "$STATE/foreground-$$.txt"
    exec 8>&-
}
trap cleanup EXIT
trap 'exit 0' INT TERM

# A prior supervisor may have died before its EXIT trap. Never inherit its
# admission gate before validating this boot's coex/provider ownership.
/system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&-

/system/bin/sh "$MODDIR/bin/speed-history.sh" start 8>&-

wifi_session() {
    CLASSPATH="$MODDIR/payload/native/session-wifi.jar" \
        /system/bin/timeout -k 2 18 /system/bin/app_process /system/bin OPAirShareSessionWifi "$1" "$STATE/session-wifi" 8>&-
}

foreground_state() {
    activities_file="$STATE/foreground-$$.txt"
    if ! /system/bin/timeout 4 /system/bin/dumpsys activity activities > "$activities_file" 2>/dev/null; then
        echo unknown;return
    fi
    resumed=$(grep -E 'topResumedActivity=|mResumedActivity:|ResumedActivity:' "$activities_file" || true)
    if [ -z "$resumed" ];then echo unknown;return;fi
    if printf '%s\n' "$resumed" | grep -Eq '(com\.google\.android\.gms/[^ ]*nearby\.sharing\.|com\.coloros\.oshare/(\.ui\.ShareActivity|com\.(oplus|coloros)\.oshare\.ui\.ShareActivity))'; then
        echo open;return
    fi
    if [ -e "$STATE/ui-session" ] &&
       printf '%s\n' "$resumed" | grep -Eq '(com\.android\.documentsui|com\.google\.android\.documentsui)/';then
        task=$(printf '%s\n' "$resumed" | sed -n 's/.* t\([0-9][0-9]*\).*/\1/p' | head -n 1)
        if [ -n "$task" ] &&
           grep -Eq "com\.google\.android\.gms/[^ ]*nearby\.sharing\..* t${task}[ }]" "$activities_file";then
            echo open;return
        fi
    fi
    echo closed
}

close_session() {
    /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&- || return 8
    # Inspect App transfer state before releasing the original provider.
    /system/bin/sh "$MODDIR/bin/transfer-idle.sh" 8>&- || return 9
    [ "$(foreground_state)" = closed ] || return 10
    if [ ! -e "$STATE/coex-off" ] &&
       [ "$(/system/bin/sh "$MODDIR/bin/receive-session.sh" wanted 8>&-)" = 1 ];then return 10;fi
    /system/bin/timeout -k 2 35 /system/bin/sh "$MODDIR/bin/provider.sh" release 8>&- || return 6
    [ -z "$(pidof "$PKG" || true)" ] || return 6
    reply=$(framework timeout 15 service call "$SERVICE" 2 i32 1 i32 4) || return 6
    printf '%s\n' "$reply" > "$STATE/ui-native-stop.txt"
    echo "$reply" | grep -q fffffff && return 6
    P=/sys/module/qca_cld3_peach_v2/parameters
    [ "$(cat "$P/opairshare_bridge_pending")" = 0 ] || return 7
    grep -q 'busy=0 fault=0 error=0 accepting=0 vdev=255 freq=0' "$P/opairshare_channel_state" || return 7
    grep -q 'used=0 armed=0 pending=0 active=0 fault=0' "$P/opairshare_awdl_rx_mac_filter" || return 7
    if [ -e "$STATE/session-wifi" ]; then wifi_session restore || return 8; fi
    /system/bin/sh "$MODDIR/bin/coex-session.sh" restore 8>&- || return 8
    /system/bin/sh "$MODDIR/bin/receive-session.sh" release-host 8>&- || return 8
    rm -f "$STATE/ui-session"
    echo 'UI_CLOSED_RADIO_RELEASED_DRIVER_RETAINED'
}

off_samples=0
provider_samples=0
provider_failures=0
provider_ready=0
receive_published=0
receive_samples=10
while same_boot "$STATE/stack-boot" && [ ! -e "$STATE/disabled" ] &&
      [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; do
    kernel_matches || { echo 'Driver identity changed; no automatic reload'; exit 6; }
    if ! same_boot "$STATE/server-boot" ||
       ! pid_is "$(cat "$STATE/server.pid" 2>/dev/null || true)" '/system_ext/bin/mosey_server'; then
        echo 'Native server lost; explicit recovery required'; exit 6
    fi
    printf '%s %s\n' "$(boot_id)" "$$" > "$STATE/supervisor-ready"
    # Only current Mosey sockets on mosey0; leave global TCP settings alone.
    socket_owner=$(pidof "$PKG" || true)
    case "$socket_owner" in
        ''|*[!0-9]*) ;;
        *)
            if /system/bin/timeout -k 1 2 "$MODDIR/payload/native/opairshare-socket-buffer" "$socket_owner" --apply 8>&-; then
                socket_guard_failed=0
            elif [ "${socket_guard_failed:-0}" = 0 ]; then
                echo 'Mosey socket-buffer guard unavailable; session retained'
                socket_guard_failed=1
            fi
            ;;
    esac
    /system/bin/sh "$MODDIR/bin/coex-session.sh" tick 8>&- || echo 'COEX_RX_RETRY: retaining session'
    receive_requested=$(/system/bin/sh "$MODDIR/bin/receive-session.sh" wanted 8>&-)
    receive_eligible=0
    receive_samples=$((receive_samples+1))
    if [ "$receive_requested" = 1 ] && [ ! -e "$STATE/coex-off" ];then receive_eligible=1;fi
    if [ "$receive_eligible" != 1 ] && [ "$receive_published" = 1 ];then
        /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&-
        receive_published=0
    fi
    foreground=$(foreground_state)
    if [ "$foreground" = unknown ] && [ "$receive_eligible" != 1 ];then
        off_samples=0;echo 'Cannot read foreground UI; retain current session';sleep 2;continue
    fi
    if [ "$foreground" = open ] || [ "$receive_eligible" = 1 ]; then
        off_samples=0
        if [ ! -e "$STATE/ui-session" ]; then
            # A stale same-boot snapshot belongs to an interrupted session.
            [ ! -e "$STATE/session-wifi" ] || { echo 'Unrestored UI session; stop required'; exit 8; }
            exec 6> "$STATE/settings.flock"
            if ! flock -n 6 6>&6;then exec 6>&-;sleep 1;continue;fi
            if ! OPAIRSHARE_SETTINGS_LOCK_HELD=1 /system/bin/sh "$MODDIR/bin/coex-session.sh" prepare 8>&- 6>&6; then
                /system/bin/sh "$MODDIR/bin/coex-session.sh" restore 8>&- || exit 8
                exec 6>&-
                sleep 3
                continue
            fi
            # Coexistence never changes association/autojoin. Only legacy mode
            # needs the original helper, whose restore may reconnect Wi-Fi.
            if [ ! -d "$STATE/coex-session" ]; then wifi_session capture || exit 8; fi
            boot_id > "$STATE/ui-session"
            startup_trace COEX_READY
            exec 6>&-
            if ! /system/bin/timeout -k 2 35 /system/bin/sh "$MODDIR/bin/provider.sh" ensure 8>&-; then
                provider_ready=0
                provider_failures=1
                echo 'Provider activation failed; retain ownership for session cleanup'
            else
                provider_ready=1
                echo 'UI_OR_RECEIVE_ORIGINAL_PROVIDER_READY'
            fi
            provider_samples=0
        else
            provider_samples=$((provider_samples+1))
            if [ -z "$(pidof "$PKG" || true)" ];then
                provider_ready=0
                /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&-
                receive_published=0
                provider_samples=10
            fi
            if [ "$provider_samples" -ge 10 ] && [ "$provider_failures" -lt 3 ]; then
                if /system/bin/timeout -k 2 35 /system/bin/sh "$MODDIR/bin/provider.sh" ensure 8>&-;then
                    provider_failures=0;provider_ready=1
                else
                    provider_ready=0
                    /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&-
                    receive_published=0
                    provider_failures=$((provider_failures+1));echo "Provider retry failed count=$provider_failures"
                fi
                provider_samples=0
            fi
        fi
        if [ "$receive_eligible" = 1 ] && [ "$provider_ready" = 1 ] && [ "$receive_samples" -ge 10 ];then
            if /system/bin/sh "$MODDIR/bin/receive-session.sh" ensure-ui 8>&-;then
                [ "$receive_published" = 1 ] || startup_trace RECEIVE_HOST_READY
                # Recheck module/coexistence ownership before opening admission.
                receive_previous=$receive_published
                receive_published=$(/system/bin/sh "$MODDIR/bin/receive-session.sh" ready 8>&-)
                if [ "$receive_previous" != 1 ] && [ "$receive_published" = 1 ];then startup_trace RECEIVE_GATE_OPEN;fi
            else
                /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&-
                receive_published=0
                echo 'OShare receiver UI unavailable; AirDrop receive admission closed'
            fi
            receive_samples=0
        fi
    else
        off_samples=$((off_samples+1))
        if [ "$off_samples" -ge 3 ] && [ -e "$STATE/ui-session" ]; then
            if close_session; then provider_failures=0;provider_ready=0;receive_published=0
            else
                rc=$?
                if [ "$rc" = 10 ];then off_samples=0
                elif [ "$rc" != 9 ]; then
                    echo "UI close uncertain rc=$rc; no driver reload"; exit "$rc"
                fi
            fi
        fi
    fi
    sleep 1
done
