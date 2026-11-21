# Android Settings acceptance — 2026-11-18

## Artifact identity

- APK: `android/app/build/outputs/apk/release/app-release.apk`
- Package / version: `com.auras.orbit` / `1.0.0.10` / version code `100000010`
- Size: 71,073,488 bytes
- SHA-256: `649FB5FAB04ADF3D9F451D83448502DB50889CF86290F296E11271913602F27E`
- Signing certificate: `92647A8D29D7EECA8D51BA91344FE1EB46AC46BCCB46D2A88BE3998A9B39B344` (matches the stable `.06` signer)
- Verification: `.github/scripts/verify-android-apk.ps1` passed package/version/signer checks and 16 KB alignment for four 64-bit native libraries and the APK ZIP layout.
- Build: Android Release assembled with `ORBIT_RELEASE_VERSION_NAME=1.0.0.10`; Gradle completed successfully.
- Publication: local only. Nothing was committed, pushed, tagged, or published.

## Changes accepted

- **Appearance & language:** informational rows now say that the accepted Auras dark theme and separate phone/TV layouts are active, and that a translated app interface is not available in this build. Neither row implies that it opens a control.
- **Downloads & storage:** Download folder opens the existing Android Storage Access Framework folder picker. Storage usage opens the existing Downloads page and storage summary. No second folder preference or storage implementation was added.
- **Profile & app data:** the active-profile row opens the existing profile picker. Returning by Back preserves the Settings category and row focus. Profile-switch reconciliation is unchanged.
- **Automatic backup:** remains disabled by design and is presented as status with the encrypted `.orbitlib` archive as the supported export route.
- Existing Playback & subtitles, Extensions, Connections, Updates, and About/TMDB screens were not changed by this Settings patch.

## Huawei phone

Target: Huawei JLN-LX1, ADB serial `U4YYD22B11502254`. The `.10` APK installed in place and launched; `dumpsys package` reported version `1.0.0.10`, code `100000010`. Existing Riaz profile and StreamPlay v711 data remained present.

- Settings > Downloads & storage > **Choose** opened Android's document-tree picker. Back canceled without changing the selected folder and returned to Settings.
- **Storage usage** navigated to Downloads. The screen showed the offline-storage summary (`0 B used`, `108.8 GB` available in this emulator session), folder control, empty offline list, and zero active/waiting items.
- Settings > Profile & app data showed **Riaz** as the active profile. **Change** opened the existing profile picker; Back returned to Profile & app data with no profile change.
- Appearance & language showed the two explicit statuses above.

## Android TV emulator

Target: AVD `AurasOrbitPreviewTV`, ADB serial `emulator-5554`, Android TV x86_64, 1920×1080. The same verified APK installed and launched. A disposable `Settings_QA` profile was created only for this emulator acceptance.

- The split Settings layout exposed all eight categories. The Appearance & language status copy fit on one line in each row; the retained screenshot is [tv-appearance-language.png](tv-appearance-language.png).
- D-pad focus reached the Storage usage action. Center opened Downloads and returned focus to its Download folder control.
- The active-profile row had a spoken/content description and opened the profile picker. Back returned to Profile & app data with focus restored to the active-profile row.
- The emulator handles `ACTION_OPEN_DOCUMENT_TREE` with `com.android.tv.frameworkpackagestubs.Stubs$DocumentsStub`, a transparent platform stub. The app invoked the system picker route, but the emulator did not display a usable folder-selection UI. Phone folder selection passed; TV folder selection needs a real TV system picker if it is required for release acceptance.

## Remaining Settings dispositions

- Localization is not implemented; the UI now states this directly.
- Android automatic backup remains intentionally disabled; encrypted `.orbitlib` export/import remains the supported backup path.
- AniList and Trakt OAuth client IDs are empty in the current Release configuration. Those provider-specific OAuth flows cannot be accepted until the product supplies valid client IDs. Jellyfin settings remain available.
- No plugin-by-plugin campaign was run. CS3 remains accepted per the user's direction and its previously recorded scope.

This record covers Settings wiring and focused phone/TV navigation only. It does not close the published `.06` updater/data-preservation gate, app-wide TalkBack acceptance, live Jellyfin, FCast, or the separate TV `.torrent` picker deferral.
