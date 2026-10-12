#!/usr/bin/env bash
# Exercise release-signing plumbing with this run's disposable development key only.
# Nothing here is a release identity and this APK is never added to dist/.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME}"
BT="$ANDROID_HOME/build-tools/35.0.0"
TEST_DIR=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/goldadbhelper-sign-test.XXXXXX")
trap 'rm -rf -- "$TEST_DIR"' EXIT
umask 077
cp build/debug.keystore "$TEST_DIR/test.keystore"
export RELEASE_KEYSTORE="$TEST_DIR/test.keystore"
export RELEASE_KEY_ALIAS=goldenbeetle-debug
export RELEASE_STORE_PASSWORD=android
export RELEASE_KEY_PASSWORD=android
read -r _ VERSION_NAME < <(python3 tools/version.py --format shell)
RELEASE_CERT_SHA256=$("$BT/apksigner" verify --print-certs "build/outputs/金甲虫ADB助手-$VERSION_NAME.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
export RELEASE_CERT_SHA256
bash tools/sign-apk.sh build/aligned.apk "$TEST_DIR/test-signed.apk"
export RELEASE_CERT_SHA256=0000000000000000000000000000000000000000000000000000000000000000
if bash tools/sign-apk.sh build/aligned.apk "$TEST_DIR/wrong-certificate.apk"; then
  echo 'Mismatched signing certificate was incorrectly accepted' >&2
  exit 1
fi
[[ ! -e "$TEST_DIR/wrong-certificate.apk" ]]
echo 'Release signing integration: matching certificate accepted, mismatched certificate rejected.'
