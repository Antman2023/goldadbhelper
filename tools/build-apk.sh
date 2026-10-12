#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK=${ANDROID_HOME:-/workspace/shared/device_bridge_toolchain/android-sdk}
BT="$SDK/build-tools/35.0.0"; PLATFORM="$SDK/platforms/android-35/android.jar"
BUILD=${BUILD_DIR:-build}
read -r VERSION_CODE VERSION_NAME < <(python3 tools/version.py --format shell)
SIGNING_MODE=${APK_SIGNING_MODE:-debug}
case "$SIGNING_MODE" in debug|unsigned) ;; *) echo 'APK_SIGNING_MODE must be debug or unsigned' >&2; exit 2 ;; esac
mkdir -p "$BUILD"/{generated,classes,dex,desugar-dex,merged-dex,res,outputs}
python3 tools/snapshot-build.py snapshot "$BUILD"
# Each compiler output belongs to this build; stale dex/classes must never enter a new APK.
find "$BUILD/classes" "$BUILD/dex" "$BUILD/desugar-dex" "$BUILD/merged-dex" -type f -delete
DESUGAR=third_party/desugar
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/res/resources.zip"
"$BT/aapt2" link -o "$BUILD/base.apk" -I "$PLATFORM" --manifest app/src/main/AndroidManifest.xml --java "$BUILD/generated" --custom-package io.github.devicebridge.android --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" -A app/src/main/assets "$BUILD/res/resources.zip"
find core/src/main/java app/src/main/java "$BUILD/generated" -name '*.java' | sort > "$BUILD/app-sources.txt"
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$PLATFORM" -classpath "app/libs/conscrypt-android-2.5.3.jar:app/libs/multidex-2.0.1.jar:$BT/core-lambda-stubs.jar" -d "$BUILD/classes" @"$BUILD/app-sources.txt"
python3 tools/snapshot-build.py verify "$BUILD"
cat > "$BUILD/main-dex.pro" <<'EOF'
-keep class io.github.devicebridge.android.BridgeRuntime { *; }
-keep class androidx.multidex.** { *; }
EOF
"$BT/d8" --min-api 19 --lib "$PLATFORM" --desugared-lib "$DESUGAR/desugar.json" --desugared-lib-pg-conf-output "$BUILD/desugar-keep.pro" --main-dex-rules "$BUILD/main-dex.pro" --output "$BUILD/dex" $(find "$BUILD/classes" -name '*.class') app/libs/conscrypt-android-2.5.3.jar app/libs/multidex-2.0.1.jar
# L8 supplies real backported Java APIs. D8 language desugaring alone does not.
java -Xmx2g -cp "$BT/lib/d8.jar" com.android.tools.r8.L8 --release --min-api 19 --lib "$PLATFORM" --desugared-lib "$DESUGAR/desugar.json" --pg-conf "$BUILD/desugar-keep.pro" --output "$BUILD/desugar-dex" "$DESUGAR/desugar_jdk_libs_nio-2.1.5.jar" "$DESUGAR/desugar_jdk_libs_configuration_nio-2.1.5.jar"
# Keep j$ DEX separate, as required by D8; AndroidX installs all secondary files.
python3 - "$BUILD" <<'PY'
import zipfile,pathlib,shutil,sys
b=pathlib.Path(sys.argv[1]);shutil.copyfile(b/'base.apk',b/'unsigned.apk')
with zipfile.ZipFile(b/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
 for index,p in enumerate(sorted((b/'dex').glob('*.dex')) + sorted((b/'desugar-dex').glob('*.dex'))):z.write(p,'classes'+(str(index+1) if index else '')+'.dex')
 for p in pathlib.Path('app/src/main/jniLibs').rglob('*.so'):z.write(p,'lib/'+str(p.relative_to('app/src/main/jniLibs')))
PY
"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
APK="$BUILD/outputs/金甲虫ADB助手-$VERSION_NAME.apk"
if [[ "$SIGNING_MODE" == debug ]]; then
  # Workspace-local development key only; CI keys are disposable, not release identities.
  mkdir -p build
  if [ ! -f build/debug.keystore ]; then keytool -genkeypair -keystore build/debug.keystore -alias goldenbeetle-debug -keyalg RSA -keysize 2048 -validity 3650 -storepass android -keypass android -dname 'CN=GoldenBeetle Development,O=Independent Development,C=ZZ' >/dev/null 2>&1; fi
  "$BT/apksigner" sign --ks build/debug.keystore --ks-key-alias goldenbeetle-debug --ks-pass pass:android --key-pass pass:android --v4-signing-enabled false --out "$APK" "$BUILD/aligned.apk"
  "$BT/apksigner" verify --verbose --print-certs "$APK"
else
  APK="$BUILD/outputs/金甲虫ADB助手-$VERSION_NAME-unsigned.apk"
  cp "$BUILD/aligned.apk" "$APK"
fi
"$BT/aapt" dump badging "$APK" | sed -n '1,10p'
sha256sum "$APK"
python3 - "$BUILD" "$APK" "$VERSION_NAME" "$VERSION_CODE" "$SIGNING_MODE" <<'PY'
import hashlib, json, pathlib, sys, zipfile
b=pathlib.Path(sys.argv[1]); apk=pathlib.Path(sys.argv[2])
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
archive=b/'金甲虫ADB助手-对应源码及许可.zip'
with zipfile.ZipFile(apk) as z:
    assets={name:hashlib.sha256(z.read(name)).hexdigest() for name in ('assets/metadata-server.jar','assets/scrcpy-server.jar')}
record={'application':'金甲虫ADB助手','package':'io.github.devicebridge.goldenbeetle','version':sys.argv[3],'version_code':int(sys.argv[4]),'signing':sys.argv[5],'min_sdk':19,'target_sdk':35,'apk_sha256':sha(apk),'corresponding_source':archive.name,'source_archive_sha256':sha(archive),'source_input_manifest_sha256':sha(b/'source-inputs.json'),'bundled_servers':assets,'verification':'APK structure only for unsigned mode; structure/signature for debug mode. Runtime evidence must name this exact APK hash; this is not a full parity or security certification.'}
(b/'outputs'/'构建来源.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
PY
