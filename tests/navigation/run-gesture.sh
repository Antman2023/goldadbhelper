#!/usr/bin/env bash
# In-memory synthetic input only: no emulator, device, private files, or server operations.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=build/nav-slow-fold-host-tests
mkdir -p "$OUT/classes"
find tests/navigation/gesture -name '*.java' | sort > "$OUT/sources.txt"
printf '%s\n' core/src/main/java/io/github/devicebridge/core/ModuleSwipePolicy.java app/src/main/java/io/github/devicebridge/android/CollapsingRoot.java >> "$OUT/sources.txt"
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"
java -ea -cp "$OUT/classes" io.github.devicebridge.android.CollapsingRootGestureTests | tee "$OUT/results.txt"
