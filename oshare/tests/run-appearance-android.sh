#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
sdk=${OSHARE_ANDROID_SDK:-$HOME/Library/Android/sdk}
work=$(mktemp -d)
remote=/data/local/tmp/oshare-appearance-test-$$.jar
trap 'rm -rf "$work"; adb shell rm -f "$remote" >/dev/null 2>&1 || true' EXIT
mkdir "$work/classes"
src="$root/app/src/main/java/com/tlsu/oshareairdrop"
javac -cp "$sdk/platforms/android-37.0/android.jar" -d "$work/classes" \
    "$root/tests/appearance/AppearanceStateTest.java" "$src/OShareAppearance.java" \
    "$src/AirDropPresentation.java" "$src/OutgoingTransfers.java"
jar cf "$work/classes.jar" -C "$work/classes" .
"$sdk/build-tools/36.0.0/d8" --lib "$sdk/platforms/android-37.0/android.jar" --output "$work/test.jar" "$work/classes.jar"
adb push "$work/test.jar" "$remote"
adb shell "CLASSPATH=$remote app_process /system/bin com.tlsu.oshareairdrop.AppearanceStateTest"
