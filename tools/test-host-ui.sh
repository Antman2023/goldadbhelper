#!/usr/bin/env bash
# Actual ExternalAdbController + actual core; Android/UI/Host/runtime are memory stand-ins.
# Only narrow synthetic 127.0.0.1 endpoints. No adb executable, VM, APK or real devices.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d "${TMPDIR:-/tmp}/devicebridge-hostui.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
sha256sum app/src/main/java/io/github/devicebridge/android/ExternalAdbController.java app/src/main/java/io/github/devicebridge/android/BridgeRuntime.java core/src/main/java/io/github/devicebridge/core/{AdbServerEndpoint,HostServerClient,AdbSession,OperationScope}.java > tests/hostui/source-hashes.txt
mkdir -p "$OUT/classes"
python3 tests/hostui/extract-runtime.py "$OUT/BridgeRuntimeObjectBindingSubject.java"
find core/src/main/java tests/hostui/stubs tests/hostui/src -name '*.java' | sort > "$OUT/sources.txt"
printf '%s\n' app/src/main/java/io/github/devicebridge/android/ExternalAdbController.java "$OUT/BridgeRuntimeObjectBindingSubject.java" >> "$OUT/sources.txt"
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"
{
  java -ea -cp "$OUT/classes" io.github.devicebridge.android.ExternalAdbControllerTests
  java -ea -cp "$OUT/classes" io.github.devicebridge.android.BridgeRuntimeObjectBindingTests
} | tee tests/hostui/results.txt
