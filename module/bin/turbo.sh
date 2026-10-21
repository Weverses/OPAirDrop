#!/system/bin/sh
# Stage62: retain existing on/off/vht profiles; fast is an explicit choice.
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
init_state
P=/sys/module/qca_cld3_peach_v2/parameters
set_p() { printf '%s' "$2" > "$P/$1"; }
apply_live() {
    [ -f "$P/opairshare_agg_stats" ] || return 0
    set_p passthru_awdl_amsdu 0 || return 1
    phy=2; mcs=7; count=4; cap=6000; nss=1; collect=0; amsdu=2
    case "$1" in
        off) phy=0; count=2; cap=3200; amsdu=0;;
        vht) phy=3; mcs=8;;
        fast) phy=3; mcs=8; count=5; cap=7935; nss=2; collect=1000;;
    esac
    set_p passthru_awdl_amsdu_max "$count" &&
    set_p passthru_awdl_amsdu_bytes "$cap" &&
    set_p passthru_awdl_mcs "$mcs" &&
    set_p passthru_awdl_phy "$phy" &&
    set_p passthru_awdl_nss "$nss" &&
    set_p passthru_awdl_collect_us "$collect" || return 1
    # Optional bandwidth experiment always returns to the tested 20MHz.
    if [ -f "$P/passthru_awdl_bw" ]; then set_p passthru_awdl_bw 20 || return 1; fi
    set_p passthru_awdl_amsdu "$amsdu"
}
choice=${1:-status}
case "$choice" in
    on|off|vht|fast)
        [ ! -d "$STATE/coex-session" ] || { echo 'Close sharing UI before changing TX profile';exit 8; }
        rm -f "$STATE/turbo-off" "$STATE/turbo-vht" "$STATE/turbo-fast"
        case "$choice" in off|vht|fast) touch "$STATE/turbo-$choice";; esac
        apply_live "$choice" || exit 1;;
    status) ;;
    *) echo "usage: $0 on|off|vht|fast|status"; exit 2;;
esac
if [ -e "$STATE/turbo-off" ]; then echo 'stored: off'
elif [ -e "$STATE/turbo-fast" ]; then echo 'stored: fast'
elif [ -e "$STATE/turbo-vht" ]; then echo 'stored: vht'
else echo 'stored: on'; fi
if [ -f "$P/opairshare_agg_stats" ]; then
    cat "$P/opairshare_agg_stats" "$P/opairshare_tx_pack_stats"
else echo 'driver not loaded (choice applies at next stack start)'; fi
