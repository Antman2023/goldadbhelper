#!/usr/bin/env bash
# Bounded follow-up: in-memory/synthetic-loopback protocol, metadata, navigation only.
# No emulator, original app, real device, actual daemon kill, path/link fixtures, or safety re-review.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/followup-navigation-server-tests
mkdir -p "$OUT/classes" "$OUT/results"
find core/src/main/java core/src/test/java -name '*.java' | sort > "$OUT/sources.txt"
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"
for suite in CoreTests ProtocolTests TransportBoundaryTests DomainTests ScreenProtocolTests OperationScopeTests InteractiveShellTests ShellClientTests LegacyShellTests LegacyCloseTests AdbSessionLifecycleTests AdbStreamFlowTests PackageCommandStrategyTests ModuleNavigationTests RetainedModuleStateTests RetainedPagerGestureTests AdbServerEndpointTests HostServerTests; do
  java -ea -cp "$OUT/classes" "io.github.devicebridge.core.$suite" | tee "$OUT/results/$suite.txt"
done
bash tests/localapps/run.sh
bash tests/navigation/run.sh
bash tests/navigation/run-lifecycle.sh

bash tools/test-host-ui.sh

bash tests/navigation/run-gesture.sh

bash tests/navigation/run-neighbor.sh
