# Tracker integrations

This document records the optional MAL, AniList, and Simkl integration behavior and its verification boundaries. The app's own local watch history and playback progress do not depend on tracker services.

## Implemented behavior

- The desktop **Tracker Library** is available from the local Library screen after an account is connected. It loads the selected provider's remote lists with loading, empty, retry, search, status-filter, and sorting states. A title edit is explicit and targets only the selected provider and ID; the displayed entry is refreshed only after the provider accepts the write. Removing a tracker entry does not remove local bookmarks or watch history.
- Title details show independently fetched status, score, and progress for each connected tracker when an exact external ID or one unambiguous title match is available. Ambiguous or missing matches are shown as non-actionable; the explicit edit action remains in Tracker Library.
- The capability matrix is explicit in the shared adapter: MAL and AniList expose anime list/status/score and count-only progress; Simkl exposes anime, TV, and movie list/status/score, plus exact episode selections for supported anime/TV entries. Unsupported exact-progress fields are not offered for count-only providers, and movie playback is not converted into a fake episode event.
- Tracker account cards show connected, expired/reauthorization-required, and disconnected states. Reconnect verifies the replacement account before replacing saved credentials, so cancellation or failed verification leaves the prior account intact. AniList expiry is presented as reauthorization rather than a silent background failure.
- Automatic playback sync is independently controlled per provider and per profile. Existing accounts default to the prior enabled behavior; disabling the switch prevents future automatic writes without disconnecting or changing remote history. Settings show the last token-free outcome and offer a manual retry for retryable/provider-rejected playback attempts.
- The shared sync model carries optional exact episode selections and media type. Simkl checks the user's current episode-level state through its targeted watched lookup, validates requested episodes against the Simkl catalogue, then writes only the set difference. TV season/episode coordinates, including aired TV specials, and sequential anime episode numbers are supported. Invalid or ambiguous selections fail before list/rating writes.
- Count-only callers remain compatible: Simkl maps a changed count to the first N aired regular episodes in its canonical catalogue order. MAL and AniList are count-only and reject exact selections rather than falsely reporting them as synchronized.
- Simkl list removal, status changes, and ratings use the matched media type so movies and shows are not sent through an anime-only ratings bucket. Adding a previously untracked item requires its media type to be supplied.
- Simkl disconnect requests OAuth token revocation before clearing the local account. The UI does not claim revocation was confirmed: RFC 7009 intentionally makes Simkl's response indistinguishable for active and unknown tokens.
- MAL, AniList, and Simkl account JSON is protected with the current Windows user's DPAPI before it is stored in the local key-value database. Legacy plaintext tracker entries are migrated only after encryption succeeds. There is no plaintext fallback.
- Provider client IDs are public configuration, not credentials. Access tokens, refresh tokens, PKCE verifiers, and any client secrets must never be committed.

## Optional provider setup for live verification

Connecting MAL, AniList, or Simkl is optional. Only if the app owner chooses to offer live tracker connections, they must register provider applications and configure their public client IDs and exact redirect URLs before consent/sign-in can be smoke-tested:

- Simkl: AUTH V2, client type “Mobile, desktop & browser apps”; register `http://127.0.0.1/oauth/callback` without a port. A free loopback port is selected per attempt, and that exact runtime URI is reused during token exchange.
- AniList: register `https://anilist.co/api/v2/oauth/pin` and use its documented PIN/implicit flow.
- MyAnimeList: register the application's redirect URL and test its PKCE authorization-code flow.

If IDs are configured in Accounts settings (or supported local system properties/environment variables), smoke-test connect, profile verification, refresh, library/status/rating reads and writes, and disconnect for each provider. Confirm remote Simkl revocation in the provider's Connected Apps page; the revoke response itself is not proof. These owner-dependent checks do not block release when tracker integrations are not offered.

## Owner-managed live verification record (2026-09-25)

Owner-managed live smoke-test evidence (2026-09-25): MAL library read, reversible status/rating/count write, and restoration passed with automatic playback sync disabled. AniList library read succeeded, but the attempted mutation and restoration were rejected without a displayed local update. Simkl library read succeeded, but its movie editor showed an ambiguous `Not on list` state with blank fields, so no write was attempted. The owner accepts the overall smoke-test phase for desktop 0.2 and treats the AniList/Simkl outcomes as external/provider limitations for this release; acceptance does not claim those writes succeeded or establish provider root cause. Account identities, title names, and remote list values are omitted to keep owner data out of release-facing documentation. No token, secret, or authorization URL was recorded. Disconnect/revocation was not tested.

## Playback sync and semantic boundary

The player syncs at the app's watched threshold and reconciles the local watched set when playback opens. Simkl receives additive `/sync/history` events with exact episode coordinates and available source IDs/title/year; this avoids deleting remote episodes absent from local history. MAL and AniList accept only an episode count, so the app advances those counts only when remote progress plus local watched episodes form a complete prefix. It skips non-contiguous or ambiguous numbering instead of claiming the wrong episodes were watched. Anime season-local coordinates are flattened only when loaded season lists prove the sequential mapping; specials or incomplete catalogs that cannot be mapped safely are omitted. Per-watch replay sessions are not synthesized.

Playback sync outcomes are classified as synced, skipped with a reason, sign-in required, retryable, or provider rejected. The health record contains only the provider name, outcome, short reason, and timestamp; it never stores tokens, authorization URLs, or request payloads. Local history remains authoritative and remote library reads never import progress into local history.

## Verification boundaries

- Automated tests validate PKCE helpers, DPAPI protection on Windows, Simkl count mapping, exact non-contiguous episode diffs, catalogue validation, playback episode mapping, safe count-prefix advancement, exact Simkl playback payloads, and the tracker-library filtering/coordinate editor model.
- A successful Windows distributable build validates packaging and linking, not provider registration or remote OAuth consent.
- Live provider smoke tests require app-owner client registration/configuration. The owner accepts the recorded smoke-test phase for desktop 0.2 with the provider-specific exceptions above; do not represent rejected or unattempted writes as successful. No account identities, title names, client IDs, tokens, or secrets are included in this verification record.
