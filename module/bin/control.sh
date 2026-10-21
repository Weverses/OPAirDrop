#!/system/bin/sh
# PJZ110 stage23 data path + stage24 lifecycle; original CNSS always retained.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
. "$MODDIR/bin/kernel-identity.sh"
init_state
preflight() {
    [ "$(id -u)" = 0 ] && [ "$(getprop ro.product.model)" = PJZ110 ] || return 2
    [ "$(getprop ro.build.version.sdk)" = 37 ] || return 2
    [ "$(uname -r)" = '6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k' ] || return 2
}
lock_stack() {
    exec 9> "$STATE/stack.flock"
    flock -n 9 9>&9 || { echo 'Another stack operation is running'; return 9; }
    trap 'exec 9>&-' EXIT
}
start_supervisor() {
    owner=$(cat "$STATE/supervisor.pid" 2>/dev/null || true)
    if ! pid_is "$owner" 'ui-lifecycle.sh'; then
        printf '\nSUPERVISOR_START boot=%s time=%s\n' "$(boot_id)" "$(date '+%Y-%m-%dT%H:%M:%S%z')" >> "$STATE/supervisor.log"
        /system/bin/sh "$MODDIR/bin/ui-lifecycle.sh" >> "$STATE/supervisor.log" 2>&1 < /dev/null 9>&- &
        owner=$!
        echo "$owner" > "$STATE/supervisor.pid"
        n=0
        while [ "$n" -lt 8 ];do
            if [ "$(cat "$STATE/supervisor-ready" 2>/dev/null || true)" = "$(boot_id) $owner" ] &&
               pid_is "$owner" 'ui-lifecycle.sh';then return 0;fi
            pid_live "$owner" || return 6
            sleep 1;n=$((n+1))
        done
        return 6
    fi
}
stop_supervisor() {
    /system/bin/sh "$MODDIR/bin/receive-session.sh" clear || return 8
    owner=$(cat "$STATE/supervisor.pid" 2>/dev/null || true)
    if pid_is "$owner" 'ui-lifecycle.sh'; then kill "$owner" || true; fi
    n=0
    while pid_is "$owner" 'ui-lifecycle.sh' && [ "$n" -lt 30 ]; do sleep 1; n=$((n+1)); done
    if pid_is "$owner" 'ui-lifecycle.sh'; then echo 'Supervisor has not exited'; return 9; fi
}
pause_wifi_hal() {
    hal_state=$(getprop init.svc.vendor.wifi_hal_legacy)
    case "$hal_state" in running) boot_id > "$STATE/hal-paused";; stopped) ;; *) return 4;; esac
    /system/bin/stop vendor.wifi_hal_legacy
    n=0
    while [ "$(getprop init.svc.vendor.wifi_hal_legacy)" != stopped ] && [ "$n" -lt 10 ]; do sleep 1;n=$((n+1));done
    [ "$(getprop init.svc.vendor.wifi_hal_legacy)" = stopped ] || return 4
    startup_trace HAL_STOPPED
}
resume_wifi_hal() {
    if same_boot "$STATE/hal-paused"; then
        /system/bin/start vendor.wifi_hal_legacy
        n=0
        while [ "$(getprop init.svc.vendor.wifi_hal_legacy)" != running ] && [ "$n" -lt 10 ]; do sleep 1;n=$((n+1));done
        [ "$(getprop init.svc.vendor.wifi_hal_legacy)" = running ] || return 4
        rm -f "$STATE/hal-paused"
        startup_trace HAL_RUNNING
    fi
}
restore_session_wifi() {
    if [ -e "$STATE/session-wifi" ]; then
        CLASSPATH="$MODDIR/payload/native/session-wifi.jar" /system/bin/timeout -k 2 18 /system/bin/app_process /system/bin OPAirShareSessionWifi restore "$STATE/session-wifi" || return 8
    fi
    /system/bin/sh "$MODDIR/bin/coex-session.sh" restore || return 8
    /system/bin/sh "$MODDIR/bin/receive-session.sh" release-host || return 8
    rm -f "$STATE/ui-session"
}
retire_old_ui_state() {
    if [ -d "$STATE/coex-session" ] && [ "$(cat "$STATE/coex-session/boot")" != "$(boot_id)" ]; then
        mv "$STATE/coex-session" "$STATE/coex-session.stale-$(boot_id)" || return 8
    fi
    if [ -d "$STATE/coex-session.preparing" ]; then
        mv "$STATE/coex-session.preparing" "$STATE/coex-session.incomplete-$(date +%s)" || return 8
    fi
    for state_name in ui-session session-wifi;do
        state_file="$STATE/$state_name"
        if [ -e "$state_file" ];then
            first_boot=$(head -n 1 "$state_file")
            if [ "$first_boot" != "$(boot_id)" ];then
                mv "$state_file" "$state_file.stale-$(boot_id)" || return 8
            fi
        fi
    done
}
passive_ui_baseline() {
    /system/bin/sh "$MODDIR/bin/receive-session.sh" clear || return 8
    /system/bin/sh "$MODDIR/bin/transfer-idle.sh" || return 9
    /system/bin/timeout -k 2 35 /system/bin/sh "$MODDIR/bin/provider.sh" release || return 6
    [ -z "$(pidof "$PKG" || true)" ] || return 6
    reply=$(framework timeout 15 service call "$SERVICE" 2 i32 1 i32 4) || return 6
    echo "$reply" | grep -q fffffff && return 6
    P=/sys/module/qca_cld3_peach_v2/parameters
    [ "$(cat "$P/opairshare_bridge_pending")" = 0 ] || return 7
    grep -q 'busy=0 fault=0 error=0 accepting=0 vdev=255 freq=0' "$P/opairshare_channel_state" || return 7
    grep -q 'used=0 armed=0 pending=0 active=0 fault=0' "$P/opairshare_awdl_rx_mac_filter" || return 7
    restore_session_wifi || return 8
}
start_native() {
    touch /apex/apex-info-list.xml
    OPAIRSHARE_ENABLE_HOPPING=1 OPAIRSHARE_SOCIAL_CHANNELS=149 \
        OPAIRSHARE_CONFIG_TRACE="$STATE/config-trace.log" \
        LD_PRELOAD="$MODDIR/payload/native/libopairshare_capability.so:$MODDIR/payload/native/libopairshare_config.so" \
        /system_ext/bin/mosey_server >> "$STATE/server.log" 2>&1 < /dev/null 9>&- &
    server=$!
    echo "$server" > "$STATE/server.pid"
    boot_id > "$STATE/server-boot"
    n=0
    while [ "$n" -lt 15 ]; do
        if framework service check "$SERVICE" | grep -q ': found'; then startup_trace NATIVE_BINDER_READY; return 0; fi
        kill -0 "$server" || return 4
        sleep 1; n=$((n+1))
    done
    return 5
}
stop_native() {
    if framework service check "$SERVICE" | grep -q ': found'; then
        result=$(framework timeout 15 service call "$SERVICE" 2 i32 1 i32 4) || { echo "$result"; return 6; }
        echo "$result" > "$STATE/native-stop.txt"
        if echo "$result" | grep -q 'fffffff'; then echo 'Native stop reported an exception'; return 6; fi
    fi
    if loaded qca_cld3_peach_v2 && [ -e /sys/module/qca_cld3_peach_v2/parameters/opairshare_bridge_pending ]; then
        [ "$(cat /sys/module/qca_cld3_peach_v2/parameters/opairshare_bridge_pending)" = 0 ] || return 7
    fi
    server=$(cat "$STATE/server.pid" 2>/dev/null || true)
    if same_boot "$STATE/server-boot" && pid_is "$server" '/system_ext/bin/mosey_server'; then
        kill "$server"
        n=0
        while pid_live "$server" && [ "$n" -lt 10 ]; do sleep 1; n=$((n+1)); done
        pid_live "$server" && return 8
    fi
    rm -f "$STATE/server.pid" "$STATE/server-boot"
}
restore_state_allowed() {
    if loaded wonder; then [ "$(module_note wonder)" = "$NOTE_wonder" ] || return 3; fi
    if loaded opairshare_wonder_provider; then [ "$(module_note opairshare_wonder_provider)" = "$NOTE_opairshare_wonder_provider" ] || return 3; fi
    if loaded qca_cld3_peach_v2; then
        note=$(module_note qca_cld3_peach_v2)
        [ "$note" = "$NOTE_qca_cld3_peach_v2" ] || [ "$note" = "$STOCK_NOTE_qca_cld3_peach_v2" ] || return 3
    fi
    if loaded mac80211; then
        note=$(module_note mac80211)
        [ "$note" = "$NOTE_mac80211" ] || [ "$note" = "$STOCK_NOTE_mac80211" ] || return 3
    fi
}
restore_owned() {
    restore_state_allowed || return 3
    /system/bin/sh "$MODDIR/bin/provider.sh" release
    stop_native || return $?
    restore_session_wifi || return 8
    if loaded wonder; then rmmod wonder || return 10; fi
    framework svc wifi disable
    wifi_wait 0 || return 4
    sleep 6
    pause_wifi_hal || return 4
    if same_boot "$STATE/qca-swapped"; then
        if loaded qca_cld3_peach_v2 && [ "$(module_note qca_cld3_peach_v2)" = "$NOTE_qca_cld3_peach_v2" ]; then
            rmmod qca_cld3_peach_v2 || return 11
        fi
        if loaded opairshare_wonder_provider; then rmmod opairshare_wonder_provider || return 12; fi
        if ! loaded qca_cld3_peach_v2; then insmod /vendor/lib/modules/qca_cld3_peach_v2.ko || return 13; fi
        [ "$(module_note qca_cld3_peach_v2)" = "$STOCK_NOTE_qca_cld3_peach_v2" ] || return 13
        rm -f "$STATE/qca-swapped" "$STATE/provider-added"
    elif same_boot "$STATE/provider-added"; then
        if loaded opairshare_wonder_provider; then rmmod opairshare_wonder_provider || return 12; fi
        rm -f "$STATE/provider-added"
    fi
    if same_boot "$STATE/mac-swapped"; then
        if loaded mac80211 && [ "$(module_note mac80211)" = "$NOTE_mac80211" ]; then rmmod mac80211 || return 14; fi
        if ! loaded mac80211; then insmod /vendor/lib/modules/mac80211.ko || return 15; fi
        [ "$(module_note mac80211)" = "$STOCK_NOTE_mac80211" ] || return 15
        rm -f "$STATE/mac-swapped"
    fi
    resume_wifi_hal || return 4
    desired=$(cat "$STATE/wifi-before" 2>/dev/null || echo 0)
    wifi_restore "$desired"
    rm -f "$STATE/stack-boot" "$STATE/restoring"
    echo 'STOCK_STACK_RESTORED'
}
rx_auto_wait() {
    P=/sys/module/qca_cld3_peach_v2/parameters
    n=0
    while [ "$n" -lt 20 ]; do
        rx=$(cat "$P/opairshare_awdl_rx_mac_filter")
        channel=$(cat "$P/opairshare_channel_state")
        if printf '%s\n' "$rx" | grep -q 'used=0 armed=0 pending=0 active=0 fault=0' &&
           printf '%s\n' "$channel" | grep -q 'busy=0 fault=0 error=0 accepting=0 vdev=255 freq=0';then
            echo 'RX_AUTO_ARMED: waiting for the first App radio session; not active yet'
            return 0
        fi
        if printf '%s\n' "$rx" | grep -q 'active=1 fault=0 .*freq=5745 reply=1 error=0' &&
           printf '%s\n' "$channel" | grep -q 'busy=0 fault=0 error=0 accepting=1 .*freq=5745';then
            echo 'RX_AUTO_READY: true firmware reply; fixed149'
            return 0
        fi
        sleep 1;n=$((n+1))
    done
    echo "RX auto not confirmed: $rx / $channel; uncertain RX state; explicit Stop/Start recovery required"
    return 6
}
activate_provider() {
    # Passive native service stays resident. Only the visible QuickShare UI
    # owns the original provider; background receiving cannot restart radio.
    owner=$(cat "$STATE/supervisor.pid" 2>/dev/null || true)
    if pid_is "$owner" 'ui-lifecycle.sh';then return 0;fi
    retire_old_ui_state || return 8
    passive_ui_baseline || return $?
    /system/bin/sh "$MODDIR/bin/callback-bootstrap.sh" || return $?
    passive_ui_baseline || return $?
    start_supervisor
}

ready_message() { echo AIRDROP_RESIDENT_READY_UI_GATED; }
case "${1:-status}" in
    status)
        echo "model=$(getprop ro.product.model) sdk=$(getprop ro.build.version.sdk) kernel=$(uname -r)"
        if same_boot "$STATE/stack-boot" && kernel_matches; then echo stack=stage44_coexist_owned; else echo stack=not_owned; fi
        framework service check "$SERVICE"
        /system/bin/sh "$MODDIR/bin/provider.sh" status
        /system/bin/sh "$MODDIR/bin/callback.sh" status
        echo "supervisor_pid=$(cat "$STATE/supervisor.pid" 2>/dev/null || true)"
        framework cmd wifi status | head -n 6
        ;;
    adopt)
        preflight; lock_stack
        kernel_matches || { echo 'Live kernel identity does not match the successful payload'; exit 3; }
        server=$(pidof mosey_server || true)
        case "$server" in ''|*[!0-9]*) exit 3;; esac
        pid_is "$server" '/system_ext/bin/mosey_server' || exit 3
        wifi_before=$(wifi_state)
        case "$wifi_before" in 0|1) ;; *) echo 'Invalid Wi-Fi state'; exit 3;; esac
        printf '%s\n' "$wifi_before" > "$STATE/wifi-before"
        for marker in stack-boot qca-swapped mac-swapped provider-added server-boot; do boot_id > "$STATE/$marker"; done
        echo "$server" > "$STATE/server.pid"
        rm -f "$STATE/disabled"
        activate_provider
        echo 'SUCCESSFUL_LIVE_STACK_ADOPTED'
        ;;
    start)
        preflight; lock_stack
        /system/bin/sh "$MODDIR/bin/transfer-idle.sh"
        /system/bin/sh "$MODDIR/bin/grant-permissions.sh"
        rm -f "$STATE/disabled"
        if same_boot "$STATE/stack-boot"; then
            kernel_matches || exit 3
            server=$(cat "$STATE/server.pid" 2>/dev/null || true)
            if ! pid_is "$server" '/system_ext/bin/mosey_server'; then start_native; fi
            activate_provider; ready_message; exit 0
        fi
        for m in wonder opairshare_wonder_provider opairshare_hw_probe; do
            if loaded "$m"; then echo "Unexpected live module $m"; exit 3; fi
        done
        [ -z "$(pidof mosey_server || true)" ] || { echo 'Existing native server; use explicit adopt only for verified state'; exit 3; }
        grep -q '^qca_cld3_peach_v2 12365824 ' /proc/modules || exit 3
        grep -q '^mac80211 1093632 0 ' /proc/modules || exit 3
        [ "$(module_note qca_cld3_peach_v2)" = "$STOCK_NOTE_qca_cld3_peach_v2" ] || exit 3
        [ "$(module_note mac80211)" = "$STOCK_NOTE_mac80211" ] || exit 3
        loaded cnss2 || exit 3
        wifi_before=$(wifi_startup_state)
        case "$wifi_before" in 0|1) ;; *) echo 'Invalid Wi-Fi state'; exit 3;; esac
        printf '%s\n' "$wifi_before" > "$STATE/wifi-before"
        boot_id > "$STATE/stack-boot"
        touch "$STATE/startup-incomplete"
        fail_cleanup() { rc=$?; trap - EXIT INT TERM; boot_id > "$STATE/restoring"; restore_owned || true; resume_wifi_hal || true; touch "$STATE/disabled"; exec 9>&-; exit "$rc"; }
        trap fail_cleanup EXIT
        trap 'exit 130' INT
        trap 'exit 143' TERM
        framework am force-stop --user 0 "$PKG"
        startup_trace WIFI_STOP_REQUEST
        framework svc wifi disable
        wifi_wait 0
        startup_trace WIFI_STOP_CONFIRMED
        sleep 6
        pause_wifi_hal
        rmmod qca_cld3_peach_v2
        boot_id > "$STATE/qca-swapped"
        insmod "$MODDIR/payload/kernel/opairshare_wonder_provider.ko"
        boot_id > "$STATE/provider-added"
        turbo_args=$(turbo_module_args)
        insmod "$MODDIR/payload/kernel/qca_cld3_peach_v2.ko" \
            passthru_awdl_addba=1 passthru_awdl_native_rx=1 passthru_ht=1 passthru_fwtype=7 passthru_mon_rings=0 passthru_dry_run=0 \
            passthru_mon_chan_sync=1 passthru_ndi_peer=1 passthru_ndi_no_up=1 \
            passthru_mgmt_bridge=1 passthru_aux_monitor=1 passthru_tx_audit=1 \
            passthru_ndi_fw_authorize=0 passthru_awdl_wmi_raw=1 \
            passthru_awdl_target_mac="" passthru_awdl_target_auto=1 passthru_awdl_target_enable=1 passthru_awdl_target_auth=2 \
            passthru_native_data_rx=1 passthru_awdl_backpressure=1 passthru_awdl_raw_rx=1 passthru_awdl_rate_mbps=54 \
            passthru_awdl_host_seq=1 passthru_awdl_rx_dedup=1 passthru_awdl_attempts=3 passthru_awdl_rx_auto=1 passthru_awdl_rx_rearm=1 passthru_sta_coexist=2 passthru_sta_regular_rx=1 passthru_sta_action_rx=1 passthru_pas_discovery_actions=1 $turbo_args
        rmmod mac80211
        boot_id > "$STATE/mac-swapped"
        insmod "$MODDIR/payload/kernel/mac80211.ko"
        insmod "$MODDIR/payload/kernel/wonder.ko" physical_name=wondertap0 virtual_name=wonder0
        kernel_matches || exit 3
        startup_trace DRIVER_SWAP_DONE
        # Finish loading the entire stack before allowing framework association.
        resume_wifi_hal
        wifi_restore "$(cat "$STATE/wifi-before")"
        start_native
        activate_provider
        rm -f "$STATE/startup-incomplete"
        trap 'exec 9>&-' EXIT
        trap - INT TERM
        ready_message
        ;;
    stop)
        lock_stack
        /system/bin/sh "$MODDIR/bin/transfer-idle.sh"
        touch "$STATE/disabled"
        stop_supervisor
        if ! same_boot "$STATE/stack-boot"; then echo 'No stack owned during this boot; driver unchanged'; exit 0; fi
        restore_state_allowed || { echo 'Unexpected kernel identity; refusing to unload'; exit 3; }
        if ! same_boot "$STATE/restoring"; then
            desired=$(wifi_state)
            printf '%s\n' "$desired" > "$STATE/wifi-before"
            boot_id > "$STATE/restoring"
        fi
        if restore_owned; then :
        else rc=$?;resume_wifi_hal || true;exit "$rc";fi
        ;;
    autostart-on)
        preflight;lock_stack
        kernel_matches && same_boot "$STATE/stack-boot" || exit 3
        [ ! -e "$STATE/startup-incomplete" ] || exit 3
        touch "$STATE/autostart";echo AUTOSTART_ENABLED
        ;;
    autostart-off)
        lock_stack;rm -f "$STATE/autostart";echo AUTOSTART_DISABLED
        ;;
    repair-provider)
        same_boot "$STATE/stack-boot" && kernel_matches || exit 3
        activate_provider
        ;;
    *) echo 'Usage: control.sh status|start|stop|adopt|repair-provider'; exit 2;;
esac
