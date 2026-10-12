#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/core-classes build/test-results
find core/src/main/java core/src/test/java -name '*.java' | sort > build/core-sources.txt
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d build/core-classes @build/core-sources.txt
for suite in CoreTests DomainTests ProtocolTests ScreenProtocolTests UiSafetyTests FileTransferTests ActivationTests InteractiveShellTests LegacyShellTests LegacyCloseTests AdbOpenCancellationTests ScreenshotCaptureTests TerminalTranscriptTests PackageCommandStrategyTests ApkManifestInspectorTests ModuleNavigationTests; do
  java -ea -cp build/core-classes "io.github.devicebridge.core.$suite" | tee "build/test-results/$suite.txt"
done
java -ea -cp build/core-classes io.github.muntashirakon.crypto.ed25519.FieldArithmeticTests | tee build/test-results/FieldArithmeticTests.txt
java --add-opens=java.base/java.net=ALL-UNNAMED -ea -cp "build/core-classes:vendor/conscrypt-openjdk-uber-2.5.2.jar" io.github.devicebridge.core.PairingTests | tee build/test-results/PairingTests.txt

SDK=${ANDROID_HOME:-/workspace/shared/device_bridge_toolchain/android-sdk}
mkdir -p build/transfer-server-classes
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -cp "$SDK/platforms/android-35/android.jar" -d build/transfer-server-classes server/src/io/github/devicebridge/server/FileCommitServer.java core/src/main/java/io/github/devicebridge/core/AndroidFileAccess.java server/test/io/github/devicebridge/server/FileCommitServerTests.java
java -ea -cp "build/transfer-server-classes:$SDK/platforms/android-35/android.jar" io.github.devicebridge.server.FileCommitServerTests | tee build/test-results/FileCommitServerTests.txt

bash tests/local/run.sh

bash tests/localapps/run.sh

bash tools/test-file-copy.sh
