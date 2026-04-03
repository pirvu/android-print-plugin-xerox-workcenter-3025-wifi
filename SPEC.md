# Xerox WorkCentre 3025 Android Print Plugin — Project Spec

## Overview

A custom Android PrintService plugin that enables direct printing from Android to a Xerox WorkCentre 3025 over local Wi-Fi, with zero cloud involvement and zero third-party dependencies.

## Problem Statement

The Xerox WorkCentre 3025 does not support Android printing out of the box:
- Xerox Print Service Plugin → "Printer blocked / Document failed to print"
- Mopria Print Service → "Printer unsupported"
- Google Cloud Print → discontinued in 2021

The printer works fine from Windows and macOS (via CUPS/AirPrint).

## Printer Details

| Property | Value |
|---|---|
| Model | Xerox WorkCentre 3025 |
| IP Address | 192.168.0.109 (local network) |
| Supported formats | `image/urf`, `application/x-QPDL` |
| Unsupported | PCL, PostScript, raw PDF, plain text |
| IPP | `ipp://192.168.0.109/ipp/print` (port 631) |
| AirPrint | Yes (URF via IPP) |
| Color | Monochrome only |
| Device ID | `MFG:Xerox;CMD:SPL,URF;MDL:WorkCentre 3025` |

## Technical Approach

### Print Pipeline

1. Android renders the document to **PDF**
2. Plugin copies PDF to temp file, opens with `PdfRenderer`
3. Each page is rendered to an **ARGB_8888 Bitmap** at 600 DPI (4960×7015 pixels for A4)
4. Bitmaps are converted to **8-bit grayscale** and encoded as **URF** (Universal Raster Format)
5. URF data is wrapped in an **IPP Print-Job** request and sent via HTTP POST to port 631

### URF Format (verified working)

```
File header:  "UNIRAST\0" (8 bytes) + uint32_be page_count (4 bytes)

Per page:
  Page header (32 bytes):
    [0]  bitsPerPixel = 8
    [1]  colorSpace = 0 (W8/grayscale)
    [2]  duplex = 1 (one-sided)
    [3]  quality = 0 (default)
    [4-11]  reserved (zeros)
    [12-15] width (uint32 BE) = 4960
    [16-19] height (uint32 BE) = 7015
    [20-23] resolution (uint32 BE) = 600
    [24-31] reserved (zeros)

  Raster data (PWG compression):
    Per scanline:
      1 byte: line repeat count (0 = no repeat, N = repeat N more times)
      Compressed line bytes:
        0-127:   repeat next byte (N+1) times
        128-255: copy next (N-127) literal bytes
```

### IPP Protocol

```
POST /ipp/print HTTP/1.1
Host: {printer_ip}
Content-Type: application/ipp
Content-Length: {size}

IPP 1.1 Print-Job request:
  attributes-charset: utf-8
  attributes-natural-language: en
  printer-uri: ipp://{ip}/ipp/print
  requesting-user-name: android-plugin
  job-name: {document_name}
  document-format: image/urf
  [end-of-attributes]
  [URF document data]
```

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   └── test_page.urf              # Pre-rendered URF test page (211 KB)
├── java/com/xerox3025/printplugin/
│   ├── Xerox3025PrintService.java  # Core PrintService (PDF→URF→IPP)
│   ├── SettingsActivity.java       # Config UI, network test, test page
│   ├── IppClient.java             # IPP protocol implementation
│   ├── UrfEncoder.java            # Bitmap→URF encoder with PWG compression
│   ├── PrintLog.java              # Ring-buffer debug logger
│   ├── PrintJobHistory.java       # Job history persistence (SharedPreferences/JSON)
│   └── JobHistoryActivity.java    # Job history UI (RecyclerView)
└── res/
    ├── drawable/ic_printer.xml
    ├── layout/
    │   ├── activity_settings.xml
    │   ├── activity_job_history.xml
    │   └── item_job_history.xml
    ├── values/
    │   ├── strings.xml
    │   └── themes.xml
    └── xml/
        ├── preferences.xml
        └── printservice.xml
```

## Key Components

### Xerox3025PrintService.java
- Extends `android.printservice.PrintService`
- `processPrintJob()`: PDF → PdfRenderer → Bitmap → UrfEncoder → IppClient
- Notification channel for print job progress/completion
- Records job history on completion/failure

### UrfEncoder.java
- Converts ARGB_8888 Bitmap to 8-bit grayscale
- Encodes as URF with PWG raster compression
- Supports line repeat optimization for identical scanlines

### IppClient.java
- Builds IPP Print-Job requests
- Sends via HTTP POST to port 631
- Parses IPP response status codes

### SettingsActivity.java
- Printer configuration (IP, display name)
- Network connectivity test (DNS, ping, TCP 9100, IPP 631)
- Test page printing (pre-rendered URF via IPP)
- Job history viewer
- Debug log viewer with copy/clear

### PrintJobHistory.java
- Stores last 50 jobs in SharedPreferences as JSON
- Records: job name, timestamp, status, detail, page count

### PrintLog.java
- In-memory ring buffer (500 entries) wrapping android.util.Log
- Thread-safe, exportable as text

## Build Configuration

- `compileSdk`: 34, `minSdk`: 26, `targetSdk`: 34
- Java: 17, Gradle: 8.4, AGP: 8.2.0
- Dependencies: appcompat, preference, material (all AndroidX)
- Build via Docker: `./build.sh` (auto-increments versionCode, uses host debug keystore)

## Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Privacy & Security

- No internet connections of any kind
- No analytics, telemetry, or crash reporting
- No accounts or registration
- All traffic on local network only
- Settings stored locally via SharedPreferences
