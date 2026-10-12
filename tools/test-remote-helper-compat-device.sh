#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ADB=${1:-/workspace/shared/device_bridge_toolchain/android-sdk/platform-tools/adb}; PORT=${2:-5041}; SERIAL=${3:-emulator-5560}; OUT=${4:-build/compat/helper-device-test}
mkdir -p "$OUT"; ID=$(python3 -c 'import uuid; print(uuid.uuid4())'); DIR="/data/local/tmp/goldenbeetle-transfer-$ID"; OUTSIDE="/data/local/tmp/gb-compat-outside-$ID"; A=("$ADB" -P "$PORT" -s "$SERIAL")
python3 - "$OUT" <<'PY'
import pathlib,sys
(pathlib.Path(sys.argv[1])/'payload.bin').write_bytes(bytes(range(256))*32+b'\x00\x0a\x0d')
PY
SHA=$(sha256sum "$OUT/payload.bin" | cut -d ' ' -f1); BYTES=$(wc -c < "$OUT/payload.bin")
"${A[@]}" shell "mkdir '$DIR' '$OUTSIDE'; chmod 700 '$DIR' '$OUTSIDE'; echo keep > '$OUTSIDE/metadata-server.odex'" </dev/null > "$OUT/prepare.txt"
"${A[@]}" push app/src/main/assets/metadata-server.jar "$DIR/metadata-server.jar" > "$OUT/push.txt" 2>&1
"${A[@]}" push "$OUT/payload.bin" "$DIR/payload" >> "$OUT/push.txt" 2>&1
"${A[@]}" shell "chmod 400 '$DIR/metadata-server.jar'; CLASSPATH='$DIR/metadata-server.jar' app_process / io.github.devicebridge.server.FileCommitServer commit '$DIR/payload' '$DIR/result' '$SHA' '$BYTES'; echo GB_COMMIT_EXIT:\$?" </dev/null > "$OUT/commit.txt" 2>&1
"${A[@]}" pull "$DIR/result" "$OUT/result.bin" >> "$OUT/push.txt" 2>&1
cmp "$OUT/payload.bin" "$OUT/result.bin"; grep -q '"ok":true' "$OUT/commit.txt"; grep -q GB_COMMIT_EXIT:0 "$OUT/commit.txt"
"${A[@]}" shell "CLASSPATH='$DIR/metadata-server.jar' app_process / io.github.devicebridge.server.FileCommitServer commit '$DIR/payload' '$DIR/result' '$SHA' '$BYTES'; echo GB_EXCLUSION_EXIT:\$?" </dev/null > "$OUT/exclusion.txt" 2>&1
grep -q '"ok":false' "$OUT/exclusion.txt"; grep -q NOT_COMMITTED "$OUT/exclusion.txt"; grep -q GB_EXCLUSION_EXIT:1 "$OUT/exclusion.txt"
"${A[@]}" shell "mkdir -p '$DIR/oat/x86'; echo generated > '$DIR/oat/x86/metadata-server.odex'; echo generated > '$DIR/oat/x86/metadata-server.vdex'; CLASSPATH='$DIR/metadata-server.jar' app_process / io.github.devicebridge.server.FileCommitServer cleanup '$DIR'; test ! -d '$DIR/oat' && echo GB_CLEANUP_PASS" </dev/null > "$OUT/cleanup.txt" 2>&1
grep -q '"ok":true' "$OUT/cleanup.txt"; grep -q GB_CLEANUP_PASS "$OUT/cleanup.txt"
printf '%s\n%s\n' "$DIR" "$OUTSIDE" > "$OUT/remote-fixtures.txt"
printf 'REMOTE_HELPER_COMPATIBILITY_PASSED binary commit; no overwrite; oat cleanup\n' | tee "$OUT/result.txt"
