#!/system/bin/sh
# Scope NFQUEUE to generated benchmark traffic from one selected AWDL peer.
set -eu
peer=${1:?Mac AWDL IPv6}
tag=${2:?unique tag}
case "$peer" in fe80::*[!0-9A-Fa-f:]*) exit 2;; fe80::*) ;; *) exit 2;; esac
case "$tag" in ''|*[!A-Za-z0-9_-]*) exit 2;; esac
D=/data/local/tmp/opairshare-order-$tag
[ ! -e "$D" ] || exit 3
sh /data/adb/modules/opairshare/bin/transfer-idle.sh
mkdir -m 700 "$D"
order= installed=0
rule() {
    ip6tables -w 3 "$1" INPUT -i mosey0 -s "$peer" -p tcp --sport 18773 \
        -m comment --comment opairshare-coex-order-test \
        -j NFQUEUE --queue-num 18773 --queue-bypass
}
if rule -C 2>/dev/null; then echo EXISTING_EXPERIMENT_RULE; exit 4; fi
cleanup() {
    status=$?
    trap - EXIT INT TERM HUP
    if [ "$installed" = 1 ]; then rule -D || status=20; installed=0; fi
    [ -z "$order" ] || kill -TERM "$order" 2>/dev/null || true
    [ -z "$order" ] || wait "$order" 2>/dev/null || true
    echo "ORDER_SWEEP_END status=$status"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP
for profile in pass1 order8 pass2 order20 pass3 order8b; do
    ms=0
    case "$profile" in order8|order8b) ms=8;; order20) ms=20;; esac
    echo "BEGIN profile=$profile wall=$(date +%s)"
    /data/local/tmp/opairshare-coex-tcp-order "$ms" 240 > "$D/$profile-order.txt" 2>&1 &
    order=$!
    sleep 1
    kill -0 "$order"
    grep -q '^ORDER_READY$' "$D/$profile-order.txt"
    rule -I
    installed=1
    /system/bin/timeout 180 /data/local/tmp/opairshare-coex-bench-recv "$peer" 8388608 > "$D/$profile.jsonl" 2>&1 || {
        status=$?; cat "$D/$profile.jsonl"; exit "$status"
    }
    rule -D
    installed=0
    kill -TERM "$order"
    wait "$order" || true
    order=
    cat "$D/$profile.jsonl" "$D/$profile-order.txt"
done
