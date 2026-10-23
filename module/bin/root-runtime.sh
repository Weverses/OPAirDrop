#!/system/bin/sh
# Manager names are diagnostic hints. Admission depends on root and capabilities.
root_manager() {
    if [ "${APATCH:-false}" = true ]; then echo APatch
    elif [ "${KSU:-false}" = true ]; then echo KernelSU
    elif [ -n "${MAGISK_VER:-}" ] || [ -n "${MAGISK_VER_CODE:-}" ]; then echo Magisk
    elif [ -x /data/adb/ap/bin/apd ]; then echo APatch
    elif [ -x /data/adb/ksud ] || [ -x /data/adb/ksu/bin/ksud ]; then echo KernelSU
    elif [ -x /data/adb/magisk/magisk ]; then echo Magisk
    else echo 'compatible root'; fi
}

root_find_tool() {
    root_applet=$1
    case "$(root_manager)" in
        APatch) root_preferred=/data/adb/ap/bin/busybox;;
        Magisk) root_preferred=/data/adb/magisk/busybox;;
        *) root_preferred=/data/adb/ksu/bin/busybox;;
    esac
    root_path_busybox=$(command -v busybox 2>/dev/null) || root_path_busybox=
    for root_candidate in "$root_preferred" /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox "$root_path_busybox"; do
        [ -x "$root_candidate" ] || continue
        "$root_candidate" --list 2>/dev/null | grep -Fxq "$root_applet" || continue
        ROOT_TOOL=$root_candidate
        ROOT_TOOL_BUSYBOX=1
        return 0
    done
    ROOT_TOOL_BUSYBOX=0
    if [ -x "/system/bin/$root_applet" ]; then ROOT_TOOL=/system/bin/$root_applet; return 0; fi
    ROOT_TOOL=$(command -v "$root_applet") && [ -x "$ROOT_TOOL" ] && return 0
    echo "Root runtime: required tool unavailable: $root_applet" >&2
    return 2
}

root_tool() {
    root_find_tool "$1" || return 2
    if [ "$ROOT_TOOL_BUSYBOX" = 1 ]; then "$ROOT_TOOL" "$@"
    else shift; "$ROOT_TOOL" "$@"; fi
}

root_enter_global() {
    root_self_ns=$(readlink /proc/self/ns/mnt) || return 2
    root_init_ns=$(readlink /proc/1/ns/mnt) || return 2
    [ "$root_self_ns" != "$root_init_ns" ] || return 0
    [ "${OPAIRSHARE_GLOBAL_ENTERED:-}" != "$1" ] || {
        echo 'Root runtime: cannot enter the Android global mount namespace' >&2; return 2;
    }
    root_find_tool nsenter || return 2
    export OPAIRSHARE_GLOBAL_ENTERED="$1"
    if [ "$ROOT_TOOL_BUSYBOX" = 1 ]; then
        exec "$ROOT_TOOL" nsenter -t 1 -m /system/bin/sh "$@"
    else
        exec "$ROOT_TOOL" -t 1 -m /system/bin/sh "$@"
    fi
}

root_write_policy() (
    root_base=$1
    # Use the actual install context on forks; recovery installs use manager hints.
    root_context=$(id -Z 2>/dev/null) || root_context=
    root_domain=$(printf '%s\n' "$root_context" | cut -d: -f3)
    case "$root_domain" in
        ksu|magisk|su) ;;
        *) case "$(root_manager)" in
            KernelSU) root_domain=ksu;;
            APatch|Magisk) root_domain=magisk;;
            *) echo "Root runtime: unsupported SELinux context: $root_context" >&2; exit 2;;
        esac;;
    esac
    sed "s/ROOT_DOMAIN/$root_domain/g" "$root_base/bin/root-sepolicy.rule" > "$root_base/sepolicy.rule"
)
