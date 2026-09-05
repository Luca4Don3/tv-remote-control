# Changelog

This project follows `major.minor.patch` releases and `major.minor-rcN` release candidates.

## Unreleased

- Added the iOS controller on the `feat/ios-controller` branch: SwiftUI shell reusing the Zig protocol core cross-compiled to `aarch64-ios` (TOFU/SAS/pin pairing and authentication state machines fully shared with macOS/Windows), a Rust XCFramework (`tvremote_core`) consuming uniffi bindings for the WebSocket debug channel (HKDF/AES-GCM session crypto, client masked frames via `WsCodec.encodeClient`, 15s heartbeat), and Keychain credential storage reused from the macOS controller.
- Fixed the WebSocket client masked-frame gap on both Rust and Kotlin sides: client-to-server frames are now RFC 6455 masked (`WsCodec.encodeClient` / `WsFrameCodec.writeClient`); unmasked client frames were rejected by the agent's server-side decoder.
- Added CI gates for iOS: Rust static libraries (aarch64-ios/ios-sim) packaged into an XCFramework, Zig core cross-build for `aarch64-ios` with explicit iPhoneOS SDK sysroot, and swiftc typecheck of the SwiftUI shell + Rust FFI bindings on the simulator SDK.
- Added `TVRemoteCoreLogic` SPM package availability on iOS 16 for cross-Apple-platform reuse.
- Known gaps (documented, not blocking): the iOS WS debug channel UI hookup awaits a C-core ABI to expose the active credential id (PSK chain); the App Clip target requires an Xcode project (certificate/entitlements) and is deferred to the packaging phase; all iOS behavior is UNVERIFIED pending real-device evidence.

- Added a Rust protocol core (`core-rs`): framing aligned with the Kotlin `FrameCodec`, strict JSON matching `StrictJson`, HKDF-SHA256 + AES-256-GCM session crypto with direction separation, replay-window sequence protection, a minimal RFC 6455 WebSocket frame codec, and uniffi FFI bindings verified through cargo-ndk cross builds for all four Android ABIs.
- Added the `text_commit` / `text_draft` protocol with AccessibilityService text injection (`ACTION_SET_TEXT`, API 21+), a new `textInput` capability bit, and a dedicated text command dispatcher with strictly increasing sequences.
- Added QR pairing: a one-time 60-second token displayed as a `tvrc://pair` QR code on the TV replaces manual code entry without changing the SAS verification flow.
- Added a plaintext WebSocket debug channel (port 47833) with application-layer end-to-end encryption (HKDF-derived directional keys, counter-bound AES-GCM, replay window), restricted to remote-control messages; intended for development debugging only.
- Added the Android phone controller (`:controller`, Compose, minSdk 24): UDP discovery, TLS with TOFU/pinned fingerprints, pairing, remote control, text input, and a WS debug-channel client.
- Extracted the pure-JVM `:protocol-core` module (protocol, auth transcripts, session manager) shared by the TV agent and the phone controller.
- Added the API 19-36 compatibility matrix documenting every version fork and its UNVERIFIED hardware items.
- Extended CI with a Rust core job and multi-module Android gates.
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
