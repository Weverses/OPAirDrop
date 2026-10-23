#!/system/bin/sh
# Called synchronously by post-fs-data, before system_server reads feature XML.
set -eu
BASE=${1:?module directory required}
# Optional fixture root for host tests, matching setup-gms.sh.
DEVICE_ROOT=${2:-}
WORK="$BASE/gms-unlock"
. "$BASE/bin/root-runtime.sh"
[ -n "$DEVICE_ROOT" ] || root_enter_global "$0" "$@"
# Android Toybox cannot reliably remount file bind mounts on this device.
# Prefer the active manager's BusyBox; fall back to available native tools.
mount_gms() {
    if [ -n "$DEVICE_ROOT" ]; then mount "$@"
    else root_tool mount "$@"; fi
}
umount_gms() {
    if [ -n "$DEVICE_ROOT" ]; then umount "$@"
    else root_tool umount "$@"; fi
}
[ -d "$WORK" ] || { echo 'GMS overlays not configured'; exit 0; }
count=0
failed=0
while IFS= read -r origin; do
    [ -n "$origin" ] || continue
    if ! grep -Fxq -- "$origin" "$BASE/bin/gms-paths.txt"; then
        echo "Rejected GMS target: $origin"
        exit 1
    fi
done < "$WORK/mounts.list"
while IFS= read -r origin; do
    [ -n "$origin" ] || continue
    source="$WORK/overlays$origin"
    target="$DEVICE_ROOT$origin"
    if [ ! -f "$source" ] || [ ! -f "$target" ]; then
        echo "Missing GMS overlay or target: $origin"
        failed=$((failed + 1))
        continue
    fi
    if mount_gms -o bind "$source" "$target"; then
        if mount_gms -o remount,bind,ro "$source" "$target"; then
            echo "GMS overlay mounted read-only: $origin"
            count=$((count + 1))
        else
            umount_gms "$target" || true
            echo "GMS overlay read-only remount failed: $origin"
            failed=$((failed + 1))
        fi
    else
        echo "GMS overlay mount failed: $origin"
        failed=$((failed + 1))
    fi
done < "$WORK/mounts.list"
echo "GMS unlock: mounted=$count failed=$failed"
[ "$failed" = 0 ]
