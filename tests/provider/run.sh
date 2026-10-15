#!/usr/bin/env bash
# Production provider + framework stand-ins; private host files, no Android grants.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=build/provider-host-tests
mkdir -p "$OUT/classes"
find tests/provider/stubs tests/provider/src -name '*.java' | sort > "$OUT/sources.txt"
printf '%s\n' app/src/main/java/io/github/devicebridge/android/LocalFileProvider.java >> "$OUT/sources.txt"
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"
java -ea -cp "$OUT/classes" io.github.devicebridge.android.LocalFileProviderTests | tee "$OUT/results.txt"
