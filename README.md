# EmberTV

<img src="assets/embertv-flame-tv-icon.png" alt="EmberTV orange flame in a television logo" width="144" />

An Android TV APK catalog and installer with an iOS companion. EmberTV reads public GitHub-hosted JSON catalogs, downloads the **publisher-signed** APK, compares it with any installed package, and asks Android to install it. It never re-signs third-party APKs or uninstalls apps to work around signature conflicts.

## TV features

- Remote-friendly navigation: Discover, Installed, Sources, Pair iPhone, and About.
- Multiple persistent sources. Paste a direct HTTPS JSON catalog URL or a public GitHub repository URL. GitHub repositories without a JSON feed are browsed through their published `.apk` release assets, with SHA-256 digests when GitHub provides them.
- One consolidated EmberTV JSON catalog for EmberTV, Drift, Reynard TV and Amazon Music TV Plus, plus built-in GitHub release discovery for Reynard and Amazon Music TV Plus. The `Build signed Drift Android TV and sync catalog` workflow checks the latest `soum91/drift` source every six hours, builds and signs a Drift TV APK with the existing EmberTV permanent signing key, verifies its certificate and package/version, publishes it under an `android-v*` release tag in `soum91/embertv`, and updates the catalog. Legacy Drift build 2 used a different signing key and must be uninstalled once; subsequent builds can update in place.
- Both EmberTV catalogs and AltStore-style `apps[].versions[]` entries with APK `downloadURL` links. iOS-only IPA entries are ignored.
- Installed version comparisons using Android `versionCode` and public signing certificates. A mismatch stops before installation and does not remove app data.
- **APK backup and export:** Installed lists user apps, system apps, and all apps, with search and a per-app export option. A single APK saves as .apk; installed split APK sets save as .apks ZIP bundles including the base and each split. Choose a Files/USB location through Android's document picker; the TV companion can alternatively download and share the same package on iPhone.
- APK backup is package-only, not a data backup. It does not copy private app data, logins, databases, OBB assets, or extract signing keys. Protected firmware/APEX packages whose APK files are unreadable will report an error instead of producing an incomplete backup.
- HTTPS download, optional SHA-256 verification, Android PackageInstaller, and explicit TV confirmation.
- Local-network companion pairing with a six-digit code, persisted pairing credentials, and authenticated install commands.
- An opt-in-controllable Android connected-device foreground service keeps the APK receiver and installer active when the TV UI is closed. It shows an ongoing notification and can restart after boot or an app update. You can stop/restart it from Pair iPhone.
- Background APK downloading and session preparation continue without the TV UI, but Android still requires user approval. The installer displays a notification and resumes pending approval when EmberTV is reopened.

## iPhone companion

The iOS 16+ EmberTV companion browses JSON and public GitHub APK release sources, pairs with the TV via its IP address and the code shown on the Pair screen, and asks the TV to download and install selected apps. Its **Backups** tab lists installed system and user apps and lets you download their APK or split APK set to the native iOS share sheet, including Save to Files. It also sends a local `.apk` through the Files picker. Both devices must be on the same trusted LAN. Launch EmberTV on the TV once to enable its background receiver. The iPhone Connect screen includes a Test TV connection action for checking the TV listener before pairing. This initial companion uses a pairing token over local HTTP and should **not** be exposed to the public internet.

Drift file transfer is handled by its own **standalone Drift Companion** app, built and released from `soum91/drift`; it is intentionally not a tab in EmberTV Companion. EmberTV's consolidated Android catalog still lists the Drift TV APK.

**iOS file-picker convention:** Use native `UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)` with a delegate callback, and validate the file extension after selection. Do not constrain a SwiftUI `fileImporter` to a dynamic APK/IPA `UTType(filenameExtension:)`: this can leave valid files greyed out or prevent the selection callback. Keep this native-picker approach for future EmberTV iOS file imports.

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

EmberTV deliberately does not silently install, bypass Android's unknown-source permission, access private GitHub assets, or replace an app with an incompatible certificate. APK and source URLs must use HTTPS. GitHub's release API requires authorization for private APKs; only public APK assets are added to the shared catalog. Projects without a downloadable APK release, such as WarpTVPort or the current Timbre repository, cannot be installed from their repository alone. The Amazon Music TV Plus APK uses the original `com.amazon.mp3` package ID, so Android will reject an in-place update of any installed version signed by a different certificate. Only pair on a private Wi-Fi network; the authenticated background receiver, including APK export, remains available while the foreground service is running. Do not expose its HTTP port to the internet. APK SHA-256 hashes are strongly recommended for catalog publishers. Split `.apks`/`.apkm` archives are not yet supported.
