#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac -d "$work" "$root/app/src/main/java/com/tlsu/oshareairdrop/DiscoveryChannels.java" "$root/tests/DiscoveryChannelsTest.java" "$root/app/src/main/java/com/tlsu/oshareairdrop/CallbackFactory.java" "$root/tests/CallbackFactoryTest.java" "$root/app/src/main/java/com/tlsu/oshareairdrop/OutgoingTransfers.java" "$root/tests/OutgoingTransfersTest.java" "$root/app/src/main/java/com/tlsu/oshareairdrop/IncomingTransfers.java" "$root/tests/IncomingTransfersTest.java" "$root/app/src/main/java/com/tlsu/oshareairdrop/AirDropPresentation.java" "$root/tests/AirDropPresentationTest.java"
java -cp "$work" CallbackFactoryTest
java -cp "$work" OutgoingTransfersTest
java -cp "$work" IncomingTransfersTest
javac -d "$work" "$root/app/src/main/java/com/tlsu/oshareairdrop/AirDropName.java" "$root/tests/AirDropNameTest.java"
java -cp "$work" AirDropNameTest

java -cp "$work" AirDropPresentationTest

java -cp "$work" DiscoveryChannelsTest

javac -d "$work" "$root/app/src/main/java/com/tlsu/oshareairdrop/OShareApkFiles.java" "$root/tests/OShareApkFilesTest.java"
java -cp "$work" com.tlsu.oshareairdrop.OShareApkFilesTest
