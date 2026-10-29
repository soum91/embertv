# EmberTV

<img src="assets/embertv-flame-tv-icon.png" alt="EmberTV orange flame in a television logo" width="144" />

An Android TV APK catalog and installer with an iOS companion. EmberTV reads public GitHub-hosted JSON catalogs, downloads the **publisher-signed** APK, compares it with any installed package, and asks Android to install it. It never re-signs third-party APKs or uninstalls apps to work around signature conflicts.

## TV features

- Remote-friendly navigation: Discover, Installed, Sources, Pair iPhone, and About.
- Multiple persistent JSON catalogs. Paste a direct `https://...json` URL or a public GitHub repository URL. For GitHub repositories EmberTV discovers `repo.json`, `source.json`, `apps.json`, and `app-repo.json` on the default branch.
- Both EmberTV catalogs and AltStore-style `apps[].versions[]` entries with APK `downloadURL` links. iOS-only IPA entries are ignored.
- Installed version comparisons using Android `versionCode` and public signing certificates. A mismatch stops before installation and does not remove app data.
- HTTPS download, optional SHA-256 verification, Android PackageInstaller, and explicit TV confirmation.
- Local-network companion pairing with a six-digit code and authenticated install commands.

## iPhone companion

The iOS 16+ companion browses the same JSON catalog, pairs with the TV via its IP address and the code shown on the Pair screen, and asks the TV to download and install selected apps. It also sends a local `.apk` through the Files picker. Both devices must be on the same trusted LAN, and EmberTV must be open on the TV to receive commands. This initial companion uses a pairing token over local HTTP and should **not** be exposed to the public internet.

## JSON format

See [`repo.json`](repo.json). Each entry should provide a `packageName` and a `versions` array, where each version contains `version`, `versionCode`, `downloadURL` and optionally `sha256` and `changelog`. `source.json`-style catalogs with `bundleIdentifier` and `localizedDescription` are also accepted when the release URL points to an APK. The installed APK manifest, not JSON, ultimately decides the package name/version/signature.

## Signing and continuity

For an in-place update Android requires the same application ID, an eligible signing identity (including recognized signing lineage), and an acceptable version code. Only the original signing private key can produce future APK signatures. Neither the installed app nor a public GitHub release exposes that key.

The release workflow requires repository secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. **Save the keystore permanently.** Without them CI produces a non-releasable compilation artifact only, not an installable release. Never put a private signing keystore in this public repository. The iOS companion is packaged unsigned and must be signed with your own iOS signing tool before installation.

## Build

Android: `cd tv && gradle :app:assembleDebug` (Android SDK 35 and JDK 17).

iOS: `cd companion && xcodegen generate && xcodebuild -project EmberTVCompanion.xcodeproj -scheme EmberTVCompanion -configuration Release -sdk iphoneos CODE_SIGNING_ALLOWED=NO build` (macOS / Xcode).

`main` pushes build Android and iOS workflow artifacts. The Android release job only publishes a versioned GitHub Release and rewrites `repo.json` when the stable signing secrets are present. Release notes are taken from commits rather than a generic repeated description.

## Security and limits

EmberTV deliberately does not silently install, bypass Android's unknown-source permission, access private GitHub assets, or replace an app with an incompatible certificate. APK and source URLs must use HTTPS. Only pair on a private Wi-Fi network; remote install is disabled after EmberTV exits. APK SHA-256 hashes are strongly recommended for catalog publishers. Split `.apks`/`.apkm` archives are not yet supported.
