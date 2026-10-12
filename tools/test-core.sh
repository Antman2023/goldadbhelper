#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/core-classes
find core/src/main/java core/src/test/java -name '*.java' | sort > build/core-sources.txt
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -Xlint:all -d build/core-classes @build/core-sources.txt
java -ea -cp build/core-classes io.github.devicebridge.core.CoreTests
