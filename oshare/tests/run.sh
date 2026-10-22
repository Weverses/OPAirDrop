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

javac -d "$work" "$root/app/src/main/java/com/tlsu/oshareairdrop/DiscoverySession.java" "$root/tests/DiscoverySessionTest.java"
java -cp "$work" com.tlsu.oshareairdrop.DiscoverySessionTest
javac -d "$work" "$root/app/src/main/java/com/tlsu/oshareairdrop/ShareMode.java" "$root/tests/ShareModeTest.java"
java -cp "$work" com.tlsu.oshareairdrop.ShareModeTest
javac -cp "$work" -d "$work" "$root/app/src/main/java/com/tlsu/oshareairdrop/TransferHistory.java" "$root/tests/TransferHistoryTest.java"
java -cp "$work" com.tlsu.oshareairdrop.TransferHistoryTest
javac -d "$work/mode-runtime" "$root"/tests/mode-runtime/android/*/*.java \
    "$root"/tests/mode-runtime/com/tlsu/oshareairdrop/*.java \
    "$root/app/src/main/java/com/tlsu/oshareairdrop/ShareMode.java" \
    "$root/app/src/main/java/com/tlsu/oshareairdrop/ShareModeRuntime.java"
java -cp "$work/mode-runtime" com.tlsu.oshareairdrop.ShareModeRuntimeTest

javac -cp "$work/mode-runtime" -d "$work/mode-hooks" \
    "$root"/tests/mode-hooks/io/github/libxposed/api/*.java \
    "$root"/tests/mode-hooks/com/tlsu/oshareairdrop/*.java \
    "$root/app/src/main/java/com/tlsu/oshareairdrop/ShareModeHooks.java"
java -cp "$work/mode-hooks:$work/mode-runtime" com.tlsu.oshareairdrop.ShareModeHooksTest

javac -d "$work" "$root/app/src/main/java/com/tlsu/oshareairdrop/BridgeReconnect.java" "$root/tests/BridgeReconnectTest.java"
java -cp "$work" com.tlsu.oshareairdrop.BridgeReconnectTest

javac -d "$work" "$root/app/src/main/java/com/tlsu/oshareairdrop/OShareApkFiles.java" "$root/tests/OShareApkFilesTest.java"
java -cp "$work" com.tlsu.oshareairdrop.OShareApkFilesTest

javac -d "$work" "$root/app/src/main/java/com/tlsu/oshareairdrop/ReceiveDirectory.java" "$root/tests/ReceiveDirectoryTest.java" "$root/app/src/main/java/com/tlsu/oshareairdrop/ReceiveUiPolicy.java" "$root/tests/ReceiveUiPolicyTest.java"
java -cp "$work" com.tlsu.oshareairdrop.ReceiveDirectoryTest
java -cp "$work" com.tlsu.oshareairdrop.ReceiveUiPolicyTest

javac -cp "$work" -d "$work" "$root/app/src/main/java/com/tlsu/oshareairdrop/SendUiState.java" "$root/tests/SendUiStateTest.java"
java -cp "$work" com.tlsu.oshareairdrop.SendUiStateTest

javac -cp "$work" -d "$work" "$root/app/src/main/java/com/tlsu/oshareairdrop/ChooserTargets.java" \
    "$root/app/src/main/java/com/tlsu/oshareairdrop/ChooserSessions.java" \
    "$root/app/src/main/java/com/tlsu/oshareairdrop/PendingShares.java" \
    "$root/app/src/main/java/com/tlsu/oshareairdrop/OutgoingPreparations.java" "$root/tests/ChooserIntegrationTest.java"
java -cp "$work" com.tlsu.oshareairdrop.ChooserIntegrationTest
