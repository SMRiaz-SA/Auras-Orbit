# Auras Orbit — project progress report

**As of:** 2026-11-21  
**Scope:** Orbit's Windows desktop and Android release worktree only.  
**Evidence boundary:** The local Android APK and connected Huawei were checked on 2026-11-21. Desktop build and release evidence is carried forward from the 2026-11-18 project record. This report records status; it does not approve publication.

## Current status

| Area | Status | What is established |
|---|---|---|
| Public release | **v1.0.0.06 remains latest** | Checked against the repository’s public Releases page on Nov 21. The .10 candidate remains local and unpublished. |
| Desktop | **Local .10 build and package checks passed** | The Nov 18 record documents desktop/shared tests, upstream library tests, native MPV task, distributable, installer verification, isolated launch smoke, and a clean-extraction source build. No new desktop build was run for this update. |
| Android | **Local .10 APK installed on the physical Huawei** | The final local APK is 71,073,487 bytes, SHA-256 37CCF2121DD393D1DDBAD03B43A2C98F41F195B1A11BFBF5F912A41ED2B9A203. Pulling base.apk from the phone produced the same hash. Package version is 1.0.0.10 / code 100000010; the existing install and app data were retained, and Orbit launched. |
| Provider links | **The former 50-link cutoff is removed** | The shared admission bound is 4,096. Details and player lists show 40 rows per page. The reported 47 visible links do not establish a 47-link cap; the title and provider are not yet identified, so the provider’s raw and accepted counts remain unexplained. |
| Subtitles | **Implementation repairs are present; focused checks passed before later source edits, and current-stream acceptance remains open** | The Nov 20 audit corrected negative-offset cue clamping and selected-track diagnostics. Its record lists 22 focused JVM cases and 3 phone instrumentation cases passing; raw instrumentation output was not retained. Later provider-link work modified shared playback code, so those results do not establish the final source snapshot. |
| Jellyfin and FCast | **In development; nonblocking** | Live Jellyfin-server and FCast-receiver acceptance remains unfinished and does not hold the release. |
| TV | **Deferred** | The user plans TV testing later. Earlier focused Settings results remain historical evidence, not current broad TV acceptance. |
| Source baseline | **Clean local baseline after wrap** | Initial inspection found commit `57afded`, 12 tracked changes, 1,202 untracked paths, and nothing staged. The reviewed Orbit changes are now committed locally; no push, tag, or release was made. |

The current APK keeps the existing .10 version name and code. It is a rebuilt local artifact, not a new versioned or published release. No GitHub push, tag, or release was made.

## Local wrap execution update — 2026-11-21

The local `.10` package set was reconciled without rebuilding the app or creating another APK. The existing signed Android Release APK from Nov 20 was verified and copied to the numeric and generic deliverable names. Both copies match the installed phone APK hash. The Nov 18 desktop installer and portable ZIP were retained; their source inputs did not change. The source ZIP was regenerated from the reconciled source list and contains no local acceptance records or status reports.

| Local deliverable | Size | SHA-256 | Verification |
|---|---:|---|---|
| `Auras-Orbit-Setup.exe` | 229,627,203 bytes | `C61FB794AB880E2FB2CF611748BB049505A909B6F3A0A306784441762AA8C6D3` | Product version `1.0.0.10`; preserved from the Nov 18 desktop package. |
| `Auras-Orbit-Portable-1.0.0.10.zip` | 275,907,770 bytes | `60827F089B6DAE608E00526B1A5D6C4B057305685C2B9B8E6D40E95AB9B0E622` | 407 entries decompressed; required executable and legal files present. |
| `Auras-Orbit-Source-1.0.0.10.zip` | 93,480,865 bytes | `9F7CDAFC932D7F4E02526A53486AC63AD6AA6C87BD9DF0F7BE19D6B3D6042B5D` | 2,978 entries; required Android sources present; local evidence, reports, and archived project excluded; relative Markdown links resolve. |
| `1.0.0.10.apk` and `Auras-Orbit.apk` | 71,073,487 bytes each | `37CCF2121DD393D1DDBAD03B43A2C98F41F195B1A11BFBF5F912A41ED2B9A203` | Both copies match the canonical `.10` APK; package, version, signer, and 16 KB alignment verified. No APK was built during this wrap. |

The merged legacy project data and the superseded Nov 17 report were moved to `D:\Codex\Auras Cloud\delete\orbit-1.0.0.10-wrap-2026-11-21`. Its manifest records 547 Git-untracked paths at the initial snapshot and 674 on-disk files moved, including ignored data. The deleted tracked source was preserved from `HEAD`; no archived data was permanently deleted.

No tests, builds, or device installs were run during this wrap; no GitHub access, push, tag, or release occurred. The local-only distribution verifier could not run because its generated `desktop-app/build/generated/installer/version.iss` and unpacked distribution directory were absent. The Nov 18 record retains the earlier full distribution-verification result; this wrap checked the preserved installer version metadata and decompressed every portable ZIP entry.

Two acceptance items remain open for a later decision: the phone’s 47-link observation still lacks an identified title/provider and raw-versus-accepted counts, and real-stream subtitle playback on the current source remains unconfirmed. TV testing remains deferred. Jellyfin and FCast remain in development and nonblocking.

## Completed work and evidence

### Desktop

The Nov 18 status record documents a successful local .10 desktop/shared JVM run (441 tests), the explicit upstream library JVM suite (546 cases), the native MPV test task, :desktop-app:createDistributable, versioned installer creation, and verify-distribution.ps1 -RequireInstaller. A 15-second launch smoke used a unique temporary data directory. The curated source ZIP also built from a clean extraction.

Those results do not validate every current uncommitted workflow change. .github/scripts/build-local-deliverables.ps1, .github/scripts/verify-android-apk.ps1, .github/workflows/ci.yml, and .github/workflows/release.yml were modified in the Orbit worktree. No new desktop or workflow validation was run for this report.

### Android release package and phone

The Release APK was assembled after the provider-link changes and installed with adb install -r on physical Huawei JLN-LX1 (U4YYD22B11502254). The installed APK hash matches the local APK byte for byte. Its signing certificate matches the established update-compatible certificate. Package metadata reports:

- Version 1.0.0.10, version code 100000010
- Original install time 2026-11-18 08:25:35
- Updated time 2026-11-20 19:37:23
- Main package com.auras.orbit retained; temporary test packages were removed

The app launched successfully. The earlier .06-to-.10 direct-install record on a disposable phone AVD preserved the legacy preference file and showed the expected first-run Orbit profile state. That is not acceptance of the hosted updater feed or semantic migration of legacy Orbit profiles.

### Provider-link changes

The provider result, Stremio adapter and Stremio response parser now use a shared 4,096-link ceiling instead of the previous 50-link cutoff. The Details screen and player chooser page through 40 rows at a time. Progressive merging validates the final source union before changing published source metadata, and player focus follows a stable source identity when quality ordering changes.

Eight focused provider-source instrumentation tests passed on the Huawei before the final Stremio parser-cap adjustment. The final parser change is included in the release APK and was reviewed in an independent source audit; a dedicated parser test was not run after that last change. The final release build succeeded after all source changes.

The CS3 playback-flow fixture delivered the fallback MP4 URL and received HTTP 200, but the Huawei decoder failed on that local fixture. This confirms link delivery and fallback selection for that run; it does not establish end-to-end playback acceptance.

Orbit rejects candidates that are not direct supported HTTP video formats or that fail URI and metadata validation. Therefore a provider can return more candidates than Orbit displays. The owner observed 47 links on the phone, but the title, provider, and exact count label were not captured in this review. Trace those counts before treating the observation as a cap regression.

### Subtitle work

The Nov 20 subtitle audit updated cue timing for negative offsets, unknown-format detection, and diagnostics when a selected track is turned off. Its record lists a focused JVM run of 22 cases across six subtitle test classes and 3 Android instrumentation cases on the physical Huawei; raw device test output was not retained. Later provider-link work modified PlaybackRequest.java, so the subtitle results are not proof against the final source snapshot. An independent source audit reported no remaining actionable subtitle findings.

Earlier playback evidence confirmed subtitles and CC On/Off on one StreamPlay The Odyssey link. That acceptance predates the latest subtitle repairs and does not establish real provider-stream behavior on the current APK. Current-source subtitle playback remains open.

### Version and integration scope

- Android version inputs remain aligned at .10; the separate Android reference app remains at .06 for compatibility checks.
- The accepted bounded CS3/provider crosswalk remains in effect. It does not mean every provider was individually exercised.
- The Nov 18 record documents that AniList/Trakt OAuth client IDs and translated interface resources are unavailable in the local build.

## Initial Orbit worktree snapshot (before local wrap)

The initial inspection snapshot was on branch `main` at commit `57afded`:

| Worktree | Branch | Tracked changes | Untracked paths | Staged |
|---|---|---:|---:|---:|
| D:\Codex\Auras Cloud\desktop\auras-desktop | main | 12 (11 modified, 1 deleted) | 1,202 after adding this report | 0 |

The untracked paths were primarily the canonical Android source tree (650 paths), merged legacy project records/artifacts (547 paths), and five other local files. These counts are a snapshot from Nov 21 and changed during the later local wrap. No files were staged, committed, reset, or merged during the preparation of this snapshot.

The Orbit worktree was not yet a clean or committed release baseline at that time. Review and consolidation of local Orbit source, project records, and workflow edits remained open.

## Pending and unfinished work

### Immediate verification

1. **Explain the 47-link observation.** Record the exact title, provider, and screen/count label. Compare the provider’s returned candidates with Orbit’s accepted direct-playable entries and check both 40-row pages where applicable.
2. **Complete real-source phone testing.** Test a title/provider that returns more than 50 valid direct links, confirm the total and both pages, and play representative links. Synthetic 64- and 80-source tests passed, but the failed local decoder fixture is not playback acceptance.
3. **Retest subtitle playback on the current APK.** Confirm provider subtitles, track selection, timing and CC controls on a real stream. The prior one-link pass predates the current subtitle repairs.

### Product and release gates

4. **Keep the release local.** .10 is not published. The hosted update-feed path remains unverified and must wait for the user’s release authorization.
5. **Local source baseline.** The Orbit changes have since been reviewed and committed locally. The candidate remains unpublished, with no push or release tag.
6. **Continue phone accessibility review.** TalkBack route traversal and TTS synthesis were partial smoke evidence. A complete focus/content review and verified human-heard wording are outstanding.
7. **TV testing remains deferred.** No new TV testing was done for this update. Earlier Settings checks are narrow and do not substitute for the user’s later TV session.
8. **Continue Jellyfin and FCast in development.** Live-server and receiver validation remain open and nonblocking.
9. **Configuration gaps remain:** translations and OAuth client IDs are not configured in this local build.
10. **Archive cleanup:** the merged legacy project data and superseded Nov 17 report were moved to the dedicated `.10` wrap archive under the workspace `delete` folder; no data was permanently deleted.

## Source records

- Previous full project status (2026-11-18): PROJECT_STATUS_REPORT_2026-11-18.md
- Android subtitle audit and phone evidence: artifacts/release-evidence/SUBTITLE_COMPATIBILITY_AUDIT_2026-11-20.md
- Local deliverables and desktop verification (2026-11-18): artifacts/release-evidence/LOCAL_DELIVERABLES_2026-11-18.md
- Android application and release notes: android/README.md
- Current public GitHub Releases page: https://github.com/SMRiaz-SA/Auras-Orbit/releases

## Provider link preservation follow-up — 2026-11-22

This source-only update supersedes the earlier statements above that described a shared 4,096-link ceiling as current or the cross-provider correction as still in progress.

- **Comparison:** CloudStream's `ResultViewModel2.loadLinks` collects complete extractor-link objects, deduplicates by URL, sorts the snapshot, and reports its count without an item-count ceiling. For playback, it maps the provider-declared media type to MIME and forwards request metadata. This is simpler and more reliable than guessing media type from a URL suffix or rejecting links because they carry legitimate query parameters or headers.
- **Orbit source correction:** Removed arbitrary count ceilings from the CS3 callback/result path, shared provider snapshots, playback request conversion and progressive merge, Stremio parsing/conversion, and shell-side source collection. Orbit now uses declared media type where available, preserves valid HTTP(S) query/header metadata, and surfaces unsupported or malformed entries in the chooser instead of silently hiding them. A malformed Stremio entry no longer discards valid siblings, and subtitle callback overflow no longer discards video links.
- **Resource guard:** CS3 retains a 32 MiB metadata safety budget with a visible partial-result warning. A failed reservation rejects that candidate only; later candidates are still checked against remaining budget. The regression covers a failed reservation followed by a later candidate that fits.
- **Verification:** Focused JVM tests passed for provider results beyond 4,096, declared MIME mapping, Stremio per-entry isolation, and metadata-budget order independence. The complete `:app:testDebugUnitTest` suite then passed against the current worktree on 2026-11-22 (`BUILD SUCCESSFUL`, 27 actionable tasks, 1 executed, 26 up-to-date). `git diff --check` passed. An independent follow-up source spot-check confirmed warning propagation and budget retry behavior with no remaining actionable behavior defect.
- **Device and remaining work:** Read-only ADB confirms the physical Huawei (`U4YYD22B11502254`) currently has Orbit 1.0.0.10 / code 100000010 installed. This is the historical package and does not contain current provider-source changes. No APK was built, installed, or changed in this execution. Full Android `PlaybackRequest.Source` construction and request URL/header handoff instrumentation remain unrun because they depend on Android runtime behavior. Dynamic same-title provider count parity, current-source subtitle checks, remaining timeline lifecycle checks, and the full phone TalkBack review remain open in the acceptance plan. The changes are local and uncommitted; no GitHub push, tag, or release was made.

## Provider-link policy correction — 2026-11-22

This follow-up supersedes the 4,096-link admission policy and strict source-conversion behavior described above. The user directed that Orbit apply the same behavior to every provider: do not impose an arbitrary provider-result count ceiling and do not silently drop returned links based on guessed URL suffixes or the presence of legitimate query parameters/headers. The 40-row chooser page remains presentation pagination and must continue across every result.

The correction covers CS3, Stremio, and shared provider-to-player boundaries: callback capture, result snapshots, progressive merge, stream parsing/conversion, chooser rows, and playback handoff. Explicit URI/protocol, transport, and resource safety checks remain; if a source is retained but the selected playback route cannot handle it, Orbit should show that outcome instead of hiding the provider result. The Nov. 18 report's “bounded CS3 gate” concerns extension compatibility/activation evidence, not a 4,096 video-link quota; the broad per-plugin testing note does not preclude a shared contract check across provider paths.

The 47-link item remains unidentified, and the Odyssey counts (43 and a separate 17 snapshot) remain pre-fix observations with uncertain request identity. They are not raw provider callback counts. The active checklist is [ORBIT_1.0.0.10_ACCEPTANCE_PLAN_2026-11-22.md](<../../plans/orbit/ORBIT_1.0.0.10_ACCEPTANCE_PLAN_2026-11-22.md>); it now requires universal count parity and focused regression coverage. Orbit source work started from clean local commit `9010dc8`; the cross-provider fix, focused JVM checks, and independent follow-up source review are complete. At the user's direction, APK packaging/install is deferred until more work is complete; no APK was built, no phone install was changed, and no GitHub push, tag, or release occurred as part of this correction.

## Episode long-press sheet local build — 2026-11-22

The user approved the episode long-press mockup and then requested “Build now,” authorizing a local `.10` Release APK for this feature. `OrbitShellActivity` now retains the existing episode title and action order, inserts the episode card artwork and provider synopsis, and places teal dividers below the title and synopsis. An independent source review found no issue in this scope; no phone visual/runtime review was performed.

The first incremental APK failed ZIP/signature validation on `classes7.dex`. A forced rebuild (`:app:assembleRelease` with `--rerun-tasks --no-build-cache`) completed successfully; the resulting signed APK passed `apksigner` v1/v2 verification, `zipalign`, and decompressed DEX length verification. Package metadata is `com.auras.orbit`, version `1.0.0.10`, code `100000010`.

| Local artifact | Size | SHA-256 | Status |
|---|---:|---|---|
| `android/app/build/outputs/apk/release/app-release.apk` | 71,089,876 bytes | `34E3A2933B8CFAB8FDA6DF3F35765F7B017A7E7868DF5C8FFD343290B4E70148` | Signed and locally verified; not installed or published. |

This scoped build does not close the remaining phone acceptance work. No phone install, GitHub push, tag, release, or hosted-updater test occurred.
