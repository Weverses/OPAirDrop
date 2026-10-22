#!/system/bin/sh
# Prepare only the private test copy; the installed KSU module is untouched.
set -eu
M=/data/local/tmp/opairshare-na20-module
[ -d "$M/bin" ] && [ -f "$M/payload/kernel/qca_cld3_peach_v2.ko" ]
new_note=$(cat /data/local/tmp/opairshare-qca-na20.note)
case "$new_note" in ''|*[!0-9a-f]*) exit 2;; esac
[ "${#new_note}" = 72 ]
cp /data/local/tmp/opairshare-qca-na20.ko "$M/payload/kernel/qca_cld3_peach_v2.ko"
sed "s/^NOTE_qca_cld3_peach_v2=.*/NOTE_qca_cld3_peach_v2=$new_note/" \
    "$M/bin/kernel-identity.sh" > "$M/bin/kernel-identity.sh.new"
mv "$M/bin/kernel-identity.sh.new" "$M/bin/kernel-identity.sh"
chmod 644 "$M/bin/kernel-identity.sh" "$M/payload/kernel/qca_cld3_peach_v2.ko"
find "$M" -type f | sort | while IFS= read -r path; do
    rel=${path#"$M/"}
    case "$rel" in SHA256SUMS|ARCHIVE-SHA256SUMS) continue;; esac
    digest=$(sha256sum "$path" | awk '{print $1}')
    printf '%s  %s\n' "$digest" "$rel"
done > "$M.SHA256SUMS.new"
mv "$M.SHA256SUMS.new" "$M/SHA256SUMS"
/system/bin/sh "$M/bin/verify.sh" "$M" installed
