# Changelog

## 1.0.0.01 — 2026-10-15

### Stable release

- Separate search into **Titles** and **People** scopes on Android and Windows so results are not mixed; normalize people queries and require at least two characters.
- Route Android startup updates through the digest-verifying updater, compare release versions numerically, and recognize Orbit's APK asset.
- Point the Windows updater at Auras Orbit releases and offer updates only when a matching Windows package is published.
- Keep startup usable when update checks fail, and open the release page for desktop app updates.

## 1.0.0.00 — 2026-10-10

### Stable release

- First stable release of Auras Orbit for Windows desktop and Android.
