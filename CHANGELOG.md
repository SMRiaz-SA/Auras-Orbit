# Changelog

> This file records workspace version changes as well as published releases. The GitHub [Releases page](https://github.com/SMRiaz-SA/Auras-Orbit/releases) identifies [`v1.0.0.06`](https://github.com/SMRiaz-SA/Auras-Orbit/releases/tag/v1.0.0.06) as the latest published release as of 2026-11-18. Desktop `APP_VERSION` and canonical Android `ORBIT_RELEASE_VERSION_NAME` are both configured as the local coordinated candidate `1.0.0.10`. It is unpublished. The Android reference app remains at legacy `1.0.0.06` for same-package update compatibility. The desktop `.07` validation used a one-run command-line override and remains historical evidence.

## Local coordinated candidate — 1.0.0.10 (unpublished)

- Desktop and canonical Android version inputs are aligned at `.10`; the legacy Android reference is explicitly pinned at `.06`. The local Android Release APK is signed with the established `.06` certificate. Nothing has been published.
- Android `:app:testDebugUnitTest` passes all 90 tests, including formatter coverage for settings screen-reader descriptions that prevents duplicated sentence punctuation. The focused phone synthetic Jellyfin player test passes 1/1; it is not live-server acceptance.
- A disposable phone AVD accepted a direct signed `.06` reference APK to `.10` install. Its legacy `rebuild_preference.xml` remained in app-private storage. Orbit's own profile list opened as first-run because legacy profile data is intentionally not imported. The detailed phone and migration records stay in workspace-only evidence and are not included in the source archive.
- The old updater's live GitHub feed and numeric asset path cannot be accepted while `.10` remains unpublished. TV testing is deferred at the user's direction. This candidate does not certify every provider or conditional live integration. See the [current Orbit status report](PROJECT_STATUS_REPORT_2026-11-21.md), the [local deliverables record](artifacts/release-evidence/LOCAL_DELIVERABLES_2026-11-18.md), and the [subtitle audit](artifacts/release-evidence/SUBTITLE_COMPATIBILITY_AUDIT_2026-11-20.md); these records remain outside the source archive.

The `.10` value is a local coordinated candidate, not publication authorization. Keep the historical `.07` validation and `.08` Android acceptance evidence tied to their original runs. Their detailed records remain outside the source archive.

## 1.0.0.06 — 2026-10-29

### Stable release

- Improve Android TV focus and scrolling across Explore and Catalog, make watchlists easier to navigate, correct actor and voice-actor focus and actions, and keep portrait posters portrait-shaped alongside 16:9 landscape artwork.
- Retry Android TV detail loads after provider plugins finish loading; bound provider link loads with per-provider timeouts while allowing caller cancellation to propagate.
- Secure desktop MPD XML parsing against external entities and route YouTube links by matching supported host names.
- Track desktop downloads by attempt generation, cancel active requests, and prevent superseded attempts from completing or updating the current task.
- Resolve Android version metadata from packed Git refs, make Maven Local opt-in, and default Windows setup to a per-user install with an elevation option.

## 1.0.0.05 — 2026-10-28

### Workspace version changes — publication not confirmed

- Put the latest resume item first in Android Explore on phone and TV, with other resume files above the current source. Keep poster titles off the artwork while retaining accessible names.
- Add the same resume block and six status-based Watchlists to Android Catalog on phone and TV while retaining provider browsing.
- Load provider details and episodes progressively on Android so the provider response appears before optional metadata enrichment; reject stale results when navigation or episode selection changes.
- Publish resume items to Android TV's system Watch Next area and a **Popular Content** launcher row from the selected provider, using landscape provider art when available and a full-poster wide fallback otherwise.
- Keep long, multi-season Cast queues moving beyond the first metadata window while retaining poster request headers only on the device.
- Persist a stable identity for each local profile so renames and avatar edits do not detach resume or Cast state; reject delayed queue appends after a profile switch.
- Keep Cast session checks on Android's main thread and bound the per-profile poster-header cache.
- Preserve TV profile focus after saving newly created profile identity.
- Prevent delayed Android TV resume updates from restoring cards after a newer source or profile state has replaced them.
- Publish desktop Explore catalogs progressively, retry only failed add-ons, and keep late results from removed sources or earlier profiles from replacing current content.
- Improve desktop metadata identity resolution and cache reuse, and prepare the next episode's stream lookup in the background with cancellation and timeout handling.
- Keep desktop segmented downloads tied to a validated response and make local-stream proxy recovery and subtitle extraction cancellation safer.
- Add local performance diagnostics for metadata, source lookup, first frame, buffering, and recovery using allowlisted timing fields without titles or stream URLs.

## 1.0.0.03 — 2026-10-27

### Workspace version changes — publication not confirmed

- Bring Android TV Explore closer to the phone experience with source selection, searchable provider lists, resume progress, discovery shelves, retry and paging actions, and Help.
- Route TV catalog browsing to Orbit's Auras catalog screen and preserve hidden poster title text with accessible labels and D-pad focus.
- Add a D-pad accessible **Open local video** action to TV downloads and connect it to the existing local file picker.

## 1.0.0.02 — 2026-10-18

### Workspace version changes — publication not confirmed

- Show actionable Android library errors, let catalog requests retry the selected source, and ensure subtitle searches finish loading with useful empty or error results.
- Connect Orbit to the VLC process it launches through a private loopback Remote Control session, adding pause, resume, and seek controls plus playback position and duration status for watch-progress tracking.
- Expand fixture coverage for HLS rewriting of relative URLs, keys and maps, byte ranges, query strings, and live and VOD playlists.
- Restore keyboard focus and accessible names for player controls, with keyboard interaction for buttons, sliders, overlays, and the context menu.

## 1.0.0.01 — 2026-10-15

### Workspace version changes — publication not confirmed

- Separate search into **Titles** and **People** scopes on Android and Windows so results are not mixed; normalize people queries and require at least two characters.
- Route Android startup updates through the digest-verifying updater, compare release versions numerically, and recognize Orbit's APK asset.
- Point the Windows updater at Auras Orbit releases and offer updates only when a matching Windows package is published.
- Keep startup usable when update checks fail, and open the release page for desktop app updates.

## 1.0.0.00 — 2026-10-10

### Stable release

- First stable release of Auras Orbit for Windows desktop and Android.
