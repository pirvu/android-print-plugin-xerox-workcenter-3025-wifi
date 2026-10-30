# First physical validation — not yet performed

These changes have only been exercised in an isolated development/test
environment. The target TCL NXTPAPER 14 (Android 15) has not received this APK,
and no real WorkCentre 3025/3225 has received print or WSD traffic during this
review. Emulator results do not establish firmware or manufacturer compatibility.

After the device owner authorizes a physical trial:

1. Install the debug APK, configure the actual private IPv4 address, and enable
   this print service in Android's printing settings. A TCP connectivity check
   establishes port reachability, not printer readiness or successful URF decoding.
2. Print one A4 PDF page, one copy. Check readable text, grayscale, edges, margins
   and the absence of a URF error sheet. Compare paper output with the source PDF.
3. Test multiple pages, selected pages, two copies, A4/A5/Letter in both orientations,
   and repeated jobs. Verify page count, ordering, dimensions and aspect ratio.
4. Print from the actual gallery and web browser used on the tablet. Their
   PrintDocumentAdapter output is part of the end-to-end behavior being validated.
5. Test loss of Wi-Fi before and during transmission, cancellation during preparation
   and upload, process recovery, sleep/power restrictions and denied notifications.
   Inspect the printer before initiating another job after an unknown result.
6. Exercise flatbed WSD scanning on port 8018 with the available resolutions and
   color modes. Check complete JPEG output, save through the document picker,
   share into another application, repeat scanning, close and recreate the activity.
7. Measure wall-clock preparation time, process memory, spool storage usage and
   responsiveness on the actual TCL, using representative larger PDFs.

An accepted IPP response confirms submission, not physical completion. An UNKNOWN
submission remains blocked and is not automatically repeated. Android cancellation
closes preparation and transport resources; it does not send IPP Cancel-Job and
cannot retract pages already received by the printer. Previously marked submissions
are held rather than resent; inspect output, cancel, then create a new job if needed.

For local-only storage choose internal storage/Downloads in the system picker.
Other document providers and share destinations may use their own network services
if the user selects them. The plugin itself contains no analytics or cloud SDK.

Keep duplex, ADF/multipage scans, PDF scan export and other printer models outside
the verified feature set until separately implemented and tested. This patch only
advertises simplex, monochrome 600 DPI print capabilities.

For each physical test, record APK SHA-256, tablet OS/build, printer model and
firmware, selected options, source document, actual sheet count, displayed outcome
and sanitized debug output. Avoid publishing personal document contents or tokens.
