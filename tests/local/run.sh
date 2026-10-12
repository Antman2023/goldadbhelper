#!/usr/bin/env bash
# Host-only filesystem/model regression checks. No emulator, connected device or permission grant.
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p build/test-results build/local-picker-tests
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -d build/local-picker-tests \
  app/src/main/java/io/github/devicebridge/android/LocalFileBrowserModel.java \
  tests/local/LocalFileBrowserModelTest.java
java -cp build/local-picker-tests io.github.devicebridge.android.LocalFileBrowserModelTest \
  | tee build/test-results/LocalFileBrowserModelTests.txt
