#!/bin/sh
# Validate the installed sender page contract offline; no vendor assets are redistributed.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
upk=${1:?usage: run-sending-assets.sh /absolute/path/to/OShareSending2.0.upk}
sdk=${OSHARE_ANDROID_SDK:-$HOME/Library/Android/sdk}
json_jar=${OSHARE_JSON_JAR:-}
if [ -z "$json_jar" ]; then
    json_jar=$(rg --files "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json" -g '*.jar' | head -n 1)
fi
if [ ! -f "$json_jar" ]; then
    echo "Set OSHARE_JSON_JAR to a local org.json JAR" >&2
    exit 1
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
classpath="$json_jar:$sdk/platforms/android-37.0/android.jar"
javac -cp "$classpath" -d "$work" \
    "$root/tests/android/com/tlsu/oshareairdrop/Entry.java" \
    "$root/app/src/main/java/com/tlsu/oshareairdrop/OShareSendingAssets.java" \
    "$root/tests/OShareSendingAssetsTest.java"
java -cp "$work:$classpath" com.tlsu.oshareairdrop.OShareSendingAssetsTest "$upk"
