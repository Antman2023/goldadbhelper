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

# Compile the actual app report mapper. Android JSON methods are not executed here;
# receipt parsing is covered separately by FileCopyJournalProbe on Android.
mkdir -p "$OUT/journal-classes"
find core/src/main/java -name '*.java' | sort > "$OUT/journal-sources.txt"
printf '%s\n' app/src/main/java/io/github/devicebridge/android/FileCopyManager.java \
 app/src/main/java/io/github/devicebridge/android/FileTransferManager.java \
 tests/copyjournal/FileCopyCompletionTests.java >> "$OUT/journal-sources.txt"
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
 -cp "$SDK/platforms/android-35/android.jar" -d "$OUT/journal-classes" @"$OUT/journal-sources.txt"
java -ea -cp "$OUT/journal-classes:$SDK/platforms/android-35/android.jar" io.github.devicebridge.android.FileCopyCompletionTests \
 | tee build/test-results/FileCopyCompletionTests.txt
