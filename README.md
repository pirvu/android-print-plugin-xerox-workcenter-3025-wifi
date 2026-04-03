# Xerox WorkCentre 3025 — Android Print Plugin

Print from any Android app to a Xerox WorkCentre 3025 over Wi-Fi. No cloud, no accounts, no third-party servers. Just local printing that works.

> *I built this out of pure frustration. I have a perfectly good Xerox WorkCentre 3025 sitting on my desk, it works fine from my Mac and PC, but Android? Nothing. The official Xerox app fails, Mopria says "unsupported", and Google Cloud Print is long dead. The only alternatives I found were paid apps that want full network access and god knows what else — no thanks. So I wrote my own. It talks directly to the printer over your local Wi-Fi, nothing leaves your network, and it just works. This is a working MVP — not pretty, but functional. If you have the same printer and the same frustration, this is for you.*

## Why this exists

The Xerox WorkCentre 3025 has no working Android print support:

- **Xerox Print Service Plugin** — "Printer blocked / Document failed to print"
- **Mopria Print Service** — "Printer unsupported"
- **Google Cloud Print** — discontinued in 2021

There are some paid third-party apps out there, but I don't trust them with my documents and network access. I just wanted something simple and local that I could verify myself.

So I built this. It talks directly to the printer over your local network using IPP — the same protocol macOS uses via AirPrint. No internet, no middlemen.

## Status

**Working MVP.** Tested with the following content types, all printed correctly:

- Multi-page documents (3-page invoice)
- Dense text (full page of small font)
- Grayscale gradients (6 distinct bands)
- Vector graphics (shapes, lines, curves)
- Different paper sizes (A4, US Letter)
- Different fonts (Helvetica, Courier, Times-Roman)

See [DEBUGLOG.md](DEBUGLOG.md) for the full testing and debugging history.

## Quick start

1. Download `app-debug.apk` from [Releases](../../releases) (or build from source)
2. Install the APK on your phone
3. Open the app and set your **printer's IP address**
4. Go to **Android Settings > Connected devices > Printing** and enable **Xerox 3025 Print Plugin**
5. Print from any app — select **Xerox WorkCentre 3025** in the print dialog

> **Tip:** Give your printer a static IP in your router's DHCP settings so it doesn't change.

## How it works

1. Android renders the document to PDF
2. The plugin renders each page to a 600 DPI bitmap using `PdfRenderer`
3. Bitmaps are converted to grayscale and encoded as URF (Universal Raster Format) with PWG compression
4. The URF data is sent to the printer via IPP (Internet Printing Protocol) on port 631

The Xerox WorkCentre 3025 only accepts **URF** (`image/urf`) and **QPDL** (`application/x-QPDL`). Standard PCL, PostScript, and raw PDF are not supported — which is why the official apps fail.

## Features

- **Print any document** — PDF pages rendered at 600 DPI, sent as URF via IPP
- **Network test** — check connectivity to printer (DNS, ping, port 9100, IPP port 631)
- **Print test page** — send a pre-rendered test page to verify the connection
- **Print test suite** — print 5 diverse test documents (invoice, grayscale, text, shapes, letter size)
- **Job history** — view recent print jobs with status
- **Debug logs** — view diagnostic logs for troubleshooting
- **Notifications** — progress and completion notifications for print jobs

## Building from source

### GitHub Actions (recommended)

APKs are automatically built on every push to `main`. To create a release:

```bash
git tag v1.0.0
git push --tags
```

GitHub Actions builds the APK, creates a [Release](../../releases), and attaches the APK for download. Versioning is derived from the tag — `v1.2.3` becomes versionName `1.2.3` and versionCode `10203`.

### Local build (Docker)

```bash
./build.sh              # dev build
./build.sh 1.0.0 10000  # specific version
```

## Troubleshooting

- **Printer not appearing in print dialog** — Make sure the plugin is enabled in Settings > Printing. If it still doesn't appear, try: Settings > Apps > Show system apps > Print Spooler > Clear data, then re-enable the plugin.
- **Print job fails** — Use "Test Network Connection" in the app to verify the printer is reachable.
- **Nothing prints** — Check "View Debug Logs" in the app for detailed error info.
- **Testing the pipeline** — Use "Print via Android Framework" in the app to open the print dialog with a test document, or "Run Print Test Suite" to send 5 test documents directly.

## Future ideas

This is a working MVP. Some things that could be added:

- Better UI (Material Design, proper settings layout)
- Automatic printer discovery (mDNS/Bonjour)
- Duplex printing support
- Scanning support (the 3025 has a scanner)
- Support for other Xerox/Samsung SPL printers

Contributions welcome.

## Privacy

This app:
- Makes **no internet connections** of any kind
- Has **no analytics, tracking, or telemetry**
- Only accesses your **local network** to reach the printer
- Stores settings **locally** via Android SharedPreferences only

## Technical details

See [SPEC.md](SPEC.md) for the full technical specification (URF format, IPP protocol, project structure).

## Acknowledgments

Built with the help of [Claude Code](https://claude.ai/code) — from reverse-engineering the printer's URF protocol to debugging Android's print service framework, it was an invaluable coding partner throughout this project.

## License

MIT
