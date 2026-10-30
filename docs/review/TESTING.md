# Reproducing the reliability checks

Use JDK 17, Android SDK platform 34 and build-tools 34.0.0. The checked-in
wrapper uses Gradle 8.4 with a distribution SHA-256 pin; AGP remains 8.2.0.
Production dependency versions and min/target SDK remain unchanged (26/34).
Robolectric tests explicitly run against Android 15 / API 35.

From the repository root, with `JAVA_HOME` and `ANDROID_HOME` configured:

```bash
./gradlew --no-daemon --max-workers=1 \
  testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest \
  -PVERSION_NAME=1.1.0-audit1 -PVERSION_CODE=10101
```

On a restricted workstation, point `GRADLE_USER_HOME`, `ANDROID_USER_HOME`
and Java's `user.home` at writable task directories. The audit used:

```bash
export JAVA_HOME=/tmp/xerox-toolchain/jdk-17.0.21+9
export ANDROID_HOME=/tmp/xerox-toolchain/sdk
export GRADLE_USER_HOME=/tmp/xerox-toolchain/gradle-home
export ANDROID_USER_HOME=/tmp/xerox-toolchain/android-home
export JAVA_TOOL_OPTIONS=-Duser.home=/tmp/xerox-toolchain/java-home
```

JUnit reports: `app/build/test-results/testDebugUnitTest/`.
Lint report: `app/build/reports/lint-results-debug.html`.
App APK: `app/build/outputs/apk/debug/app-debug.apk`.
Instrumentation APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
CI runs the same unit/lint/build tasks; it compiles the instrumentation
tests but does not claim to execute an emulator.

## Native PDF tests

Use a dedicated Android 15 emulator. The audit uses an AOSP x86_64 API 35
image, KVM, 1024 MiB guest RAM and a 1200×800 tablet display. These tests
exercise Android's native `PdfRenderer`; Robolectric alone does not validate
native PDF rendering or printer firmware.

The following commands deliberately name the emulator and an isolated ADB
server. Substitute the serial/port of your own dedicated emulator only:

```bash
adb -P 5047 -s emulator-5580 shell getprop ro.kernel.qemu
adb -P 5047 -s emulator-5580 shell getprop ro.build.version.sdk
adb -P 5047 -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -P 5047 -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -P 5047 -s emulator-5580 shell am instrument -w -r \
  com.xerox3025.printplugin.test/androidx.test.runner.AndroidJUnitRunner
```

Confirm that the first two commands return `1` and `35` before installing.
No physical printer is needed; the native tests create synthetic PDFs and
temporary URF files. Socket tests use loopback fake servers and check that
no second submission is made. UI setup tests use an invalid printer address
to avoid contacting a LAN scanner.

The native tests compare every band pixel with a complete page, allowing at most
two 8-bit grayscale levels for native edge-coverage rounding and requiring at least
99% byte-identical pixels. They also check bitmap
recycling, repeated multipage/copy output and file-descriptor stability,
A4/A5/Letter orientations, cancellation, malformed PDFs and rejected
unsupported options. The bitmap allocation bound applies to app-owned
raster bands, not to all memory used by Android's PDF implementation.

## Physical validation

Follow [the hardware checklist](HARDWARE_TEST_PLAN.md).
Success in these automated checks cannot establish physical print completion,
firmware compatibility, WSD operation on the target unit, source gallery/browser
integration or TCL-specific power/lifecycle behavior.
