# Android application

This directory is the canonical Android Gradle project. The separate `android-reference/` tree remains the legacy app and updater baseline; the desktop root also maps its `:library` module from `android-reference/library`.

## Version and variants

The Android local candidate version is set by `ORBIT_RELEASE_VERSION_NAME` in `gradle.properties`. It is currently `1.0.0.10` (version code `100000010`) and matches the desktop root's `APP_VERSION=1.0.0.10`. `LEGACY_ANDROID_APP_VERSION=1.0.0.06` keeps the separate reference app on the published stable version for update compatibility. The `.10` candidate is local and unpublished. The release version code is derived from the numeric version components. Stable release builds keep application ID `com.auras.orbit` for the legacy updater path.

Debug and diagnostic variants use version name `0.1.0-shell`; diagnostic variants apply their configured application ID suffixes. The `cs3Acceptance` variant uses `.cs3acceptance`. These variants are for development and acceptance work, not stable release delivery.

## Build

Use JDK 17 and an installed Android SDK. From this directory:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --no-configuration-cache --console=plain
.\gradlew.bat :app:assembleCs3Acceptance --no-daemon --no-configuration-cache --console=plain
```

Local stable builds automatically read the existing update-compatible signer from `android-reference/app/build/test-signing/local-signing.properties`, beside `android-reference/app/build/test-signing/auras-orbit-local-test.p12`. The `test-signing` name is historical: this private key matches the stable `.06` signer and is required for in-place updates. These files are ignored local build data; never commit or paste their contents. CI can continue to supply `AURAS_RELEASE_STORE_FILE`, `AURAS_RELEASE_STORE_PASSWORD`, `AURAS_RELEASE_KEY_ALIAS`, and `AURAS_RELEASE_KEY_PASSWORD` as protected environment secrets. The Gradle build has no debug-key fallback. From the repository root, the release verifier checks package/version metadata, the established legacy signer, native libraries, and APK alignment.

## Release and verification status — 2026-11-21

The 1.0.0.07 Release variant and its historical evidence are retained in workspace-only records outside the source archive. The local `.10` Release APK is assembled, signed with the established update-compatible certificate, and aligned for 16 KB pages. Earlier phone and TV Settings evidence and the phone-only signed `.06` to `.10` install record remain workspace-only; no TV testing was done for the Nov 21 closeout, and further TV acceptance is deferred at the user's direction. The old updater's live feed remains unverified because `.10` has not been published.

The earlier phone subtitle acceptance covers one link and predates the latest subtitle repairs; it does not establish real provider-stream behavior on the current APK. The [subtitle audit](../artifacts/release-evidence/SUBTITLE_COMPATIBILITY_AUDIT_2026-11-20.md) and [local deliverables record](../artifacts/release-evidence/LOCAL_DELIVERABLES_2026-11-18.md) remain outside the source archive. The `.10` APK remains local and unpublished. Earlier Android unit and focused instrumentation results are recorded in the [current Orbit status report](../PROJECT_STATUS_REPORT_2026-11-21.md); they do not establish current-source real-stream acceptance or verify the unpublished update feed.

## Subtitle implementation

Subtitle defaults are stored per profile. The language setting supports automatic selection using the device language, explicit `None`, or a chosen language tag. Subtitle appearance settings include text size, text color, background, and outline style, and apply to the player.

The player can select embedded text tracks or provider-reported subtitles for the current video link, load a local document (VTT, SRT, TTML/XML, SSA, or ASS), and search/load online subtitles through the four configured repositories: OpenSubtitles, Addic7ed, SubDL, and SubSource. The CC control toggles the selected track on and off.

Subtitle delay is session-only and adjusts in 250 ms steps within ±10 seconds. Reloading the current source for a timing change preserves the playback position and applies the source's video/provider and subtitle-specific headers.

## Third-party sources

See [third-party notices](THIRD-PARTY-NOTICES.md). The patched Torrentserver source is retained under `third-party/torrentserver/`; its required runtime AAR is in `app/libs/`.
