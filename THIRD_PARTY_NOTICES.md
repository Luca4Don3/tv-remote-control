# Third-party notices

Release packaging must include the license texts from each downloaded source archive in addition to this summary.

| Component | Locked version | License | Purpose |
|---|---:|---|---|
| Mbed TLS | 3.6.7 | Apache-2.0 OR GPL-2.0-or-later; this project selects Apache-2.0 | Desktop TLS 1.2/1.3 client |
| scrcpy server | 4.1 | Apache-2.0 | Optional user-authorized ADB video/audio backend |
| Android SDK Platform Tools | 37.0.1 | Android SDK License (text bundled as `LICENSE-android-platform-tools.txt`) | Optional ADB executable; downloaded only after user confirmation |
| Kotlin standard library | 2.3.21 | Apache-2.0 | Embedded in the Android APK (bundled by AGP built-in Kotlin support) |
| ZXing core | 3.5.3 | Apache-2.0 | TV-side pairing QR code generation |

## Build-time tools

These tools are used to build and test the project but are not distributed in release artifacts.

| Component | Locked version | License | Purpose |
|---|---:|---|---|
| Zig | 0.16.0 | MIT | Windows/macOS controller toolchain |
| Gradle | 9.6.1 | Apache-2.0 | Android build system |
| Android Gradle Plugin | 9.3.1 | Apache-2.0 | Android build tooling (bundles Kotlin standard library into the APK) |
| Eclipse Temurin JDK | 17.0.20 | GPL-2.0-with-classpath-exception | Android build runtime |
| Android SDK Platform | 36 | Android SDK License | Android compilation SDK |
| JUnit | 4.13.2 | EPL-1.0 | Unit tests only; not distributed |
| AndroidX Compose (BOM 2024.12.01 / material3 / ui / activity-compose / lifecycle) | per BOM | Apache-2.0 | Phone controller (`:controller`) UI framework |
| uniffi | 0.32.0 | MPL-2.0 | Rust core FFI bindings (build-time bindings generator) |
| RustCrypto (aes-gcm, hkdf, sha2, subtle, zeroize) | per Cargo.lock | Apache-2.0 / MIT | `core-rs` session crypto |

## CI infrastructure

The following actions are used by GitHub Actions workflows and are not distributed in release artifacts.

| Component | Locked version | License | Purpose |
|---|---:|---|---|
| actions/checkout | v4 | MIT | Repository checkout |
| actions/setup-java | v4 | MIT | JDK provisioning for the Android job |
| swift-actions/setup-swift | v2 | MIT | Swift 6 toolchain provisioning for the macOS job |

No minicap binary is distributed until an exact `SDK + ABI + firmware` profile has passed device verification and its source, license, size, and SHA-256 have been added to `dependencies.lock.json`.
