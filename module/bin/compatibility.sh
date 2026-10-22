#!/system/bin/sh
# Shared install/start device gates: Oplus brand, sun platform, Linux 6.6.118.
compatibility_error() { echo "Compatibility: $*" >&2; return 2; }

compatibility_device() (
    brands=$(getprop ro.product.manufacturer; getprop ro.product.brand; getprop ro.product.vendor.manufacturer; getprop ro.product.vendor.brand)
    printf '%s\n' "$brands" | tr '[:upper:]' '[:lower:]' | grep -Eq '^(oplus|oppo|oneplus|realme)$' || {
        compatibility_error 'Oplus device required (OPPO / OnePlus / realme)'; exit 2;
    }
    board=$(getprop ro.board.platform)
    [ "$board" = sun ] || {
        compatibility_error "sun platform required; found $board"; exit 2;
    }
    kernel=$(uname -r)
    case "$kernel" in
        6.6.118|6.6.118-*|6.6.118+*) ;;
        *) compatibility_error "Linux 6.6.118 required; found $kernel"; exit 2;;
    esac
)
