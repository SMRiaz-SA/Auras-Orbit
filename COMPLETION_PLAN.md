# Auras Orbit completion plan

Last audited: 2026-09-25. This checklist tracks release work separately from long-term architecture proposals.

## Release work

- [x] Preserve and audit the existing working-tree changes before editing.
- [x] Make the default TV episode strip compact and responsive; retain the larger rich-card layout for grid presentation.
- [x] Remove the unreferenced `CdpResolverImpl` and `DesktopTracker` source files and the commented-out sandbox hook that referenced the resolver.
- [x] Describe the shipped Cloudflare flow accurately: visible, user-driven system-browser clearance; no automatic/headless challenge solver.
- [x] Replace placeholder tracker sign-in with real client-ID setup and OAuth flows for MAL, AniList, and Simkl.
- [x] Implement tracker account verification, library loading, status reads/writes, and profile-scoped account storage.
- [x] Extend the shared tracker contract with optional exact episode selections; reconcile Simkl selections against current episode state and preserve count-only compatibility for MAL/AniList.
- [x] Add state/issuer-checked Simkl loopback OAuth, PKCE helpers, and redirect parsing tests.
- [x] Use Simkl activity timestamps and persisted per-account library snapshots so unchanged library views do not repeatedly download the full list.
- [x] Replace the obsolete in-source maintenance report with this repo-root plan.
- [x] Promote the accepted implementation to the local `master` branch, run a clean full test matrix (185 tests, 0 failures/errors/skips), and produce the Windows distributable (386 packaged files).

## Optional post-release integrations (not a release gate)

- Auras Orbit keeps its own local watch history and playback progress; connecting an external tracker is not required to use or release the app.
- MAL, AniList, and Simkl are optional library-sync integrations. No client IDs are configured in this workspace. If these integrations are offered, the app owner can register the clients and configure each provider's redirect URL later; do not commit IDs or access tokens.
- Live sign-in and read/write smoke tests are deferred until the app owner chooses to configure provider clients. MAL and AniList use a visible browser plus paste-back of the returned URL/token. Simkl uses a temporary loopback callback.
- Playback now forwards locally watched episode coordinates to Simkl as additive exact-history events. MAL and AniList expose only a watched count, so playback sync updates them only when merging local episodes with their existing count forms a sequential prefix; non-contiguous sets are skipped rather than misreported as the first N episodes. Live verification remains optional and requires owner-configured provider client IDs.
- Simkl disconnect sends a remote revocation request before removing the local credential. The provider's intentionally ambiguous revoke response cannot prove the grant was active or revoked; verify from the provider's Connected Apps page when that assurance is needed.

## Longer-term engineering backlog (not a release gate)

These are candidates for separately scoped work. Each needs compatibility evidence and tests before replacement; the former maintenance note treated several proposals as confirmed defects without sufficient evidence.

- Evaluate plugin isolation beyond the current JVM/plugin-runtime design before changing the supported JDK or security boundary.
- Keep DEX conversion and bytecode transformation compatible with real plugin fixtures; benchmark and validate alternatives before replacing the current toolchain.
- Expand Android compatibility stubs when plugin diagnostics show a real missing API. Java dynamic proxies cannot stand in for arbitrary missing Android classes.
- Measure stream-proxy latency and resource use under realistic HLS load before deciding whether to replace its server implementation.
- Replace reflective `AudioFile` construction with a supported factory/bridge when its suspend-call boundary can be handled cleanly.
- Give player-generated temporary playlists/configuration files an explicit lifecycle and crash-recovery policy; do not wipe shared temporary directories on startup.
- Review the global BouncyCastle provider registration with crypto compatibility tests before narrowing or removing it.
- Review JSON compatibility dependencies against the plugin ecosystem before any serializer migration.

## Audited claims corrected from the removed report

- OAuth utility stubs are gone; verifier generation and query/fragment parsing use JVM APIs and have regression tests.
- MAL is no longer a provider stub; AniList and Simkl also have working desktop provider implementations.
- The app already probes several MPV library names, and startup coroutines use an application-owned scope rather than `GlobalScope`.
- No `Xetadata` metadata rewrite was found in the audited source.
- The prior report referred to a `PluginSecurityManager.kt` path that does not exist in this checkout and misstated the BouncyCastle insertion order (the current call inserts it at position 2).
