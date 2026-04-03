#!/bin/bash
set -e

# Local build via Docker (for development/testing)
# For releases, use: git tag v1.0.0 && git push --tags

VERSION_NAME="${1:-dev}"
VERSION_CODE="${2:-99999}"

echo "Building version: $VERSION_NAME (code: $VERSION_CODE)"

docker run --rm \
  -v "$(pwd):/project" \
  -v "$HOME/.android/debug.keystore:/root/.android/debug.keystore:ro" \
  -w /project \
  thyrlian/android-sdk:latest \
  bash -c "
    yes | sdkmanager --licenses > /dev/null 2>&1
    sdkmanager 'platforms;android-34' 'build-tools;34.0.0' > /dev/null 2>&1
    chmod +x ./gradlew
    ./gradlew assembleDebug -PVERSION_NAME=$VERSION_NAME -PVERSION_CODE=$VERSION_CODE
    cp app/build/outputs/apk/debug/app-debug.apk /project/app-debug.apk
  "

echo "APK saved to $(pwd)/app-debug.apk"
