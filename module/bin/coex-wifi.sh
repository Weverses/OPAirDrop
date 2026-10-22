#!/system/bin/sh
# Sourced after common.sh. Read-only connection checks; never change Wi-Fi.
# Return 0 for connected channel149, 1 for a known unsupported/disconnected
# state, and 2 when the framework state cannot be confirmed.
coex_wifi_read() {
    coex_wifi_frequency=
    coex_wifi_network=
    coex_wifi_reason=wifi_unavailable
    coex_wifi_text=$(framework timeout 5 cmd wifi status 2>/dev/null) || return 2
    case "$coex_wifi_text" in
        'Wifi is disabled'*) coex_wifi_reason=wifi_disconnected;return 1;;
        'Wifi is enabled'*) ;;
        *) return 2;;
    esac
    if ! printf '%s\n' "$coex_wifi_text" | grep -q '^Wifi is connected to ';then
        coex_wifi_reason=wifi_disconnected;return 1
    fi
    coex_wifi_frequency=$(printf '%s\n' "$coex_wifi_text" | sed -n 's/.*Frequency: \([0-9][0-9]*\)MHz.*/\1/p' | head -1)
    coex_wifi_network=$(printf '%s\n' "$coex_wifi_text" | sed -n 's/.*Net ID: \([0-9][0-9]*\).*/\1/p' | head -1)
    [ -n "$coex_wifi_frequency" ] || return 2
    if [ "$coex_wifi_frequency" != 5745 ];then
        coex_wifi_reason=wifi_not149;return 1
    fi
    [ -n "$coex_wifi_network" ] &&
        printf '%s\n' "$coex_wifi_text" | grep -q 'Supplicant state: COMPLETED' || return 2
    coex_wifi_reason=
}

coex_requested() { [ ! -e "$STATE/coex-off" ]; }
coex_available() {
    coex_requested && [ ! -e "$STATE/coex-wifi-blocked" ] &&
        [ ! -e "$STATE/coex-session/invalidated" ] && coex_wifi_read
}
