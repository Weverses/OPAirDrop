#!/system/bin/sh
# Late APK mounts: original signed APKs remain available during package scanning.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
CB="$STATE/callback"
SYSTEM_APK=/system_ext/priv-app/MoseyApp/base.apk
BB=/data/adb/ksu/bin/busybox
die() { echo "Callback patch: $*" >&2; exit 3; }
digest() { sha256sum "$1" | awk '{print $1}'; }
base_digest() { awk '$2=="base.apk" {print $1}' "$1"; }
original=$(base_digest "$MODDIR/APP-SHA256SUMS")
patched=$(base_digest "$MODDIR/APP-CALLBACK-SHA256SUMS")
if [ "$(id -u)" != 0 ]; then die 'root required'; fi
if [ "$(readlink /proc/self/ns/mnt)" != "$(readlink /proc/1/ns/mnt)" ]; then
    exec "$BB" nsenter -t 1 -m /system/bin/sh "$0" "$@"
fi
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
    # Check the actual upper bind source, including a prior module's retained
    # backing files; never detach the lower Magic Mount view.
    awk -v target="$1" -v source="$2" '$5==target {root=$4} END {exit(root!=source)}' /proc/self/mountinfo
}
restore_owned() {
    same_boot "$CB/boot" || return 0
    for pair in "system:$SYSTEM_APK" "active:$(cat "$CB/active-path")"; do
        kind=${pair%%:*}; target=${pair#*:}
        [ "$kind" != active ] || [ "$target" != "$SYSTEM_APK" ] || continue
        if owned "$target" "/adb/opairshare/callback/$kind.apk"; then
            umount "$target" || "$BB" umount -l "$target"
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
            owned "$SYSTEM_APK" /adb/opairshare/callback/system.apk || die 'system patch mount changed'
            if [ "$active" != "$SYSTEM_APK" ]; then
                owned "$active" /adb/opairshare/callback/active.apk || die 'active patch mount changed'
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
            mount --bind "$CB/active.apk" "$active"
            "$BB" mount -o remount,bind,ro "$CB/active.apk" "$active"
        fi
        mount --bind "$CB/system.apk" "$SYSTEM_APK"
        "$BB" mount -o remount,bind,ro "$CB/system.apk" "$SYSTEM_APK"
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
