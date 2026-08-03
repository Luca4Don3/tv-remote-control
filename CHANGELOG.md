# Changelog

This project follows `major.minor.patch` releases and `major.minor-rcN` release candidates.

## Unreleased

- Added Android, Zig, and Swift regression coverage plus cross-platform CI and a unified local quality gate.
- Improved desktop operation timeouts, event-loop failure handling, credential migration visibility, process cleanup diagnostics, and protocol framing separation.
- Tightened Android H.264 configuration, identifier, TLS-policy, media-writer, and encoder cleanup handling.
- Added security and contribution policies. Hardware-dependent validation remains explicitly unverified.

## 0.1.2

- Added the Android TV agent and native Windows/macOS controller foundations for authenticated discovery, pairing, control acknowledgements, and media transport.
- Added bounded MediaProjection quality reduction and optional, user-enabled ADB/scrcpy media support with `control=false`.
- Added Windows x86/x64/ARM64 and macOS ARM64 build paths, external Android signing support, locked third-party dependencies, and release safety gates.
- Fixed Android, Windows, macOS, and media lifecycle issues found during staged reviews.

Hardware-dependent Android and Windows media behavior remained unverified at release time; cross-compilation was not treated as device evidence.
