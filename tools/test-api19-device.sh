#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ADB=${1:-/workspace/shared/device_bridge_toolchain/android-sdk/platform-tools/adb}
PORT=${2:-5041}
SERIAL=${3:-emulator-5560}
OUT=${4:-build/compat/device-test}
APK=${5:-build/compat/apk/outputs/金甲虫-0.1.0-dev.apk}
mkdir -p "$OUT"
DIR="/data/local/tmp/gb-compat-$(date +%s)-$$"
A=("$ADB" -P "$PORT" -s "$SERIAL")
ABI=$("${A[@]}" shell getprop ro.product.cpu.abi </dev/null | tr -d '\r\n')
python3 - "$APK" "$OUT" "$ABI" <<'PY'
import sys,pathlib,zipfile
out=pathlib.Path(sys.argv[2])
with zipfile.ZipFile(sys.argv[1]) as apk:
 for name in sorted(n for n in apk.namelist() if n.startswith('classes') and n.endswith('.dex')):
  with zipfile.ZipFile(out/(name+'.jar'),'w') as jar:jar.writestr('classes.dex',apk.read(name))
 (out/'libconscrypt_jni.so').write_bytes(apk.read('lib/'+sys.argv[3]+'/libconscrypt_jni.so'))
PY
"${A[@]}" shell "mkdir '$DIR' && chmod 700 '$DIR'" </dev/null > "$OUT/prepare.txt"
CP=""
for item in "$OUT"/classes*.jar; do
 name=$(basename "$item"); "${A[@]}" push "$item" "$DIR/$name" >> "$OUT/push.txt" 2>&1
 CP="${CP:+$CP:}$DIR/$name"
done
"${A[@]}" push build/compat/device-probe.jar "$DIR/probe.jar" >> "$OUT/push.txt" 2>&1
"${A[@]}" push "$OUT/libconscrypt_jni.so" "$DIR/libconscrypt_jni.so" >> "$OUT/push.txt" 2>&1
"${A[@]}" shell "ln -s '$DIR/probe-bytes' '$DIR/symlink'; CLASSPATH='$DIR/probe.jar:$CP' LD_LIBRARY_PATH='$DIR:/system/lib:/vendor/lib' app_process -Djava.library.path='$DIR:/system/lib:/vendor/lib' / io.github.devicebridge.core.Api19CompatibilityProbe '$DIR' '$DIR/libconscrypt_jni.so'; echo GB_PROBE_EXIT:\$?" </dev/null > "$OUT/probe.txt" 2>&1
cat "$OUT/probe.txt"
printf '%s\n' "$DIR" > "$OUT/remote-fixture.txt"
# Keep the exclusively named test directory for debugging; no broad or final-path deletion.
grep -q 'API19_COMPATIBILITY_PROBE_PASSED' "$OUT/probe.txt"
grep -q 'GB_PROBE_EXIT:0' "$OUT/probe.txt"
