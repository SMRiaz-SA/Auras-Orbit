# Changelog

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
