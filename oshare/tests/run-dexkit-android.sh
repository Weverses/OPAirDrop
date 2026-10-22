#!/bin/sh
# Offline APK resolution in app_process; no app install, hooks or LSPosed changes.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
sdk=${OSHARE_ANDROID_SDK:-$HOME/Library/Android/sdk}
apk=${1:?usage: run-dexkit-android.sh OShare.apk [baseline]}
mode=${2:-scan}
compile_only=${3:-}
case "$compile_only" in ''|--compile-only) ;; *) echo 'third argument must be --compile-only' >&2; exit 2;; esac
case "$mode" in scan|baseline|renamed|ambiguous) ;; *) echo "unsupported test mode: $mode" >&2; exit 2;; esac
bridge="$root/app/build/outputs/apk/debug/app-debug.apk"
work=$(mktemp -d)
remote=/data/local/tmp/opairshare-dexkit-test-$$
cleanup() {
    rm -rf "$work"
    if [ "$compile_only" != --compile-only ]; then adb shell rm -rf "$remote" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
mkdir "$work/classes"
# A supplied, fixed-version AAR is only used for javac; runtime deps are in bridge.
aar=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.luckypray/dexkit/2.3.0" -name '*.aar' | head -1)
unzip -p "$aar" classes.jar > "$work/dexkit.jar"
javac -source 17 -target 17 -cp "$sdk/platforms/android-37.0/android.jar:$work/dexkit.jar:$root/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes" \
    -d "$work/classes" "$root/tests/OShareDexResolverTest.java"
jar cf "$work/classes.jar" -C "$work/classes" .
"$sdk/build-tools/36.0.0/d8" --lib "$sdk/platforms/android-37.0/android.jar" --min-api 35 \
    --output "$work/test.jar" "$work/classes.jar"
if [ "$compile_only" = --compile-only ]; then
    echo 'OShare and Chooser DexKit harness compiled successfully (not executed)'
    exit 0
fi
adb shell mkdir -p "$remote"
adb push "$bridge" "$remote/bridge.apk" >/dev/null
adb push "$apk" "$remote/target.apk" >/dev/null
adb push "$work/test.jar" "$remote/test.jar" >/dev/null
adb shell "CLASSPATH=$remote/bridge.apk:$remote/test.jar app_process /system/bin com.tlsu.oshareairdrop.OShareDexResolverTest $remote/bridge.apk $remote/target.apk $mode"
