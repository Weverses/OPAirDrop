#!/system/bin/sh
# Late APK mounts: original signed APKs remain available during package scanning.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
. "$MODDIR/bin/root-runtime.sh"
CB="$STATE/callback"
SYSTEM_APK=/system_ext/priv-app/MoseyApp/base.apk
die() { echo "Callback patch: $*" >&2; exit 3; }
digest() { sha256sum "$1" | awk '{print $1}'; }
base_digest() { awk '$2=="base.apk" {print $1}' "$1"; }
original=$(base_digest "$MODDIR/APP-SHA256SUMS")
patched=$(base_digest "$MODDIR/APP-CALLBACK-SHA256SUMS")
if [ "$(id -u)" != 0 ]; then die 'root required'; fi
root_enter_global "$0" "$@" || die 'global mount namespace unavailable'
init_state
mkdir -p "$CB"
exec 7> "$CB/operation.flock"
flock -n 7 7>&7 || die 'another callback operation is running'

active_base() {
    framework pm path --user 0 "$PKG" | sed -n 's/^package:\(.*\/base.apk\)$/\1/p'
}
idle() {
    /system/bin/sh "$MODDIR/bin/transfer-idle.sh" 7>&-
    [ -z "$(pidof "$PKG" || true)" ] || die 'close Quick Share before changing the patch'
    p=/sys/module/qca_cld3_peach_v2/parameters/opairshare_channel_state
    [ ! -r "$p" ] || grep -q 'accepting=0 vdev=255 freq=0' "$p" || die 'radio session still active'
}
owned() {
    # A file mount must expose our dedicated backing inode. mountinfo's root
    # varies with /data mount roots and OverlayFS, so never hardcode /adb here.
    awk -v target="$1" '$5==target {found=1} END {exit(!found)}' /proc/self/mountinfo &&
        [ "$1" -ef "$2" ]
}
restore_owned() {
    same_boot "$CB/boot" || return 0
    for pair in "system:$SYSTEM_APK" "active:$(cat "$CB/active-path")"; do
        kind=${pair%%:*}; target=${pair#*:}
        [ "$kind" != active ] || [ "$target" != "$SYSTEM_APK" ] || continue
        if owned "$target" "$CB/$kind.apk"; then
            root_tool umount "$target" || root_tool umount -l "$target"
            [ "$(digest "$target")" = "$original" ] || die 'original APK view did not return'
        elif [ "$(digest "$target")" != "$original" ]; then
            die "unowned APK view at $target; leave it intact"
        fi
    done
    rm -f "$CB/boot" "$CB/active-path"
}

case "${1:-status}" in
    ensure|on)
        if [ "${1:-}" = on ]; then rm -f "$STATE/callback-off"; fi
        if [ -e "$STATE/callback-off" ]; then
            /system/bin/sh "$MODDIR/bin/app-check.sh" original 7>&-
            exit 0
        fi
        [ "$(getprop sys.boot_completed)" = 1 ] || die 'wait for Android package scanning to finish'
        [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || die 'module disabled or removed'
        payload="$MODDIR/payload/app-callback/base.apk"
        [ "$(digest "$payload")" = "$patched" ] || die 'payload hash differs'
        active=$(active_base)
        [ -n "$active" ] && [ "$(printf '%s\n' "$active" | wc -l)" = 1 ] || die 'expected one active base APK'
        if same_boot "$CB/boot" && [ "$(cat "$CB/active-path")" = "$active" ]; then
            owned "$SYSTEM_APK" "$CB/system.apk" || die 'system patch mount changed'
            if [ "$active" != "$SYSTEM_APK" ]; then
                owned "$active" "$CB/active.apk" || die 'active patch mount changed'
            fi
            /system/bin/sh "$MODDIR/bin/app-check.sh" patched 7>&-
            exit 0
        fi
        /system/bin/sh "$MODDIR/bin/app-check.sh" original 7>&-
        [ "$(digest "$SYSTEM_APK")" = "$original" ] || die 'system APK is not the supported original'
        idle
        cp "$payload" "$CB/system.apk"
        cp "$payload" "$CB/active.apk"
        chown 0:0 "$CB/system.apk" "$CB/active.apk"
        chmod 0444 "$CB/system.apk" "$CB/active.apk"
        chcon u:object_r:system_file:s0 "$CB/system.apk"
        chcon u:object_r:apk_data_file:s0 "$CB/active.apk"
        printf '%s\n' "$active" > "$CB/active-path"
        boot_id > "$CB/boot"
        trap 'rc=$?; trap - EXIT; if [ "$rc" != 0 ]; then restore_owned || true; fi; exit "$rc"' EXIT
        if [ "$active" != "$SYSTEM_APK" ]; then
            root_tool mount -o bind "$CB/active.apk" "$active"
            root_tool mount -o remount,bind,ro "$CB/active.apk" "$active"
        fi
        root_tool mount -o bind "$CB/system.apk" "$SYSTEM_APK"
        root_tool mount -o remount,bind,ro "$CB/system.apk" "$SYSTEM_APK"
        /system/bin/sh "$MODDIR/bin/app-check.sh" patched 7>&-
        trap - EXIT
        echo 'CALLBACK_PATCH_READY stage69-discovery-r3'
        ;;
    restore|off)
        idle
        restore_owned
        [ "${1:-}" != off ] || touch "$STATE/callback-off"
        /system/bin/sh "$MODDIR/bin/app-check.sh" original 7>&-
        echo 'CALLBACK_ORIGINAL_APK_RESTORED'
        ;;
    status)
        echo "callback_enabled=$([ -e "$STATE/callback-off" ] && echo no || echo yes)"
        echo "callback_owned_this_boot=$(same_boot "$CB/boot" && echo yes || echo no)"
        echo "callback_system_sha=$(digest "$SYSTEM_APK")"
        echo "callback_active_path=$(active_base)"
        ;;
    *) die 'usage: callback.sh ensure|on|off|restore|status';;
esac
