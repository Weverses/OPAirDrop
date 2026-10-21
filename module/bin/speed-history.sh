#!/system/bin/sh
# Passive per-AWDL-session counters, independent of the WebUI process.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
init_state
D="$STATE/speed-history"
mkdir -p "$D";chmod 700 "$D"
IFACE=/sys/class/net/mosey0
now_ms(){ awk '{printf "%.0f",$1*1000}' /proc/uptime; }
reset_record(){ started=0;start_wall=0;sent=0;received=0;peak_tx=0;peak_rx=0;last_data=0;confirmed=off; }
reset_record
record_json(){
    duration=$((last_data-started));[ "$duration" -gt 0 ] || duration=1
    kind=traffic;[ "$confirmed" != on ] || kind=transfer
    awk -v id="$start_wall-$idx-$started" -v start="$start_wall" -v dur="$duration" -v tx="$sent" -v rx="$received" -v ptx="$peak_tx" -v prx="$peak_rx" -v ctx="${rate_tx:-0}" -v crx="${rate_rx:-0}" -v state="$1" -v kind="$kind" 'BEGIN {
      printf "{\"id\":\"%s\",\"started_at\":%.0f,\"duration_s\":%.3f,\"sent_bytes\":%.0f,\"received_bytes\":%.0f,\"peak_tx_bps\":%.0f,\"peak_rx_bps\":%.0f,\"average_tx_bps\":%.0f,\"average_rx_bps\":%.0f,\"tx_bps\":%.0f,\"rx_bps\":%.0f,\"state\":\"%s\",\"kind\":\"%s\"}\n",id,start,dur/1000,tx,rx,ptx,prx,tx*1000/dur,rx*1000/dur,ctx,crx,state,kind;
    }'
}
flush(){
    [ "$started" -gt 0 ] || return 0
    if [ "$confirmed" = on ] || [ $((sent+received)) -ge 65536 ];then
        # Atomic bounded history: only the newest 100 sessions are retained.
        { [ ! -f "$D/records.jsonl" ] || tail -n 99 "$D/records.jsonl";record_json complete; } > "$D/records.next"
        mv "$D/records.next" "$D/records.jsonl"
    fi
    reset_record
}
waiting(){
    # Do not rewrite the same idle snapshot every second.
    if [ -r "$D/live.json" ] && grep -q '"state":"waiting"' "$D/live.json";then return 0;fi
    printf '{"state":"waiting","tx_bps":0,"rx_bps":0}\n' > "$D/live.next";mv "$D/live.next" "$D/live.json"; }
run(){
    exec 5> "$D/worker.flock";flock -n 5 5>&5 || exit 0
    echo "$$" > "$D/pid"
    idx=0;previous=0;prev_tx=0;prev_rx=0;rate_tx=0;rate_rx=0
    cleanup(){ trap - EXIT INT TERM;flush;waiting;[ "$(cat "$D/pid" 2>/dev/null || true)" != "$$" ] || rm -f "$D/pid"; }
    trap cleanup EXIT
    trap 'exit 0' INT TERM
    while same_boot "$STATE/stack-boot" && [ ! -e "$STATE/disabled" ] && [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ];do
        t=$(now_ms)
        if [ ! -r "$IFACE/statistics/tx_bytes" ] || [ ! -r "$IFACE/statistics/rx_bytes" ];then
            flush;waiting;idx=0;previous=0;sleep 1;continue
        fi
        next_idx=$(cat "$IFACE/ifindex" 2>/dev/null || echo 0)
        tx=$(cat "$IFACE/statistics/tx_bytes" 2>/dev/null || echo 0)
        rx=$(cat "$IFACE/statistics/rx_bytes" 2>/dev/null || echo 0)
        if [ "$previous" = 0 ] || [ "$next_idx" != "$idx" ] || [ "$tx" -lt "$prev_tx" ] || [ "$rx" -lt "$prev_rx" ];then
            flush;idx=$next_idx;previous=$t;prev_tx=$tx;prev_rx=$rx;waiting;sleep 1;continue
        fi
        dt=$((t-previous));[ "$dt" -gt 0 ] || { sleep 1;continue; }
        dtx=$((tx-prev_tx));drx=$((rx-prev_rx))
        rate_tx=$((dtx*1000/dt));rate_rx=$((drx*1000/dt))
        if [ $((dtx+drx)) -gt 0 ];then
            if [ "$started" = 0 ];then started=$previous;start_wall=$(date +%s);fi
            sent=$((sent+dtx));received=$((received+drx));last_data=$t
            [ "$rate_tx" -le "$peak_tx" ] || peak_tx=$rate_tx
            [ "$rate_rx" -le "$peak_rx" ] || peak_rx=$rate_rx
        fi
        # Positive evidence only. Unknown dumps never claim a real transfer.
        if [ "$started" -gt 0 ] && [ "$confirmed" != on ];then
            if /system/bin/timeout 3 /system/bin/dumpsys activity service "$COMP" 2>/dev/null | grep -Eq 'isTransferFinished=false|activeTransfers=\[[^]]+\]|Active Transfers \([1-9][0-9]*\)|Receive provider transferring: true';then confirmed=on;fi
        fi
        if [ "$started" -gt 0 ];then
            if [ $((t-last_data)) -ge 6000 ];then flush;waiting
            else record_json recording > "$D/live.next";mv "$D/live.next" "$D/live.json";fi
        else waiting;fi
        previous=$t;prev_tx=$tx;prev_rx=$rx
        sleep 1
    done
}
case "${1:-status}" in
    start)
        pid=$(cat "$D/pid" 2>/dev/null || true)
        if pid_is "$pid" '/bin/speed-history.sh run';then exit 0;fi
        same_boot "$STATE/stack-boot" && [ ! -e "$STATE/disabled" ] || exit 0
        /system/bin/sh "$MODDIR/bin/speed-history.sh" run >> "$D/worker.log" 2>&1 < /dev/null &
        ;;
    run) run;;
    status)
        active=off
        pid=$(cat "$D/pid" 2>/dev/null || true)
        if pid_is "$pid" '/bin/speed-history.sh run';then active=on;fi
        printf '{"recorder":"%s","live":' "$active"
        if [ -r "$D/live.json" ];then cat "$D/live.json";else printf '{"state":"waiting","tx_bps":0,"rx_bps":0}';fi
        printf ',"history":['
        if [ -r "$D/records.jsonl" ];then awk 'NR>1{printf ","}{printf "%s",$0}' "$D/records.jsonl";fi
        printf ']}\n'
        ;;
    *) exit 2;;
esac
