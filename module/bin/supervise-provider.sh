#!/system/bin/sh
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
init_state
trap '' HUP
exec 8> "$STATE/provider.flock"
flock -n 8 8>&8 || exit 0
echo "$$" > "$STATE/supervisor.pid"
cleanup() {
    [ "$(cat "$STATE/supervisor.pid" 2>/dev/null || true)" != "$$" ] || rm -f "$STATE/supervisor.pid"
    exec 8>&-
}
trap cleanup EXIT
trap 'exit 0' INT TERM
failures=0
while same_boot "$STATE/stack-boot" && [ ! -e "$STATE/disabled" ] &&
      [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; do
    if ! same_boot "$STATE/server-boot" ||
       ! pid_is "$(cat "$STATE/server.pid" 2>/dev/null || true)" '/system_ext/bin/mosey_server'; then
        echo 'Native server lost; leaving driver unchanged, explicit start required'
        exit 6
    fi
    if timeout 25 /system/bin/sh "$MODDIR/bin/provider.sh" ensure 8>&-; then failures=0;
    else
        failures=$((failures+1))
        echo "Provider restart failed count=$failures"
        [ "$failures" -lt 3 ] || exit 7
    fi
    # mksh defers traps while waiting for a foreground child. Short sleeps
    # make stop responsive without polling ActivityManager more frequently.
    n=0
    while [ "$n" -lt 30 ] && [ ! -e "$STATE/disabled" ] &&
          [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; do
        sleep 1; n=$((n+1))
    done
done
