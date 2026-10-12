#!/usr/bin/env bash
# Ordinary synthetic PackageManager fixtures. Does not build/install an APK or start a VM.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=build/local-app-tests
mkdir -p "$OUT" build/test-results
find tests/localapps/stubs -name '*.java' | sort > "$OUT/sources.txt"
printf '%s\n' app/src/main/java/io/github/devicebridge/android/LocalAppSelection.java \
  app/src/main/java/io/github/devicebridge/android/LocalApps.java tests/localapps/LocalAppsTest.java >> "$OUT/sources.txt"
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -d "$OUT" @"$OUT/sources.txt"
java -ea -cp "$OUT" io.github.devicebridge.android.LocalAppsTest \
  | tee build/test-results/LocalAppsTest.txt
