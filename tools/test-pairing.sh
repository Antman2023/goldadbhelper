#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/pairing-classes
find core/src/main/java core/src/test/java -name '*.java' | sort > build/pairing-sources.txt
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d build/pairing-classes @build/pairing-sources.txt
# Conscrypt OpenJDK 2.5.2 predates strong JDK module encapsulation. This is a
# test-process-only compatibility option; Android uses the dedicated Android AAR.
java --add-opens=java.base/java.net=ALL-UNNAMED -ea -cp "build/pairing-classes${CONSCRYPT_TEST_JAR:+:$CONSCRYPT_TEST_JAR}" io.github.devicebridge.core.PairingTests
