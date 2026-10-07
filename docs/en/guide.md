# Connection and deployment guide

The helper is an Android ADB client and Web launcher, separate from the Accessibility/keyboard [android-bridge](https://github.com/nl2sh/android-bridge) companion and the [JADX helper](https://github.com/nl2sh/jadx-helper). It does not contain a model Agent or an A2A/MCP gateway. The controller runs Android API 26+; the target needs an ARM64, ARMv7, or x86_64 nl2sh release and permission to execute from `/data/local/tmp`.

## Interface

Connection methods, status and history follow the shared nl2sh dark semantic design. See [Interface and visual conventions](design.md). A checkmark identifies the current mode; busy tasks disable duplicate actions. Details and errors wrap and scroll.

## Connect

- **TCP ADB:** enter the target address and ADB connection port. Enable TCP ADB separately; the helper does not enable it. Approve the RSA authorization on the target on first connection.
- **Pairing code:** Android 11+ target Wireless debugging supplies a temporary pairing address, pairing port and six-digit code. The helper pairs, then discovers the separate connection service. The pairing port is not the connection port.
- **QR pairing:** display the QR code in the helper and scan it using the target's Wireless debugging QR scanner. Both devices must share a network with working multicast/mDNS discovery. The controller does not need camera permission. Each QR attempt creates a fresh secret.

History is separated by connection mode and saves successful connections only. Records contain address, port, wireless GUID and update time, never the pairing code or QR password. Reconnecting a wireless record searches for its GUID for ten seconds, then tries its last address. Deleting a history row removes that record; it does not revoke the target's ADB authorization. Revoke authorization in target developer settings when necessary.

## Connection, updates, and service management

“Connect / first installation” and history Connect first inspect the installed program. A healthy native service is reused without querying releases, downloading, uploading, or restarting. An installed but stopped service starts its existing binary. Only a missing installation downloads the latest release. Once connected, Service management provides separate Check updates, Restart service, and Stop service actions, each with confirmation. Stop/restart cancel tasks and pending approvals while preserving configuration and sessions. Wireless GUID rediscovery retains the selected action.

Lifecycle protocol 1 uses `nl2sh service ... --json`. The helper builds the browser URL from the actual returned port. Device readiness and controller `/healthz` plus `/api/info` checks independently verify status, PID, running version, and port; port 9999 is not assumed. A saved URL does not establish readiness. Connecting an existing managed legacy `nohup` service displays a compatibility warning, preserves its PID/executable and Web checks, and does not migrate or update automatically. Legacy mode still requires fixed port 9999. An explicit update moves to native lifecycle management when the installed version supports it.

## Installation and update procedure

1. Connect within 45 seconds and read the target ABI list. Native `x86_64` takes priority over translated ARM ABIs, followed by `arm64-v8a` and `armeabi-v7a`; x86 releases are unsupported.
2. First installation or explicit update queries the latest `nl2sh/nl2sh` GitHub release, requiring matching `nl2sh-android-ABI` and `.sha256` assets over HTTPS. There is no version selector or Gitee fallback.
3. Fetch and verify checksums even for private cache hits. Limits are 32,000,000 bytes for programs, 512 KiB for metadata, and 1024 bytes for checksums. Text I/O requests retry up to three times, rather than retrying the entire deployment.
4. A matching target version and checksum reuses the service. Otherwise, upload `/data/local/tmp/nl2sh.download` and verify its device checksum and `--version` while the existing service runs. Verify and preserve the old program as `nl2sh.previous`, stop the owned service, atomically rename the new binary, and start it.
5. The new service must pass target version, actual PID/port, and controller Web checks. Failure or cancellation attempts restoration before closing the ADB client: stop the new owned service, quarantine `nl2sh.failed`, verify and restore previous, and check the old service version. Report incomplete rollback honestly and retain files. A lost ADB connection, permission failures, or a corrupt backup may require manual recovery.
6. Successful deployment writes adjacent `nl2sh.owner.json` containing Helper ownership, version, checksum, and GitHub source, without pairing secrets or model credentials. Keep previous until the next update; clean the legacy helper-directory binary only after success.

The default binary path is `/data/local/tmp/nl2sh`. No `--config` or API key is supplied; nl2sh applies its own configuration lookup. Native service locks, state, and logs live in `config.service/` beside the default configuration. Legacy `helper.pid` and `nl2sh-web.log` remain under `/data/local/tmp/nl2sh-helper/`. Open the returned actual URL on the controller to configure the provider and use the Agent.

## Permissions and troubleshooting

The manifest requests Internet and Wi-Fi multicast permissions, allows cleartext traffic for the target Web UI, and disables application backup. Release downloads require HTTPS. Target Web currently listens on all IPv4 interfaces without login: use a trusted network. Web tool actions still pass through the native security and browser confirmation chain; ADB installation commands themselves are explicit installer operations.

If pairing succeeds but connection fails, check Wireless debugging's current connection port and multicast routing; pairing and connection ports differ and may change. If release lookup fails, check access to GitHub API and assets, rate limits and whether both ABI assets exist. A checksum mismatch is a failure, not an instruction to skip verification. Native services report an available port; the controller must reach that actual port. Inspect `config.service/service.log` through authorized ADB for native startup failures, or `/data/local/tmp/nl2sh-helper/nl2sh-web.log` for legacy mode. Update failures state whether rollback completed.

## Build

Use JDK 17, Android SDK Platform 36, the checked-in Gradle 9.1.0 Wrapper and Android Gradle Plugin 9.0.1. The app has minSdk 26 and targetSdk 35; the ADB dependency is pinned to JitPack `com.github.Ernest-su:adb:v0.3.0`.

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application ID is `ernest.nl2sh.helper`. For local and GitHub Actions release signing, see [Signed builds and releases](releasing.md). Keep `local.properties`, build outputs and signing credentials out of Git. Unit tests cover ABI/ELF/checksum selection, caching, and cancellation/failure restoration. The emulator test below covers end-to-end lifecycle behavior; it does not establish vendor-device or wireless-pairing coverage.

## Reproducible lifecycle verification

Use only a disposable x86_64 Android emulator. Prepare a real runtime and a valid Android ELF that deliberately fails startup, then provide authorized TCP ADB and Web routes. The test does not contact release servers. It verifies connection without downloading/restarting, failed upgrade recovery of the old binary and owner, successful Helper-owned upgrade, and reconnect preserving PID. It replaces the target default binary; do not run against a production device.

```sh
python3 scripts/prepare-runtime-fixtures.py --runtime ../nl2sh/target/x86_64-linux-android/release/nl2sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class ernest.nl2sh.helper.RuntimeLifecycleTest \
  -e target_host TARGET_ADB_HOST -e adb_port TARGET_ADB_PORT -e web_port TARGET_WEB_PORT \
  -e runtime_version RUNTIME_VERSION \
  ernest.nl2sh.helper.test/androidx.test.runner.AndroidJUnitRunner
```

Preparation requires ANDROID_NDK_HOME/ANDROID_NDK_ROOT, rustc, and the x86_64-linux-android target. Fixtures stay in ignored app/build output and are excluded from production APKs. The controller must reach the actual service port through its configured Web route.

Release downloads authenticate the compatibility manifest first, then verify the native asset SHA-256, exact size and detached GPG signature. The pinned public-key fingerprint is `5230D3A7CCBEED4616D39C51FC6AD1BC63F7D4D8`; network responses cannot replace it. The manifest requires a compatible Helper version and service protocol. Unsigned releases cannot be newly installed or upgraded. Healthy installed services remain connectable without querying releases.

Signature validation: 18 unit tests and debug/release assembly/lint passed. API 26 device tests cover the pinned key, authenticated signature fixture, tamper and manifest URL drift rejection, plus connection reuse and failed upgrade rollback. Production signing runs in the release workflow and may be deferred locally.

## Android Bridge management

After connecting, the Android control card offers inspection, install/upgrade, opening Bridge, and opening Accessibility/keyboard settings. It displays installed version/protocol, the signed manifest recommendation, and independent enabled/running service states. Version drift is explicit. Failed compatibility verification stays unknown. Healthy service connection does not query releases; only explicit Bridge inspection/installation fetches the signed manifest for that native version.

Installation verifies the GPG signature, exact size, SHA-256, APK package/version and certificate digest. The staged device file is hashed again, installed with `pm install -r`, and checked for actual version/protocol before opening the app. Signature conflicts preserve the existing app and require an explicit migration. Helper opens settings without rewriting Accessibility or keyboard switches or replacing the native service. Enable the independent services manually on the target.

For a disposable emulator, prepare assets with `scripts/prepare-runtime-fixtures.py --runtime <x86_64 Android binary> --bridge <signed Bridge APK>` and build `:app:assembleDebugAndroidTest`. `BridgeManagementTest` takes explicit ADB/Web parameters and covers certificate rejection, real in-place installation, preserved native PID/settings, and all three open actions. Test assets are not committed and do not require production private keys.

Extension diagnostics also show JADX, Tailcat and update ownership from `/api/info` after checking PID/version/actual port. Unverified status stays unknown. API 26 Bridge management regression, 18 unit tests and debug/release assembly/lint passed. UI checks cover normal portrait, 320dp/two-times-font confirmation and narrow landscape actions. Rotation keeps the selected target while clearing stale diagnostics; long confirmation text scrolls.

![Bridge and extension diagnostics; unauthenticated recommendations stay unknown](../assets/ui-bridge.png)
