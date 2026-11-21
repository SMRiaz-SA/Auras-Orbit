# Changelog

## 1.0.0.10 — Settings wiring completed; unpublished local candidate

- Replaced misleading Appearance and language placeholders with explicit status: the accepted dark theme and separate phone/TV layouts are active, while translated interface resources are not available in this build.
- Wired the Settings download-folder action to the existing Android folder picker, Storage usage to the existing Downloads summary, and the active-profile action to the existing profile picker. The profile action returns to its Settings row after selection or cancel.
- Kept Android automatic backup explicitly off by design and directed users to the encrypted `.orbitlib` archive. Existing backup behavior was not changed.
- Built and signed the Release APK with the established update-compatible certificate, verified its package/version and 16 KB alignment, and installed it on the Huawei phone and Android TV emulator. Phone folder selection, storage navigation, profile picker return, and appearance status passed. TV D-pad storage/profile navigation and focus return passed; its system folder picker is a transparent platform stub. Details: [Settings acceptance](artifacts/settings-acceptance-2026-11-18/README.md).
- APK SHA-256: `649FB5FAB04ADF3D9F451D83448502DB50889CF86290F296E11271913602F27E`. This local build is unpublished; it does not close `.06` updater/data migration, app-wide TalkBack, or conditional live Jellyfin/FCast acceptance.

## 1.0.0.09 — TMDB API key controls

- Added About settings to view the app-default public TMDB v3 key, enter a personal 32-character key after validating it against TMDB, and restore the app default. The personal key is stored locally and is not required for users who keep the default.

## 1.0.0.08 — local phone acceptance passed; unpublished

- Added profile-scoped subtitle defaults: automatic selection follows the device language, `None` disables automatic selection, and explicit language tags are supported. Profile-scoped appearance settings cover text size, color, background, and outline.
- Added link-specific selection of embedded and provider-reported subtitles, local document loading for VTT/SRT/TTML/XML/SSA/ASS, and online search/resource loading through OpenSubtitles, Addic7ed, SubDL, and SubSource. The CC control toggles the selected track.
- Added session-only subtitle timing adjustment in 250 ms steps, bounded to ±10 seconds. Source reloads preserve playback position and use the configured video/provider and subtitle-specific headers.
- Detect and parse provider-reported SubRip content even when the link labels it `text/vtt`; operator-observed StreamPlay phone acceptance confirmed visible captions and the in-player CC On/Off control on one title/link. No screenshot or raw device log is retained. The historical acceptance record remains in workspace-only evidence and is not included in the source archive.
- The 1.0.0.08 Release APK was assembled, signed with the established update-compatible certificate, aligned for 16 KB pages, and installed in place on the Huawei phone without clearing app data. Its local SHA-256 is `CCD195504974654BE3982B75240F8008887DBED7B2597F867B94EA67F09FC8E9`. This device check covers one StreamPlay title/link and is not a provider-wide playback campaign. The APK remains unpublished.
- Android app sources compile, and 14 focused helper JUnit tests passed when invoked directly. Gradle `:app:testDebugUnitTest` remains blocked by an existing unrelated Kotlin test type mismatch. Published `.06` to `.08` updater/data migration acceptance remains open.
