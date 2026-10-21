#!/system/bin/sh
# Called synchronously by post-fs-data, before system_server reads feature XML.
set -eu
BASE=${1:?module directory required}
# Optional fixture root for host tests, matching setup-gms.sh.
DEVICE_ROOT=${2:-}
WORK="$BASE/gms-unlock"
# Android Toybox cannot reliably remount file bind mounts on this device.
# Pin KernelSU's BusyBox instead of depending on standalone-ash/PATH behavior.
mount_gms() {
    if [ -n "$DEVICE_ROOT" ]; then mount "$@"
    else /data/adb/ksu/bin/busybox mount "$@"; fi
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
            umount "$target" || true
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
