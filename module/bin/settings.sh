#!/system/bin/sh
# Module WebUI API: fixed commands and enums; no user-provided shell text.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
. "$MODDIR/bin/coex-wifi.sh"
. "$MODDIR/bin/airdrop-name.sh"
. "$MODDIR/bin/receive-directory.sh"
. "$MODDIR/bin/webui-status.sh"
. "$MODDIR/bin/share-mode.sh"
init_state
P=/sys/module/qca_cld3_peach_v2/parameters
fail() { echo "$*" >&2;exit 8; }
flag() { [ -e "$STATE/$1" ] && echo on || echo off; }
profile() {
    if [ -e "$STATE/turbo-off" ];then echo off
    elif [ -e "$STATE/turbo-fast" ];then echo fast
    elif [ -e "$STATE/turbo-vht" ];then echo vht
    else echo on;fi
}
coexist() { echo on; }
scope() { echo auto; }
read_p() { if [ -r "$P/$1" ];then head -c 2048 "$P/$1";fi; }
json_string() {
    # Data is rendered as text in the page; quote strings even for unusual SSIDs.
    printf '%s' "$1" | LC_ALL=C awk 'BEGIN {printf "\""}
      {if(NR>1)printf "\\n";for(i=1;i<=length($0);i++) {
        c=substr($0,i,1);
        if(c=="\\")printf "\\\\";else if(c=="\"")printf "\\\"";
        else if(c=="\t")printf "\\t";else if(c=="\r")printf "\\r";
        else if(c ~ /[[:cntrl:]]/)printf "?";else printf "%s",c;
      }} END {printf "\""}'
}
pair() { printf '"%s":' "$1";json_string "$2"; }
status() {
    coex_wifi_read || true
    wifi=$coex_wifi_text
    freq=$(printf '%s\n' "$wifi" | sed -n 's/.*Frequency: \([0-9][0-9]*\)MHz.*/\1/p' | head -1)
    channel=$(awk -v f="${freq:-0}" 'BEGIN {
        if (f == 2484) print 14;
        else if (f >= 2412 && f <= 2472) printf "%d\n", (f - 2407) / 5;
        else if (f >= 5955 && f <= 7115) printf "%d\n", (f - 5950) / 5;
        else if (f >= 5150 && f <= 5895) printf "%d\n", (f - 5000) / 5;
        else print "unknown";
    }')
    connected=$(printf '%s\n' "$wifi" | sed -n 's/^Wifi is connected to //p' | head -1)
    sta_class=${coex_wifi_class:-unknown}
    sta_band=${coex_wifi_band:-unknown}
    coexist_active=off
    coexist_reason=wifi_unavailable
    if coex_requested;then
        coexist_reason=$coex_wifi_reason
        if [ -e "$STATE/coex-wifi-blocked" ];then
            coexist_reason=$(cat "$STATE/coex-wifi-blocked" 2>/dev/null || echo wifi_unavailable)
        elif [ -e "$STATE/coex-session/invalidated" ];then
            coexist_reason=recovering
        elif [ -n "$coexist_reason" ];then
            :
        else coexist_active=on;coexist_reason=
        fi
    fi
    version=$(sed -n 's/^version=//p' "$MODDIR/module.prop")
    name_hex=$(read_name_hex)
    name_default=$(default_airdrop_name)
    name_custom=$(decode_name_hex "$name_hex")
    name_effective=${name_custom:-$name_default}
    radio=$(read_p opairshare_channel_state);matcher=$(read_p opairshare_awdl_rx_mac_filter)
    ready=off
    owner=$(cat "$STATE/supervisor.pid" 2>/dev/null || true)
    if same_boot "$STATE/stack-boot" && pid_is "$owner" 'ui-lifecycle.sh';then ready=on;fi
    session=off
    if [ -e "$STATE/ui-session" ] || [ -d "$STATE/coex-session" ] || [ -d "$STATE/coex-session.preparing" ];then session=on;fi
    editable=on
    if [ "$session" = on ];then editable=off;fi
    if [ -e "$P/opairshare_channel_state" ];then
        if [ "$(read_p opairshare_bridge_pending)" != 0 ] ||
           ! printf '%s\n' "$radio" | grep -q 'accepting=0 vdev=255 freq=0' ||
           ! printf '%s\n' "$matcher" | grep -q '^used=0 armed=0 pending=0 active=0 fault=0';then editable=off;fi
    fi
    quickshare=$(app_state com.google.android.mosey)
    oshare=$(app_state com.coloros.oshare)
    awdl=stopped
    if [ -r "$P/opairshare_channel_state" ] && [ -n "$(pidof mosey_server 2>/dev/null || true)" ];then awdl=running;fi
    json_object \
        share_mode "$(saved_share_mode)" share_mode_active "$(active_share_mode)" \
        receive_directory "$(decode_name_hex "$(read_receive_directory_hex)")" \
        awdl "$awdl" \
        quickshare "$quickshare" \
        oshare "$oshare" \
        version "$version" \
        coexist "$coexist_active" \
        coexist_requested "$(coexist)" \
        coexist_reason "$coexist_reason" \
        airdrop_name "$name_effective" \
        airdrop_name_default "$name_default" \
        airdrop_name_custom "$name_custom" \
        scope "$(scope)" \
        profile "$(profile)" \
        autostart "$(flag autostart)" \
        session "$session" \
        editable "$editable" \
        ready "$ready" \
        frequency "${freq:-unknown}" \
        channel "$channel" \
        sta_class "$sta_class" \
        sta_band "$sta_band" \
        network "$connected" \
        radio "$radio" \
        matcher "$matcher" \
        pending "$(read_p opairshare_bridge_pending)" \
        phy "$(read_p passthru_awdl_phy)" \
        mcs "$(read_p passthru_awdl_mcs)" \
        nss "$(read_p passthru_awdl_nss)" \
        batch "$(read_p passthru_awdl_amsdu_max)" \
        collect "$(read_p passthru_awdl_collect_us)" \
        callback "$([ -e "$STATE/callback-off" ] && echo off || echo on)"
}
idle() {
    [ ! -e "$STATE/ui-session" ] && [ ! -d "$STATE/coex-session" ] &&
    [ ! -d "$STATE/coex-session.preparing" ] || fail '请先退出一加互传 / Quick Share，等待发现会话结束。'
    /system/bin/sh "$MODDIR/bin/transfer-idle.sh" || fail '有传输进行中或状态无法确认，请结束传输后再保存。'
    if [ -e "$P/opairshare_channel_state" ];then
        [ "$(read_p opairshare_bridge_pending)" = 0 ] &&
        grep -q 'accepting=0 vdev=255 freq=0' "$P/opairshare_channel_state" &&
        grep -q '^used=0 armed=0 pending=0 active=0 fault=0' "$P/opairshare_awdl_rx_mac_filter" ||
            fail '无线会话尚未完全释放，设置未修改。'
    fi
}
case "${1:-status}" in
    status) [ "$#" = 1 ] || exit 2;status;;
    share-mode)
        [ "$#" = 2 ] || exit 2
        valid_share_mode "$2" || exit 2
        [ "$(id -u)" = 0 ] || fail '需要 Root 权限。'
        exec 7> "$STATE/share-mode.flock";flock -n 7 7>&7 || fail '分享入口正在保存，请稍后重试。'
        # Keep the applied mode stable until the supervisor confirms idle.
        init_share_mode || fail '无法确认当前分享入口，设置未修改。'
        /system/bin/settings put global "$SHARE_MODE_KEY" "$2" || fail '保存分享入口失败。'
        [ "$(saved_share_mode)" = "$2" ] || fail '分享入口保存结果未确认，请刷新后核对。'
        if [ "$2" = "$(active_share_mode)" ];then
            /system/bin/setprop debug.opairshare.share_switching 0 || fail '切换状态未确认，请刷新后核对。'
        elif [ -z "$(pidof "$PKG" 2>/dev/null || true)" ] &&
             [ ! -e "$STATE/ui-session" ] && [ ! -d "$STATE/coex-session" ] &&
             [ ! -d "$STATE/coex-session.preparing" ];then
            /system/bin/setprop debug.opairshare.share_mode "$2" || fail '分享入口保存成功，等待后台应用。'
        fi
        status
        ;;
    receive-directory-set|receive-directory-reset)
        [ "$(id -u)" = 0 ] || fail '需要 Root 权限。'
        if [ "$1" = receive-directory-set ];then
            [ "$#" = 2 ] || exit 2
            valid_receive_directory_hex "$2" || fail '请输入 Download 或 Documents 下的目录，不能包含隐藏目录、非法字符或路径跳转。'
            directory_value=$(printf '%s' "$2" | tr A-F a-f)
        else
            [ "$#" = 1 ] || exit 2
            directory_value=
        fi
        exec 7> "$STATE/receive-directory.flock";flock -n 7 7>&7 || fail '目录正在保存，请稍后重试。'
        if [ -n "$directory_value" ];then
            result=$(/system/bin/settings put global "$RECEIVE_DIRECTORY_KEY" "$directory_value") || fail '保存接收目录失败。'
        else
            result=$(/system/bin/settings delete global "$RECEIVE_DIRECTORY_KEY") || fail '恢复默认接收目录失败。'
        fi
        [ "$(read_receive_directory_hex)" = "$directory_value" ] || fail '目录保存结果未确认，请刷新后核对。'
        status
        ;;
    name-set|name-reset)
        [ "$(id -u)" = 0 ] || fail '需要 Root 权限。'
        if [ "$1" = name-set ];then
            [ "$#" = 2 ] || exit 2
            valid_name_hex "$2" || fail '名称需要包含 1–64 个字符，不能包含换行或控制字符。'
            name_value=$(printf '%s' "$2" | tr A-F a-f)
        else
            [ "$#" = 1 ] || exit 2
            name_value=
        fi
        # Name-only edits are safe while radio/transfer sessions remain active.
        exec 7> "$STATE/name.flock";flock -n 7 7>&7 || fail '名称正在保存，请稍后重试。'
        if [ -n "$name_value" ];then
            result=$(/system/bin/settings put global "$AIR_NAME_KEY" "$name_value") || fail '保存 AirDrop 名称失败。'
        else
            result=$(/system/bin/settings delete global "$AIR_NAME_KEY") || fail '恢复默认名称失败。'
        fi
        [ "$(read_name_hex)" = "$name_value" ] || fail '名称保存结果未确认，请刷新后核对。'
        status
        ;;
    speed)
        [ "$#" = 1 ] || exit 2
        iface=/sys/class/net/mosey0
        available=off;tx=0;rx=0;index=0
        if [ -r "$iface/statistics/tx_bytes" ] && [ -r "$iface/statistics/rx_bytes" ];then
            available=on
            tx=$(cat "$iface/statistics/tx_bytes");rx=$(cat "$iface/statistics/rx_bytes")
            index=$(cat "$iface/ifindex")
        fi
        clock_ms=$(awk '{printf "%.0f",$1*1000}' /proc/uptime)
        printf '{'
        pair available "$available";printf ',';pair tx_bytes "$tx";printf ',';pair rx_bytes "$rx";printf ','
        pair ifindex "$index";printf ',';pair time_ms "$clock_ms";printf '}\n'
        ;;
    export-logs)
        [ "$#" = 1 ] || exit 2
        folder=/sdcard/Download/OPAirShare
        mkdir -p "$folder"
        target="$folder/airdrop-$(date +%Y%m%d-%H%M%S)-$$.txt"
        temp="$STATE/log-export-$$.tmp"
        trap 'rm -f "$temp"' EXIT
        {
            echo 'OPAirShare AirDrop diagnostic logs'
            date '+%Y-%m-%d %H:%M:%S %z'
            echo
            cat "$MODDIR/module.prop"
            echo
            echo '[status]'
            status
            for name in startup-trace.log boot-start.log supervisor.log speed-history/worker.log;do
                printf '\n[%s]\n' "$name"
                [ ! -r "$STATE/$name" ] || tail -n 120 "$STATE/$name"
            done
        } > "$temp"
        cp "$temp" "$target"
        printf '{"path":';json_string "$target";printf '}\n'
        ;;
    logs)
        [ "$#" = 1 ] || exit 2
        for name in startup-trace.log boot-start.log supervisor.log;do
            printf '\n[%s]\n' "$name"
            [ ! -r "$STATE/$name" ] || tail -n 45 "$STATE/$name"
        done
        ;;
    apply)
        [ "$#" = 5 ] || exit 2
        [ "$2" = on ] || exit 2
        [ "$3" = auto ] || exit 2
        case "$4" in off|on|vht|fast) ;;*) exit 2;;esac
        case "$5" in on|off) ;;*) exit 2;;esac
        [ "$(id -u)" = 0 ] || fail '需要 Root 权限。'
        exec 9> "$STATE/stack.flock";flock -n 9 9>&9 || fail '底层正在执行其他操作，请稍后重试。'
        exec 6> "$STATE/settings.flock";flock -n 6 6>&6 || fail '发现会话正在启动，请稍后重试。'
        idle
        [ "$2" != on ] || [ ! -e "$STATE/callback-off" ] || fail '先恢复进度/发现补丁后再开启共存。'
        old_coex=$(coexist);old_scope=$(scope);old_profile=$(profile);old_auto=$(flag autostart)
        if [ "$2:$3:$4:$5" = "$old_coex:$old_scope:$old_profile:$old_auto" ];then status;exit 0;fi
        snapshot=$(mktemp -d "$STATE/webui-settings.XXXXXX")
        markers='turbo-off turbo-vht turbo-fast turbo-on autostart autostart-off'
        knobs='passthru_awdl_amsdu passthru_awdl_amsdu_max passthru_awdl_amsdu_bytes passthru_awdl_phy passthru_awdl_mcs passthru_awdl_nss passthru_awdl_collect_us passthru_awdl_bw'
        for name in $markers;do [ ! -e "$STATE/$name" ] || touch "$snapshot/marker-$name";done
        for name in $knobs;do [ ! -r "$P/$name" ] || cat "$P/$name" > "$snapshot/$name";done
        rollback() {
            rc=$?;trap - EXIT INT TERM
            if [ "$rc" != 0 ];then
                # Disable batching until every original knob is restored.
                [ ! -f "$snapshot/passthru_awdl_amsdu" ] || echo 0 > "$P/passthru_awdl_amsdu" || true
                for name in $knobs;do
                    [ "$name" = passthru_awdl_amsdu ] && continue
                    [ ! -f "$snapshot/$name" ] || cat "$snapshot/$name" > "$P/$name" || true
                done
                [ ! -f "$snapshot/passthru_awdl_amsdu" ] || cat "$snapshot/passthru_awdl_amsdu" > "$P/passthru_awdl_amsdu" || true
                for name in $markers;do
                    if [ -e "$snapshot/marker-$name" ];then touch "$STATE/$name";else rm -f "$STATE/$name";fi
                done
                echo "保存失败，已尝试还原；恢复快照：$snapshot" >&2
            fi
            exit "$rc"
        }
        trap rollback EXIT
        trap 'exit 130' INT
        trap 'exit 143' TERM
        if [ "$4" != "$old_profile" ];then /system/bin/sh "$MODDIR/bin/turbo.sh" "$4" > "$snapshot/profile-result.txt";fi
        if [ "$4" = on ];then touch "$STATE/turbo-on";else rm -f "$STATE/turbo-on";fi
        if [ "$5" = on ];then
            touch "$STATE/autostart";rm -f "$STATE/autostart-off"
        else
            rm -f "$STATE/autostart";touch "$STATE/autostart-off"
        fi
        trap - EXIT INT TERM
        status
        ;;
    *) echo 'usage: settings.sh status|logs|share-mode quickshare/oshare/both|apply on auto off/on/vht/fast on/off' >&2;exit 2;;
esac
