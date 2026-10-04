# Connection and deployment guide

The helper is an Android ADB client and Web launcher, separate from the Accessibility/keyboard [android-bridge](https://github.com/nl2sh/android-bridge) companion and the [JADX helper](https://github.com/nl2sh/jadx-helper). It does not contain a model Agent or an A2A/MCP gateway. The controller runs Android API 26+; the target needs an ARM64 or ARMv7 nl2sh release and permission to execute from `/data/local/tmp`.

## Connect

- **TCP ADB:** enter the target address and ADB connection port. Enable TCP ADB separately; the helper does not enable it. Approve the RSA authorization on the target on first connection.
- **Pairing code:** Android 11+ target Wireless debugging supplies a temporary pairing address, pairing port and six-digit code. The helper pairs, then discovers the separate connection service. The pairing port is not the connection port.
- **QR pairing:** display the QR code in the helper and scan it using the target's Wireless debugging QR scanner. Both devices must share a network with working multicast/mDNS discovery. The controller does not need camera permission. Each QR attempt creates a fresh secret.

History is separated by connection mode and saves successful connections only. Records contain address, port, wireless GUID and update time, never the pairing code or QR password. Reconnecting a wireless record searches for its GUID for ten seconds, then tries its last address. Deleting a history row removes that record; it does not revoke the target's ADB authorization. Revoke authorization in target developer settings when necessary.

## What installation does

1. Connect (45-second limit), read the target ABI list and prefer `arm64-v8a` over `armeabi-v7a`. x86/x86_64 releases are not supported by this installer.
2. Query `nl2sh/nl2sh` GitHub's latest release, require matching `nl2sh-android-ABI` and `.sha256` assets, and download over HTTPS. There is no release selector or Gitee fallback.
3. Fetch the checksum even on cache hits. Reuse a private cached binary only after checking its digest. Downloads are limited to 32,000,000 bytes; metadata to 512 KiB and checksums to 1024 bytes. Text requests retry I/O failures up to three times; this is not a retry of the entire deployment.
4. Compare the target binary's digest. If different, upload to `nl2sh.download`, chmod and rename it to `nl2sh`, then verify its device digest. Check `--version` against the release tag.
5. Stop the process recorded in `helper.pid` only if `/proc/PID/exe` matches the managed binary path. Refuse a conflicting port 9999. Start `nohup ./nl2sh --web-only`, saving `helper.pid` and `nl2sh-web.log`, then check the process and `/api/sessions` before reporting success.

Managed files live in `/data/local/tmp/nl2sh-helper/`. The launcher supplies no `--config`: nl2sh uses its own default configuration lookup. It does not provision an API key. Open the returned `http://TARGET_IP:9999/` in the controller's browser to configure the provider and use the Agent. Reconnecting also installs/checks the latest release and restarts the managed service.

## Permissions and troubleshooting

The manifest requests Internet and Wi-Fi multicast permissions, allows cleartext traffic for the target Web UI, and disables application backup. Release downloads require HTTPS. Target Web currently listens on all IPv4 interfaces without login: use a trusted network. Web tool actions still pass through the native security and browser confirmation chain; ADB installation commands themselves are explicit installer operations.

If pairing succeeds but connection fails, check Wireless debugging's current connection port and multicast routing; pairing and connection ports differ and may change. If release lookup fails, check access to GitHub API and assets, rate limits and whether both ABI assets exist. A checksum mismatch is a failure, not an instruction to skip verification. If port 9999 is occupied, stop the identified conflicting service yourself or use a separate nl2sh launch configuration. If startup fails, inspect `/data/local/tmp/nl2sh-helper/nl2sh-web.log` through an authorized ADB session and confirm the controller can reach port 9999.

## Build

Use JDK 17, Android SDK Platform 36, the checked-in Gradle 9.1.0 Wrapper and Android Gradle Plugin 9.0.1. The app has minSdk 26 and targetSdk 35; the ADB dependency is pinned to JitPack `com.github.Ernest-su:adb:v0.3.0`.

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application ID is `ernest.nl2sh.helper`. For local and GitHub Actions release signing, see [Signed builds and releases](releasing.md). Keep `local.properties`, build outputs and signing credentials out of Git. Unit tests cover release ABI/checksum selection and cache reuse/corruption, not real device pairing or Web startup.
