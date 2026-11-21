# Subtitle Compatibility Implementation Plan

> **For agentic workers:** Follow the user's execution request and the configured Codex agent roles. The orchestrator assigns owned scopes and review gates; per-task fresh-agent reviews are opt-in, not the default. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve safe provider subtitle candidates with unknown formats, then identify supported text formats from bounded content when the user selects a track.

**Architecture:** Keep Orbit's URI, header, and transport admission checks, but stop using MIME inference as a reason to discard an otherwise safe provider track. Store an internal unknown-format marker so Media3 can route the selected track through Orbit's parser factory; sniff a bounded prefix for VTT, SubRip, TTML/XML, and SSA/ASS, use a declared supported format only as fallback, and report unrecognized content without treating it as SubRip. Centralize provider-candidate normalization so both CS3 playback entry points enforce the same policy.

**Tech Stack:** Android Java/Kotlin, Media3 `SubtitleParser.Factory`, the existing CloudStream compatibility layer.

**Spec:** Approved behavior below in this plan; source comparison is against the bundled CloudStream implementation in `android-reference/`.

## Global Constraints

- Preserve the existing 4,096-character provider subtitle URI limit, header budgets, HTTP(S)-only transport, and rejection of cleartext HTTP subtitle URLs that carry headers or query parameters.
- Retain the 1,024-item callback materialization ceiling and the 30 accepted provider subtitle limit per playback source.
- Continue to support VTT, SubRip, TTML/XML, and SSA/ASS, plus content-detected versions of those formats.
- Do not default an unknown body to SubRip; leave video playback active and tell the user when a selected subtitle body cannot be identified.
- TV playback checks remain deferred at the user's direction; do not publish or push a release.
- Do not add or run automated tests in this task. The user requested implementation and a build; use a local Android debug build as compile evidence.

## Approved Behavior

- URI suffix and provider MIME are hints. Recognized values can propose a parser; an unfamiliar suffix or absent MIME does not by itself remove a safe HTTP(S) candidate.
- Unknown-format retention applies to provider-reported callback tracks. Keep the current local-file and online-search resource admission behavior unchanged in this change.
- Inspect subtitle bytes only as they reach the parser for the selected track. Bound classification to the first 2,048 bytes and recognize VTT, SubRip cue structure, TTML/XML, and SSA/ASS signatures.
- Detected body format takes precedence over suffix or declared MIME. If content is not recognized, fall back only to a supported declared MIME. If neither identifies a supported format, emit no cues and report that the selected subtitle format could not be identified.
- Admission diagnostics count rejected URLs, invalid headers/transport, and items beyond the track cap; unknown-format candidates remain in the chooser and are not counted as rejected.
- The chooser labels unknown candidates as pending content detection and does not call inferred MIME "provider-reported."

## Review Focus

- Confirm no transport or header safety gate is weakened while allowing unknown-format candidates.
- Confirm a content mismatch selects the actual parser without losing subtitle timing offsets.
- Confirm an unrecognized body does not crash playback or silently parse as SubRip, and that the diagnostic is associated with the selected track without exposing URLs or credentials.
- Confirm both the regular CS3 playback path and the OrbitShell native-provider path use the same admission and skipped-count logic.
- Build evidence is `:app:assembleDebug --no-daemon` success from the `android/` directory and the resulting APK path. Runtime subtitle playback remains unverified; no test suite is authorized in this task.

## Tasks

### Task 1: Centralize provider subtitle admission

**Files:**
- Modify: `android/app/src/main/java/com/auras/orbit/player/PlaybackRequest.java`
- Modify: `android/app/src/main/java/com/auras/orbit/player/SubtitleCandidatePolicy.java`
- Modify: `android/app/src/main/java/com/auras/orbit/ui/OrbitShellActivity.java`
- Modify: `android/app/src/main/java/com/auras/orbit/providers/ProviderLinkResult.java`
- Modify: `android/app/src/main/java/com/auras/orbit/providers/Cs3MediaProviderAdapter.kt`

**Interfaces:**
- `PlaybackRequest.normalizeProviderSubtitles(List<ProviderSubtitleCandidate>)` returns immutable accepted subtitle entries, using the unknown-format marker where format hints are absent.
- `PlaybackRequest.countRejectedProviderSubtitles(List<ProviderSubtitleCandidate>)` reports unsafe/invalid and over-cap candidates only.
- `ProviderLinkResult.rejectedSubtitleCount` carries callbacks rejected before a `ProviderSubtitleCandidate` can be constructed; both player paths add it to normalized rejection diagnostics.
- The OrbitShell path delegates to those shared methods rather than maintaining a second MIME gate.

- [x] Keep current URL/header/transport validation and both admission ceilings.
- [x] Count callbacks rejected before candidate construction and carry that count through progress/final results.
- [x] Allow unknown MIME as an admitted format state and serialize it through `PlaybackRequest.Subtitle`.
- [x] Route both provider playback entry points through the shared normalization and diagnostic methods.

### Task 2: Detect supported subtitle bodies at parse time

**Files:**
- Modify: `android/app/src/main/java/com/auras/orbit/player/SubtitleFormat.java`
- Modify: `android/app/src/main/java/com/auras/orbit/player/SubtitleContentSniffer.java`
- Modify: `android/app/src/main/java/com/auras/orbit/player/SubtitleOffsetParserFactory.kt`

**Interfaces:**
- `SubtitleContentSniffer.sniffMimeType(byte[], int, int)` returns a supported MIME string for a recognized bounded prefix, or `null` when it cannot classify the body.
- `SubtitleFormat.UNKNOWN_MIME_TYPE` is an internal Media3 routing marker, never treated as a provider-declared format.
- `SubtitleOffsetParserFactory` accepts the marker, dispatches recognized body content to a Media3 parser, preserves the existing cue-offset behavior, and reports unrecognized content through a callback.

- [x] Let a recognized explicit MIME remain usable when a URI has an unfamiliar suffix; return the unknown marker only when neither URI nor MIME identifies a supported type.
- [x] Extend bounded sniffing to VTT, SubRip, TTML/XML, and SSA/ASS while retaining strict cue-structure checks for SubRip.
- [x] Prefer detected content over declared MIME; use a supported declared MIME only if content detection is inconclusive.
- [x] For an unknown marker with inconclusive content, emit no subtitle cues and report the unsupported selection; do not fall back to SubRip.

### Task 3: Make the chooser and diagnostics accurate

**Files:**
- Modify: `android/app/src/main/java/com/auras/orbit/player/OrbitPlayerActivity.java`
- Modify: `android/app/src/main/java/com/auras/orbit/player/PlaybackRequest.java`

- [x] Show pending-detection copy for candidates with unknown format and label known formats without claiming their MIME was explicitly provider-reported.
- [x] Explain skipped count using only URL, header, transport, and track-limit reasons.
- [x] Show the unrecognized-format message only for the selected subtitle; keep video playback active.

### Task 4: Build the local Android debug artifact

**Files:** No source changes beyond Tasks 1–3.

- [x] Run `android/gradlew.bat :app:assembleDebug --no-daemon` from the `android/` directory.
- [x] Record the exit status and generated APK path in the handoff. Do not run test tasks, install the APK, perform device/TV acceptance, or publish it.

## Completion Record (2026-11-20)

- Independent source audit: **complete, no material findings**.
- Debug build: `:app:assembleDebug --no-daemon`, exit code 0 (`BUILD SUCCESSFUL`).
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`, 79,382,885 bytes; SHA-256 `804A511049174EF54BC3C8758F201608F8253AA65AC8C3A6E649176667A39EF3`.
- At this initial debug-build handoff, automated tests, phone/TV playback, and installation had not been run. Later authorized phone testing and installation are recorded below.
- No GitHub push, release, or publish was performed.

## Subtitle Audit and Repair Follow-up (2026-11-20)

The user requested a fresh subtitle audit and fixes. The implementation stayed within the Orbit checkout; no independent Aurasplay files or build outputs were inspected or changed.

- Corrected negative subtitle offsets so cue start and end are both shifted/clamped at timeline zero, shortening cues that previously remained visible too long. Explicit Media3 time sentinels remain unchanged; finite negative cue starts still clamp normally.
- Suppressed delayed unsupported-format notices when a previously selected track is now Off, while retaining the selected ID so the user can toggle that track back on. Matching pending selections still receive the notice.
- Updated diagnostic coverage for the provider-only unknown-format admission rule. JVM tests assert rejection counts; the AndroidJUnit4 instrumentation test covers normalization/retention because the production request path uses Android `Uri.parse`.
- Independent re-audit: **complete, no remaining actionable findings**. Local Media3 1.11.1 bytecode confirms unknown provider MIME routes through Orbit's custom parser as a selectable cues track; this is static evidence only.
- Focused JVM verification: `:app:testDebugUnitTest` with six subtitle test classes, **22 test cases passed**.
- Orbit Android debug build: `:app:assembleDebug --no-daemon`, exit code 0; 39 tasks, 3 executed and 36 up-to-date.
- Fresh APK: `android/app/build/outputs/apk/debug/app-debug.apk`, 79,208,184 bytes; SHA-256 `536402D5E26003A6C4842F695A2985F338B4B0D24A98A30606B18EC702702674`.
- At the audit handoff, instrumentation and phone/TV playback remained unrun. Later authorized phone instrumentation is recorded below; real subtitle-stream playback remains open, and TV testing is still deferred. No GitHub push or release was performed.

## Physical Phone Update and Subtitle Instrumentation (2026-11-20)

- Targeted only the connected physical Huawei nova 9 SE (`U4YYD22B11502254`, model `JLN_LX1`); neither emulator was used.
- Built the current local Orbit release APK and installed it over `com.auras.orbit` with `adb install -r`. Package metadata remains version `1.0.0.10` / versionCode `100000010`; the APK was replaced from current source while retaining app data (`firstInstallTime` stayed `2026-11-18 08:25:35`, `lastUpdateTime` changed to `2026-11-20 17:30:14`).
- Launched `com.auras.orbit/.ui.OrbitShellActivity` on the handset successfully.
- Re-signed the temporary AndroidJUnitRunner APK with Orbit's local test signing key, then ran `SubtitleOffsetParserFactoryInstrumentationTest` and `PlaybackRequestSubtitleDiagnosticsInstrumentationTest` on the handset: **3 tests passed, 0 failures**.
- Removed the temporary `com.auras.orbit.test` package and local temporary signing/screenshot artifacts after testing.
- Real subtitle-provider playback was not exercised. TV testing remains deferred. No GitHub push or release was performed.
