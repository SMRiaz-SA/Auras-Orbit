# Phone TalkBack smoke for the `.10` candidate

**Date:** 2026-11-18  
**Device:** disposable phone AVD `emulator-5556`, Android 16/API 36, 1080 × 2400.  
**Purpose:** limited screen-reader smoke evidence; not full accessibility acceptance.

## Observed routes and controls

With Google TalkBack enabled, the phone AVD was traversed through Home, Search, Library, Downloads, Settings, Appearance & language, and Profile & app data. Accessibility hierarchy labels/descriptions were present for the top-level navigation, profile action, Home search and source picker, download folder/storage/queue rows, Settings categories, appearance availability, encrypted backup, and Android auto-backup status. Search filter categories and torrent actions were present in the empty-provider state.

The focused synthetic Jellyfin/player instrumentation passed on the phone AVD. It now directly asserts the Back, Replay, Rewind, and Forward labels and action/focus semantics. Google TTS synthesis/dispatch was observed in system logs while TalkBack was bound.

The smoke review found that the “Profile & app data” action description could end in two periods because its supplied description already contained sentence punctuation. The shared settings formatter now trims each segment and adds terminal punctuation only when needed. Two JVM cases cover this behavior. After rebuilding, the refreshed `.10` app's phone hierarchy reported `CloseoutQA. Active profile on this device. Switching profiles reloads that profile's settings and extensions. Change.` with no duplicated periods. This verifies the generated content description, not the words actually heard; no new human-heard transcript was captured.

## Limits

- No human-heard transcript or recording was captured, so the exact spoken wording is not verified.
- The route traversal is a smoke sample, not a full audit of focus order, dialogs, announcements, errors, or every control.
- The player test uses a local synthetic fixture, not a live Jellyfin server or provider.
- No TV emulator or TV device was operated; the user deferred TV testing.
- TalkBack was restored to off on the disposable phone AVD after the smoke run.
