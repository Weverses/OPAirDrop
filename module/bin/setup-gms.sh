#!/system/bin/sh
# Adapted from fei-ke's user-supplied Unlock CN GMS v3.8.
# Prepare private overlays; the device's original files are never edited.
set -eu
BASE=${1:?module directory required}
# Optional fixture root used by host tests; actual installation leaves it empty.
DEVICE_ROOT=${2:-}
[ -f "$BASE/module.prop" ] || { echo 'Invalid module directory'; exit 1; }
WORK="$BASE/gms-unlock"
STAGE=$(mktemp -d "$BASE/.gms-unlock.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT
: > "$STAGE/mounts.list"
: > "$STAGE/SHA256SUMS"
count=0
while IFS= read -r origin; do
    [ -n "$origin" ] || continue
    [ -f "$DEVICE_ROOT$origin" ] || continue
    target="$STAGE/overlays$origin"
    mkdir -p "${target%/*}"
    awk -f "$BASE/bin/gms-filter.awk" "$DEVICE_ROOT$origin" > "$target"
    chown 0:0 "$target"
    chmod 0644 "$target"
    chcon u:object_r:system_file:s0 "$target"
    printf '%s\n' "$origin" >> "$STAGE/mounts.list"
    (cd "$STAGE" && sha256sum "overlays$origin") >> "$STAGE/SHA256SUMS"
    echo "已准备 GMS 配置：$origin"
    count=$((count + 1))
done < "$BASE/bin/gms-paths.txt"
(cd "$STAGE" && sha256sum mounts.list) >> "$STAGE/SHA256SUMS"
chmod 0755 "$STAGE"
chmod 0644 "$STAGE/mounts.list" "$STAGE/SHA256SUMS"
rm -rf "$WORK"
mv "$STAGE" "$WORK"
trap - EXIT
if [ "$count" = 0 ]; then
    echo '未找到国行 GMS 限制配置，跳过解锁挂载。'
else
    echo "已内置 Unlock CN GMS，重启后应用 $count 份配置。Google 服务仍需在系统设置中开启。"
fi
