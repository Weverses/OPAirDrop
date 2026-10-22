#!/system/bin/sh
# Small, sequential parameter sweep against the generated AWDL-only sender.
# Does not change Wi-Fi association, persistent settings, or module binaries.
set -eu
peer=${1:?Mac AWDL IPv6}
tag=${2:?unique tag}
case "$tag" in ''|*[!A-Za-z0-9_-]*) exit 2;; esac
P=/sys/module/qca_cld3_peach_v2/parameters
D=/data/local/tmp/opairshare-sweep-$tag
[ ! -e "$D" ] || exit 3
sh /data/adb/modules/opairshare/bin/transfer-idle.sh
mkdir -m 700 "$D"
knobs='passthru_awdl_mcs passthru_awdl_ackfilter passthru_awdl_collect_us passthru_awdl_amsdu'
for knob in $knobs; do cat "$P/$knob" > "$D/$knob"; done
restore() { for knob in $knobs; do cat "$D/$knob" > "$P/$knob"; done; }
finish() {
    result=$?
    trap - EXIT INT TERM HUP
    restore || result=20
    echo "SWEEP_END result=$result"
    exit "$result"
}
trap finish EXIT
trap 'exit 130' INT TERM HUP
for profile in default1 mcs5 default2 ackfilter default3 collect0 default4 amsdu0 default5; do
    restore
    case "$profile" in
        mcs5) echo 5 > "$P/passthru_awdl_mcs";;
        ackfilter) echo Y > "$P/passthru_awdl_ackfilter";;
        collect0) echo 0 > "$P/passthru_awdl_collect_us";;
        amsdu0) echo 0 > "$P/passthru_awdl_amsdu";;
    esac
    echo "BEGIN profile=$profile wall=$(date +%s)"
    cat "$P/opairshare_tx_metrics" > "$D/$profile-tx-before.txt"
    /system/bin/timeout 180 /data/local/tmp/opairshare-coex-bench-recv "$peer" 8388608 > "$D/$profile.jsonl" 2>&1 || {
        result=$?
        cat "$D/$profile.jsonl"
        echo "BENCH_FAILED profile=$profile result=$result"
        exit "$result"
    }
    cat "$D/$profile.jsonl"
    cat "$P/opairshare_tx_metrics" > "$D/$profile-tx-after.txt"
done
