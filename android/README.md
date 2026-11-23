# Android application

This directory is the canonical Android Gradle project. The separate `android-reference/` tree remains the legacy app and updater baseline; the desktop root also maps its `:library` module from `android-reference/library`.

## Version and variants

The Android workspace candidate is `1.0.0.10` (version code `100000010`), set by `ORBIT_RELEASE_VERSION_NAME` in `gradle.properties`; it matches the desktop root's `APP_VERSION=1.0.0.10` and is not published. `LEGACY_ANDROID_APP_VERSION=1.0.0.06` keeps the separate reference app on the latest published version for update compatibility. The release version code is derived from the numeric version components. Stable release builds keep application ID `com.auras.orbit` for the legacy updater path.

Debug and diagnostic variants use version name `0.1.0-shell`; diagnostic variants apply their configured application ID suffixes. The `cs3Acceptance` variant uses `.cs3acceptance`. These variants are for development and acceptance work, not stable release delivery.

## Build

Use JDK 17 and an installed Android SDK. From this directory:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --no-configuration-cache --console=plain
.\gradlew.bat :app:assembleCs3Acceptance --no-daemon --no-configuration-cache --console=plain
```

Local stable builds automatically read the existing update-compatible signer from `android-reference/app/build/test-signing/local-signing.properties`, beside `android-reference/app/build/test-signing/auras-orbit-local-test.p12`. The `test-signing` name is historical: this private key matches the stable `.06` signer and is required for in-place updates. These files are ignored local build data; never commit or paste their contents. CI can continue to supply `AURAS_RELEASE_STORE_FILE`, `AURAS_RELEASE_STORE_PASSWORD`, `AURAS_RELEASE_KEY_ALIAS`, and `AURAS_RELEASE_KEY_PASSWORD` as protected environment secrets. The Gradle build has no debug-key fallback. From the repository root, the release verifier checks package/version metadata, the established legacy signer, native libraries, and APK alignment.

## Release and verification status — 2026-11-23

The coordinated Android and desktop workspace candidate is `1.0.0.10`; it has not been published. The latest published GitHub release is [v1.0.0.06](https://github.com/SMRiaz-SA/Auras-Orbit/releases/tag/v1.0.0.06). The Android Release APK keeps the existing update-compatible signer and application ID, and the local release verifier checks package/version metadata, signer, native libraries, and 16 KB alignment. Candidate packages are not hosted on the project Releases page.

The candidate does not claim full TV acceptance. TV testing remains deferred. Real provider-stream subtitle acceptance and complete phone TalkBack review remain follow-up work. Jellyfin and FCast remain in development; live service acceptance is separate and nonblocking.

## Subtitle implementation

Subtitle defaults are stored per profile. The language setting supports automatic selection using the device language, explicit `None`, or a chosen language tag. Subtitle appearance settings include text size, text color, background, and outline style, and apply to the player.

The player can select embedded text tracks or provider-reported subtitles for the current video link, load a local document (VTT, SRT, TTML/XML, SSA, or ASS), and search/load online subtitles through the four configured repositories: OpenSubtitles, Addic7ed, SubDL, and SubSource. The CC control toggles the selected track on and off.

Subtitle delay is session-only and adjusts in 250 ms steps within ±10 seconds. Reloading the current source for a timing change preserves the playback position and applies the source's video/provider and subtitle-specific headers.

## Third-party sources

See [third-party notices](THIRD-PARTY-NOTICES.md). The patched Torrentserver source is retained under `third-party/torrentserver/`; its required runtime AAR is in `app/libs/`.
