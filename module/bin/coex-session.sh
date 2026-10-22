#!/system/bin/sh
# Own a UI/background coexistence session; never change Wi-Fi association.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
. "$MODDIR/bin/coex-wifi.sh"
init_state
P=/sys/module/qca_cld3_peach_v2/parameters
S="$STATE/coex-session"
knobs='passthru_sta_coexist passthru_awdl_allow_concurrent passthru_mgmt_home_channel passthru_awdl_amsdu passthru_awdl_phy passthru_awdl_mcs passthru_awdl_nss passthru_awdl_collect_us'
idle() {
    [ "$(cat "$P/opairshare_bridge_pending")" = 0 ] &&
    grep -q 'accepting=0 vdev=255 freq=0' "$P/opairshare_channel_state" &&
    grep -q '^used=0 armed=0 pending=0 active=0 fault=0' "$P/opairshare_awdl_rx_mac_filter"
}
# Every band inherits the selected TX profile, exactly as same149.
# Each session retains the user-selected performance profile.
# Rotated snapshots are kept only for inspection. Band switches rotate often,
# so bound the state directory instead of growing it for the device lifetime.
state_prune() {
    ls -1dt "$STATE"/coex-session.restored-* "$STATE"/coex-session.stale-* \
        "$STATE"/coex-session.incomplete-* 2>/dev/null | tail -n +9 |
        while IFS= read -r state_old; do rm -rf "$state_old" || true; done
    return 0
}
case "${1:-status}" in
    prepare)
        if [ "${OPAIRSHARE_SETTINGS_LOCK_HELD:-}" != 1 ];then
            exec 6> "$STATE/settings.flock"
            flock -n 6 6>&6 || { echo 'SETTINGS_BUSY retry next UI sample';exit 8; }
        fi
        idle
        [ ! -e "$STATE/callback-off" ] || { echo 'COEX_BLOCKED callback patch disabled';exit 8; }
        [ -z "$(getprop debug.opairshare.discovery)" ] && [ -z "$(getprop debug.opairshare.standby)" ] || exit 8
        [ ! -e "$S" ] && [ ! -e "$S.preparing" ] || exit 8
        if ! coex_wifi_eligible;then
            echo "COEX_BLOCKED reason=$coex_wifi_reason class=${coex_wifi_class:-unknown} frequency=${coex_wifi_frequency:-unknown}"
            exit 8
        fi
        freq=$coex_wifi_frequency
        class=$coex_wifi_class
        scope=$(coex_scope)
        mode=3
        case "$class" in
            same149) policy=same149;;
            cross5g|band24|band6) policy=auto;;
            *) exit 8;;
        esac
        mkdir -m 700 "$S.preparing"
        boot_id > "$S.preparing/boot"
        getprop debug.opairshare.discovery > "$S.preparing/discovery"
        getprop debug.opairshare.standby > "$S.preparing/standby"
        printf '%s\n' "$freq" > "$S.preparing/frequency"
        printf '%s\n' "$coex_wifi_network" > "$S.preparing/network"
        printf '%s\n' "$class" > "$S.preparing/class"
        printf '%s\n' "$scope" > "$S.preparing/scope"
        printf '%s\n' "$policy" > "$S.preparing/policy"
        for knob in $knobs;do cat "$P/$knob" > "$S.preparing/$knob";done
        mv "$S.preparing" "$S"
        # Snapshot is committed before the first mutation. Failed writes retain it.
        setprop debug.opairshare.discovery "$policy"
        echo "$mode" > "$P/passthru_sta_coexist"
        echo Y > "$P/passthru_awdl_allow_concurrent"
        echo Y > "$P/passthru_mgmt_home_channel"
        # Keep the selected fast profile across channels/bands.
        echo "COEX_UI_PREPARED frequency=$freq class=$class scope=$scope policy=$policy mode=$mode"
        ;;
    tick)
        [ -d "$S" ] || exit 0
        same_boot "$S/boot" || exit 8
        channel=$(cat "$P/opairshare_channel_state")
        matcher=$(cat "$P/opairshare_awdl_rx_mac_filter")
        case "$channel/$matcher" in
            *'accepting=1 '*'freq=5745/'*'armed=1 pending=0 active=1 fault=0 '*'freq=5745 '*)
                if [ "$(cat "$P/passthru_awdl_rx_ring_select")" != 1 ]; then
                    # Normal native Stop resets this session-owned selection.
                    echo 1 > "$P/passthru_awdl_rx_ring_select"
                fi;;
        esac
        ;;
    restore)
        [ -d "$S" ] || exit 0
        same_boot "$S/boot" || exit 8
        idle
        for knob in $knobs;do cat "$S/$knob" > "$P/$knob";done
        setprop debug.opairshare.discovery "$(cat "$S/discovery")"
        setprop debug.opairshare.standby "$(cat "$S/standby")"
        restored=$(mktemp -d "$S.restored-$(date +%s).XXXXXX")
        rmdir "$restored"
        mv "$S" "$restored"
        state_prune
        echo COEX_UI_RESTORED
        ;;
    prune) state_prune;;
    status)
        echo "coexist_enabled=$(if coex_available;then echo yes;else echo no;fi)"
        echo "coexist_requested=$(if coex_requested;then echo yes;else echo no;fi)"
        echo "coexist_session=$([ -d "$S" ] && echo owned || echo idle)"
        echo "coexist_scope=$(coex_scope)"
        echo "sta_class=$(if coex_wifi_read;then echo "$coex_wifi_class";else echo "${coex_wifi_class:-unknown}";fi)"
        echo 'supported_sta_mhz=2400-2484,5150-5925,5955-7125'
        ;;
    *) exit 2;;
esac
