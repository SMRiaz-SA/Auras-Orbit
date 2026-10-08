# Changelog

## 0.2.0.26 — 2026-10-08

### Fixed

- Show the media type from explicit TV or movie shelf categories when a provider gives its cards the wrong type.

## 0.2.0.25 — 2026-10-08

### Added

- Added an Android Help & Manual with guidance for catalogs, person filmographies, and TMDB attribution.
- Added cast and crew person pages on Android, including movie/TV credit filters and provider lookup for selected titles.

### Fixed

- Made the Catalogs More button load additional pages and capped Explore results at the top 10.
- Corrected Android poster labels so TV titles are identified as TV rather than Movie.
- Updated the desktop Help & Manual and TMDB attribution details.

## 0.2.0.24 — 2026-10-07

### Added

- Published the Android beta APK alongside the Windows desktop builds, with both apps using the same source version.

### Fixed

- Gave large plugin archive downloads longer transfer timeouts while retaining HTTPS checks, hash validation, size limits, and archive validation.

## 0.2.0.23 — 2026-10-06

### Added

- Added the Auras Android app foundation with an Explore screen linking to catalogs, search, sources, and library.
- Shared the desktop source version with the Android app and documented the local Android build entry point.

### Fixed

- Expanded the in-app trailer player to the full app window and kept its native video surface square-edged so the player no longer clips at the corners.
- Allowed the native WebView bridge to load capability-protected local trailer URLs while continuing to reject unrelated navigation.

## 0.2.0.22 — 2026-10-06

### Fixed

- Fixed Home catalog sections remaining on loading placeholders after providers returned results.
