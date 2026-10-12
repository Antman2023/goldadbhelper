#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=build/nav03-neighbor-host-tests
mkdir -p "$OUT/classes"
python3 tests/navigation/neighbor/extract.py "$OUT/generated"
find tests/navigation/stubs -name '*.java' ! -path '*/android/view/View.java' | sort > "$OUT/sources.txt"
find tests/navigation/neighbor/stubs -name '*.java' | sort >> "$OUT/sources.txt"
printf '%s\n' core/src/main/java/io/github/devicebridge/core/OperationScope.java core/src/main/java/io/github/devicebridge/core/RetainedModuleState.java app/src/main/java/io/github/devicebridge/android/RetainedModulePager.java "$OUT/generated/NeighborPreparationTests.java" >> "$OUT/sources.txt"
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"
java -ea -cp "$OUT/classes" io.github.devicebridge.android.NeighborPreparationTests | tee "$OUT/results.txt"
