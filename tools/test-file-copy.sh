#!/usr/bin/env bash
# Focused ordinary copy fixtures only. Does not execute paused adversarial suites.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK=${ANDROID_HOME:-/workspace/shared/device_bridge_toolchain/android-sdk}
OUT=build/copy-check/test
mkdir -p "$OUT" build/test-results
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options \
 -cp "$SDK/platforms/android-35/android.jar" -d "$OUT" \
 server/src/io/github/devicebridge/server/FileCommitServer.java \
 server/src/io/github/devicebridge/server/FileCopyEngine.java \
 server/src/io/github/devicebridge/server/FileMoveEngine.java \
 core/src/main/java/io/github/devicebridge/core/AndroidFileAccess.java \
 server/test/io/github/devicebridge/server/FileCopyEngineTests.java \
 server/test/io/github/devicebridge/server/FileMoveEngineTests.java
java -ea -cp "$OUT:$SDK/platforms/android-35/android.jar" io.github.devicebridge.server.FileCopyEngineTests \
 | tee build/test-results/FileCopyEngineTests.txt
java -ea -cp "$OUT:$SDK/platforms/android-35/android.jar" io.github.devicebridge.server.FileMoveEngineTests \
 | tee build/test-results/FileMoveEngineTests.txt
