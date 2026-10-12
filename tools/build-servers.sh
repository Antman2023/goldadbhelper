#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK=${ANDROID_HOME:-/workspace/shared/device_bridge_toolchain/android-sdk}
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
ROOT="$PWD"
mkdir -p build/server-classes build/scrcpy/gen/com/genymobile/scrcpy build/scrcpy/classes app/src/main/assets
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d build/server-classes server/src/io/github/devicebridge/server/*.java core/src/main/java/io/github/devicebridge/core/AndroidFileAccess.java
"$BT/d8" --min-api 19 --lib "$ANDROID_JAR" --output "$ROOT/app/src/main/assets/metadata-server.jar" $(find build/server-classes -name '*.class')
cat > build/scrcpy/gen/com/genymobile/scrcpy/BuildConfig.java <<'JAVA'
package com.genymobile.scrcpy;
public final class BuildConfig { public static final boolean DEBUG=false; public static final String VERSION_NAME="2.4"; }
JAVA
pushd vendor/scrcpy-2.4/server/src/main/aidl >/dev/null
for f in android/view/IRotationWatcher.aidl android/content/IOnPrimaryClipChangedListener.aidl android/view/IDisplayFoldListener.aidl; do "$BT/aidl" -I. -o"$ROOT/build/scrcpy/gen" "$f"; done
popd >/dev/null
find vendor/scrcpy-2.4/server/src/main/java server-power/src build/scrcpy/gen -name '*.java' | sort > build/scrcpy/sources.txt
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$BT/core-lambda-stubs.jar" -d build/scrcpy/classes @build/scrcpy/sources.txt
"$BT/d8" --min-api 21 --lib "$ANDROID_JAR" --output "$ROOT/app/src/main/assets/scrcpy-server.jar" $(find build/scrcpy/classes -name '*.class')
cp vendor/scrcpy-2.4/LICENSE app/src/main/assets/scrcpy-LICENSE.txt
sha256sum app/src/main/assets/*server.jar
