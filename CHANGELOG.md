# Changelog

## 1.0.0.05 — 2026-10-28

### Stable release

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

### Stable release

- Bring Android TV Explore closer to the phone experience with source selection, searchable provider lists, resume progress, discovery shelves, retry and paging actions, and Help.
- Route TV catalog browsing to Orbit's Auras catalog screen and preserve hidden poster title text with accessible labels and D-pad focus.
- Add a D-pad accessible **Open local video** action to TV downloads and connect it to the existing local file picker.

## 1.0.0.02 — 2026-10-18

### Stable release

- Show actionable Android library errors, let catalog requests retry the selected source, and ensure subtitle searches finish loading with useful empty or error results.
- Connect Orbit to the VLC process it launches through a private loopback Remote Control session, adding pause, resume, and seek controls plus playback position and duration status for watch-progress tracking.
- Expand fixture coverage for HLS rewriting of relative URLs, keys and maps, byte ranges, query strings, and live and VOD playlists.
- Restore keyboard focus and accessible names for player controls, with keyboard interaction for buttons, sliders, overlays, and the context menu.

## 1.0.0.01 — 2026-10-15

### Stable release

- Separate search into **Titles** and **People** scopes on Android and Windows so results are not mixed; normalize people queries and require at least two characters.
- Route Android startup updates through the digest-verifying updater, compare release versions numerically, and recognize Orbit's APK asset.
- Point the Windows updater at Auras Orbit releases and offer updates only when a matching Windows package is published.
- Keep startup usable when update checks fail, and open the release page for desktop app updates.

## 1.0.0.00 — 2026-10-10

### Stable release

- First stable release of Auras Orbit for Windows desktop and Android.
