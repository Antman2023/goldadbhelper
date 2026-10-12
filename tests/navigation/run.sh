#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=build/navigation-host-tests
mkdir -p "$OUT/classes"
find tests/navigation/stubs tests/navigation/src -name '*.java' | sort > "$OUT/sources.txt"
printf '%s\n' app/src/main/java/io/github/devicebridge/android/RetainedModulePager.java >> "$OUT/sources.txt"
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"
java -ea -cp "$OUT/classes" io.github.devicebridge.android.RetainedModulePagerTests | tee "$OUT/results.txt"
