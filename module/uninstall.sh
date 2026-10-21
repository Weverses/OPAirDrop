#!/system/bin/sh
MODDIR=${0%/*}
if /system/bin/sh "$MODDIR/bin/control.sh" stop; then
    /system/bin/sh "$MODDIR/bin/callback.sh" restore || true
fi
echo '请重启以移除挂载；系统分区及固件未被刷写。'
