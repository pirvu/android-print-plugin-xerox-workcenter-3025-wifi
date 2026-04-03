# Debug Log

Chronological log of debugging sessions and findings.

## 2026-04-03: Print service not binding + crash on print

### Problem 1: Service `is_bound=false` — printer not appearing in dialog

**Symptom**: After updating the APK, the Xerox printer stopped appearing in the Android print dialog. `dumpsys print` showed `is_bound=false` even though the service was enabled and the process was running.

**Investigation**:
- Verified print service manifest declaration is correct (intent-filter, permission, meta-data)
- Verified the service resolves in `dumpsys package` Service Resolver Table
- Verified app is not stopped, not restricted, standby bucket = ACTIVE
- Added static initializer + constructor logging to the service class
- No logs from the service class were produced — the class was never loaded
- System server logs showed no `Start proc` for our service — Android never attempted to bind

**Root cause**: Stale state in the Android print spooler (`com.android.printspooler`). The print manager's internal `RemotePrintService` wrapper had a corrupt binding state from a previous installation that persisted across APK updates.

**Fix**: 
```bash
adb shell pm clear com.android.printspooler
adb uninstall com.xerox3025.printplugin
adb install app-debug.apk
adb shell settings put secure enabled_print_services "com.xerox3025.printplugin/.Xerox3025PrintService:com.android.bips/.BuiltInPrintService"
```

**Lesson**: When debugging print service binding issues, always try clearing the print spooler data as a first step. A full uninstall+reinstall may also be needed.

### Problem 2: `IllegalAccessError: must be called from the main thread`

**Symptom**: After fixing binding, the print dialog appeared and the Xerox printer could be selected. Tapping Print caused the service to crash with:
```
java.lang.IllegalAccessError: must be called from the main thread
at android.printservice.PrintJob.start(PrintJob.java:218)
```

**Root cause**: `processPrintJob()` was running in a background `Thread`, but ALL `PrintJob` methods (`start()`, `getInfo()`, `getId()`, `complete()`, `fail()`, `cancel()`, `getDocument()`) must be called from the main thread. This is enforced by `PrintService.throwIfNotCalledOnMainThread()`.

**Fix**: 
1. Extract all needed info from `PrintJob` on the main thread in `onPrintJobQueued()`:
   - `printJob.getInfo().getLabel()` → `jobName`
   - `printJob.getId().hashCode()` → `notifId`
   - `printJob.getDocument().getData()` → `pfd`
   - Call `printJob.start()` on main thread
2. Pass plain data to the background thread
3. Use `mainHandler.post()` for lifecycle callbacks: `printJob.complete()`, `printJob.fail()`, `printJob.cancel()`

### Problem 3: Confirmation dialog blocks print

**Observation**: First time printing with a third-party print service, Android shows a confirmation dialog: "Use Xerox 3025 Print Plugin? Your document may pass through one or more servers on its way to the printer." User must tap OK. This only appears once per service.

### Problem 4: URF PWG Decoding Fail at position 0x13B (315)

**Symptom**: After fixes 1-3, the print job was sent to the printer successfully, but the printer displayed: `URF ERROR - URFPWG Decoding Fail POSITION 0x13 (315)`

**Root cause**: The PWG compression literal byte encoding formula was wrong. Our code used `lit.size() - 1 + 128` which maps:
- 1 literal → byte 128 (which is a NO-OP in CUPS PackBits!)
- 2 literals → byte 129 (decoder reads 257-129=128 literals, but we only provide 2)

This causes immediate stream misalignment — the decoder reads past the provided literals into the next command bytes, corrupting the entire stream.

The correct CUPS PackBits encoding is:
- Byte 0-127: repeat next byte (N+1) times
- Byte 128: no-op (never used by encoder)
- Byte 129-255: next (257-N) bytes are literal data

**Fix**: Changed literal encoding from `lit.size() - 1 + 128` to `257 - litCount`. Also improved literal collection to properly handle short runs (1-2 identical bytes) as literals and only break for runs of 3+.

### Result: SUCCESS

Tested from emulator → printer accepted job (IPP status 0x0000) → page printed correctly. All three bugs fixed. The full pipeline works: PDF → PdfRenderer → Bitmap (4960x7015 @ 600 DPI) → URF with CUPS PackBits compression → IPP Print-Job → printed page.

## 2026-04-03: Print Test Suite — 5 documents, 7 pages, 100% success

Ran automated test suite directly from the app (bypasses Android print dialog, sends PDF→URF→IPP directly):

| # | Document | Pages | URF Size | Content |
|---|----------|-------|----------|---------|
| 1 | invoice_3page.pdf | 3 | 783 KB | Invoice + Terms + Appendix (Courier font) |
| 2 | grayscale_test.pdf | 1 | 19 KB | 6 grayscale bands (5%-100%) |
| 3 | dense_text.pdf | 1 | 2,260 KB | Full page Lorem ipsum (Times-Roman, 10pt) |
| 4 | shapes_test.pdf | 1 | 184 KB | Rectangles, circle, lines at various weights |
| 5 | letter_size.pdf | 1 | 182 KB | US Letter (612x792pt) page |

All 7 pages printed correctly. Some IPP responses returned `0xffffffff` (printer busy processing previous job) but the data was received and printed. The 3-second inter-job pause is too short for the printer — a longer pause or retry logic could help but isn't critical since the printer queues jobs internally.
