#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=build/nav02-lifecycle-host-tests
mkdir -p "$OUT/classes"
python3 tests/navigation/lifecycle/extract.py "$OUT/generated"
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d "$OUT/classes" \
  core/src/main/java/io/github/devicebridge/core/OperationScope.java \
  core/src/main/java/io/github/devicebridge/core/RetainedModuleState.java \
  "$OUT/generated/Nav02LifecycleTests.java"
java -ea -cp "$OUT/classes" Nav02LifecycleTests | tee "$OUT/results.txt"
