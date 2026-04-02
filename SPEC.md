# Xerox WorkCentre 3025 Android Print Plugin — Project Spec

## Overview

A custom Android PrintService plugin that enables direct printing from Android 13+ to a Xerox WorkCentre 3025 over local Wi-Fi, with zero cloud involvement and zero third-party dependencies.

## Problem Statement

The Xerox WorkCentre 3025 does not support Android printing out of the box:
- Xerox Print Service Plugin → "Printer blocked / Document failed to print"
- Mopria Print Service → "Printer unsupported"
- Google Cloud Print → discontinued in 2021

The printer works fine from Windows and macOS. It has a web interface at `http://192.168.0.109/sws/index.html`.

## Printer Details

| Property | Value |
|---|---|
| Model | Xerox WorkCentre 3025 |
| Firmware | V3.50.21.07 (April 2025, latest) |
| IP Address | 192.168.0.109 (local network) |
| Raw TCP/IP | ✅ Enabled, port 9100 |
| LPR/LPD | ✅ Enabled, port 515 |
| IPP | ✅ Enabled, URI: `ipp://192.168.0.109/ipp/print` |
| AirPrint | ✅ Enabled |
| Color | Monochrome only |
| Android tested | 13 |

## Technical Approach

Android renders documents to PDF internally. This plugin receives that PDF data and streams it raw over TCP to port 9100 on the printer. This bypasses Android's IPP stack entirely, which is what causes the "blocked" error with other plugins.

Protocol chosen: **Raw TCP/IP port 9100** — simplest, no handshake, printer accepts the stream directly.

## Project Structure

```
xerox3025-print-plugin/
├── .github/
│   └── workflows/
│       └── build.yml               # GitHub Actions: builds release APK
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/xerox3025/printplugin/
│       │   ├── Xerox3025PrintService.java   # Core PrintService implementation
│       │   └── SettingsActivity.java        # Printer IP/port configuration UI
│       └── res/
│           ├── drawable/ic_printer.xml      # Vector printer icon
│           ├── layout/activity_settings.xml
│           ├── values/strings.xml
│           ├── values/themes.xml
│           └── xml/
│               ├── preferences.xml          # Settings screen definition
│               └── printservice.xml         # PrintService metadata
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── build.gradle
├── settings.gradle
├── .gitignore
└── README.md
```

## Key Components

### Xerox3025PrintService.java
- Extends `android.printservice.PrintService`
- Implements `onCreatePrinterDiscoverySession()` → returns `Xerox3025DiscoverySession`
- Implements `onPrintJobQueued()` → spawns background thread → calls `processPrintJob()`
- `processPrintJob()`:
  - Reads printer IP and port from SharedPreferences
  - Opens `java.net.Socket` to `{ip}:{port}` with 5s connect timeout, 30s read timeout
  - Reads PDF bytes from `printJob.getDocument().getData()` (a `ParcelFileDescriptor`)
  - Streams bytes in 8KB chunks to the socket's `OutputStream`
  - Calls `printJob.complete()` on success, `printJob.fail(reason)` on exception
  - Respects `printJob.isCancelled()` during streaming loop

### Xerox3025DiscoverySession (inner class)
- Extends `PrinterDiscoverySession`
- `onStartPrinterDiscovery()` → calls `addPrinters(buildPrinterList())`
- `buildPrinterList()` constructs a single `PrinterInfo` with:
  - Media sizes: A4 (default), Letter, A5
  - Resolutions: 600dpi (default), 300dpi
  - Color mode: `COLOR_MODE_MONOCHROME` only (3025 is mono)
  - Duplex: NONE and LONG_EDGE supported, NONE default

### SettingsActivity.java
- Extends `AppCompatActivity`
- Hosts a `PreferenceFragmentCompat`
- Three editable preferences:
  - `printer_name` (String, default: "Xerox WorkCentre 3025")
  - `printer_ip` (String, default: "192.168.0.109")
  - `printer_port` (String/numeric, default: "9100")
- IP field uses `TYPE_TEXT_VARIATION_URI` input type
- Port field uses `TYPE_CLASS_NUMBER` input type

## Build Configuration

- `compileSdk`: 34
- `minSdk`: 26 (Android 8+)
- `targetSdk`: 34
- Java: 17
- Gradle: 8.4
- AGP: 8.2.0
- Dependencies:
  - `androidx.appcompat:appcompat:1.6.1`
  - `androidx.preference:preference:1.2.1`
  - `com.google.android.material:material:1.11.0`
- Release build uses `signingConfig signingConfigs.debug` (debug key, sufficient for sideloading)
- `minifyEnabled false` (keep it simple, app is tiny)

## Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

The service also requires:
```xml
android:permission="android.permission.BIND_PRINT_SERVICE"
```

## GitHub Actions CI

File: `.github/workflows/build.yml`

- Triggers on push to `main`/`master`, PRs, and manual `workflow_dispatch`
- Runner: `ubuntu-latest`
- Steps: checkout → JDK 17 (temurin) → Gradle setup → `assembleRelease`
- Artifact: `app/build/outputs/apk/release/app-release.apk`
- Retention: 30 days
- Fallback: if release fails, builds debug APK instead

## Installation Flow (end user)

1. Download APK from GitHub Actions artifacts
2. Enable "Install unknown apps" on Android
3. Install APK
4. Open app → verify printer IP (`192.168.0.109`)
5. Android Settings → Connected devices → Printing → enable "Xerox 3025 Print Plugin"
6. Open any document → Print → select "Xerox WorkCentre 3025"

## Known Issues & Future Work

### Current limitations
- IP address is static (manually configured) — printer should have a static DHCP lease in the router
- No automatic printer discovery (mDNS/Bonjour discovery not implemented yet)
- No print job status feedback — job is marked complete when bytes are sent, not when printer finishes
- No test for LPR/LPD fallback (port 515) — only port 9100 implemented

### Potential improvements
- **mDNS discovery**: Use `android.net.nsd.NsdManager` to auto-discover the printer by hostname `XRXE84DEC1A6785.local` instead of requiring manual IP entry
- **IPP fallback**: If port 9100 fails, retry via IPP at `ipp://192.168.0.109/ipp/print`
- **Print status polling**: Query printer status via SNMP or IPP after sending job
- **Multiple printers**: Allow saving multiple printer profiles
- **Paper size auto-detection**: Read printer capabilities via IPP before advertising them

## Privacy & Security

- No internet connections of any kind
- No analytics, telemetry, or crash reporting
- No accounts or registration required
- Settings stored locally via Android SharedPreferences only
- All traffic stays on local network (LAN only)

## Context for Claude Code

The zip file contains a complete, buildable Android project. The code compiles and the GitHub Actions workflow is ready to run. The main areas that may need attention:

1. **Testing the actual print output** — the printer may need PCL header bytes prepended to the PDF stream. If prints come out garbled, add a PJL header:
   ```
   \x1B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PDF\r\n
   ```
   before the PDF bytes, and `\x1B%-12345X` after.

2. **mDNS discovery** — implementing `NsdManager` to find the printer automatically instead of requiring manual IP would be a good first enhancement.

3. **Build errors** — if the Gradle wrapper binary is missing (it's not included in the zip), run `gradle wrapper` inside the project directory first, or let GitHub Actions handle it (it downloads Gradle automatically).
