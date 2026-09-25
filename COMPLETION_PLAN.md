# Auras Orbit desktop 0.2.0.00 — execution and reconciliation record

Last reconciled against the workspace: 2026-09-25.

The user authorized execution with “bismillah build and reconcile.” This record distinguishes completed local build work from release acceptance. The 0.2.0.00 handoff is portable-only: no installer, version tag, GitHub Release, or user-specific artifact is part of this delivery.

## Verified evidence

- Branch and base revision: `main` started at `376b7f053bb8f4f08d9bb5ab573c4c0a6dcc6b9a`; the release commit is `d2cad58e`. The workspace was already dirty; existing changes were preserved.
- After the Home safe-area alignment fix, the explicit full `.\gradlew.bat test --rerun-tasks --no-daemon` rerun succeeded; the aggregated JUnit XML reports **195 tests, 0 failures, 0 errors, 0 skipped** across desktop-app (127), player-abstraction (14), plugin-runtime (46), and common (8). The release-mode distributable build also succeeded.
- `.\.github\scripts\verify-distribution.ps1` passed for the portable app. A prior installer verification also passed, but no installer is retained in the current deliverable directory.
- The current portable app is `desktop-app/build/compose/binaries/main/app/Auras-Orbit/`; the previously generated installer was preserved outside the repository output directory as a backup and is not part of this handoff.
- The portable `legal/` directory contains all eight required files: Auras Orbit license/notice, third-party notices, Cloudstream upstream license, WebView2 SDK license/notice, MPV GPL license, and MPV provenance.
- MPV is pinned to `mpv-dev-x86_64-20260610-git-304426c.7z`; archive SHA-256 `8cbb25ea784f01afbb3f904217cab1317430a8bcfd5680fd827a866367f71cc9`, extracted DLL SHA-256 `5c876d79e070529128331591b48f87846fb30557f19c11280df9c6ee9b6dbafa`, source commit `304426c39`.
- `installer/setup.iss` now points Publisher, Support, and Updates URLs to the Auras-Orbit GitHub repository. The release workflow now derives `installer/version.iss` from a validated numeric `v*` tag before building; the portable CI workflow was executed successfully on the configured `Auras-Orbit-Local-Win-x64` self-hosted runner, while the tag-driven release workflow was not triggered.
- `git diff --check` has passed on the source changes; Git reports only its existing LF-to-CRLF working-copy notices.

## Reconciled workspace changes

All current changed paths are retained and assigned to a scope below (30 tracked modifications and 15 untracked files at last inventory). Several files were already modified before this execution; overlapping work was extended without resetting or discarding it.

| Paths | Intent and disposition | Evidence / remaining check |
|---|---|---|
| `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `.github/scripts/fetch-mpv.ps1`, `.github/scripts/verify-distribution.ps1`, `desktop-app/build.gradle.kts`, `desktop-app/appResources/legal/*`, `THIRD-PARTY-NOTICES.md`, `gradle.properties`, `installer/setup.iss`, `installer/version.iss` | Keep: pinned MPV retrieval/provenance, legal-notice packaging, app/installer version checks, canonical URLs, tag-driven installer version. | Local portable + installer builds and version checks pass. The changed portable CI workflow also passed on the configured self-hosted Windows runner; the tag-driven release workflow was intentionally not triggered. |
| `HomeScreen.kt`, `CategoryGridCache.kt`, `CategoryGridScreen.kt`, `DesktopHomeViewModel.kt`, `HomeCategorySection.kt`, `home/contract/*`, `HomeCatalogFilters.kt`, `HomeCuratedRows.kt`, `HomeDiscovery.kt`, `HomeCatalogFiltersTest.kt`, `HomeDiscoveryTest.kt` | Keep: Home catalog filtering plus bookmark Library, provider-backed Recent/Trending rows, local-history/bookmark recommendations, bounded provider loading, merge/deduplication, retry and merged View All routes. Dashboard shelves and empty states now use the shell’s horizontal safe-area inset; the hero backdrop remains full-bleed. Recommendation ranking is a small local title-token overlap heuristic; it is not a service or learned model. | Full suite and portable/installer rebuild pass after the alignment fix. Interactive loading/error/empty/compact-layout and details/playback-route checks remain open; automated tests do not cover every provider-routing branch. |
| `ExploreScreen.kt`, `DesktopRepositoryManager.kt`, `ComposeNavigation.kt`, `ui/navigation/Config.kt`, `DefaultRootComponent.kt`, `RootComponent.kt`, `navigation/components/ScreenComponents.kt`, `providerbrowse/ProviderBrowseScreen.kt`, `providerbrowse/ProviderBrowseViewModel.kt`, `GenreBrowseScreen.kt` | Keep: provider browse/navigation and the clarified whole-block Genre Browser collapse; genre and topic rails remain part of the collapsible block. | Compilation and suite pass. The UI bridge exposed no desktop windows and its documented launch API was unavailable, so visual collapse/expand QA was not performed. |
| `AppConfig.kt`, `extensions/BrowseTab.kt`, `extensions/ExtensionsScreen.kt` | Keep: onboarding and extension browse/install-mode behavior already present in the dirty workspace. | Included in successful compile/test build; no live provider repository interaction was performed. |
| `EmbeddedVideoPlayer.kt`, `player/webview/WebView2RuntimeDetector.kt`, `WebView2RuntimeRequired.kt`, `WebView2RuntimeDetectorTest.kt`, `DesktopStrings.kt` | Keep: WebView2 prerequisite detection and user guidance, alongside player integration and localized strings. | Detector tests pass. Windows UI behavior with WebView2 present/missing and native playback smoke test remain open. |
| `syncproviders/providers/SimklApi.kt` | Keep: tracker API/version adjustment in the existing tracker scope. | Automated tracker tests pass; live sign-in/API smoke tests need owner-managed registration and remain optional. |
| `README.md`, `COMPLETION_PLAN.md`, `ECOSYSTEM_DIRECTION.md` | Keep: build instructions, this execution record, and the separately preserved future ecosystem direction. | The ecosystem document remains future scope. Sync, relay/remote play, pairing, and watch parties were not implemented. |

## Release gates still open

The local artifacts build and version checks pass. The owner accepted the Home and Genre Browser visuals and accepted the owner-managed tracker smoke-test phase for desktop 0.2 with the provider-specific exceptions documented below. Remaining follow-up work is:

1. **Installer acceptance:** intentionally deferred. The 0.2.0.00 handoff is portable-only and does not package or publish an installer. Installer sources remain in the repository for a separately authorized workflow.
2. **Release workflow:** intentionally deferred. The changed portable CI workflow passed on the configured self-hosted Windows runner; the tag-driven installer/release workflow was not triggered, and no GitHub Release was created.
3. **Final review:** inspect the complete final diff and confirm all third-party runtime notices required by the resolved dependency graph. The package currently includes the enumerated project, Cloudstream, WebView2 SDK, and MPV notices.

These follow-ups do not block the requested portable-only handoff. Live tracker credentials remain outside the release gate; do not add secrets to the repository.

## Explicitly not blocking desktop 0.2

- The owner accepts the desktop 0.2 owner-managed tracker smoke-test phase as passed with exceptions: MAL reversible read/write/restore passed; AniList rejected the attempted mutation and restoration; Simkl's movie editor showed an ambiguous state, so no write was attempted. The owner treats the AniList/Simkl outcomes as external/provider limitations for this release. This acceptance does not claim those writes succeeded or independently establish provider root cause. Never commit credentials.
- The product direction in `ECOSYSTEM_DIRECTION.md` is preserved for later. Source registry, cross-device sync, QR pairing, remote control/handoff, Relay/remote streaming, and watch parties are not to be implemented as part of this Home/release plan.
- The public website/PWA is out of scope.
- The engineering candidates below remain separate work unless a concrete release-blocking defect is found and evidenced: plugin isolation/JDK boundary; DEX and bytecode-transformation compatibility/performance; Android stub coverage; stream-proxy performance; reflective audio bridge; player temp-file lifecycle; BouncyCastle registration; JSON dependency compatibility. Any newly discovered release blocker must be documented with reproduction evidence and an explicit scope decision.

## Previously completed work (retain; do not reopen without evidence)

- Compact/responsive TV episode strip while retaining the richer grid presentation.
- Removal of the identified unreferenced resolver/tracker files and commented sandbox hook.
- Accurate documentation of visible, user-driven Cloudflare clearance; no automatic/headless challenge solver.
- Desktop MAL, AniList, and Simkl OAuth/account/library/status integration, profile-scoped credentials, exact Simkl episode-history support, and related regression tests, as recorded in `TRACKER_INTEGRATIONS.md`.

## Tracker completion reconciliation (2026-09-25)

The tracker completion pass extends the existing provider adapters into a user-facing desktop flow. `Library` now links to `Tracker Library`, which reads connected provider lists, exposes provider capability text, supports search/status filtering/sorting, and allows explicit status, score, count-progress, and supported Simkl exact-episode edits. The write path verifies the active account, disables duplicate saves, preserves the displayed value on rejection, and refreshes only after acceptance. Account settings now expose reconnect/reauthorization, profile-scoped automatic-sync switches, token-free playback health, and a manual retry for the last retryable/provider-rejected playback attempt.

Deterministic tracker-model tests were added. The final desktop test suite and distributable checks passed, including 127 desktop tests with 0 failures, errors, or skips. Owner-managed smoke-test evidence: MAL library read, reversible status/rating/count write, remote-result display, and restoration passed; AniList library read succeeded but its attempted mutation and restoration were rejected without a displayed local update; Simkl library read succeeded but its movie editor showed an ambiguous `Not on list`/blank-field state, so no write was attempted. The owner accepts the overall smoke-test phase for this release and classifies the AniList/Simkl outcomes as external/provider limitations; this is acceptance of the observed behavior, not a claim that those writes succeeded or that provider root cause was independently proven. No credential, token, client secret, tracker account identity, or title from the owner's list is recorded in this release note. No disconnect/revocation operation was performed. The owner also accepts the Home and Genre Browser visuals for this release; no additional visual gate remains unless a new defect is reported.

## Local three-artifact delivery (2026-09-25)

The requested local deliverables are one source ZIP, the portable application directory, and one ZIP of that portable directory. `.github/scripts/build-local-deliverables.ps1` programs the repeatable sequence: enumerate the current working-tree source plus the CloudStream submodule; exclude VCS state, build/cache output, and local secret/config files; run desktop compilation, tests, and the release-mode distributable build without `clean`; verify version/legal files; then create and inspect the two ZIPs. It does not build an installer or publish anything. `-ForceArchives` is required before replacing either existing archive.

- The sequence completed successfully for version `0.2.0.00`. The explicit test rerun reports 195 tests across desktop-app (127), player-abstraction (14), plugin-runtime (46), and common (8), with 0 failures, 0 errors, and 0 skipped.
- Distribution version/legal verification passed. The source package contains the current working tree, including uncommitted tracked changes, non-ignored untracked source, and the CloudStream submodule; it contains 2,042 files and omits ignored local build products.
- Portable directory: `desktop-app/build/compose/binaries/main/app/Auras-Orbit/`.
- Source ZIP: `desktop-app/build/outputs/Auras-Orbit-Source-0.2.0.00.zip`.
- Portable ZIP: `desktop-app/build/outputs/Auras-Orbit-Portable-0.2.0.00.zip`.
- These are local working-tree artifacts, not a clean tagged-source archive or published GitHub Release. The owner has accepted the broader Home/Genre visuals and tracker smoke-test phase; installer installation/uninstallation/upgrade is intentionally out of scope for this portable-only handoff, and the tag-driven release workflow remains a follow-up.
- Existing corrections to the former maintenance report: OAuth helpers use JVM APIs; MPV library-name probing and app-owned coroutine scope are present; no `Xetadata` rewrite or referenced `PluginSecurityManager.kt` was found; BouncyCastle insertion order was corrected in the note.
