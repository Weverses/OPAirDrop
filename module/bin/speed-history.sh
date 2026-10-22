#!/system/bin/sh
# Passive per-AWDL-session counters, independent of the WebUI process.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
init_state
. "$MODDIR/bin/transfer-power.sh"
D="$STATE/speed-history"
mkdir -p "$D";chmod 700 "$D"
IFACE=/sys/class/net/mosey0
now_ms(){ awk '{printf "%.0f",$1*1000}' /proc/uptime; }
reset_record(){ started=0;start_wall=0;sent=0;received=0;peak_tx=0;peak_rx=0;last_data=0;confirmed=off; }
reset_record
# Android mksh arithmetic is signed 32-bit, including on arm64. Keep byte
# counters and bytes*1000 out of $((...)); large transfers otherwise wrap.
sample_rates(){
    read -r dtx drx rate_tx rate_rx <<EOF
$(awk -v tx="$tx" -v rx="$rx" -v ptx="$prev_tx" -v prx="$prev_rx" -v dt="$dt" 'BEGIN {
    tx-=ptx;rx-=prx;printf "%.0f %.0f %.0f %.0f\n",tx,rx,tx*1000/dt,rx*1000/dt
}')
EOF
}
add_sample(){
    read -r sent received peak_tx peak_rx <<EOF
$(awk -v tx="$sent" -v rx="$received" -v dtx="$dtx" -v drx="$drx" -v ptx="$peak_tx" -v prx="$peak_rx" -v ctx="$rate_tx" -v crx="$rate_rx" 'BEGIN {
    printf "%.0f %.0f %.0f %.0f\n",tx+dtx,rx+drx,(ctx>ptx?ctx:ptx),(crx>prx?crx:prx)
}')
EOF
}
record_json(){
    duration=$((last_data-started));[ "$duration" -gt 0 ] || duration=1
    kind=traffic;[ "$confirmed" != on ] || kind=transfer
    awk -v id="$start_wall-$idx-$started" -v start="$start_wall" -v dur="$duration" -v tx="$sent" -v rx="$received" -v ptx="$peak_tx" -v prx="$peak_rx" -v ctx="${rate_tx:-0}" -v crx="${rate_rx:-0}" -v state="$1" -v kind="$kind" 'BEGIN {
      printf "{\"id\":\"%s\",\"started_at\":%.0f,\"duration_s\":%.3f,\"sent_bytes\":%.0f,\"received_bytes\":%.0f,\"peak_tx_bps\":%.0f,\"peak_rx_bps\":%.0f,\"average_tx_bps\":%.0f,\"average_rx_bps\":%.0f,\"tx_bps\":%.0f,\"rx_bps\":%.0f,\"state\":\"%s\",\"kind\":\"%s\"}\n",id,start,dur/1000,tx,rx,ptx,prx,tx*1000/dur,rx*1000/dur,ctx,crx,state,kind;
    }'
}
flush(){
    [ "$started" -gt 0 ] || return 0
    if [ "$confirmed" = on ] || awk -v tx="$sent" -v rx="$received" 'BEGIN {exit(tx+rx<65536)}';then
        # Atomic bounded history: only the newest 100 sessions are retained.
        { [ ! -f "$D/records.jsonl" ] || tail -n 99 "$D/records.jsonl";record_json complete; } > "$D/records.next"
        mv "$D/records.next" "$D/records.jsonl"
    fi
    reset_record
}
waiting(){
    printf '{"state":"waiting","tx_bps":%s,"rx_bps":%s}\n' "${rate_tx:-0}" "${rate_rx:-0}" > "$D/live.next"
    mv "$D/live.next" "$D/live.json"
}
transfer_state(){
    transfer_dump=$(/system/bin/timeout 3 /system/bin/dumpsys activity service "$COMP" 2>/dev/null) || transfer_dump=
    if printf '%s\n' "$transfer_dump" | grep -q '^OPAIRSHARE_HISTORY state=active$';then
        provider_state=busy
    elif printf '%s\n' "$transfer_dump" | grep -q '^OPAIRSHARE_HISTORY state=waiting$';then
        provider_state=pending
    elif printf '%s\n' "$transfer_dump" | grep -q '^OPAIRSHARE_HISTORY state=idle$';then
        provider_state=idle
    elif printf '%s\n' "$transfer_dump" | grep -Eq 'OPAIRSHARE_RECEIVE busy=1([[:space:]]|$)|isTransferFinished=false|activeTransfers=\[[^]]+\]|Active Transfers \([1-9][0-9]*\)|Receive provider transferring: true';then
        provider_state=busy
    elif printf '%s\n' "$transfer_dump" | grep -Eq '^No services match:|^No service found' ||
         { printf '%s\n' "$transfer_dump" | grep -q 'ExternalSharingService state:' &&
           printf '%s\n' "$transfer_dump" | grep -Eq 'Active Transfers \(0\)' &&
           printf '%s\n' "$transfer_dump" | grep -q 'Receive provider transferring: false'; };then
        provider_state=idle
    else provider_state=unknown;fi
}
run(){
    exec 5> "$D/worker.flock";flock -n 5 5>&5 || exit 0
    echo "$$" > "$D/pid"
    idx=0;previous=0;prev_tx=0;prev_rx=0;rate_tx=0;rate_rx=0;transfer_check=0;provider_state=unknown;awaiting=off
    cleanup(){ trap - EXIT INT TERM;performance_restore;flush;rate_tx=0;rate_rx=0;waiting;[ "$(cat "$D/pid" 2>/dev/null || true)" != "$$" ] || rm -f "$D/pid"; }
    trap cleanup EXIT
    trap 'exit 0' INT TERM
    while same_boot "$STATE/stack-boot" && [ ! -e "$STATE/disabled" ] && [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ];do
        t=$(now_ms)
        if [ ! -r "$IFACE/statistics/tx_bytes" ] || [ ! -r "$IFACE/statistics/rx_bytes" ];then
            performance_restore;flush;rate_tx=0;rate_rx=0;waiting;idx=0;previous=0;provider_state=unknown;transfer_check=0;awaiting=off;sleep 0.5;continue
        fi
        next_idx=$(cat "$IFACE/ifindex" 2>/dev/null || echo 0)
        tx=$(cat "$IFACE/statistics/tx_bytes" 2>/dev/null || echo 0)
        rx=$(cat "$IFACE/statistics/rx_bytes" 2>/dev/null || echo 0)
        if [ "$previous" = 0 ] || [ "$next_idx" != "$idx" ] || awk -v tx="$tx" -v rx="$rx" -v ptx="$prev_tx" -v prx="$prev_rx" 'BEGIN {exit !(tx<ptx||rx<prx)}';then
            flush;idx=$next_idx;previous=$t;prev_tx=$tx;prev_rx=$rx;rate_tx=0;rate_rx=0;provider_state=unknown;transfer_check=0;awaiting=off;waiting;sleep 0.5;continue
        fi
        dt=$((t-previous));[ "$dt" -gt 0 ] || { sleep 0.5;continue; }
        sample_rates
        # Observe both boundaries even when discovery already has a long-running
        # traffic record. A busy transition starts a fresh transfer interval.
        if [ $((t-transfer_check)) -ge 1000 ];then
            transfer_check=$t
            transfer_state
            performance_tick "$provider_state"
        fi
        if [ "$provider_state" = pending ];then
            if [ "$confirmed" = on ];then flush;else reset_record;fi
            awaiting=on
        elif [ "$provider_state" = busy ] || [ "$provider_state" = idle ];then
            awaiting=off
        fi
        if [ "$provider_state" = busy ] && [ "$confirmed" != on ];then
            reset_record
            started=$previous;start_wall=$(($(date +%s)-dt/1000));confirmed=on
        fi
        if [ "$awaiting" != on ] && { [ "$dtx" != 0 ] || [ "$drx" != 0 ]; } && { [ "$provider_state" != idle ] || [ "$confirmed" = on ]; };then
            if [ "$started" = 0 ];then started=$previous;start_wall=$(($(date +%s)-dt/1000));fi
            add_sample;last_data=$t
        fi
        if [ "$provider_state" = idle ];then
            if [ "$confirmed" = on ];then flush;else reset_record;fi
        elif [ "$provider_state" = busy ];then
            last_data=$t
        fi
        if [ "$started" -gt 0 ];then
            if [ $((t-last_data)) -ge 6000 ];then flush;waiting
            else record_json recording > "$D/live.next";mv "$D/live.next" "$D/live.json";fi
        else waiting;fi
        previous=$t;prev_tx=$tx;prev_rx=$rx
        sleep 0.5
    done
}
case "${1:-status}" in
    start)
        pid=$(cat "$D/pid" 2>/dev/null || true)
        # /proc/PID/cmdline separates arguments with NUL, not spaces.
        if pid_is "$pid" '/bin/speed-history.sh';then exit 0;fi
        same_boot "$STATE/stack-boot" && [ ! -e "$STATE/disabled" ] || exit 0
        /system/bin/sh "$MODDIR/bin/speed-history.sh" run >> "$D/worker.log" 2>&1 < /dev/null &
        ;;
    run) run;;
    status)
        active=off
        pid=$(cat "$D/pid" 2>/dev/null || true)
        if pid_is "$pid" '/bin/speed-history.sh';then active=on;fi
        printf '{"recorder":"%s","live":' "$active"
        if [ -r "$D/live.json" ];then cat "$D/live.json";else printf '{"state":"waiting","tx_bps":0,"rx_bps":0}';fi
        printf ',"history":['
        if [ -r "$D/records.jsonl" ];then awk 'NR>1{printf ","}{printf "%s",$0}' "$D/records.jsonl";fi
        printf ']}\n'
        ;;
    *) exit 2;;
esac
