# nl2sh Helper

The Compose + Navigation3 interface organizes connection, service, Bridge and history on four separate tabs.

<img src="assets/icon.png" width="128" alt="nl2sh助手图标">

[简体中文](README.md) | [English](README_EN.md)

Android API 26+ ADB installer and browser launcher for [nl2sh](https://github.com/nl2sh/nl2sh). Connect by TCP ADB or Android 11+ wireless pairing code/QR, verify the latest ARM64/ARMv7/x86_64 release with SHA-256, and launch the target Web UI in the background. Successful connections are saved without pairing secrets.

Use a trusted network: the target Web UI listens on its returned actual port without login. This app is separate from the Accessibility/keyboard companion and the JADX helper.

Build with JDK 17 and Android SDK 36:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

[Connection, deployment and troubleshooting guide](docs/en/guide.md)

[Signed builds and GitHub Releases](docs/en/releasing.md)

[Interface and visual conventions](docs/en/design.md) · [Contributor design contract](UI_DESIGN.md)

Connecting reuses a healthy installation. Separate controls check updates, restart, or stop; URLs use the actual native service port. Updates preserve a verified previous binary and attempt rollback on failure. See the [guide](docs/en/guide.md) for legacy compatibility and recovery.

Release downloads authenticate the compatibility manifest first, then verify the native asset SHA-256, exact size and detached GPG signature. The pinned public-key fingerprint is `5230D3A7CCBEED4616D39C51FC6AD1BC63F7D4D8`; network responses cannot replace it. The manifest requires a compatible Helper version and service protocol. Unsigned releases cannot be newly installed or upgraded. Healthy installed services remain connectable without querying releases.


On Android 11+, Local mode pairs with this device over wireless debugging and installs/runs nl2sh as shell. It supports a draggable pairing overlay without split screen, discovery and manual connection ports; Web uses the actual port on `127.0.0.1`. See the [local guide](docs/en/guide.md#install-and-run-on-this-device).
