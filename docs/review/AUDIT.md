# Technical audit — baseline (2026-10-29)

Upstream: https://github.com/pirvu/android-print-plugin-xerox-workcenter-3025-wifi
Baseline: d1928ae2eb9b208e8f1e7731caa4ed4b9d2c5dbe (full clone, main preserved).
Working branch: fix/reliability-android15. No tablet installation or printer traffic authorized/performed.

Reviewed README, docs/SPEC.md, docs/DEBUGLOG.md, manifest/resources, all Java sources,
build configuration and workflows, and all GitHub issues (one open issue, #1, no comments).
The upstream debug log already records physical output despite missing status; it does
not prove that a missing reply means printer-busy. Issue #1 has no packet capture, so
fragmentation is a demonstrated code defect and plausible explanation, not a proven
exclusive cause on the reporter's printer.

| Severity | Baseline defect | Required correction |
| --- | --- | --- |
| High | One read of HTTP response; substring `200`; no framing/length/chunk support | Bounded HTTP reader, validate IPP reply, three-way result, never retry |
| High | All 600 DPI pages retained; several whole-document byte arrays | Banded rendering, streaming URF to private disk spool, file upload |
| High | One cancellation flag across all simultaneous unbounded threads | Per-job token, close active I/O, single worker, terminal-state ordering |
| High | Ambiguous submission reported failed, encourages duplicate printing | Explicit UNKNOWN state, blocked Android job with no resubmission |
| Medium | All pages stretched to A4; copies ignored; unsupported 300 DPI/duplex advertised | Actual selected media/orientation, copies in URF, simplex/600 only |
| Medium | Resources not closed on exceptions, no write timeout or disk limits | Structured ownership, cancellation checks, bounded spool/deadline |
| Medium | Arbitrary destination/DNS and WSD redirects; unbounded network input | Private numeric IPv4, no proxy/redirect, response limits |
| Medium | Entire cache exposed through FileProvider; backup enabled | Scans-only sharing directory; disable backup |
| Medium | Scan save may report success without data; legacy storage needs missing permission | SAF save across supported versions; close streams, narrow URI grants |
| Medium | Diagnostic suite duplicates unsafe raster path and calls unknown outcomes errors | Reuse bounded PDF pipeline; distinguish accepted/unknown/rejected |
| Low | Concurrent history writes can lose records; logs include scan job token | Synchronize history, omit token |
| Legal | README claims MIT but no LICENSE file in baseline/history | Do not invent an author license; ask upstream to supply LICENSE before redistribution |

PrintService registration has correct BIND_PRINT_SERVICE protection and metadata.
Android receives spooler PDFs, so image/web printing depends on source app's PrintDocumentAdapter.
No advertising, account, analytics or cloud SDK found. Production dependencies are AndroidX UI
and Material; 'zero third-party dependencies' in SPEC is inaccurate. Plain HTTP is necessary
for this device and carries document data unencrypted over LAN. Hardware output, WSD behavior,
manufacturer Android 15 lifecycle/power behavior and print completion remain unverified.
