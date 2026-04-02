# Xerox WorkCentre 3025 Android Print Plugin

A lightweight Android PrintService plugin for the Xerox WorkCentre 3025 (and similar models), using direct Raw TCP/IP printing on port 9100. No cloud, no third-party servers — 100% local network.

## How it works

Android renders the document to PDF, then this plugin opens a raw TCP socket to your printer on port 9100 and streams the data directly. This bypasses IPP/Mopria compatibility issues that affect the 3025.

## Prerequisites

On your printer's web interface (`http://<printer-ip>/sws/index.html`):
- **Properties → Network Settings → Raw TCP/IP, LPR, IPP**
- Ensure **Raw TCP/IP Printing** is enabled on port **9100**

## Build & Install

### Option A — GitHub Actions (recommended)

1. Fork or push this repo to GitHub
2. Go to **Actions** tab → **Build APK** → **Run workflow**
3. Download the APK from the workflow artifacts
4. Sideload to your Android device:
   - Enable **Settings → Developer options → Install unknown apps**
   - Open the APK file on your device

### Option B — Docker locally

```bash
docker run --rm -v "$PWD":/project -w /project thyrlian/android-sdk bash -c \
  "chmod +x gradlew && ./gradlew assembleRelease"
```
APK will be at `app/build/outputs/apk/release/app-release.apk`

## Setup on Android

1. Install the APK
2. Open the app → set your **printer IP address** (default: `192.168.0.109`)
3. Go to **Android Settings → Connected devices → Printing**
4. Enable **Xerox 3025 Print Plugin**
5. Open any document, tap **Print**, select **Xerox WorkCentre 3025**

## Configuration

| Setting | Default | Description |
|---|---|---|
| Printer IP | 192.168.0.109 | Your printer's local IP address |
| Port | 9100 | Raw TCP/IP port (don't change unless needed) |
| Display Name | Xerox WorkCentre 3025 | Name shown in print dialog |

> **Tip:** Give your printer a static IP in your router's DHCP settings so the IP never changes.

## Troubleshooting

- **Printer not appearing** — Make sure the plugin is enabled in Settings → Printing
- **Job fails** — Verify the IP is correct and printer is on the same WiFi network
- **Wrong output** — The 3025 expects PCL/PDF data; Android should send PDF by default

## Privacy

This app:
- Makes **no internet connections** of any kind
- Has **no analytics, tracking, or telemetry**
- Only accesses your **local network** to reach the printer
- Stores settings **locally** via Android SharedPreferences only

## License

MIT
