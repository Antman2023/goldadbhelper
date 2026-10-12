#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ADB=${1:-/workspace/shared/device_bridge_toolchain/android-sdk/platform-tools/adb};PORT=${2:-5041};SERIAL=${3:-emulator-5560};OUT=${4:-build/compat/tls-device-test};APK=${5:-build/candidate19-v2/outputs/金甲虫-0.1.0-dev.apk};MODE=${6:-tls};VM=${7:-default};PROBE=${8:-build/compat/tls-production-probe.jar}
case "$MODE" in tls|spake) ;; *) exit 2;; esac
case "$VM" in default) VMARG=;; int) VMARG=-Xint;; *) exit 2;; esac
mkdir -p "$OUT";sha256sum "$APK" "$PROBE" > "$OUT/input-sha256.txt";DIR="/data/local/tmp/gb-compat-tls-$(date +%s)-$$";A=("$ADB" -P "$PORT" -s "$SERIAL")
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
CP="$DIR/probe.jar"
for item in "$OUT"/classes*.jar; do name=$(basename "$item");"${A[@]}" push "$item" "$DIR/$name" >> "$OUT/push.txt" 2>&1;CP="$CP:$DIR/$name";done
"${A[@]}" push "$PROBE" "$DIR/probe.jar" >> "$OUT/push.txt" 2>&1
"${A[@]}" push "$OUT/libconscrypt_jni.so" "$DIR/libconscrypt_jni.so" >> "$OUT/push.txt" 2>&1
"${A[@]}" shell "CLASSPATH='$CP' LD_LIBRARY_PATH='$DIR:/system/lib:/vendor/lib' app_process $VMARG -Djava.library.path='$DIR:/system/lib:/vendor/lib' / io.github.devicebridge.core.Api19TlsProbe '$MODE'; echo GB_TLS_EXIT:\$?" </dev/null > "$OUT/probe.txt" 2>&1
cat "$OUT/probe.txt";printf '%s\n' "$DIR" > "$OUT/remote-fixture.txt";printf '%s\n' "$VM" > "$OUT/vm-mode.txt"
grep -q 'API19_TLS_PROBE_PASSED' "$OUT/probe.txt";grep -q GB_TLS_EXIT:0 "$OUT/probe.txt"
