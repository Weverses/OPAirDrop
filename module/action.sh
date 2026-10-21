#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/bin/common.sh"
init_state
if same_boot "$STATE/stack-boot";then command=stop;else command=start;fi
/system/bin/sh "$MODDIR/bin/control.sh" "$command" > "$STATE/action-last.log" 2>&1
rc=$?
cat "$STATE/action-last.log"
echo "ACTION command=$command rc=$rc"
if [ "$rc" != 0 ];then
    echo "Operation failed; inspect $STATE/action-last.log"
fi
/system/bin/sh "$MODDIR/bin/control.sh" status
exit "$rc"
