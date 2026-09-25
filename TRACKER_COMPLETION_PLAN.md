# Desktop tracker completion plan

**Status:** Local implementation completed. The owner accepts Home and Genre Browser visuals and accepts the owner-managed tracker smoke-test phase for desktop 0.2 with AniList/Simkl provider-specific exceptions recorded below.
**Purpose:** Close the desktop tracker gaps identified in the code audit, then reconcile implementation, verification, and project notes without silently expanding provider behavior.

## 1. Completion target

Deliver a tracker experience that is understandable to a normal user and honest about each provider's capabilities:

- Connect, reconnect, and disconnect MAL, AniList, and Simkl accounts from the desktop app.
- Browse a connected account's tracker library and inspect/edit supported per-title list status, score, and progress from the desktop UI.
- Offer background playback progress sync with clear per-provider control and visible health/error feedback.
- Never guess title identity or episode mappings, overwrite remote state implicitly, or claim success when a provider rejected a write.
- Keep the app's local watch history independent and authoritative for local playback. Remote library reads are informational; importing remote progress into local history is a separate, explicit user action if ever added.
- Verify behavior with deterministic tests first, followed by owner-managed live provider smoke tests.

This plan completes the tracker product surface and its verification. It does **not** authorize implementation, provider account registration, production OAuth use, or live writes by itself.

## 2a. Local implementation record (2026-09-25)

The local desktop implementation now includes the planned deterministic and user-facing paths:

- MAL, AniList, and Simkl capabilities are explicit in the shared model. Library entries carry media type so Simkl edits cannot silently fall back to an anime-only bucket.
- The Library screen links to a separate Tracker Library. Connected accounts can be selected, remote lists can be searched/filtered/sorted, and supported status, score, count-progress, and Simkl exact episode selections can be edited explicitly. Writes keep the previous displayed entry until the provider accepts them, then refresh the affected library.
- Reconnect/reauthorization keeps the existing account until the replacement token and returned account have been verified. Expired AniList sessions are visible as reauthorization-required.
- Automatic playback sync is profile-scoped per provider, preserves the existing enabled default for upgraded accounts, and records token-free synced/skipped/sign-in-required/retryable/provider-rejected outcomes. A manual retry is available for the last retryable/provider-rejected playback attempt.
- Local watch history remains authoritative. The Tracker Library never imports remote progress, movie playback is not represented as a fabricated episode, and count-only providers reject exact episode selections.

The implementation deliberately does not claim live consent or revocation confirmation for providers that have not been owner-tested. MAL connected-library and reversible edit acceptance is recorded below; AniList and Simkl connected-library, edit, sync-health, OAuth, and lifecycle checks remain coupled to the Phase 6 owner checks.

## 2b. Visual acceptance record (2026-09-25)

The portable `Auras-Orbit` build was opened on Windows from the local distributable. The tracker-specific disconnected/no-account states were visually exercised:

- profile chooser and Main profile entry;
- Home and the empty local Library state;
- Tracker Library with no connected tracker account, including its recovery path to tracker settings;
- Settings > Profiles & Accounts, including profile controls and the MAL, AniList, and Simkl configure/connect rows; and
- the scrolled tracker-settings area, including the optional Discord Rich Presence control.

The checked states rendered without visible clipping or overlap, and the copy clearly explained that a tracker account must be connected before opening a remote library. No provider account was connected and no remote read/write was performed, so connected-library, edit, sync-health, and OAuth screens remain part of owner-managed live verification.

The owner separately accepts the Home and Genre Browser visuals for this release. This records the owner's acceptance disposition; it does not claim an independent visual audit of every layout or display size.

## 2c. Owner-managed live verification record (2026-09-25)

The owner configured tracker clients and connected the desktop app using private owner accounts. Account identities, title names, and list values are intentionally omitted from this release-facing record. No credential, token, or client secret was recorded in the repository or diagnostics.

- **Build/OS:** local portable `Auras-Orbit` Windows build, Windows desktop.
- **MAL:** connected-library read passed. A reversible status/rating/count edit was accepted and displayed by the library, then the original values were restored and displayed.
- **Scope/cleanup:** automatic playback sync remained disabled during the controlled MAL write. No disconnect/revocation operation was performed.

The AniList library read completed. An owner-approved temporary mutation and restoration were both rejected; the app showed the rejection and did not display a local update. The owner accepts this outcome as an external/provider limitation for this release.

The Simkl library read completed. For a movie entry, the editor showed an ambiguous `Not on list` state with blank fields, so no write was attempted. The owner accepts this outcome as an external/provider limitation for this release. No title names or remote list values are retained here.

**Owner disposition (2026-09-25):** treat the overall owner-managed tracker smoke-test phase as passed for desktop 0.2 with the exceptions above accepted. This is not a claim that AniList/Simkl writes passed or that their root cause was independently confirmed.

## 2. Audited baseline

| Area | Current state | Completion implication |
|---|---|---|
| Account settings | Configure client ID, connect, show account, remove account; credentials are profile-scoped and Windows DPAPI-protected. | Improve setup and recovery; retain profile isolation and safe credential migration. |
| Provider APIs | Search/load/status/update/library operations are implemented in provider classes. | Wire those APIs into a desktop library and title-details experience; API implementation alone is not a complete desktop feature. |
| Desktop UI call sites | Account management and playback sync are present. No desktop library screen currently calls tracker `library()`, and no user-facing desktop flow was found for manual tracker search/status editing. | Add the missing UI and repository/view-model path. |
| Playback sync | Triggered from local watch-history/player paths. Simkl uses exact episode events; MAL/AniList use conservative anime count advancement. | Add per-account preference, observable outcome/retry, lifecycle handling, and explicit movie-scope decision. |
| Capability differences | Simkl handles anime/shows/movies for list operations and exact episodes for supported series. MAL/AniList are anime/count-based here. | Present capabilities in UI; do not expose unsupported exact-progress actions. |
| AniList token expiry | Access token is long-lived but has no refresh flow; expired sessions require reauthorization. | Add a clear reauthorization state/action that does not delete the old account until a replacement has been verified. |
| Verification | Unit tests cover OAuth helpers, Windows DPAPI, Simkl episode payload/mapping, and playback mapping. No provider HTTP-contract or live end-to-end suite was found. | Add provider contract tests and a documented owner-run live test checklist. |
| Owner/provider setup | Client IDs and redirect registration are owner/user configured; no live provider smoke test has been recorded. | Resolve distribution model and run real consent/read/write/revoke checks before claiming tracker feature acceptance. |

The existing behavior and security boundaries remain documented in [TRACKER_INTEGRATIONS.md](TRACKER_INTEGRATIONS.md). This plan is the work roadmap; that file remains the implementation/verification reference.

## 3. Decisions and guardrails

Resolve these before the dependent implementation starts. Until then, the plan uses the defaults below so work can be estimated without pretending the decisions are settled.

### D1 — OAuth client ownership

- **Recommended:** use app-owner-registered public client IDs so setup is close to one-click, after checking each provider's app-registration and distribution requirements.
- **Fallback:** retain bring-your-own-client-ID support, but provide a short provider-specific setup wizard, validate the ID before saving, and explain redirect configuration in plain language.
- Client IDs are public configuration. Never put access tokens, refresh tokens, PKCE verifiers, or client secrets in source control, packaged defaults, logs, screenshots, or diagnostics.

### D2 — Playback-sync consent

- Add a per-provider setting for automatic playback sync and a visible explanation of what is sent.
- For already-connected users, preserve current behavior on upgrade unless a deliberate migration decision says otherwise; expose the setting immediately. New connections must see the choice before automatic writes begin.
- Disconnecting removes local credentials; it must not be described as revoking a provider grant unless the provider confirms that result. Keep the existing accurate Simkl/MAL/AniList wording.

### D3 — Media and progress contract

- MAL and AniList: anime list/status/rating and watched-episode **count** only. Never offer exact non-contiguous episode selections.
- Simkl: anime, TV, and movie list/status/library operations; exact episode history for supported anime/TV coordinates.
- Decide whether Simkl movie playback should automatically record a watched movie event. If yes, verify the provider API semantics and add a distinct movie-event path; do not force movies through an episode event. If no, label this limitation in the UI and help text.
- Exact external-ID matches take priority. Ambiguous title/year matches require user selection; no auto-write from a non-unique match.

### D4 — Manual writes and import boundary

- User-initiated changes in the tracker UI write only the selected tracker and selected title.
- Do not build background two-way reconciliation or silently copy tracker library progress into local watch history in this scope.
- Before a write, show the target account/title and changed fields. On failure, preserve the previous displayed value and present a retryable error.

## 4. Work sequence

### Phase 0 — Freeze scope and current behavior

1. Confirm D1–D4 and record the chosen answers in the tracker notes.
2. Produce the provider capability matrix used by both UI and tests: supported media types, list states, scores, count/exact progress, token refresh, revocation, and library paging.
3. Trace watched threshold, player-open reconciliation, account switching, and existing-account upgrade behavior; record intended semantics and idempotency expectations.
4. Record baseline tests and current working-tree state. Do not clean, reset, or overwrite unrelated workspace changes.

**Exit criteria:** no unresolved product assumption is embedded in the code tasks; each deliberate provider limitation has a user-facing description.

### Phase 1 — Provider contract and capability correctness

1. Audit MAL, AniList, and Simkl request/response handling against the provider contracts used by the project; verify ID namespaces, status translations, score scales, pagination, null/unknown fields, and write acceptance parsing.
2. Make provider capabilities explicit in shared models and ensure the UI consumes them (including the existing watch-type capability metadata where applicable).
3. Implement deterministic, testable mappers for provider library entries and user edits. Validate media type before all write paths.
4. Decide/implement the D3 Simkl movie-playback path only after the API semantics are documented; otherwise codify the unsupported behavior.
5. Remove or explain the AniList `TODO REST` marker once its intended field is established; do not invent a missing field just to remove the marker.

**Exit criteria:** provider capabilities are represented accurately; unsupported operations fail visibly and safely; no type/status/score conversion is implicit or lossy without a documented rule.

### Phase 2 — Account setup, recovery, and security UX

1. Implement the chosen D1 setup path. Keep provider-specific redirect guidance and avoid requiring users to understand OAuth terminology.
2. Give each provider a single clear state: not configured, ready to connect, connecting, connected, expired/reauthorization required, error, or disconnected.
3. Add **Reconnect**. Complete browser consent, verify the returned account, and only then replace stored credentials; cancellation or failed verification must leave the existing account intact.
4. Handle AniList expiry as a recoverable account state instead of a background-only failure. Preserve current account/profile association during reauthorization.
5. Keep tracker tokens DPAPI-protected, profile-scoped, excluded from logs, and migrate legacy entries only after successful protection. Test switching profiles and protecting/unlocking under the current Windows user.
6. Make disconnect wording provider-accurate. Where remote revocation is not guaranteed, show the provider-side action needed to revoke access.

**Exit criteria:** fresh connect, failed/cancelled connect, reconnect, profile switch, local disconnect, and remote-revocation guidance all work without losing another profile's credentials or falsely reporting a revoked grant.

### Phase 3 — Desktop tracker library and title controls

1. Add the repository/view-model flow that fetches the connected provider library, handles loading/empty/error/retry states, refreshes stale data, and does not block the rest of the app on provider network failures.
2. Add a Tracker Library entry or a clearly labeled tracker section in the existing Library. Include provider/account selection, status filters, search, sorting supported by the data, and paging/large-library handling.
3. On title details, show each connected provider's independently fetched status, score, and progress. Provide only actions supported by that provider/media type.
4. Add explicit title matching when provider IDs are missing or candidates are ambiguous. Show candidate title/year/provider ID and require confirmation before linking or writing. Keep the mapping scoped to the active profile.
5. Add status, rating, progress, and remove-from-list actions. For Simkl exact episode editing, render the supported season/episode coordinates and preserve non-contiguous selections. For MAL/AniList, render count-only progress and clearly explain that exact selection is unavailable.
6. On every mutation, disable duplicate submissions, surface provider acceptance/rejection, update the UI only after success, and refresh the affected entry. Do not imply that removing an item deletes local watch history.

**Exit criteria:** users can read and explicitly edit the supported tracker fields without relying on background playback; mismatches and unsupported fields are understandable and cannot trigger an unsafe write.

### Phase 4 — Playback sync controls and observability

1. Add per-provider automatic-sync settings and consent/help text per D2. Apply profile isolation to these preferences.
2. Keep local watch history authoritative. Send only watched items past the app's threshold; make repeated player-open/history events idempotent at the provider boundary.
3. Preserve Simkl exact additive episode events and safe season/episode mapping. Preserve MAL/AniList anime count advancement only when the merged remote/local set forms a contiguous prefix; skip ambiguous IDs/catalogues and non-contiguous sets.
4. Implement or explicitly defer the Simkl movie event according to D3. Do not mark a movie watched by inventing an episode number.
5. Add per-provider last-attempt/last-success state and clear outcomes: synced, skipped with reason, sign-in required, rate-limited/retryable, or provider rejected. Never log tokens, auth URLs containing credentials, or sensitive payloads.
6. Add a manual retry action for retryable outcomes. Avoid unbounded retries and prevent retrying a write that the provider may already have accepted unless the operation is idempotent.
7. Ensure turning sync off stops future automatic writes without deleting the connected account or changing remote history.

**Exit criteria:** each account can independently opt in/out; a user can tell whether sync is healthy and why an item was skipped; repeated triggers cannot duplicate or regress remote progress.

### Phase 5 — Automated verification

Add tests before enabling the new UI paths:

- **Pure model/mapping tests:** provider status and score conversions; media capability matrix; missing, conflicting, and ambiguous external IDs; movie-vs-episode payload selection; special episodes; incomplete catalogs; season flattening; watched threshold; contiguous and non-contiguous counts; duplicate/replay triggers.
- **Provider contract tests with mocked HTTP:** OAuth exchange/refresh/error handling, user verification, library paging and malformed pages, search/detail mapping, status read/write/delete, score/progress updates, exact Simkl add-only payload, rejection/rate-limit/network errors, and revocation wording/result semantics.
- **Desktop UI/view-model tests:** account states and reauthorization; active-profile isolation; library loading/empty/error/retry; provider switching; unsupported action hiding; ambiguous match confirmation; mutation success/failure; per-provider sync controls and feedback.
- **Windows security tests:** DPAPI round trip, ciphertext not containing token text, migration success/failure behavior, profile isolation, and recoverable corrupt/unreadable credentials.
- **Regression checks:** local history continues working with no tracker; tracker failure does not interrupt playback; disabled sync performs no remote write; no test or diagnostic leaks credentials.

**Exit criteria:** deterministic suite passes on the supported Windows build; all new provider calls have contract tests; tests assert both accepted and rejected writes.

### Phase 6 — Owner-managed live verification

Run only after D1 registration/configuration is complete, using designated test accounts and explicit consent for writes:

1. Fresh connect for each provider; confirm account identity, redirect handling, cancellation, and a second profile with a different account.
2. Read/search/detail/library checks including paging and empty libraries.
3. Write one reversible status, rating, and supported progress change; read each back from the provider; restore the test account to its original state.
4. Playback smoke tests: one ordinary episode, one repeated trigger, one ambiguous/missing ID, one non-contiguous history set, and provider-appropriate special/movie cases.
5. Token lifecycle: MAL/Simkl refresh; AniList expiry/reauthorization UX (use a controlled test fixture or expired-token simulation where a real year-long wait is not practical).
6. Disconnect each provider; confirm local credentials are cleared and inspect provider-side connected-app state where available. Record Simkl revocation as requested but not confirmed if the provider response cannot prove revocation.
7. Test offline, rate-limited, provider-error, and revoked-consent recovery without losing local history or freezing the desktop UI.

**Exit criteria:** dated test record names app build, OS, provider/account test identity (no tokens), steps, observed result, cleanup/restoration result, and any known provider limitation. Live checks are required before advertising trackers as verified; they are not a gate for a build that deliberately ships trackers disabled/not offered.

### Phase 7 — Reconcile docs and release evidence

1. Update [TRACKER_INTEGRATIONS.md](TRACKER_INTEGRATIONS.md) with actual user-visible features, provider matrix, sync controls, security behavior, and precisely bounded live-verification claims.
2. Update [COMPLETION_PLAN.md](COMPLETION_PLAN.md) and the user-facing README/settings copy to distinguish implemented, tested, owner-configured, and intentionally unsupported behavior.
3. Remove stale “implemented” claims for UI flows not present; do not call the full tracker feature complete while any acceptance criteria above are open.
4. Review the final diff for credentials, callback tokens, unsafe logging, stale TODOs, and unrelated modifications. Preserve unrelated user working-tree changes.
5. Run the desktop compile/test/distributable checks used by the project and attach the test and live-smoke evidence to the completion record.

**Exit criteria:** code, UI, capability matrix, tests, owner setup, and notes agree; every open exception has an owner and an explicit defer/accept decision.

## 5. Global definition of done

Tracker completion may be reported only when:

1. Account setup and reauthorization are usable without sacrificing existing credentials; profile boundaries and DPAPI storage remain tested.
2. Tracker libraries can be browsed and supported per-title fields can be explicitly edited in the desktop app.
3. Playback sync is independently controllable per tracker, safe for that provider's semantics, observable, and retryable where safe.
4. Unsupported media, exact-progress limits, ambiguous matches, token expiry, rejected writes, and revocation limits are explained accurately.
5. Automated model/provider/UI/security tests pass; owner live tests are either recorded or explicitly waived because tracker connections are not being offered.
6. Documentation no longer describes API methods as completed user workflows, and the final diff contains no secrets or unrelated discarded work.

**Current disposition:** this is a plan only. No implementation or external provider operation has been performed as part of authoring it.
