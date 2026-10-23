#!/system/bin/sh
# Check the effective filesystem, independent of Magic Mount/OverlayFS/module ID.
set -eu
BASE=${1:?module directory required}
DEVICE_ROOT=${2:-}
. "$BASE/bin/root-runtime.sh"
[ -n "$DEVICE_ROOT" ] || root_enter_global "$0" "$@"
failed=0
for rel in system_ext/bin/mosey_server system_ext/lib64/libmosey_daemon_ffi.so \
    system_ext/etc/default-permissions/default-permissions-opairshare-mosey.xml \
    system_ext/etc/permissions/privapp-permissions-opairshare-mosey.xml \
    system_ext/etc/vintf/manifest/manifest_mosey.xml \
    product/etc/permissions/mosey-hiddenapi-opairshare.xml; do
    source="$BASE/system/$rel"
    [ -f "$source" ] || source="$BASE/$rel"
    target="$DEVICE_ROOT/$rel"
    if [ ! -f "$source" ] || [ ! -r "$target" ]; then
        echo "Mount check: missing payload or system view: /$rel" >&2
        failed=1
        continue
    fi
    case "$rel" in
        *.xml) if ! cmp -s "$source" "$target"; then
            echo "Mount check: system configuration differs: /$rel" >&2; failed=1
        fi;;
        */bin/*) if [ ! -x "$target" ]; then
            echo "Mount check: system binary is not executable: /$rel" >&2; failed=1
        fi;;
    esac
done
while read -r expected name; do
    [ -n "$name" ] || continue
    if [ ! -r "$DEVICE_ROOT/system_ext/priv-app/MoseyApp/$name" ]; then
        echo "Mount check: missing system APK: $name" >&2; failed=1
    fi
done < "$BASE/APP-SHA256SUMS"
if [ "$failed" != 0 ]; then
    echo 'Mount check: 请启用管理器的系统挂载功能或兼容挂载模块并重启。Mountify 若使用选择性挂载，请将 opairshare 加入其挂载列表；同时检查模块禁用/skip_mount 状态和应用卸载模块设置。' >&2
    exit 3
fi
echo "Mount check: system payload visible ($(root_manager))"
