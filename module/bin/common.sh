#!/system/bin/sh
STATE=/data/adb/opairshare
PKG=com.google.android.mosey
COMP=$PKG/.ExternalSharingService
SERVICE=com.google.android.moseyservice.IMoseyService/default
boot_id() { cat /proc/sys/kernel/random/boot_id; }
loaded() { grep -q "^$1 " /proc/modules; }
same_boot() { [ -r "$1" ] && [ "$(cat "$1")" = "$(boot_id)" ]; }
module_note() { od -An -tx1 "/sys/module/$1/notes/.note.gnu.build-id" 2>/dev/null | tr -d ' \n'; }
pid_live() {
    case "$1" in ''|*[!0-9]*) return 1;; esac
    [ -r "/proc/$1/stat" ] || return 1
    read -r ps_pid ps_name ps_state ps_rest < "/proc/$1/stat" || return 1
    case "$ps_state" in Z|X) return 1;; esac
}
pid_is() {
    pid_live "$1" || return 1
    timeout 2 grep -aFq -- "$2" "/proc/$1/cmdline" 2>/dev/null
}
init_state() { mkdir -p "$STATE"; chmod 700 "$STATE"; }
framework() {
    # KernelSU standalone ash prefers BusyBox applets even with Android PATH.
    # Pin framework tools; Binder commands still receive pipe FDs via capture.
    fw_tool=$1; shift
    case "$fw_tool" in
        timeout)
            fw_seconds=$1; shift
            fw_child=$1; shift
            case "$fw_child" in
                am|cmd|dumpsys|pm|service|svc) ;;
                *) echo "Unsupported timed framework tool: $fw_child" >&2; return 2;;
            esac
            set -- /system/bin/timeout "$fw_seconds" "/system/bin/$fw_child" "$@"
            ;;
        am|cmd|dumpsys|pm|service|svc) set -- "/system/bin/$fw_tool" "$@";;
        /*) set -- "$fw_tool" "$@";;
        *) echo "Unsupported framework tool: $fw_tool" >&2; return 2;;
    esac
    if fw_output=$("$@" 2>&1); then fw_rc=0; else fw_rc=$?; fi
    printf '%s\n' "$fw_output"
    return "$fw_rc"
}
wifi_restore() {
    if [ "$1" = 1 ]; then framework svc wifi enable; else framework svc wifi disable; fi
}
wifi_state() {
    wifi_text=$(framework cmd wifi status)
    case "$wifi_text" in
        'Wifi is enabled'*) echo 1;;
        'Wifi is disabled'*) echo 0;;
        *) echo 'Cannot determine runtime Wi-Fi state' >&2; return 3;;
    esac
}
sysext_path() {
    if [ -f "$MODDIR/system/system_ext/$1" ]; then echo "$MODDIR/system/system_ext/$1";
    else echo "$MODDIR/system_ext/$1"; fi
}
active_services() {
    framework timeout 8 dumpsys activity services "$PKG" |
        sed -n '/  User 0 active services:/,$p'
}
kernel_matches() {
    [ "$(cat /sys/module/qca_cld3_peach_v2/parameters/passthru_native_data_rx)" = Y ] &&
    [ "$(cat /sys/module/qca_cld3_peach_v2/parameters/passthru_awdl_raw_rx)" = Y ] &&
    [ "$(cat /sys/module/qca_cld3_peach_v2/parameters/passthru_awdl_backpressure)" = Y ] &&
    [ "$(cat /sys/module/qca_cld3_peach_v2/parameters/passthru_awdl_target_auto)" = Y ] &&
    [ "$(cat /sys/module/qca_cld3_peach_v2/parameters/passthru_awdl_rx_auto)" = Y ] &&
    [ "$(cat /sys/module/qca_cld3_peach_v2/parameters/passthru_awdl_rx_rearm)" = Y ] &&
    [ "$(cat /sys/module/qca_cld3_peach_v2/parameters/passthru_sta_coexist)" = 2 ] &&
    [ "$(cat /sys/module/qca_cld3_peach_v2/parameters/passthru_sta_regular_rx)" = Y ] &&
    [ "$(cat /sys/module/qca_cld3_peach_v2/parameters/passthru_sta_action_rx)" = Y ] &&
    [ "$(module_note qca_cld3_peach_v2)" = "$NOTE_qca_cld3_peach_v2" ] &&
    [ "$(module_note mac80211)" = "$NOTE_mac80211" ] &&
    [ "$(module_note wonder)" = "$NOTE_wonder" ] &&
    [ "$(module_note opairshare_wonder_provider)" = "$NOTE_opairshare_wonder_provider" ] &&
    [ "$(cat /sys/module/qca_cld3_peach_v2/parameters/passthru_awdl_wmi_raw)" = Y ]
}
turbo_module_args() {
    # Persistent choice made with bin/turbo.sh; default is ON.
    [ -e "$STATE/turbo-off" ] && return 0
    if [ -e "$STATE/turbo-fast" ]; then
        echo "passthru_awdl_amsdu=2 passthru_awdl_amsdu_max=5 passthru_awdl_amsdu_bytes=7935 passthru_awdl_phy=3 passthru_awdl_mcs=8 passthru_awdl_nss=2 passthru_awdl_collect_us=1000"
        return 0
    fi
    if [ -e "$STATE/turbo-vht" ]; then phy=3; mcs=8; else phy=2; mcs=7; fi
    echo "passthru_awdl_amsdu=2 passthru_awdl_amsdu_max=4 passthru_awdl_amsdu_bytes=6000 passthru_awdl_phy=$phy passthru_awdl_mcs=$mcs"
}
