#!/system/bin/sh
# Own a UI/background coexistence session; never change Wi-Fi association.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
. "$MODDIR/bin/coex-wifi.sh"
init_state
P=/sys/module/qca_cld3_peach_v2/parameters
S="$STATE/coex-session"
knobs='passthru_awdl_allow_concurrent passthru_mgmt_home_channel passthru_awdl_amsdu passthru_awdl_phy passthru_awdl_mcs passthru_awdl_nss passthru_awdl_collect_us'
idle() {
    [ "$(cat "$P/opairshare_bridge_pending")" = 0 ] &&
    grep -q 'accepting=0 vdev=255 freq=0' "$P/opairshare_channel_state" &&
    grep -q '^used=0 armed=0 pending=0 active=0 fault=0' "$P/opairshare_awdl_rx_mac_filter"
}
case "${1:-status}" in
    prepare)
        if [ "${OPAIRSHARE_SETTINGS_LOCK_HELD:-}" != 1 ];then
            exec 6> "$STATE/settings.flock"
            flock -n 6 6>&6 || { echo 'SETTINGS_BUSY retry next UI sample';exit 8; }
        fi
        [ ! -e "$STATE/coex-off" ] || exit 0
        idle
        [ ! -e "$STATE/callback-off" ] || { echo 'COEX_BLOCKED callback patch disabled';exit 8; }
        [ -z "$(getprop debug.opairshare.discovery)" ] && [ -z "$(getprop debug.opairshare.standby)" ] || exit 8
        [ ! -e "$S" ] && [ ! -e "$S.preparing" ] || exit 8
        if ! coex_wifi_read;then
            echo "COEX_BLOCKED same149-only reason=$coex_wifi_reason frequency=${coex_wifi_frequency:-unknown}"
            exit 8
        fi
        freq=$coex_wifi_frequency
        policy=same149
        mkdir -m 700 "$S.preparing"
        boot_id > "$S.preparing/boot"
        getprop debug.opairshare.discovery > "$S.preparing/discovery"
        getprop debug.opairshare.standby > "$S.preparing/standby"
        printf '%s\n' "$freq" > "$S.preparing/frequency"
        printf '%s\n' "$coex_wifi_network" > "$S.preparing/network"
        for knob in $knobs;do cat "$P/$knob" > "$S.preparing/$knob";done
        mv "$S.preparing" "$S"
        # Snapshot is committed before the first mutation. Failed writes retain it.
        setprop debug.opairshare.discovery "$policy"
        echo Y > "$P/passthru_awdl_allow_concurrent"
        echo Y > "$P/passthru_mgmt_home_channel"
        echo "COEX_UI_PREPARED frequency=$freq policy=$policy"
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
        mv "$S" "$S.restored-$(date +%s)"
        echo COEX_UI_RESTORED
        ;;
    on|off)
        [ ! -d "$S" ] || { echo 'Close the sharing UI and wait for normal Stop first';exit 8; }
        if [ "$1" = on ];then rm -f "$STATE/coex-off";else touch "$STATE/coex-off";fi
        ;;
    status)
        echo "coexist_enabled=$(if coex_available;then echo yes;else echo no;fi)"
        echo "coexist_requested=$(if coex_requested;then echo yes;else echo no;fi)"
        echo "coexist_session=$([ -d "$S" ] && echo owned || echo idle)"
        echo 'supported_sta_mhz=5745'
        ;;
    *) exit 2;;
esac
