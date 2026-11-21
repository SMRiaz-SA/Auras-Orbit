# Auras Orbit — full project status and roadmap

**As of:** 2026-11-18  
**Scope:** Orbit Windows desktop and Android phone/TV. This is a Nov 18 historical snapshot; the Nov 21 Orbit status report records later evidence. The Nov 17 report remains a dated snapshot of the earlier Android `.07` state; its artifact identities and conclusions remain historical.

## Executive status

| Area | Current status | What the evidence establishes |
|---|---|---|
| Latest public release | **`v1.0.0.06`** | GitHub Releases still marks `.06` Latest, checked 2026-11-18. Local builds and candidates described below are not published. |
| Desktop workspace | **Local `.10` packages built and verified; phone-safe launch smoke passed** | The full desktop/shared JVM run passed 441 tests with no failures, and the native MPV test task passed. Portable distribution and `.10` installer verified. The executable remained up for 15 seconds using an isolated temporary data folder. |
| Android app | **Latest local .10 APK installed on Huawei; no post-update acceptance yet** | The signed APK passed version, signer, and 16 KB alignment checks. It was updated in place on Huawei JLN-LX1 with app data retained; the earlier disposable-phone .06-to-.10 migration check remains recorded below. The candidate is unpublished. No TV testing was done for this closeout. |
| Subtitle issue | **Phone acceptance passed for one StreamPlay title/link** | The provider labeled its English subtitle `text/vtt`, but the response body was SubRip. Orbit detected the body format, rendered captions during `The Odyssey`, and the player CC control switched subtitles On/Off. This is not provider-wide certification. |
| CS3/provider-adoption scope | **Bounded gate ACCEPTED** | The independent verdict covers the agreed static/source review and exact F39 phone/TV host-shell acceptance. The user directs providers to be treated as working unless evidence proves otherwise; no broad plugin campaign was run. |
| Product activation | **Ordinary activation enabled by the accepted crosswalk; canary disabled** | `TARGET_PLUGIN_CROSSWALK_VALIDATED=true` reflects the accepted bounded crosswalk. `CS3_CANARY_BUILD=false` remains off. This does not authorize a public release. |
| Source/release baseline | **Local, dirty, and not committed** | Android source and a substantial set of project records are untracked or modified on `main`. No clean, committed source baseline, release tag, or publication has been made. |
| Generated-output cleanup | **Moved to the workspace delete folder** | 90 ignored build/cache targets (83,829 files; 12,433,105,965 bytes), including nine superseded desktop ZIPs, were moved outside the repository. Current .10 deliverables and test evidence remain; user deletion is pending. |

## Partial and pending work

| Work item | Status | Evidence and remaining action |
|---|---|---|
| Phone TalkBack | **Partial** | Main shell routes, a focused player flow, synthesized speech, and the refreshed profile description were checked. Exact human-heard wording and a complete focus/announcement review remain open. |
| Android update and migration | **Partial** | Signed .06-to-.10 package replacement passed on a disposable phone AVD and preserved the legacy preference file. The refreshed local .10 APK is now installed in place on Huawei JLN-LX1, but no feature acceptance was rerun after that update. Orbit profile auto-import is intentionally absent; the hosted .10 updater feed/numeric asset path remains unverified while unpublished. |
| TV acceptance | **Deferred to the user** | Historical Settings results remain documented. No new TV actions were performed for this closeout; the user plans further TV testing later. Real system folder selection also needs a non-stub Android TV picker. |
| Parent Git commit baseline | **Pending review** | The curated source ZIP builds from a clean extraction; it does not clean or commit this checkout. On main, 11 tracked files are modified, 1 is deleted, and 1,200 files are untracked; nothing is staged in Git. Review and any local commit remain open. |
| Cleanup folder removal | **Pending; user-owned** | The generated data is in D:\Codex\Auras Cloud\delete\auras-cleanup-2026-11-18. Remove that whole folder to free about 12.4 GB. It is outside the Git checkout. |
| Public release and updater feed | **Deferred** | `.10` remains local. Do not push, tag, publish, or test the hosted updater path until the user authorizes publication. |
| Jellyfin and FCast integration | **In development; nonblocking** | Live-server/receiver acceptance is unfinished. Continue the optional integration work without delaying the current release. |
| Localization and OAuth setup | **Pending product configuration** | App translations and AniList/Trakt OAuth client IDs are unavailable; the corresponding flows remain limited or disabled. |

## Version and release identity

Three identities must remain distinct:

| Identity | Version | Status |
|---|---:|---|
| Public stable release | `1.0.0.06` | Latest on GitHub Releases as checked 2026-11-18. [Release page](https://github.com/SMRiaz-SA/Auras-Orbit/releases). |
| Desktop and Android source inputs | `1.0.0.10` | Repository-root `APP_VERSION` and `android/gradle.properties` `ORBIT_RELEASE_VERSION_NAME` are aligned. This is a local coordinated candidate, not a publication decision. |
| Legacy Android reference | `1.0.0.06` / version code `100000006` | `LEGACY_ANDROID_APP_VERSION` keeps `android-reference/app` on the published stable version for same-package update testing. |

The prior plan describing `.07` as the next shared release is superseded. `.10` is now the coordinated local candidate for desktop and Android, above the `.06` public stable. A release tag, publication, and hosted updater acceptance are deferred. The `.07` desktop run remains a validation override, not a release candidate.

At this report date, the Android Release APK was 71,073,491 bytes, SHA-256 `E1B3A4DD3FB312C175CB10DBC3C7689FED8C5D2CA04535FC0C6E5F1EE6650802`. This is a historical `.10` package identity; the later Nov 20 APK is recorded in the [Nov 21 status report](PROJECT_STATUS_REPORT_2026-11-21.md). The signing certificate matches stable `.06`, fingerprint `92647A8D29D7EECA8D51BA91344FE1EB46AC46BCCB46D2A88BE3998A9B39B344`. Local signing reads the existing key from ignored local signing files; keep the key and secret values local. The [deliverables manifest](artifacts/release-evidence/LOCAL_DELIVERABLES_2026-11-18.md) records the package checks and identities. The full workspace hash ledger includes the source ZIP's own SHA-256 and stays outside that ZIP to avoid a self-referential hash.

On 2026-11-18, the refreshed Android Release APK was installed in place on the physical Huawei JLN-LX1 using adb install -r. The install returned Success; package manager reported versionName 1.0.0.10 and versionCode 100000010. No uninstall or app-data clear occurred. This records package replacement only; no screen or playback acceptance was run after installation. The APK SHA-256 is E1B3A4DD3FB312C175CB10DBC3C7689FED8C5D2CA04535FC0C6E5F1EE6650802. On disposable phone AVD emulator-5556, the .06 reference APK was previously replaced in place by the refreshed .10 candidate. The legacy shared_prefs/rebuild_preference.xml hash was identical before and after; the new Orbit profile list showed its first-run screen, consistent with the accepted no-auto-import migration contract. See the phone update record at android/artifacts/local-update-acceptance-2026-11-18/README.md. No TV actions were performed for this closeout. The live GitHub feed and numeric updater asset cannot be verified while .10 is unpublished.

## Android implementation and acceptance

`android/` is the canonical independent Android Gradle root. `android-reference/app/` remains the `.06` legacy app/updater and signing reference; `android-reference/library/` remains mapped into the desktop build. Desktop `APP_VERSION` and Android `ORBIT_RELEASE_VERSION_NAME` now agree at `.10`.

The current `.10` candidate includes the `.08` subtitle work and `.09` TMDB key controls listed in the [Android changelog](android/CHANGELOG.md). Settings now routes Download folder to the existing SAF flow, Storage usage to Downloads, and the active-profile action to the existing picker. Appearance and Language use explicit status copy; Android automatic backup remains a noninteractive off-by-design status. The implementation reuses existing destinations and stored state. The [Settings acceptance record](android/artifacts/settings-acceptance-2026-11-18/README.md) binds phone and TV results to the exact `.10` APK. On phone, the folder picker opened and canceled cleanly, Storage usage displayed existing storage information, and profile picker Back returned to the same Settings row. On TV, D-pad Storage usage and profile selection worked, Back restored focus, and the appearance statuses were visible without clipping. The TV emulator maps `ACTION_OPEN_DOCUMENT_TREE` to a transparent platform stub; use a real Android TV system picker to accept folder selection there. Language translations remain unavailable in this build. AniList and Trakt OAuth client IDs remain empty in the local Release build, so those provider-specific connection flows are not enabled. The user-directed CS3 state is unchanged.

The `.08` StreamPlay `The Odyssey` acceptance is carried forward for one title/link only: the English row reported `text/vtt` but its body contained SubRip cues; content sniffing selects the SubRip parser, captions appeared, and player CC switched Off. Playback was not rerun on `.10` because this settings-only change did not modify subtitle code. No screenshot or raw device log for that playback is retained. The historical phone acceptance record remains in the local evidence folder and is not bundled in this source ZIP. It does not survey other providers or links.

The user accepts the current phone/TV build designs as the visual baseline and retired stale mockup comparison as an acceptance requirement. Current Home/hero evidence confirms the TV hero is present; the earlier screenshot had been scrolled below it. This acceptance does not claim a fresh screen-by-screen screenshot audit. A phone AVD TalkBack smoke visited the main shell routes and confirmed the service synthesized speech; exact heard wording and a complete phone traversal remain unverified. TV TalkBack is deferred at the user's direction.

| Check | Current disposition |
|---|---|
| Release build, signer, package/version, 16 KB alignment | Passed for the exact final local `.10` artifact above; four 64-bit native libraries and the APK ZIP layout passed 16 KB checks. |
| Physical phone launch and retained app data | Passed for the in-place `.10` install; Riaz profile and StreamPlay v711 remained. This is not `.06` updater/data-migration acceptance. |
| Settings phone acceptance | Passed for download-folder picker open/cancel, Storage usage navigation, profile picker return, and explicit appearance/language status. |
| Settings TV acceptance | Passed for `.10` launch, split Settings navigation, D-pad Storage usage/profile actions, profile Back/focus return, and visible appearance/language status. The emulator's document picker is a transparent system stub, so folder selection on TV remains unverified on a real device. |
| StreamPlay subtitle render and player toggle | Operator-observed pass on one `The Odyssey` link: visible captions and CC On/Off confirmed. No screenshot or raw log is retained in the project bundle. |
| Android unit tests | **Passed: 90 tests** | Fixed the test-only `TrailerData` typing mismatch and a Java suspend-bridge test that passed a null `Continuation`; added formatter coverage for settings screen-reader descriptions so already punctuated descriptions do not get a second period. |
| Synthetic player instrumentation | **Passed: 1/1 on the phone AVD** | Jellyfin UI/player fixture asserts Back, Replay, Rewind, and Forward semantics. It uses local fixtures and is not live Jellyfin acceptance. |
| Phone TalkBack smoke | **Partial** | Main shell routes and a focused player flow were traversed with TalkBack bound; TTS synthesis occurred. Settings description punctuation was corrected, covered by JVM tests, and checked in the refreshed `.10` phone hierarchy; no verified human-heard transcript or complete content/focus audit is available. TV testing is deferred. |
| Android warnings/toolchain | The `.10` Release build completed with three unused-expression Kotlin warnings in `OrbitCs3SourceCompat.kt`; the same AGP 9.4.1 / Kotlin Gradle Plugin 2.4.21 toolchain is used. Java deprecation/unchecked notes and strict-lint inventory remain separate from the Settings acceptance. |
| Jellyfin live-server acceptance | In development; unfinished and not a release blocker. |
| FCast receiver acceptance | In development; unfinished and not a release blocker. |

## CS3 and provider status

The bounded CS3 gate is **accepted**. Its independent gate review and F39 host acceptance remain in the local evidence folder and are not bundled in this source ZIP. The accepted scope includes static source/API/ABI, dependency/runtime-classpath, archive, DEX and hierarchy evidence, plus exact F39 phone/TV cold start, profile/Home, TV focus, Back and relaunch checks. The older host-shell result remains bound to APK `86482…`; the fresh F39 result is bound to `F39BB60E…A92C85`.

Per the user’s policy, all 20 provider inventory entries are marked working unless evidence proves otherwise; StreamPlay is user-confirmed. The provider register’s working labels are policy/status dispositions, not claims that Orbit ran every plugin. No broad plugin test campaign was run. The subtitle result above adds one direct phone playback pass for one StreamPlay link.

`TARGET_PLUGIN_CROSSWALK_VALIDATED=true` reflects the accepted bounded crosswalk and permits ordinary product activation. `CS3_CANARY_BUILD=false` keeps canary activation off. No provider-by-provider playback claim or public release authorization is made.

## Desktop status

The repository-root desktop source is configured as `.10`. The local helper ran formatting, compilation, desktop and shared JVM tests (441 XML test cases; zero failures/errors), the explicit upstream `:library:jvmTest` suite (546 XML cases; zero failures/errors), the native MPV test task, and `:desktop-app:createDistributable` successfully. Inno Setup generated a versioned `.10` installer, and `verify-distribution.ps1 -RequireInstaller` passed. A 15-second hidden launch smoke stayed alive and initialized its profile database, extension-cache files, and app log under a unique temporary data directory; that directory is separate from the user's app data. The [deliverables manifest](artifacts/release-evidence/LOCAL_DELIVERABLES_2026-11-18.md) records package identities and verification scope. The earlier `.07` run is separate historical evidence.

The historical desktop `.07` validation used a dirty worktree; its executable was not launched. That run did not validate a `.07` installer, hosted Actions, or a publishable `.07`/`.08` desktop release. The current `.10` local build, installer, and launch smoke are separate evidence above. The curated `.10` source ZIP now passes a full root Gradle build from a clean extraction. This required matching the checked-in Android library ABI reference's final newline to Gradle's generated reference; no API declarations changed. Review and release disposition of the broader uncommitted source changes remains a local baseline decision.

External MAL, AniList, and Simkl tracker integrations were removed on 2026-09-28 at the owner's request. Desktop local watch history, playback progress, and saved lists remain the product behavior. The retained tracker plans and smoke-test records describe the retired implementation and are historical, not active release gates.

## Remaining roadmap, in order

1. **Establish a clean committed source baseline.** The curated 2,985-entry source ZIP passes a full root Gradle build from a clean extraction, with ignored build output and production signing data excluded. Review and commit of the dirty worktree remain open; no files are staged or committed.
2. **Finish updater acceptance when publication is authorized.** The phone-only signed `.06`-to-`.10` direct install passed package/version/signer checks and retained the legacy preference file. The Orbit profile store correctly starts empty under the existing no-auto-import migration contract. The new `.10` GitHub feed/numeric asset path remains untested because the user deferred publication.
3. **Version alignment is resolved locally.** Root desktop `APP_VERSION` and Android `ORBIT_RELEASE_VERSION_NAME` agree at `.10`; `LEGACY_ANDROID_APP_VERSION=.06` preserves the updater reference. No tag or release was made.
4. **Android debug unit tests are resolved.** `:app:testDebugUnitTest` passes all 90 tests. Settings accessibility description formatting now avoids duplicate sentence punctuation. The focused phone synthetic player instrumentation also passes 1/1.
5. **Continue accessibility acceptance on phone; defer TV.** Phone route traversal with TalkBack and a focused player fixture are partial smoke evidence. Exact heard wording and a full phone focus/announcement review remain open; TV testing is deferred at the user's direction.
   - Settings wiring is complete for its previously static download-folder, storage-usage, and active-profile rows. Appearance, language, and Android automatic-backup rows now state availability/design without implying a click. A translated app interface and AniList/Trakt client IDs remain unavailable; these are product/configuration gates. TV folder selection needs a real system picker because the current emulator image supplies a stub.
6. **Continue optional integrations in development.** Jellyfin and FCast live acceptance remains unfinished and is not a release blocker; continue that work independently of the current release.
7. **Keep release local.** Desktop `.10` packaging, installer verification, local launch smoke, Android release verification, and the clean-extraction source build are complete. Do not publish or push until the user says to proceed.

**Deferred by explicit direction:** The user will handle further TV testing later. Broad per-plugin testing is not planned; the provider policy remains working-by-default unless a specific failure is established.

## Workspace and cleanup

The repository is `desktop/auras-desktop`, on local branch `main` at `57afded24d9953077412f81e3e456f6a0e0584c1` with a dirty, uncommitted worktree. Android source is currently untracked in the parent Git tree. The report and documentation changes are local. No files were staged in the Git index, committed, pushed, tagged, or published as part of this reconciliation.

The cleanup folder is D:\Codex\Auras Cloud\delete\auras-cleanup-2026-11-18, outside the repository. Its exact path list and inventory are in MANIFEST.md. This is a manual-delete staging folder, not Git staging.

It contains 90 moved ignored targets: 83,829 files / 12,433,105,965 bytes. These are Gradle and Kotlin caches, intermediate build outputs across the current and reference modules, and nine superseded desktop ZIPs from versions .00 through .06. The target paths contained no reparse points.

As of Nov 18, the current .10 installer, portable ZIP, source ZIP, and Android APK copies were hash-checked before and after the move. All remained unchanged during that cleanup. The source ZIP was refreshed after cleanup to include the then-current README and status records; no product code changed. Android and desktop reports/test-results, android-reference/app/build, canonical source, and workspace-only legacy project evidence remained in the repository at that time. The later `.10` wrap handles obsolete project material separately.

On 2026-11-18, the local coordinated `.10` packages, source ZIP, installer and Android Release APK were built and verified. The prior Settings record retains earlier phone and TV findings; no TV testing was done for this closeout. The phone-only direct `.06` to refreshed `.10` hash-preservation check, 90 Android unit tests, focused player test, and 441 desktop/shared JVM tests are recorded above. A TalkBack row-description punctuation issue found during smoke review was corrected and unit-tested; the refreshed `.10` phone hierarchy reports a single period at each sentence boundary, while full human-heard wording remains unverified. The live updater feed, complete phone TalkBack, and TV testing remain pending. Jellyfin and FCast remain in development and do not block the release. The refreshed .10 APK was installed in place on Huawei JLN-LX1 after package verification, with no feature retest. The worktree remained local and uncommitted; nothing was pushed, tagged, or published.
