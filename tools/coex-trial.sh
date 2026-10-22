#!/system/bin/sh
# Finite, one-variable experiment. Every exit restores the original knob.
set -eu
tag=${1:?trial tag}
knob=${2:?knob}
value=${3:?value}
case "$tag" in ''|*[!A-Za-z0-9_-]*) exit 2;; esac
case "$knob:$value" in
    passthru_awdl_rx_order_ms:0|passthru_awdl_rx_order_ms:8|passthru_awdl_rx_order_ms:20|passthru_awdl_ackfilter:Y|passthru_awdl_ackfilter:N|passthru_awdl_mcs:5|passthru_awdl_mcs:8) ;;
    *) exit 2;;
esac
P=/sys/module/qca_cld3_peach_v2/parameters
D=/data/local/tmp/opairshare-coex-$tag
[ ! -e "$D" ] || exit 3
/system/bin/sh /data/adb/modules/opairshare/bin/transfer-idle.sh
mkdir -m 700 "$D"
cat "$P/$knob" > "$D/original"
printf '%s\n' "$knob" > "$D/knob"
printf '%s\n' "$$" > "$D/pid"
capture= sampler=
cleanup() {
    status=$?
    trap - EXIT INT TERM HUP
    cat "$D/original" > "$P/$knob" || status=20
    [ -z "$sampler" ] || kill "$sampler" 2>/dev/null || true
    [ -z "$capture" ] || kill -INT "$capture" 2>/dev/null || true
    [ -z "$sampler" ] || wait "$sampler" 2>/dev/null || true
    [ -z "$capture" ] || wait "$capture" 2>/dev/null || true
    cat "$P/$knob" > "$D/restored"
    cmp -s "$D/original" "$D/restored" || status=21
    echo "TRIAL_END status=$status restored=$(cat "$D/restored")"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 0' INT TERM HUP
echo "$value" > "$P/$knob"
[ "$(cat "$P/$knob")" = "$value" ] || exit 4
tcpdump -i mosey0 -n -s 128 -U -w "$D/tcp.pcap" tcp > "$D/tcpdump.txt" 2>&1 &
capture=$!
sh /data/local/tmp/opairshare-coex-capture.sh 180 > "$D/samples.txt" 2>&1 &
sampler=$!
echo "TRIAL_READY tag=$tag knob=$knob value=$value pid=$$"
# Finish through a marker instead of signalling the outer su process: some
# root managers proxy that PID and terminate its descendants before traps run.
i=0
while [ "$i" -lt 180 ] && [ ! -e "$D/stop" ]; do
    sleep 1
    i=$((i+1))
done
cleanup
