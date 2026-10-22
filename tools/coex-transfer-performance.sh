#!/system/bin/sh
# Experimental candidate retained to reproduce trials E/F; not deployed by the
# module. Trial F did not reproduce a gain. A tuple snapshot also cannot prove
# ownership across concurrent policy writers, same-BSSID reassociation or kill9.
PERF_STATE="$STATE/transfer-performance"
performance_link() {
    perf_ifindex=$(cat /sys/class/net/wlan0/ifindex 2>/dev/null) || return 1
    perf_link=$(/system/bin/timeout 2 /system/bin/iw dev wlan0 link 2>/dev/null) || return 1
    perf_bssid=$(printf '%s\n' "$perf_link" | sed -n 's/^Connected to \([0-9a-fA-F:]*\) .*/\1/p')
    perf_freq=$(printf '%s\n' "$perf_link" | sed -n 's/^[[:space:]]*freq: \([0-9]*\).*/\1/p')
    [ -n "$perf_bssid" ] && [ -n "$perf_freq" ]
}
performance_power() {
    /system/bin/timeout 2 /system/bin/iw dev wlan0 get power_save 2>/dev/null |
        sed -n 's/^Power save: //p'
}
performance_restore() {
    [ -d "$PERF_STATE" ] || return 0
    if ! same_boot "$PERF_STATE/boot"; then
        rm -rf "$PERF_STATE"
        return 0
    fi
    if [ ! -e /sys/class/net/wlan0/ifindex ]; then
        rm -rf "$PERF_STATE"
        return 0
    fi
    performance_link || return 0
    if [ "$perf_ifindex" != "$(cat "$PERF_STATE/ifindex")" ] ||
       [ "$perf_bssid" != "$(cat "$PERF_STATE/bssid")" ] ||
       [ "$perf_freq" != "$(cat "$PERF_STATE/frequency")" ]; then
        # A new association owns its own power policy.
        rm -rf "$PERF_STATE"
        return 0
    fi
    perf_power=$(performance_power)
    case "$perf_power" in
        off)
            /system/bin/timeout 2 /system/bin/iw dev wlan0 set power_save on || return 0
            [ "$(performance_power)" = on ] || return 0
            echo 'TRANSFER_POWER_SAVE_RESTORED'
            ;;
        on) ;;
        *) return 0;;
    esac
    rm -rf "$PERF_STATE"
}
performance_tick() {
    case "$1" in
        idle|pending) performance_restore; return 0;;
        unknown)
            if [ -f "$PERF_STATE/last-busy" ]; then
                perf_last=$(cat "$PERF_STATE/last-busy")
                case "$perf_last" in ''|*[!0-9]*) performance_restore;;
                    *) [ $((t-perf_last)) -le 15000 ] || performance_restore;;
                esac
            fi
            return 0;;
        busy) ;;
        *) return 0;;
    esac
    if [ -d "$PERF_STATE" ]; then
        if same_boot "$PERF_STATE/boot"; then
            printf '%s\n' "$t" > "$PERF_STATE/last-busy"
            return 0
        fi
        rm -rf "$PERF_STATE"
    fi
    same_boot "$STATE/coex-session/boot" || return 0
    [ "$(cat "$STATE/coex-session/class" 2>/dev/null)" = cross5g ] || return 0
    [ ! -e "$STATE/coex-session/invalidated" ] || return 0
    performance_link || return 0
    # Only the measured cross-5GHz case. Preserve pre-existing power-save off.
    case "$perf_freq" in 5745) return 0;; esac
    [ "$perf_freq" -ge 5150 ] && [ "$perf_freq" -le 5925 ] || return 0
    [ "$(performance_power)" = on ] || return 0
    rm -rf "$PERF_STATE.preparing"
    mkdir -m 700 "$PERF_STATE.preparing" || return 0
    boot_id > "$PERF_STATE.preparing/boot"
    printf '%s\n' "$perf_ifindex" > "$PERF_STATE.preparing/ifindex"
    printf '%s\n' "$perf_bssid" > "$PERF_STATE.preparing/bssid"
    printf '%s\n' "$perf_freq" > "$PERF_STATE.preparing/frequency"
    printf '%s\n' "$t" > "$PERF_STATE.preparing/last-busy"
    mv "$PERF_STATE.preparing" "$PERF_STATE" || return 0
    if /system/bin/timeout 2 /system/bin/iw dev wlan0 set power_save off &&
       [ "$(performance_power)" = off ]; then
        echo "TRANSFER_POWER_SAVE_OFF frequency=$perf_freq"
    else
        performance_restore
    fi
    return 0
}
