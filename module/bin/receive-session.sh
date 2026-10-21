#!/system/bin/sh
# Root-owned admission gate. OShare's native timeout owns oshare_state.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
[ "$(id -u)" = 0 ] || exit 2

receive_wanted() {
    # User-selected mapping: Contacts (2) keeps AirDrop visible persistently;
    # Everyone (1) follows OShare's native timeout. Neither mode verifies Apple
    # contact identity; every incoming invitation still requires confirmation.
    # Off, unknown values and settings errors close admission.
    value=$(/system/bin/timeout 4 /system/bin/settings --user 0 get system oshare_state 2>/dev/null) || value=unknown
    case "$value" in 1|2) return 0;; *) return 1;; esac
}

case "${1:-wanted}" in
    wanted)
        if receive_wanted; then echo 1; else echo 0; fi
        ;;
    ensure-ui)
        receive_wanted || exit 9
        records=$(framework timeout 8 dumpsys activity services com.coloros.oshare) || exit 9
        # Repeated startservice calls re-run native OShare initialization and
        # timers. Start the non-exported service as root only when absent.
        if ! printf '%s\n' "$records" | grep -q 'com.oplus.oshare.OShareServer'; then
            receive_wanted || exit 9
            reply=$(framework timeout 8 am startservice --user 0 -n com.coloros.oshare/com.oplus.oshare.OShareServer) || exit 9
            printf '%s\n' "$reply" | grep -q '^Starting service:' || exit 9
        fi
        ;;
    ready)
        # Only advertise in an owned, current-boot coexistence session. Legacy
        # UI-only operation may restore/reconnect STA and is not background RX.
        if receive_wanted && same_boot "$STATE/ui-session" &&
           same_boot "$STATE/coex-session/boot"; then
            /system/bin/setprop debug.opairshare.receive.ready 1
            echo 1
        else
            /system/bin/setprop debug.opairshare.receive.ready 0
            echo 0
        fi
        ;;
    clear)
        /system/bin/setprop debug.opairshare.receive.ready 0
        ;;
    *) echo 'Usage: receive-session.sh wanted|ensure-ui|ready|clear' >&2; exit 2;;
esac
