#!/system/bin/sh
# Accept standard KSU layout or metamodule's relocated system_ext layout.
set -eu
BASE=${1:?}
MODE=${2:-archive}
case "$MODE" in archive|installed) ;; *) echo 'Invalid verification mode'; exit 2;; esac
while read -r expected rel; do
    [ -n "$expected" ] || continue
    path="$BASE/$rel"
    case "$rel" in
        system/system_ext/*|system/product/*)
            if [ ! -f "$path" ]; then path="$BASE/${rel#system/}"; fi
            ;;
    esac
    if [ ! -f "$path" ]; then
        # KernelSU removes these installation-only files after customize.sh.
        # Archive/install validation remains strict; runtime payload never skips.
        case "$MODE:$rel" in installed:README.md|installed:customize.sh) continue;; esac
        echo "Missing payload: $rel"; exit 1
    fi
    actual=$(sha256sum "$path" | awk '{print $1}')
    [ "$actual" = "$expected" ] || { echo "Checksum mismatch: $rel"; exit 1; }
done < "$BASE/SHA256SUMS"
echo "Module payload SHA256 verified"
