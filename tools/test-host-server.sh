#!/usr/bin/env bash
# Isolated synthetic loopback smart-socket and existing in-memory/raw-wire tests.
# No adb executable, original APK/native asset, VM, device, account, or remote target.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/host-server-regression
mkdir -p "$OUT/classes" "$OUT/results"
find core/src/main/java core/src/test/java -name '*.java' | sort > "$OUT/sources.txt"
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"
for suite in HostServerTests CoreTests DomainTests ProtocolTests ScreenProtocolTests InteractiveShellTests LegacyShellTests LegacyCloseTests ScreenshotCaptureTests TerminalTranscriptTests; do
  java -ea -cp "$OUT/classes" "io.github.devicebridge.core.$suite" | tee "$OUT/results/$suite.txt"
done
