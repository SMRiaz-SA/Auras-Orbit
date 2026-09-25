# Tracker integrations

This document records the optional MAL, AniList, and Simkl integration behavior and its verification boundaries. The app's own local watch history and playback progress do not depend on tracker services.

## Implemented behavior

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

## Playback sync and semantic boundary

The player syncs at the app's watched threshold and reconciles the local watched set when playback opens. Simkl receives additive `/sync/history` events with exact episode coordinates and available source IDs/title/year; this avoids deleting remote episodes absent from local history. MAL and AniList accept only an episode count, so the app advances those counts only when remote progress plus local watched episodes form a complete prefix. It skips non-contiguous or ambiguous numbering instead of claiming the wrong episodes were watched. Anime season-local coordinates are flattened only when loaded season lists prove the sequential mapping; specials or incomplete catalogs that cannot be mapped safely are omitted. Per-watch replay sessions are not synthesized.

## Verification boundaries

- Automated tests validate PKCE helpers, DPAPI protection on Windows, Simkl count mapping, exact non-contiguous episode diffs, catalogue validation, playback episode mapping, safe count-prefix advancement, and exact Simkl playback payloads.
- A successful Windows distributable build validates packaging and linking, not provider registration or remote OAuth consent.
- Live provider smoke tests require app-owner client registration/configuration. No client IDs, tokens, or secrets are included in source.
