# Debugging with the Android Emulator

This guide covers how to set up and use the Android emulator to debug the Xerox 3025 Print Plugin without needing a physical phone.

## Prerequisites

- [Android SDK](https://developer.android.com/studio#command-tools) (command-line tools are sufficient, no need for Android Studio)
- An ARM64 system image (required for Apple Silicon Macs; x86_64 works on Intel/AMD)
- ~10 GB free disk space

## Setting up the emulator

### 1. Install system image and emulator

```bash
sdkmanager "system-images;android-34;google_apis;arm64-v8a" "emulator" "platform-tools"
```

> Use `google_apis` (not `google_apis_playstore`) — it's smaller and sufficient for debugging.

### 2. Create an AVD

```bash
avdmanager create avd \
  -n xerox_test \
  -k "system-images;android-34;google_apis;arm64-v8a" \
  -d pixel_6
```

### 3. Start the emulator

```bash
emulator -avd xerox_test -no-snapshot &
```

Wait for the boot to complete (~30-90 seconds). Verify with:

```bash
adb wait-for-device
adb shell getprop sys.boot_completed  # returns "1" when ready
```

## Installing and enabling the plugin

### Install the APK

```bash
adb install app-debug.apk
```

### Enable the print service

```bash
adb shell settings put secure enabled_print_services \
  "com.xerox3025.printplugin/.Xerox3025PrintService:com.android.bips/.BuiltInPrintService"
```

This enables our plugin alongside the built-in print service. Without this step, the printer won't appear in print dialogs.

### Verify the service is registered

```bash
adb shell dumpsys print | head -40
```

Look for `is_bound=true` under our service. If it says `is_bound=false`, see [Troubleshooting](#troubleshooting) below.

## Running tests

### Launch the app

```bash
adb shell am start -n com.xerox3025.printplugin/.SettingsActivity
```

### Trigger a print job (via UI automation)

```bash
# Tap "Print via Android Framework" button (adjust coordinates for your screen)
adb shell input tap 540 1430

# Wait for print dialog, then tap Print button
sleep 10
adb shell input tap 975 428
```

### Monitor print logs

```bash
# Watch print service logs in real time
adb logcat -s "Xerox3025Print" "IppClient" "UrfEncoder"
```

### Check print system state

```bash
adb shell dumpsys print
```

Key fields to check:
- `is_bound` — whether Android has bound to our service
- `has_discovery_session` — whether printer discovery is active
- `has_active_print_jobs` — whether a job is being processed
- `cached_print_jobs` — queued jobs and their state

## Useful commands

### Clear logcat and start fresh

```bash
adb logcat -c
```

### Check if the emulator can reach the printer

```bash
adb shell ping -c 3 192.168.0.109
```

> **Note:** The emulator on macOS shares the host's network, so it can reach printers on your local Wi-Fi. This is not the case inside Docker.

### Read SharedPreferences

```bash
adb shell "run-as com.xerox3025.printplugin cat \
  /data/data/com.xerox3025.printplugin/shared_prefs/com.xerox3025.printplugin_preferences.xml"
```

### Push test PDFs to app cache

The "Run Print Test Suite" reads PDFs from the app's cache directory:

```bash
# Push to temp, then copy into app sandbox
adb push my_test.pdf /data/local/tmp/
adb shell "run-as com.xerox3025.printplugin cp /data/local/tmp/my_test.pdf ./cache/"
```

### Take screenshots

```bash
adb shell screencap /sdcard/screenshot.png
adb pull /sdcard/screenshot.png ./screenshot.png
```

### Dump UI hierarchy (for finding tap coordinates)

```bash
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml | tr '>' '>\n' | grep "text=" | grep -v '""'
```

## Troubleshooting

### `is_bound=false` — service won't bind

This typically means stale state in the Android print spooler. Fix:

```bash
adb shell pm clear com.android.printspooler
adb uninstall com.xerox3025.printplugin
adb install app-debug.apk
adb shell settings put secure enabled_print_services \
  "com.xerox3025.printplugin/.Xerox3025PrintService:com.android.bips/.BuiltInPrintService"
```

### `IllegalAccessError: must be called from the main thread`

All `PrintJob` methods (`start()`, `complete()`, `fail()`, `cancel()`, `getInfo()`, `getDocument()`) must be called on the main thread. If you see this crash in logcat:

```
java.lang.IllegalAccessError: must be called from the main thread
    at android.printservice.PrintService.throwIfNotCalledOnMainThread
    at android.printservice.PrintJob.start
```

Make sure to:
1. Extract all data from `PrintJob` in `onPrintJobQueued()` (runs on main thread)
2. Call `printJob.start()` on the main thread before spawning background work
3. Use `new Handler(Looper.getMainLooper()).post(...)` for `complete()`/`fail()`/`cancel()` callbacks

### Emulator ANRs and slowness

The emulator is slow, especially on Apple Silicon with ARM emulation. Tips:
- Close other apps on the emulator: `adb shell am force-stop com.android.chrome`
- Use longer sleep delays between UI interactions (10-15 seconds)
- Don't run multiple heavy operations at once
- If ANR dialogs appear, tap "Wait" — the app usually recovers

### IPP status `0xffffffff`

This means the printer returned HTTP 200 but the IPP response couldn't be parsed, usually because:
- The printer is busy processing a previous job
- The response was truncated

The print may still succeed — check the printer output. Add longer delays between consecutive print jobs (5+ seconds).

### `EACCES (Permission denied)` reading files

Android's scoped storage prevents apps from reading `/sdcard/Download/` directly. Use the app's cache directory instead:

```bash
adb push file.pdf /data/local/tmp/
adb shell "run-as com.xerox3025.printplugin cp /data/local/tmp/file.pdf ./cache/"
```

## Architecture notes

For a full technical deep-dive, see:
- [SPEC.md](../SPEC.md) — URF format, IPP protocol, project structure
- [DEBUGLOG.md](../DEBUGLOG.md) — chronological debugging history with root causes and fixes
