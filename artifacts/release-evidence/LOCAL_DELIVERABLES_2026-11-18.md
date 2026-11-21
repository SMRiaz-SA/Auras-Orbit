# Local coordinated `.10` deliverables

**Built:** 2026-11-18  
**Status:** local review candidate only. No GitHub push, tag, publication, or updater-feed change was made.

Desktop `APP_VERSION` and canonical Android `ORBIT_RELEASE_VERSION_NAME` are aligned at `1.0.0.10` (`100000010`). The legacy Android reference and latest public stable remain `1.0.0.06` (`100000006`). The Android APK is `com.auras.orbit`, signed with the established `.06` certificate fingerprint `92647A8D29D7EECA8D51BA91344FE1EB46AC46BCCB46D2A88BE3998A9B39B344`. The refreshed APK SHA-256 is `E1B3A4DD3FB312C175CB10DBC3C7689FED8C5D2CA04535FC0C6E5F1EE6650802`; the generic and numeric local copies match it.

The local package set contains the Windows installer, portable ZIP, source ZIP, and Android Release APK. The installer passed Inno Setup compilation and `verify-distribution.ps1 -RequireInstaller`. The portable executable survived a 15-second launch smoke with a temporary isolated data directory; the installer was not installed on Windows. The Android package/version/signer verifier and 16 KB alignment checks passed.

## Verification results

| Check | Result |
|---|---|
| Android release version gate | Passed: `.10` matches desktop and canonical Android inputs, and exceeds legacy/reference and public stable `.06`. |
| Desktop/shared JVM XML tests | 441 tests, 0 failures, 0 errors. |
| Explicit `:library:jvmTest` suite | 546 tests, 0 failures, 0 errors. |
| Native MPV test task | Passed. |
| Android `:app:testDebugUnitTest` | 90 tests, 0 failures. Includes punctuation formatting cases for settings accessibility descriptions. |
| Clean-source build | Full root `gradlew build` passed from a clean extraction of this source ZIP. The Android library ABI reference was regenerated to match Gradle's required final newline; API declarations were unchanged. |
| Phone player instrumentation | 1/1 passed on disposable phone AVD `emulator-5556`, Android 16/API 36. It uses synthetic Jellyfin fixtures, not a live server. |
| Signed `.06` to `.10` replacement | Passed on `emulator-5556` with `adb install -r`; no uninstall or clear-data step between the two release packages. Legacy preference hash stayed `f2871df62679cf5baadf57ac0255def4bc157c9ce0a19878fa9946409ba9e112`. The new app showed first-run profile setup, consistent with the no-auto-import migration contract. |
| Profile accessibility label | The refreshed phone hierarchy reported `CloseoutQA. Active profile on this device. Switching profiles reloads that profile's settings and extensions. Change.` with no duplicated periods. This verifies the generated content description, not wording heard by a person. |
| Source archive | Integrity check passed. The checked-in synthetic Jellyfin instrumentation fixture remains for test reproducibility; local production signing paths and local-only design/planning documents are excluded. |

The disposable AVD was restored to the fresh `0.1.0-shell` debug app with TalkBack disabled. At the documented package closeout, neither the connected TV AVD nor the physical Huawei phone was operated. Afterward, the refreshed local .10 APK was installed on Huawei JLN-LX1 with adb install -r; package manager reports versionName 1.0.0.10 and versionCode 100000010, with no uninstall or app-data clear. No feature acceptance was run after that installation. Phone TalkBack review remains partial: system TTS synthesis was observed earlier, but no human-heard transcript or complete focus/content audit was captured. TV testing is deferred to a later session. Jellyfin and FCast remain in development; live-server/receiver acceptance is unfinished and does not block the current release. No broad plugin campaign was run. `TARGET_PLUGIN_CROSSWALK_VALIDATED=true` and `CS3_CANARY_BUILD=false` remain unchanged.

The full workspace hash ledger records the exact Windows artifact identities and the source ZIP's own SHA-256. That separate ledger stays outside the source ZIP to avoid embedding the archive's own hash here.
