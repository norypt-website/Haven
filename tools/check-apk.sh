#!/usr/bin/env bash
# Verifies a final APK: no network permissions, no exported services/providers, not debuggable (release).
set -euo pipefail
APK="${1:?usage: check-apk.sh <apk>}"
AAPT2="${AAPT2:-$(ls -d "${ANDROID_HOME:-$HOME/android-sdk}"/build-tools/*/aapt2 | sort -V | tail -1)}"
echo "Using $AAPT2"
PERMS=$("$AAPT2" dump permissions "$APK")
echo "$PERMS"
for p in android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE android.permission.ACCESS_WIFI_STATE android.permission.BLUETOOTH android.permission.NFC android.permission.READ_EXTERNAL_STORAGE android.permission.WRITE_EXTERNAL_STORAGE; do
  if grep -q "$p" <<<"$PERMS"; then echo "FAIL: $p present"; exit 1; fi
done
XML=$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK")
if grep -qE "debuggable.*=true" <<<"$XML" && [[ "$APK" == *release* ]]; then echo "FAIL: release APK is debuggable"; exit 1; fi
if grep -B3 -A3 -E "E: (service|provider)" <<<"$XML" | grep -q "exported.*=true"; then echo "FAIL: exported service/provider"; exit 1; fi
echo "OK: no network permissions, no exported services/providers"
