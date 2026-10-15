#!/usr/bin/env bash
# Production USB adapters with Android USB stand-ins; no hardware or native I/O.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=build/usb-host-tests
mkdir -p "$OUT/classes"
find core/src/main/java tests/usb/stubs tests/usb/src -name '*.java' | sort > "$OUT/sources.txt"
printf '%s\n' app/src/main/java/io/github/devicebridge/android/{UsbBulkConnection,UsbTransport,UsbFastbootTransport}.java >> "$OUT/sources.txt"
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"
java -ea -cp "$OUT/classes" io.github.devicebridge.android.UsbTransportTests | tee "$OUT/results.txt"
