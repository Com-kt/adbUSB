#!/usr/bin/env bash
set -e

echo "=== Downloading Bundletool ==="
gh release download --repo google/bundletool --pattern "bundletool-all-1.18.3.jar" --clobber
mv bundletool-all-1.18.3.jar bundletool.jar

RELEASE_AAB=$(ls app/build/outputs/bundle/release/*.aab | head -n 1)
DEBUG_AAB=$(ls app/build/outputs/bundle/debug/*.aab | head -n 1)
AAPT2_PATH=$(ls -d $ANDROID_HOME/build-tools/*/aapt2 | tail -1)

echo "Found GitHub Actions AAPT2 at: $AAPT2_PATH"
echo "=== Release AAB: $RELEASE_AAB ==="
echo "=== Debug AAB: $DEBUG_AAB ==="

java -jar bundletool.jar build-apks \
  --bundle="$RELEASE_AAB" \
  --output=app/build/outputs/bundle/release/app-release.apks \
  --aapt2="$AAPT2_PATH" \
  --mode=default \
  --ks=app/bash/new_key.jks \
  --ks-pass="pass:${KEY_STORE_PASSWORD}" \
  --ks-key-alias="${KEY_ALIAS}" \
  --key-pass="pass:${KEY_PASSWORD}"

java -jar bundletool.jar build-apks \
  --bundle="$DEBUG_AAB" \
  --output=app/build/outputs/bundle/debug/app-debug.apks \
  --aapt2="$AAPT2_PATH" \
  --mode=default \
  --ks=app/bash/new_key.jks \
  --ks-pass="pass:${KEY_STORE_PASSWORD}" \
  --ks-key-alias="${KEY_ALIAS}" \
  --key-pass="pass:${KEY_PASSWORD}"

APKSIGNER=$(find "$ANDROID_HOME/build-tools" -name apksigner | sort -V | tail -n 1)
echo "Using system apksigner: $APKSIGNER"

echo "=== Re-signing Release Split APKs with V3.1 ==="
mkdir -p ext_release
unzip -o app/build/outputs/bundle/release/app-release.apks -d ext_release
find ext_release -name "*.apk" | while read -r apk; do
  echo "Processing: $apk"
  "$APKSIGNER" sign \
    --lineage app/bash/app_lineage_1.bin \
    --v1-signing-enabled false \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --v4-signing-enabled true \
    --ks app/bash/old_key.jks \
    --ks-pass "pass:${OLD_KEY_STORE_PASSWORD}" \
    --ks-key-alias "${OLD_KEY_ALIAS}" \
    --key-pass "pass:${OLD_KEY_PASSWORD}" \
    --next-signer \
    --ks app/bash/new_key.jks \
    --ks-pass "pass:${KEY_STORE_PASSWORD}" \
    --ks-key-alias "${KEY_ALIAS}" \
    --key-pass "pass:${KEY_PASSWORD}" \
    --next-signer \
    --ks app/bash/new_keys.jks \
    --ks-pass "pass:${NEW_KEY_STORE_PASSWORD}" \
    --ks-key-alias "${NEW_KEY_ALIAS}" \
    --key-pass "pass:${NEW_KEY_PASSWORD}" \
    --signer-lineage app/bash/app_lineage_2.bin \
    --hybrid-signer-role classical \
    --hybrid-min-sdk-version 37 \
    --next-signer \
    --ks app/bash/pqc_key.jks \
    --ks-pass "pass:${PQC_KEY_STORE_PASSWORD}" \
    --ks-key-alias "${PQC_KEY_ALIAS}" \
    --key-pass "pass:${PQC_KEY_PASSWORD}" \
    --signer-lineage app/bash/app_lineage_3.bin \
    --hybrid-signer-role pqc \
    --hybrid-min-sdk-version 37 \
    --lineage app/bash/app_lineage_1.bin \
    "$apk"
done

cd ext_release && zip -r ../app/build/outputs/bundle/release/app-release.apks . && cd ..

echo "=== Re-signing Debug Split APKs with V3.1 ==="
mkdir -p ext_debug
unzip -o app/build/outputs/bundle/debug/app-debug.apks -d ext_debug
find ext_debug -name "*.apk" | while read -r apk; do
  echo "Processing: $apk"
  "$APKSIGNER" sign \
    --lineage app/bash/app_lineage_1.bin \
    --v1-signing-enabled false \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --v4-signing-enabled true \
    --ks app/bash/old_key.jks \
    --ks-pass "pass:${OLD_KEY_STORE_PASSWORD}" \
    --ks-key-alias "${OLD_KEY_ALIAS}" \
    --key-pass "pass:${OLD_KEY_PASSWORD}" \
    --next-signer \
    --ks app/bash/new_key.jks \
    --ks-pass "pass:${KEY_STORE_PASSWORD}" \
    --ks-key-alias "${KEY_ALIAS}" \
    --key-pass "pass:${KEY_PASSWORD}" \
    --next-signer \
    --ks app/bash/new_keys.jks \
    --ks-pass "pass:${NEW_KEY_STORE_PASSWORD}" \
    --ks-key-alias "${NEW_KEY_ALIAS}" \
    --key-pass "pass:${NEW_KEY_PASSWORD}" \
    --signer-lineage app/bash/app_lineage_2.bin \
    --hybrid-signer-role classical \
    --hybrid-min-sdk-version 37 \
    --next-signer \
    --ks app/bash/pqc_key.jks \
    --ks-pass "pass:${PQC_KEY_STORE_PASSWORD}" \
    --ks-key-alias "${PQC_KEY_ALIAS}" \
    --key-pass "pass:${PQC_KEY_PASSWORD}" \
    --signer-lineage app/bash/app_lineage_3.bin \
    --hybrid-signer-role pqc \
    --hybrid-min-sdk-version 37 \
    "$apk"
done

cd ext_debug && zip -r ../app/build/outputs/bundle/debug/app-debug.apks . && cd ..

echo "=== V3.1 Key Rotation signing completed successfully! ==="
