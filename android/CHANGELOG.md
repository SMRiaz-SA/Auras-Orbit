# Changelog

## 1.0.0.11 — 2026-11-23 (prepared; unpublished)

- Prefetch up to two adjacent posters on visible Home provider shelves, using the shared artwork cache and a bounded request queue.
- Open a selected title's details immediately from a Home shelf's **View all** page by dismissing the expanded shelf first.

## 1.0.0.10 — 2026-11-23

- Add episode artwork and synopsis to the long-press sheet while retaining its title and actions. Separate the sheet sections with teal dividers and use nested scrolling to keep touch gestures distinct.
- Add safe-area padding to the phone launcher icon and Android 12 splash mark to prevent logo cropping.
- Preserve provider and add-on stream results without an arbitrary item-count limit. Handle malformed Stremio entries individually, map declared media types, and retain valid playback and subtitle request metadata.
- Keep the existing profile subtitle controls and selection behavior while improving subtitle data handling at provider boundaries.
- Replace misleading Appearance and language placeholders with accurate status. Wire the Settings download-folder, storage usage, and active-profile actions to their existing Android screens.
- Keep automatic Android backup off by design and direct users to the encrypted `.orbitlib` archive.

## 1.0.0.09 — TMDB API key controls

- Added About settings to view the app-default public TMDB v3 key, enter a personal 32-character key after validating it against TMDB, and restore the app default. The personal key is stored locally and is not required for users who keep the default.

## 1.0.0.08 — local phone acceptance passed; unpublished

- Added profile-scoped subtitle defaults: automatic selection follows the device language, `None` disables automatic selection, and explicit language tags are supported. Profile-scoped appearance settings cover text size, color, background, and outline.
- Added link-specific selection of embedded and provider-reported subtitles, local document loading for VTT/SRT/TTML/XML/SSA/ASS, and online search/resource loading through OpenSubtitles, Addic7ed, SubDL, and SubSource. The CC control toggles the selected track.
- Added session-only subtitle timing adjustment in 250 ms steps, bounded to ±10 seconds. Source reloads preserve playback position and use the configured video/provider and subtitle-specific headers.
- Detect and parse provider-reported SubRip content even when the link labels it `text/vtt`; operator-observed StreamPlay phone acceptance confirmed visible captions and the in-player CC On/Off control on one title/link. No screenshot or raw device log is retained. The historical acceptance record remains in workspace-only evidence and is not included in the source archive.
- The 1.0.0.08 Release APK was assembled, signed with the established update-compatible certificate, aligned for 16 KB pages, and installed in place on the Huawei phone without clearing app data. Its local SHA-256 is `CCD195504974654BE3982B75240F8008887DBED7B2597F867B94EA67F09FC8E9`. This device check covers one StreamPlay title/link and is not a provider-wide playback campaign. The APK remains unpublished.
- Android app sources compile, and 14 focused helper JUnit tests passed when invoked directly. Gradle `:app:testDebugUnitTest` remains blocked by an existing unrelated Kotlin test type mismatch. Published `.06` to `.08` updater/data migration acceptance remains open.
