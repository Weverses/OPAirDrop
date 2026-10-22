#!/system/bin/sh
# Requested mode is persistent; the supervisor publishes the applied mode at idle.
SHARE_MODE_KEY=opairshare_share_mode
valid_share_mode() { case "$1" in quickshare|oshare|both) return 0;; *) return 1;; esac; }
saved_share_mode() {
    share_saved=$(/system/bin/settings get global "$SHARE_MODE_KEY" 2>/dev/null) || share_saved=
    if valid_share_mode "$share_saved";then printf '%s\n' "$share_saved";else echo both;fi
}
active_share_mode() {
    share_active=$(/system/bin/getprop debug.opairshare.share_mode)
    if valid_share_mode "$share_active";then printf '%s\n' "$share_active";else echo both;fi
}
init_share_mode() {
    share_active=$(/system/bin/getprop debug.opairshare.share_mode)
    if ! valid_share_mode "$share_active";then
        /system/bin/setprop debug.opairshare.share_mode "$(saved_share_mode)" || return 1
    fi
}

# Called only by the provider-owning supervisor. Active/unknown transfers keep
# their process and radio; the switching gate prevents new work racing teardown.
apply_share_mode() {
    exec 7> "$STATE/share-mode.flock"
    if ! flock -n 7 7>&7;then exec 7>&-;return 9;fi
    share_target=$(saved_share_mode)
    share_current=$(active_share_mode)
    if [ "$share_target" = "$share_current" ];then
        if [ "$(/system/bin/getprop debug.opairshare.share_switching)" = 1 ];then
            /system/bin/setprop debug.opairshare.share_switching 0 || { exec 7>&-;return 9; }
        fi
        exec 7>&-;return 0
    fi
    /system/bin/setprop debug.opairshare.share_switching 1 || { exec 7>&-;return 9; }
    /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&- || { exec 7>&-;return 9; }
    /system/bin/sh "$MODDIR/bin/transfer-idle.sh" 8>&- >/dev/null 2>&1 || { exec 7>&-;return 9; }
    if [ -n "$(pidof "$PKG" 2>/dev/null || true)" ];then
        share_dump=$(framework timeout 8 dumpsys activity service "$COMP") || { exec 7>&-;return 9; }
        # An idle transfer dump alone does not acknowledge release of the bridge.
        printf '%s\n' "$share_dump" | grep -q '^OPAIRSHARE_MODE switch_ready=1$' || { exec 7>&-;return 9; }
    fi
    exec 6> "$STATE/settings.flock"
    if ! flock -n 6 6>&6;then exec 6>&-;exec 7>&-;return 9;fi
    if [ -e "$STATE/ui-session" ] || [ -d "$STATE/coex-session" ];then
        if ! close_session recover;then exec 6>&-;exec 7>&-;return 9;fi
    elif [ -n "$(pidof "$PKG" 2>/dev/null || true)" ];then
        /system/bin/sh "$MODDIR/bin/provider.sh" release 8>&- || { exec 6>&-;exec 7>&-;return 9; }
    fi
    /system/bin/setprop debug.opairshare.share_mode "$share_target" || { exec 6>&-;exec 7>&-;return 9; }
    /system/bin/setprop debug.opairshare.share_switching 0 || { exec 6>&-;exec 7>&-;return 9; }
    exec 6>&-;exec 7>&-
    provider_failures=0;provider_ready=0;provider_samples=0;receive_samples=10
    echo "SHARE_MODE_APPLIED previous=$share_current current=$share_target"
}
