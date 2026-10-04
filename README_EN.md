# nl2sh Helper

[简体中文](README.md) | [English](README_EN.md)

Android API 26+ ADB installer and browser launcher for [nl2sh](https://github.com/nl2sh/nl2sh). Connect by TCP ADB or Android 11+ wireless pairing code/QR, verify the latest ARM64/ARMv7 release with SHA-256, and launch the target Web UI in the background. Successful connections are saved without pairing secrets.

Use a trusted network: the target Web UI currently listens on port 9999 without login. This app is separate from the Accessibility/keyboard companion and the JADX helper.

Build with JDK 17 and Android SDK 36:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

[Connection, deployment and troubleshooting guide](docs/en/guide.md)
