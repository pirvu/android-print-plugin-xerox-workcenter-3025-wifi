# Contributing

Thanks for your interest in contributing! This is a small project but PRs are welcome.

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

This uses Docker with the Android SDK and outputs `app-debug.apk` in the project root.

## Project structure

See [SPEC.md](SPEC.md) for the full technical specification including:
- URF format details and PWG compression
- IPP protocol implementation
- Project file layout and key components
- Build configuration

## Debugging

See [docs/emulator-debugging.md](docs/emulator-debugging.md) for a complete guide on setting up and using the Android emulator for debugging, including common issues and solutions.

See [DEBUGLOG.md](DEBUGLOG.md) for a chronological log of issues found and resolved during development.

The app includes built-in diagnostics:
- **View Debug Logs** — in-app log viewer with copy/clear
- **Test Network Connection** — tests DNS, ping, port 9100, IPP port 631
- **Print Test Page** — sends a pre-rendered URF page directly via IPP
- **Run Print Test Suite** — sends 5 diverse test documents through the full pipeline

## Signing

Release APKs are signed with a dedicated keystore stored in GitHub Actions secrets. See the release workflow for details. The signing keys ensure users can upgrade between versions.

## Areas for contribution

- Better UI (Material Design, proper settings layout)
- Automatic printer discovery (mDNS/Bonjour)
- Duplex printing support
- Scanning support (the 3025 has a scanner)
- Support for other Xerox/Samsung SPL printers
- Localization
