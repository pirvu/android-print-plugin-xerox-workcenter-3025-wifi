#!/bin/bash
set -e

# Auto-increment versionCode before building
GRADLE_FILE="app/build.gradle"
CURRENT_CODE=$(grep 'versionCode' "$GRADLE_FILE" | sed 's/[^0-9]//g')
NEW_CODE=$((CURRENT_CODE + 1))
sed -i.bak "s/versionCode $CURRENT_CODE/versionCode $NEW_CODE/" "$GRADLE_FILE" && rm -f "${GRADLE_FILE}.bak"
echo "Version code: $CURRENT_CODE -> $NEW_CODE"

docker run --rm \
  -v "$(pwd):/project" \
  -v "$HOME/.android/debug.keystore:/root/.android/debug.keystore:ro" \
  -w /project \
  thyrlian/android-sdk:latest \
  bash -c "
    yes | sdkmanager --licenses > /dev/null 2>&1
    sdkmanager 'platforms;android-34' 'build-tools;34.0.0' > /dev/null 2>&1
    chmod +x ./gradlew
    ./gradlew assembleDebug
    cp app/build/outputs/apk/debug/app-debug.apk /project/app-debug.apk
  "

echo "APK saved to $(pwd)/app-debug.apk"
