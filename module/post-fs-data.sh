#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/opairshare
mkdir -p "$STATE"
chmod 0700 "$STATE"
/system/bin/sh "$MODDIR/bin/gms-unlock.sh" "$MODDIR" > "$STATE/gms-unlock.log" 2>&1
