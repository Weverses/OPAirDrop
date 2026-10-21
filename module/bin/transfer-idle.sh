#!/system/bin/sh
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
records=$(framework timeout 8 dumpsys activity service "$COMP") || exit 9
if printf '%s\n' "$records" | grep -Eq 'OPAIRSHARE_RECEIVE busy=1([[:space:]]|$)|isTransferFinished=false|activeTransfers=\[[^]]+\]|Active Transfers \([1-9][0-9]*\)|Receive provider transferring: true';then
    echo 'Active transfer; refusing session/stack change';exit 9
fi
if [ -z "$records" ];then
    [ -z "$(pidof "$PKG" || true)" ] && exit 0
    echo 'Empty provider dump while process exists; retaining session';exit 9
fi
if printf '%s\n' "$records" | grep -Eq '^No services match:|^No service found';then
    exit 0
fi
if printf '%s\n' "$records" | grep -q 'ExternalSharingService state:' &&
   printf '%s\n' "$records" | grep -Eq 'Active Transfers \(0\)' &&
   printf '%s\n' "$records" | grep -q 'Receive provider transferring: false';then
    exit 0
fi
echo 'Unrecognized provider transfer state; retaining session';exit 9
