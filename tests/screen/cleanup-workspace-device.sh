#!/usr/bin/env bash
# Normal fixtures only: run the source-built server, clean its workspace, and
# retain an ordinary unknown sidecar. No symlinks, replacements, or escape tests.
set -euo pipefail
cd "$(dirname "$0")/../.."
ADB=${1:?Pass the adb executable}; PORT=${2:?Pass the adb server port}; SERIAL=${3:?Pass a QA device serial}
OUT=${4:-build/screen-cleanup-device-test}
mkdir -p "$OUT"
A=("$ADB" -P "$PORT" -s "$SERIAL")
SDK=$("${A[@]}" shell getprop ro.build.version.sdk </dev/null | tr -d '\r\n')
[[ "$SDK" =~ ^[0-9]+$ ]] && (( SDK >= 21 )) || { echo 'This screen-server fixture requires API 21+' >&2; exit 1; }
FIRST="/data/local/tmp/goldenbeetle-screen-$(python3 -c 'import uuid; print(uuid.uuid4())')"
SECOND="/data/local/tmp/goldenbeetle-screen-$(python3 -c 'import uuid; print(uuid.uuid4())')"
printf '%s\n%s\n' "$FIRST" "$SECOND" > "$OUT/remote-fixtures.txt"
prepare() {
    local dir=$1 log=$2
    "${A[@]}" shell "umask 077; mkdir '$dir' && echo GB_RESERVED" </dev/null > "$log" 2>&1
    grep -q GB_RESERVED "$log"
    "${A[@]}" push app/src/main/assets/metadata-server.jar "$dir/metadata-server.jar" >> "$log" 2>&1
    "${A[@]}" push app/src/main/assets/scrcpy-server.jar "$dir/scrcpy-server.jar" >> "$log" 2>&1
    "${A[@]}" shell "chmod 0400 '$dir/metadata-server.jar' '$dir/scrcpy-server.jar'" </dev/null >> "$log" 2>&1
}
cleanup() {
    local dir=$1 log=$2
    "${A[@]}" shell "CLASSPATH='$dir/metadata-server.jar' app_process / io.github.devicebridge.server.FileCommitServer cleanup-screen '$dir'; echo GB_CLEANUP_EXIT:\$?" </dev/null > "$log" 2>&1
}
assert_clean() {
    local dir=$1 log=$2
    "${A[@]}" shell "test ! -e '$dir/scrcpy-server.jar' && test ! -e '$dir/metadata-server.jar' && test ! -e '$dir/oat' && rmdir '$dir' && echo GB_WORKSPACE_REMOVED" </dev/null >> "$log" 2>&1
    grep -q GB_WORKSPACE_REMOVED "$log"
}
prepare "$FIRST" "$OUT/prepare.txt"
# Listing codecs exercises real scrcpy app_process/Dex loading without changing
# screen power or starting a capture/control session. Keep the jar for inspection.
"${A[@]}" shell "CLASSPATH='$FIRST/scrcpy-server.jar' app_process / com.genymobile.scrcpy.Server 2.4 list_encoders=true cleanup=false; echo GB_SERVER_EXIT:\$?" </dev/null > "$OUT/server.txt" 2>&1
grep -q GB_SERVER_EXIT:0 "$OUT/server.txt"
"${A[@]}" shell "ls -la '$FIRST'; ls -la '$FIRST/oat' 2>/dev/null" </dev/null > "$OUT/before-cleanup.txt" 2>&1
cleanup "$FIRST" "$OUT/cleanup.txt"
grep -q '"ok":true' "$OUT/cleanup.txt"; grep -q GB_CLEANUP_EXIT:0 "$OUT/cleanup.txt"
assert_clean "$FIRST" "$OUT/cleanup.txt"

prepare "$SECOND" "$OUT/sidecar-prepare.txt"
# This regular marker is owned by this fixture but intentionally absent from the
# production whitelist. The production helper must leave it and return failure.
"${A[@]}" shell "mkdir '$SECOND/oat' '$SECOND/oat/arm'; printf keep > '$SECOND/oat/arm/fixture-note.txt'" </dev/null >> "$OUT/sidecar-prepare.txt" 2>&1
cleanup "$SECOND" "$OUT/sidecar-retained.txt"
grep -q '"ok":false' "$OUT/sidecar-retained.txt"; grep -q GB_CLEANUP_EXIT:1 "$OUT/sidecar-retained.txt"
"${A[@]}" shell "test -f '$SECOND/metadata-server.jar' && test -f '$SECOND/scrcpy-server.jar' && test \"\$(cat '$SECOND/oat/arm/fixture-note.txt')\" = keep && echo GB_SIDECAR_RETAINED" </dev/null >> "$OUT/sidecar-retained.txt" 2>&1
grep -q GB_SIDECAR_RETAINED "$OUT/sidecar-retained.txt"
# Remove only the fixture's registered regular marker, then retry normal cleanup.
"${A[@]}" shell "rm '$SECOND/oat/arm/fixture-note.txt'" </dev/null > "$OUT/sidecar-removed.txt" 2>&1
cleanup "$SECOND" "$OUT/after-sidecar-cleanup.txt"
grep -q '"ok":true' "$OUT/after-sidecar-cleanup.txt"; grep -q GB_CLEANUP_EXIT:0 "$OUT/after-sidecar-cleanup.txt"
assert_clean "$SECOND" "$OUT/after-sidecar-cleanup.txt"
printf 'SCREEN_WORKSPACE_CLEANUP_PASSED API%s real server loading; fixed artifacts; unknown regular sidecar retained\n' "$SDK" | tee "$OUT/result.txt"
