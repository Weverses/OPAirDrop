#!/system/bin/sh
# User-requested PS-off variant: every supported STA band/channel during transfer.
# Source after common.sh/init_state. Restore the prior on state when idle.
PERF_STATE="$STATE/transfer-power"
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
        if same_boot "$PERF_STATE/boot" && performance_link &&
           [ "$perf_ifindex" = "$(cat "$PERF_STATE/ifindex")" ] &&
           [ "$perf_bssid" = "$(cat "$PERF_STATE/bssid")" ] &&
           [ "$perf_freq" = "$(cat "$PERF_STATE/frequency")" ]; then
            printf '%s\n' "$t" > "$PERF_STATE/last-busy"
            # Wi-Fi policy may turn PS back on during a long transfer.
            if [ "$(performance_power)" = on ]; then
                /system/bin/timeout 2 /system/bin/iw dev wlan0 set power_save off || true
            fi
            return 0
        fi
        # Reassociation gets a fresh snapshot; do not restore the old link's PS.
        rm -rf "$PERF_STATE"
    fi
    same_boot "$STATE/coex-session/boot" || return 0
    [ ! -e "$STATE/coex-session/invalidated" ] || return 0
    performance_link || return 0
    # Same149 and every cross-channel class use the same PS-off policy.
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
        echo "TRANSFER_POWER_SAVE_OFF_ALL_CHANNELS frequency=$perf_freq"
    else
        performance_restore
    fi
    return 0
}
