#!/usr/bin/env bash
# Sign an already zipaligned APK with an explicitly supplied, stable release key.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ $# == 2 ]] || { echo 'Usage: bash tools/sign-apk.sh aligned.apk signed.apk' >&2; exit 2; }
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${RELEASE_KEYSTORE:?Set RELEASE_KEYSTORE to a private file outside the repository}"
: "${RELEASE_KEY_ALIAS:?Set RELEASE_KEY_ALIAS}"
: "${RELEASE_STORE_PASSWORD:?Set RELEASE_STORE_PASSWORD}"
: "${RELEASE_KEY_PASSWORD:?Set RELEASE_KEY_PASSWORD}"
: "${RELEASE_CERT_SHA256:?Set RELEASE_CERT_SHA256 to the expected certificate fingerprint}"
BT="$ANDROID_HOME/build-tools/35.0.0"
EXPECTED=$(printf '%s' "$RELEASE_CERT_SHA256" | tr -d ':' | tr '[:upper:]' '[:lower:]')
[[ "$EXPECTED" =~ ^[a-f0-9]{64}$ ]] || { echo 'Invalid RELEASE_CERT_SHA256' >&2; exit 1; }
[[ -f "$RELEASE_KEYSTORE" && ! -L "$RELEASE_KEYSTORE" ]] || { echo 'Release keystore must be a regular private file' >&2; exit 1; }
KEY_PATH=$(realpath -- "$RELEASE_KEYSTORE")
case "$KEY_PATH" in "$PWD"/*) echo 'Keep the release keystore outside the repository' >&2; exit 1 ;; esac
"$BT/zipalign" -c -p 4 "$1"
mkdir -p "$(dirname "$2")"
"$BT/apksigner" sign --ks "$RELEASE_KEYSTORE" --ks-key-alias "$RELEASE_KEY_ALIAS" \
  --ks-pass env:RELEASE_STORE_PASSWORD --key-pass env:RELEASE_KEY_PASSWORD \
  --v4-signing-enabled false --out "$2" "$1"
CERT_REPORT=$("$BT/apksigner" verify --verbose --print-certs "$2")
ACTUAL=$(printf '%s\n' "$CERT_REPORT" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]')
if [[ "$ACTUAL" != "$EXPECTED" ]]; then
  rm -f -- "$2"
  echo 'Release signing certificate differs from RELEASE_CERT_SHA256; refusing this APK.' >&2
  exit 1
fi
"$BT/zipalign" -c -p 4 "$2"
printf '%s\n' "$CERT_REPORT"
