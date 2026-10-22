#!/system/bin/sh
# Sourced by the supervisor; only its own bounded warmup child is managed here.
receive_warmup_pid=
receive_warmup_start() {
    [ "${receive_eligible:-0}" = 1 ] || return 0
    startup_trace RECEIVE_WARMUP_BEGIN
    /system/bin/timeout -k 2 20 /system/bin/sh "$MODDIR/bin/receive-session.sh" ensure-ui \
        >> "$STATE/receive-warmup.log" 2>&1 8>&- &
    receive_warmup_pid=$!
}
receive_warmup_wait() {
    [ -n "$receive_warmup_pid" ] || return 0
    if wait "$receive_warmup_pid";then startup_trace RECEIVE_WARMUP_READY
    else echo 'Receiver warmup deferred; normal readiness check will retry';fi
    receive_warmup_pid=
}
receive_warmup_cleanup() {
    [ -n "$receive_warmup_pid" ] || return 0
    kill "$receive_warmup_pid" 2>/dev/null || true
    wait "$receive_warmup_pid" 2>/dev/null || true
    receive_warmup_pid=
}
