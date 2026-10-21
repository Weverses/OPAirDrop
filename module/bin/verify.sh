#!/system/bin/sh
# Accept standard KSU layout or metamodule's relocated system_ext layout.
set -eu
BASE=${1:?}
while read -r expected rel; do
    [ -n "$expected" ] || continue
    path="$BASE/$rel"
    case "$rel" in
        system/system_ext/*|system/product/*)
            if [ ! -f "$path" ]; then path="$BASE/${rel#system/}"; fi
            ;;
    esac
    [ -f "$path" ] || { echo "Missing payload: $rel"; exit 1; }
    actual=$(sha256sum "$path" | awk '{print $1}')
    [ "$actual" = "$expected" ] || { echo "Checksum mismatch: $rel"; exit 1; }
done < "$BASE/SHA256SUMS"
echo "Module payload SHA256 verified"
