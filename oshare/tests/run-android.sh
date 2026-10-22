#!/bin/sh
# Uses the production callback code with only the LSPosed logger stubbed out.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
sdk=${OSHARE_ANDROID_SDK:-$HOME/Library/Android/sdk}
work=$(mktemp -d)
remote_jar=/data/local/tmp/oshare-callback-test-$$.jar
remote_cache=/data/local/tmp/oshare-callback-cache-$$
trap 'rm -rf "$work"; adb shell rm -f "$remote_jar" >/dev/null 2>&1 || true' EXIT
mkdir "$work/classes"
src="$root/app/src/main/java/com/tlsu/oshareairdrop"
javac -cp "$sdk/platforms/android-37.0/android.jar" -d "$work/classes" \
    "$root"/tests/android/com/tlsu/oshareairdrop/*.java \
    "$src/ProviderApi.java" "$src/OutgoingTransfers.java" "$src/CallbackFactory.java" \
    "$src/ProviderHolder.java" "$src/HostContext.java" "$src/IncomingTransfers.java" \
    "$src/AirDropName.java" "$src/ReceiveDirectory.java" "$src/IncomingReceiver.java" "$src/ReceiveFiles.java" "$src/BridgeMessenger.java" \
    "$src/DiscoverySession.java" "$src/ShareMode.java" "$src/ShareModeRuntime.java" "$src/StartupTrace.java"
jar cf "$work/classes.jar" -C "$work/classes" .
"$sdk/build-tools/36.0.0/d8" --lib "$sdk/platforms/android-37.0/android.jar" \
    --output "$work/test.jar" "$work/classes.jar"
adb push "$work/test.jar" "$remote_jar"
adb shell "CLASSPATH=$remote_jar app_process /system/bin com.tlsu.oshareairdrop.TransferCallbackTest $remote_cache"
adb shell "CLASSPATH=$remote_jar app_process /system/bin com.tlsu.oshareairdrop.BridgeAccessTest"
adb shell "CLASSPATH=$remote_jar app_process /system/bin com.tlsu.oshareairdrop.BridgeHandshakeTest"
