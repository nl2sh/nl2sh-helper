# Signed builds and releases

This follows ascrcpy's four-secret signing workflow. CI builds debug and unsigned release APKs without secrets. The Release workflow checks out the requested tag, tests, lints, signs, verifies and publishes the APK. Use JDK 17 and Android SDK 36.

## Create or reuse a release key

Reuse the existing key for this application. For the first release, generate one locally; keytool prompts for passwords:

```sh
mkdir -p "$HOME/.android-signing"
chmod 700 "$HOME/.android-signing"
keytool -genkeypair -v -storetype JKS \
  -keystore "$HOME/.android-signing/nl2sh-helper-release.jks" \
  -alias nl2sh-helper -keyalg RSA -keysize 4096 -validity 10000
chmod 600 "$HOME/.android-signing/nl2sh-helper-release.jks"
```

Back up the key, alias and passwords securely. Keep the same signing key for updates; never commit or share credentials in chat.

## Build locally

Copy `keystore.properties.example` to the Git-ignored `keystore.properties` at the repository root. Set `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. The path is absolute or relative to the repository root; `~` is not expanded. Java properties require escaped backslashes and leading spaces in passwords. Alternatively use environment variables `NL2SH_HELPER_KEYSTORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`, which override the file and preserve passwords literally.

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease \
  -Pnl2shHelperVersionName=0.1.0 -Pnl2shHelperVersionCode=100
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

Verification must pass with v2/v3 signatures. An existing debug installation has a different signature and cannot be overwritten; uninstalling clears app data. With no signing settings the output is `app-release-unsigned.apk`. Partial settings, a missing file or incorrect passwords fail the build.

## Configure repository secrets

In **nl2sh/nl2sh-helper**, open `Settings → Secrets and variables → Actions` and set `KEYSTORE_BASE64` (the Base64-encoded keystore), `STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. With authenticated GitHub CLI:

```sh
base64 -w0 "$HOME/.android-signing/nl2sh-helper-release.jks" | \
  gh secret set KEYSTORE_BASE64 --repo nl2sh/nl2sh-helper
gh secret set STORE_PASSWORD --repo nl2sh/nl2sh-helper
gh secret set KEY_ALIAS --repo nl2sh/nl2sh-helper
gh secret set KEY_PASSWORD --repo nl2sh/nl2sh-helper
```

The last three commands prompt for values. CI passes passwords through environment variables and removes its temporary key when finished. Pull requests never receive signing secrets.

## Publish

Commit and push the workflow changes first, then tag the release commit:

```sh
git tag -a v0.1.0 -m "nl2sh-helper 0.1.0"
git push origin v0.1.0
```

Watch Actions → Release and download `nl2sh-helper-0.1.0.apk` from GitHub Releases. Manual dispatch accepts an existing **full tag**, such as `v0.1.0`, and checks out that tag. An already published release cannot be created again; use a new tag for the next version.

`versionName` removes the leading `v`. `versionCode = MAJOR * 10000 + MINOR * 100 + PATCH`, matching ascrcpy. No leading zeros; minor and patch < 100, major < 210000; `v0.0.0` is rejected. Suffixes such as `-rc1` create prereleases but share the same code as the corresponding stable version. Choose a higher three-part version for upgrades, and always exceed previously distributed version codes. Local defaults are `0.2.0` / `200`; pass both Gradle version overrides for a release.

Release runs unit tests and release lint, and requires successful apksigner verification before publication. Missing secrets or invalid signatures prevent publication.

Runtime release order: publish the selected Bridge/JADX versions, publish the native release with its signed manifest, then distribute Helper meeting the minimum version. The production GPG private key stays in the native Actions signing environment; Helper packages only the public key. Local production signing may be deferred.
