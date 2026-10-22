#!/system/bin/sh
# Compare ACK batching only, with STA power save disabled in every trial.
set -eu
peer=${1:?Mac AWDL IPv6}
tag=${2:?unique tag}
mode=${3:-all}
case "$mode" in
    all) profiles='default1 collect250 collect5000 default2 aggregate2 ackfilter default3'; bytes=4194304;;
    ackrepeat) profiles='ackfilter1 default1 default2 ackfilter2 ackfilter3 default3'; bytes=8388608;;
    *) exit 2;;
esac
case "$tag" in ''|*[!A-Za-z0-9_-]*) exit 2;; esac
P=/sys/module/qca_cld3_peach_v2/parameters
D=/data/local/tmp/opairshare-ack-$tag
[ ! -e "$D" ] || exit 3
sh /data/adb/modules/opairshare/bin/transfer-idle.sh
mkdir -m 700 "$D"
original=$(iw dev wlan0 get power_save | sed -n 's/^Power save: //p')
case "$original" in on|off) ;; *) exit 4;; esac
knobs='passthru_awdl_collect_us passthru_awdl_amsdu_max passthru_awdl_ackfilter'
for knob in $knobs; do cat "$P/$knob" > "$D/$knob"; done
restore() { for knob in $knobs; do cat "$D/$knob" > "$P/$knob"; done; }
cleanup() {
    result=$?
    trap - EXIT INT TERM HUP
    restore || result=20
    iw dev wlan0 set power_save "$original" || result=21
    echo "ACK_SWEEP_END result=$result"
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP
iw dev wlan0 set power_save off
for profile in $profiles; do
    restore
    case "$profile" in
        collect250) echo 250 > "$P/passthru_awdl_collect_us";;
        collect5000) echo 5000 > "$P/passthru_awdl_collect_us";;
        aggregate2) echo 2 > "$P/passthru_awdl_amsdu_max";;
        ackfilter*) echo Y > "$P/passthru_awdl_ackfilter";;
    esac
    echo "BEGIN profile=$profile wall=$(date +%s)"
    /system/bin/timeout 180 /data/local/tmp/opairshare-coex-bench-recv "$peer" "$bytes" > "$D/$profile.jsonl" 2>&1 || {
        result=$?; cat "$D/$profile.jsonl"; exit "$result"
    }
    cat "$D/$profile.jsonl"
done
