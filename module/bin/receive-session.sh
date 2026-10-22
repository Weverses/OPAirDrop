#!/system/bin/sh
# Root-owned background admission; independent of OShare's native visibility.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
. "$MODDIR/bin/coex-wifi.sh"
[ "$(id -u)" = 0 ] || exit 2

receive_wanted() {
    # User-selected policy: the enabled module owns continuous reception.
    # OShare is the invitation UI host, not the switch or ten-minute timer.
    same_boot "$STATE/stack-boot" && [ ! -e "$STATE/disabled" ] &&
        [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] &&
        coex_available
}

case "${1:-wanted}" in
    wanted)
        if receive_wanted; then echo 1; else echo 0; fi
        ;;
    ensure-ui)
        receive_wanted || exit 9
        records=$(framework timeout 8 dumpsys activity services com.coloros.oshare) || exit 9
        # Start the non-exported host as root only when absent. No Activity is
        # opened and no native OShare visibility setting is written.
        if ! printf '%s\n' "$records" | grep -Eq 'com\.oplus\.oshare\.OShareServer|isFgsDelegate=true'; then
            receive_wanted || exit 9
            reply=$(framework timeout 8 am startservice --user 0 -n com.coloros.oshare/com.oplus.oshare.OShareServer) || exit 9
            printf '%s\n' "$reply" | grep -q '^Starting service:' || exit 9
        fi
        records=$(framework timeout 8 dumpsys activity services com.coloros.oshare) || exit 9
        if ! printf '%s\n' "$records" | grep -q 'isFgsDelegate=true';then
            framework timeout 8 cmd activity set-foreground-service-delegate --user 0 com.coloros.oshare start || exit 9
            # Own only the delegate started here; native foreground services
            # and any delegate already present are not ours to release.
            boot_id > "$STATE/receive-delegate-owned"
            records=$(framework timeout 8 dumpsys activity services com.coloros.oshare) || exit 9
            printf '%s\n' "$records" | grep -q 'isFgsDelegate=true' || exit 9
        fi
        ;;
    ready)
        # Only advertise in an owned, current-boot coexistence session. Legacy
        # UI-only operation may restore/reconnect STA and is not background RX.
        if receive_wanted && same_boot "$STATE/ui-session" &&
           same_boot "$STATE/coex-session/boot" &&
           [ "$coex_wifi_network" = "$(cat "$STATE/coex-session/network" 2>/dev/null)" ]; then
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
    release-host)
        if same_boot "$STATE/receive-delegate-owned";then
            # Clearing admission is allowed during a transfer; removing its UI
            # host's foreground lifetime is allowed only after transfer cleanup.
            /system/bin/sh "$MODDIR/bin/transfer-idle.sh" || exit 9
            framework timeout 8 cmd activity set-foreground-service-delegate --user 0 com.coloros.oshare stop || exit 9
            rm -f "$STATE/receive-delegate-owned"
        fi
        ;;
    *) echo 'Usage: receive-session.sh wanted|ensure-ui|ready|clear|release-host' >&2; exit 2;;
esac
