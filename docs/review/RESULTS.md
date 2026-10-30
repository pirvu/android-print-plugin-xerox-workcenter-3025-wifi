# Reliability review results — 2026-10-29

**Status: development sandbox and emulator validation only.** This is a candidate
for a supervised first physical trial, not a production-validated release.
No APK was installed on the target TCL NXTPAPER 14, and no real Xerox printer
received print or WSD traffic during this review. The audit and proposed changes
were prepared with OpenAI Codex and need maintainer review and hardware testing.

Baseline: `d1928ae2eb9b208e8f1e7731caa4ed4b9d2c5dbe`, preserved on `main` with
the original history. Work is on `fix/reliability-android15`.
[The initial audit](AUDIT.md) records the baseline findings.

## Findings and corrections

| Severity | Finding | Resulting behavior |
| --- | --- | --- |
| High | A single socket read could leave only HTTP headers and produce `0xffffffff` instead of an IPP status. | Bounded HTTP/1.0/1.1 framing handles Content-Length, chunked bodies, informational responses and connection-close bodies. Truncation, inconsistent framing and oversized input are rejected as unconfirmed replies. |
| High | Ambiguous delivery was reported as failure, inviting duplicate printing. | IPP version, content type, request ID and attribute framing are checked. Accepted, rejected and unknown results are distinct; unknown submissions remain blocked and are never automatically resent. A spooler tag guards process recovery. |
| High | All full-resolution page bitmaps and several document buffers stayed in RAM. | One PDF page and one 128-row ARGB band at 600 DPI are processed at a time; URF is encoded to private disk and uploaded as a file with a known Content-Length. |
| High | Cancellation was shared across jobs; work used unbounded overlapping threads. | Per-job tokens close owned I/O, one worker has eight waiting slots, and main-thread terminal handling prevents a cancelled job from completing later. |
| Medium | Output was stretched to A4, copies ignored, 300 DPI and duplex advertised without implementation. | Selected A4/A5/Letter dimensions and orientation are used, aspect ratio is retained, copies are expanded, and only monochrome simplex 600 DPI is advertised. |
| Medium | Resource leaks, indefinite blocked writes and unbounded network/disk input. | Structured ownership, bitmap recycling, spool limits, read/connect timeouts and overall socket deadlines bound the relevant operations. |
| Medium | Arbitrary hostnames/destinations, broad cache sharing and enabled backups. | Numeric private/link-local IPv4 only, direct sockets without proxies/redirects, scans-only FileProvider, backup/transfer exclusion and removal of unused Wi-Fi/network-state permissions. |
| Medium | Scan saving could claim success without a written image or require absent legacy permissions. | Complete bounded MIME image parts, sampled preview, system document picker, checked destination streams and read-only share grants. |
| Medium | Diagnostic rendering duplicated the old memory path and counted unknown results as failures. | Diagnostics reuse the bounded pipeline, generate a valid two-page PDF and report accepted/not accepted/unknown/skipped separately. |
| Low | Concurrent history updates could overwrite results; scan tokens appeared in logs. | Synchronized history and omitted JobToken logging. |
| Repository follow-up | README says MIT but the baseline has no LICENSE file. | Flagged for the maintainer; no author license or copyright notice was invented. |

The single-read bug is demonstrable in the baseline. Its being the exclusive
cause of [issue #1](https://github.com/pirvu/android-print-plugin-xerox-workcenter-3025-wifi/issues/1)
on a 3225 remains an inference; there is no packet capture from that printer.

## Executed automated checks

| Check | Result |
| --- | --- |
| HTTP framing JUnit | 8 passed |
| IPP protocol and loopback socket JUnit | 13 passed |
| URF independent decoder, cancellation and I/O ownership JUnit | 7 passed |
| Local address validation JUnit | 2 passed |
| WSD image/MIME JUnit | 4 passed |
| Android registration, UI lifecycle, sharing and history — Robolectric API 35 | 5 passed |
| PrintService lifecycle and no resend — Robolectric API 35 | 2 passed |
| Native PDF instrumentation — AOSP Android 15 x86_64 emulator | 5 passed |
| Total | **46 passed, 0 failed, 0 skipped** |
| Android lint | **0 errors, 11 warnings** |
| Debug and instrumentation APK builds | Passed |
| Debug APK signature verification | Passed, APK Signature Scheme v2 |
| Emulator installation and settings activity launch | Passed |
| Git whitespace/error check | Passed |

Socket tests use loopback fake printers and cover one-byte fragmentation,
valid success/error classes, malformed or mismatched replies, missing/truncated
responses, read timeout, overall timeout, a stalled upload, connection refusal,
cancellation and no automatic second submission. They do not contact a LAN printer.

Native PDF checks compare every pixel of a 600 DPI vector fixture against a
full-page reference, check bitmap recycling, repeated five-page/two-copy spools
and descriptor stability, paper dimensions/orientation, cancellation, invalid PDFs,
duplex rejection and copy limits. Native tile edge rounding produced differences
in 467 of 540,000 pixels (0.0865%), with a maximum of 2/255 grayscale levels.
The test explicitly permits at most that per-pixel bound and requires at least
99% byte-identical pixels. It does not claim bit-identical raster output or
establish actual paper quality for arbitrary PDFs.

Lint warnings concern existing localization/layout items, available dependency
updates and the API-31 extraction-rule attribute on older supported Android
versions. AGP 8.2's lint lacks the documented `device_*` backup domains; only
those eight domain checks are suppressed, with a source comment. The exclusions
remain in place. Android documents these domains in its
[backup rule syntax](https://developer.android.com/identity/data/autobackup#xml-config-syntax).

## Limits and security scope

- Input PDF: 256 MiB; URF: 512 MiB; 1000 output pages including copies; 1–100 copies.
- App-owned raster bitmap: at most `7200 × 128 × 4` bytes, about 3.52 MiB.
  Android's native PDF parser/renderer and scan buffers have additional memory
  costs; this is not a bound on the entire process or all possible PDF complexity.
- IPP: 5-second connect, 120-second read inactivity, 10-minute overall transfer
  deadline. WSD: 5-second connect, 120-second read inactivity, 3-minute overall
  deadline; image responses at most 32 MiB, ordinary replies at most 1 MiB.
- Production dependencies remain unchanged. OSV querybatch checked the 67 resolved
  production Maven coordinates on 2026-10-29 and returned no matching advisories.
  The current runtime inventory matches the queried set. This is an advisory
  lookup, not a source audit of every dependency or a security guarantee; build
  and test tool dependencies were outside that query.
- The Gradle 8.4 wrapper JAR hash matches the official wrapper checksum and the
  Gradle distribution checksum is pinned. JUnit, Robolectric and instrumentation
  libraries are test dependencies and do not become production runtime SDKs.
- No advertising, analytics, accounts or cloud SDKs were found in the production
  app. LAN document traffic remains unencrypted HTTP, as used by the existing
  device protocol. System document providers and share targets follow user choice.
- Final requested Android permissions are INTERNET and POST_NOTIFICATIONS, plus
  AndroidX's app-scoped signature permission for unexported receivers.

## APK candidate

`com.xerox3025.printplugin`, version `1.1.0-audit1`, versionCode `10101`.
Minimum SDK 26; compile/target SDK 34; runtime checks on API 35. No native `.so`
libraries are bundled. The APK is development-signed and debuggable, not an
upstream release. Size: 5,753,680 bytes.

SHA-256:

```text
81cf547bf213c9ac0267e6af126b959b076075665ae4989edb6f5e733c402a24
```

[TESTING.md](TESTING.md) contains reproduction commands. The
[physical test plan](HARDWARE_TEST_PLAN.md) covers actual sheet output, firmware,
source gallery/browser integration, real WSD saving/sharing and TCL power/lifecycle
behavior. Duplex, ADF, multipage scans, scan-to-PDF and other printer models are
not verified features. IPP acceptance is not physical completion, and stopping
local sending cannot retract data already received by a printer.
