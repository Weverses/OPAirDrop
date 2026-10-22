#!/system/bin/sh
# Temporary Wi-Fi power/latency comparison, with original power-save restore.
set -eu
peer=${1:?Mac AWDL IPv6}
tag=${2:?unique tag}
case "$tag" in ''|*[!A-Za-z0-9_-]*) exit 2;; esac
D=/data/local/tmp/opairshare-power-$tag
[ ! -e "$D" ] || exit 3
sh /data/adb/modules/opairshare/bin/transfer-idle.sh
mkdir -m 700 "$D"
iw dev wlan0 get power_save > "$D/power-original.txt"
original=$(sed -n 's/^Power save: //p' "$D/power-original.txt")
case "$original" in on|off) ;; *) exit 4;; esac
# Caller must verify the initial operation mode is 0; preserve its dump.
dumpsys wifi > "$D/wifi-original.txt"
grep -q '^Current operation mode: 0$' "$D/wifi-original.txt" || exit 5
lowlatency=0
restore() {
    if [ "$lowlatency" = 1 ]; then cmd wifi force-low-latency-mode disabled; lowlatency=0; fi
    iw dev wlan0 set power_save "$original"
}
cleanup() {
    result=$?
    trap - EXIT INT TERM HUP
    restore || result=20
    iw dev wlan0 get power_save
    echo "POWER_SWEEP_END result=$result"
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP
for profile in default1 poweroff default2 lowlatency default3 poweroff2; do
    restore
    case "$profile" in
        poweroff|poweroff2) iw dev wlan0 set power_save off;;
        lowlatency) lowlatency=1; cmd wifi force-low-latency-mode enabled;;
    esac
    echo "BEGIN profile=$profile wall=$(date +%s)"
    iw dev wlan0 get power_save
    /system/bin/timeout 180 /data/local/tmp/opairshare-coex-bench-recv "$peer" 4194304 > "$D/$profile.jsonl" 2>&1 || {
        result=$?; cat "$D/$profile.jsonl"; exit "$result"
    }
    cat "$D/$profile.jsonl"
done
