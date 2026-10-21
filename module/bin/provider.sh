#!/system/bin/sh
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
[ "$(id -u)" = 0 ] || exit 2
init_state
case "${1:-status}" in
    status)
        records=$(active_services)
        echo "provider_pid=$(pidof "$PKG" || true)"
        if echo "$records" | grep -q 'ExternalSharingService'; then echo provider_active=yes; else echo provider_active=no; fi
        if echo "$records" | grep -q 'isFgsDelegate=true'; then echo foreground_delegate=yes; else echo foreground_delegate=no; fi
        ;;
    ensure|adopt)
        /system/bin/sh "$MODDIR/bin/app-check.sh" || exit 3
        framework service check "$SERVICE" | grep -q ': found' || { echo 'Native service absent'; exit 3; }
        # Boot receiver disabled the component when native service was absent.
        framework pm enable --user 0 "$PKG" >/dev/null
        framework pm enable --user 0 "$COMP" >/dev/null
        trap 'framework cmd deviceidle tempwhitelist -r "$PKG" >/dev/null 2>&1 || true' EXIT
        records=$(active_services)
        if ! echo "$records" | grep -q 'ExternalSharingService'; then
            framework cmd deviceidle tempwhitelist -u 0 -d 90000 "$PKG" >/dev/null
            result=$(framework am startservice --user 0 -n "$COMP")
            echo "$result"
            echo "$result" | grep -q '^Starting service:' || exit 3
        fi
        n=0;launched_delegate=0;provider_ready=0
        while [ "$n" -lt 15 ]; do
            records=$(active_services)
            provider_pid=$(pidof "$PKG" || true)
            if echo "$records" | grep -q 'ExternalSharingService' &&
               echo "$records" | grep -q 'app=ProcessRecord' && pid_live "$provider_pid"; then
                if ! echo "$records" | grep -q 'isFgsDelegate=true'; then
                    framework cmd activity set-foreground-service-delegate --user 0 "$PKG" start || true
                    records=$(active_services)
                    if echo "$records" | grep -q 'isFgsDelegate=true'; then launched_delegate=1; fi
                fi
                if echo "$records" | grep -q 'isFgsDelegate=true'; then provider_ready=1;break;fi
            fi
            sleep 1;n=$((n+1))
        done
        [ "$provider_ready" = 1 ] || exit 5
        if [ "$launched_delegate" = 1 ] || [ "${1:-}" = adopt ]; then boot_id > "$STATE/delegate-owned";fi
        echo "PROVIDER_READY pid=$(pidof "$PKG") foreground_delegate=yes"
        ;;
    release)
        framework pm disable-user --user 0 "$PKG"
        framework am force-stop --user 0 "$PKG"
        if same_boot "$STATE/delegate-owned"; then
            framework cmd activity set-foreground-service-delegate --user 0 "$PKG" stop || true
        fi
        rm -f "$STATE/delegate-owned"
        ;;
    *) echo 'Usage: provider.sh status|ensure|adopt|release';exit 2;;
esac
