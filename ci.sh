#!/usr/bin/env bash
# GitHub Actions derleme betiği (tüm çıktı build.log'a yazılır)
set -euo pipefail
echo "ANDROID_HOME=${ANDROID_HOME:-} ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
SM=$(ls "$SDK"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | head -1 || true)
[ -z "$SM" ] && SM=$(command -v sdkmanager || true)
echo "sdkmanager: $SM"
ls "$SDK/platforms" || true
if [ -n "$SM" ]; then
  yes | "$SM" --licenses > /dev/null || true
  "$SM" "platforms;android-36" "build-tools;36.0.0" > /dev/null || echo "sdkmanager uyarı verdi, devam"
fi
ls "$SDK/platforms" || true
java -version
gradle --version
gradle assembleFullRelease assembleLiteRelease --no-daemon --stacktrace
