#!/usr/bin/env bash
# Hybrid (ML-DSA-65 + ECDSA) APK signing for Android 17's APK Signature Scheme v3.2.
# Exercised on 2026-09-24 with build-tools 37.0.0 and a JDK 25 (native ML-DSA keys).
# Secrets are never read from the repository: pass keystores and passwords through the environment.
#
#   HAVEN_JDK=/path/to/jdk25 HAVEN_BT=$ANDROID_HOME/build-tools/37.0.0 \
#   HAVEN_KS_LEGACY=... HAVEN_KS_CLASSICAL=... HAVEN_KS_PQC=... HAVEN_LINEAGE_CLASSICAL=... HAVEN_LINEAGE_PQC=... \
#   HAVEN_KS_PASS=env:HAVEN_PASS tools/sign-hybrid.sh app/build/outputs/apk/release/app-release-unsigned.apk out.apk
#
# Lineages: LINEAGE_CLASSICAL = [legacy -> classical] (apksigner rotate);
#           LINEAGE_PQC       = [legacy -> classical -> pqc] (apksigner rotate --in LINEAGE_CLASSICAL).
# Both hybrid signers must be given the SAME lineage (the three-key one); the signer rejects
# differing histories. Note: build-tools 37.0.0's own `apksigner verify` then reports the hybrid
# classical signer's certificate as not matching its lineage, while AGP 9.4.1's apksig library
# verifies the file. Treat installation on an Android 17 device as the authoritative check.
set -euo pipefail
IN="${1:?unsigned apk}"; OUT="${2:?output apk}"
JAVA="${HAVEN_JDK:?}/bin/java"; BT="${HAVEN_BT:?}"
ALIGNED="$(mktemp --suffix=.apk)"
"$BT/zipalign" -p -f 4 "$IN" "$ALIGNED"
"$JAVA" --enable-native-access=ALL-UNNAMED -jar "$BT/lib/apksigner.jar" sign \
  --ks "${HAVEN_KS_LEGACY:?}" --ks-pass "${HAVEN_KS_PASS:?}" --ks-key-alias "${HAVEN_ALIAS_LEGACY:-legacy}" \
  --next-signer --ks "${HAVEN_KS_CLASSICAL:?}" --ks-pass "${HAVEN_KS_PASS}" --ks-key-alias "${HAVEN_ALIAS_CLASSICAL:-classical}" \
      --signer-lineage "${HAVEN_LINEAGE_PQC:?}" --hybrid-signer-role classical \
  --next-signer --ks "${HAVEN_KS_PQC:?}" --ks-pass "${HAVEN_KS_PASS}" --ks-key-alias "${HAVEN_ALIAS_PQC:-pqc}" \
      --signer-lineage "${HAVEN_LINEAGE_PQC}" --hybrid-signer-role pqc \
  --hybrid-min-sdk-version 37 --out "$OUT" "$ALIGNED"
rm -f "$ALIGNED"
"$JAVA" --enable-native-access=ALL-UNNAMED -jar "$BT/lib/apksigner.jar" verify --verbose --print-certs "$OUT" || true
echo "Signed: $OUT (install on an Android 17 device to confirm the v3.2 block is accepted)"
