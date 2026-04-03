# Xerox WorkCentre 3025 Android Print Plugin

A lightweight Android PrintService plugin for the Xerox WorkCentre 3025 (and similar models). Prints via IPP using URF (Apple Raster) format — no cloud, no third-party servers, 100% local network.

## How it works

1. Android renders the document to PDF
2. The plugin renders each PDF page to a bitmap using `PdfRenderer`
3. Bitmaps are encoded as URF (Universal Raster Format) with PWG compression
4. The URF data is sent via IPP (Internet Printing Protocol) on port 631

The Xerox WorkCentre 3025 only accepts **URF** (`image/urf`) and **QPDL** (`application/x-QPDL`) formats. Standard PCL, PostScript, and raw PDF are not supported.

## Build & Install

### Build locally (Docker)

```bash
./build.sh
```

This uses Docker with the Android SDK to build and outputs `app-debug.apk` in the project root. The script auto-increments `versionCode` on each build and uses your local `~/.android/debug.keystore` for consistent signing.

### GitHub Actions

1. Push to GitHub
2. Go to **Actions** tab → **Build APK** → **Run workflow**
3. Download the APK from workflow artifacts

## Setup on Android

1. Install the APK
2. Open the app → set your **printer IP address** (default: `192.168.0.109`)
3. Go to **Android Settings → Connected devices → Printing**
4. Enable **Xerox 3025 Print Plugin**
5. Open any document, tap **Print**, select **Xerox WorkCentre 3025**

## Features

- **Print any document** — PDF pages rendered at 600 DPI and sent as URF via IPP
- **Network test** — verify connectivity to printer (DNS, ping, port 9100, IPP port 631)
- **Print test page** — send a pre-rendered URF test page to verify the connection
- **Job history** — view recent print jobs with status (completed/failed/cancelled)
- **Debug logs** — view diagnostic logs for troubleshooting
- **Notifications** — progress and completion notifications for print jobs

## Configuration

| Setting | Default | Description |
|---|---|---|
| Printer IP | 192.168.0.109 | Your printer's local IP address |
| Display Name | Xerox WorkCentre 3025 | Name shown in print dialog |

> **Tip:** Give your printer a static IP in your router's DHCP settings.

## Technical Details

- **Protocol**: IPP (port 631) with `document-format: image/urf`
- **Format**: URF (UNIRAST) with PWG raster compression
- **Resolution**: 600 DPI, grayscale (monochrome printer)
- **Paper**: A4 (default), Letter, A5
- **Android**: minSdk 26 (Android 8.0+), targetSdk 34

## Troubleshooting

- **Printer not appearing** — Make sure the plugin is enabled in Settings → Printing
- **Job fails** — Use "Test Network Connection" in the app to verify connectivity
- **No output** — Check "View Debug Logs" for detailed error information
- **Check the printer's web interface** at `http://<printer-ip>/sws/index.html`

## Privacy

This app:
- Makes **no internet connections** of any kind
- Has **no analytics, tracking, or telemetry**
- Only accesses your **local network** to reach the printer
- Stores settings **locally** via Android SharedPreferences only

## License

MIT
