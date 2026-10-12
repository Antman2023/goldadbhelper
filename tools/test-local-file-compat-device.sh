#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ADB=${1:-/workspace/shared/device_bridge_toolchain/android-sdk/platform-tools/adb}; PORT=${2:-5041}; SERIAL=${3:-emulator-5560}; OUT=${4:-build/compat/local-file-test}
mkdir -p "$OUT"; DIR="/data/local/tmp/gb-compat-files-$(date +%s)-$$"; A=("$ADB" -P "$PORT" -s "$SERIAL")
"${A[@]}" shell "mkdir '$DIR' && chmod 700 '$DIR'; ln -s '$DIR/source' '$DIR/source-link'; ln -s '$DIR/picked' '$DIR/directory-link'" </dev/null > "$OUT/prepare.txt"
"${A[@]}" push build/compat/local-file-probe.jar "$DIR/probe.jar" > "$OUT/push.txt" 2>&1
"${A[@]}" shell "CLASSPATH='$DIR/probe.jar' app_process / io.github.devicebridge.android.CompatibilityFileProbe '$DIR'; echo GB_LOCAL_EXIT:\$?" </dev/null > "$OUT/probe.txt" 2>&1
cat "$OUT/probe.txt"; printf '%s\n' "$DIR" > "$OUT/remote-fixture.txt"
grep -q 'LOCAL_FILE_COMPATIBILITY_PROBE_PASSED' "$OUT/probe.txt"; grep -q 'GB_LOCAL_EXIT:0' "$OUT/probe.txt"
