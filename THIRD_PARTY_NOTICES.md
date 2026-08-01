# Third-party notices

Release packaging must include the license texts from each downloaded source archive in addition to this summary.

| Component | Locked version | License | Purpose |
|---|---:|---|---|
| Mbed TLS | 3.6.7 | Apache-2.0 OR GPL-2.0-or-later; this project selects Apache-2.0 | Desktop TLS 1.2/1.3 client |
| scrcpy server | 4.1 | Apache-2.0 | Optional user-authorized ADB video/audio backend |
| Android SDK Platform Tools | Resolved stable release | Android SDK License | Optional ADB executable; downloaded only after user confirmation |

No minicap binary is distributed until an exact `SDK + ABI + firmware` profile has passed device verification and its source, license, size, and SHA-256 have been added to `dependencies.lock.json`.
