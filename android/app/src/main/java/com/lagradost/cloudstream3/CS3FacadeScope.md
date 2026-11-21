# Orbit CS3 descriptor facade scope

This source tree contains Orbit-owned JVM signatures selected from the audited static
crosswalk contract in `android/tools/cs3-compat/OrbitCs3FacadeContract.tsv`. The matrix
contract contains 107 CloudStream types, 52 fields, and 254 methods across eleven
preserved plugin archives. The descriptors were compared against the official
`recloudstream/cloudstream` pre-release source commit
`5024ace2922a148d38df2a816b483b529789cf58` and stable `v4.8.0` source commit
`3796406e636356f556c16e04e1da4e37d6a19038`. The broader matrix and exact artifact
provenance remain in workspace-only audit records and are not part of the source archive.

## Historical source-contract status snapshot (2026-11-04)

The earlier documents claimed that 42 method rows from the 19-archive contract had been
selected and implemented, but the selection was not preserved. The contract has 254 method
rows and no field marking the selected rows; an independent review could not reconstruct
the exact 42. Do not use that count as a verified closure claim. The actionable source
slices reviewed against the pinned source are explicitly bounded to:

- `ParCollectionsKt.argamap` and `runAllAsync` result ordering and exception handling.
- The sampled AniList DTO getters/hash behavior and `SyncRepo.library()` delegation.
- `GogoHelper.extractVidstream` callback, bounded request behavior, and one-shot caller
  continuation completion on cancellation.
- The reviewed `Coroutines.ioSafe` and `DataStore` helper paths.
- `LoadResponse` companion helpers and reference-preserving recommendation setters for
  Movie, Anime, and TV-series responses.
- `APIHolder.capitalize(String)` and its non-null receiver/titlecase behavior, plus the
  no-token `getCaptchaToken` fallback for unsupported input or failed requests.

The independent official-source review found and cleared three discrepancies in Gogo
cancellation, recommendation-list aliasing, and `APIHolder.capitalize`. A further source
alignment now gives `MainAPI.search(query, page)` the pinned legacy-search delegation and
matches the pinned base defaults for `NotImplementedError` and nullable `getLoadUrl`. Orbit
now also includes the pinned `ProvidersInfoJson` serializable model, applies process-local
`MainAPI.Companion.overrideData` in `MainAPI.init()`, and invokes `APIHolder.initAll()` after
successful profile plugin activation. An independent source review against the pinned commit
confirmed the model and override semantics, and the direct enable/update recovery paths were
repaired and re-reviewed. The current ordinary Debug build compiled `ProvidersInfoJson`,
`OrbitMainApiDefaults`, and `MainAPI`; this compile checkpoint does not prove exact DEX coverage
for the full measured contract or plugin runtime behavior. A
source-candidate ledger covering all 413 contract rows and three supplemental ABI rows is
at `android/tools/cs3-compat/OrbitCs3FacadeSourceCoverage.tsv`; its derivation is
name-level and does not prove exact owner/signature mapping or method behavior. Independent
row-by-row review and disposition of unresolved candidates remain open. The ledger does
not reconstruct the undocumented 42-row selection or establish universal CloudStream
compatibility. Magnetz is an active app feature tracked in `APP_BUILD_PLAN.md`, outside this
CS3 descriptor contract; its source now matches the anonymous desktop/legacy Android request
flow. Live acceptance remains open because the upstream returned HTTP 401. Interim ordinary phone/TV APKs have been built and installed, but no CS3 plugin
load occurred. Do not treat those builds as final source closeout.

The measured contract is not universal CloudStream parity. The 19-archive row mapping and
per-reference source dispositions are complete for the selected measured corpus; this does not
establish universal compatibility. Remaining differences and unmeasured calls stay bounded in the
matrix; no accepted feature is deferred. The approved `.orbitlib`
flow remains manual file transfer of profile/list data, not online external-account sync. The
measured `getLoadUrl` nullable default and provider-by-sync-ID boundary remain explicit. Accepted
`SyncRepo`/`AuthRepo` and encrypted credential-store profile-switch, redirect, and logout repairs are
complete and independently reviewed in the active source checklist. AniList's network-backed login,
viewer, search, load, status, update, and library methods are source-complete and independently
reviewed against the pinned commit; behavior SHA-256
`9B0034B8E04EF697578B074D4B02ED292FA3E7B924752B2DF04E2C021ABFEAB2`, with final source SHA-256
`B8A382A5F5432B32385F7F42DB3D8B33959E89ACB9DE3417BAB7A802327D3517` differing only in the
transport explanation comment. Search uses the pinned 10-item ROMAJI/bannerImage mapping, and data
operation failures propagate through `SyncRepo`. Its 2 MiB response cap is a documented bounded
exception for up to 11,000 library entries; redirects and retries are intentionally disabled. Build
and runtime acceptance, plus unmeasured calls, remain open.
The base `MainAPI`
provider operations rely on provider overrides and the Orbit adapter exposes only operations
overridden by a plugin. `APIHolder.getCaptchaToken` now delegates to the bounded pinned
api.js/anchor/reload flow in `OrbitCaptchaToken.kt`; independent source review passed, while
build/runtime acceptance remains open. `DataStore` preferences remain app-wide; Gogo HLS manifest expansion
delegates to pinned `M3u8Helper` outside Gogo's own page/AJAX request budget; arbitrary plugin
dependency closure, plugin resources/settings, and runtime lifecycle behavior remain unverified.
The broader facade inventory alone does not provide full plugin lifecycle support.

The original facade was a descriptor scaffold, not a complete runtime host. Source-backed
helper slices such as bounded `Mp4Upload`, `FilemoonV2` with its pinned aliases, and the
Odnoklassniki family are separately recorded in `APP_BUILD_PLAN.md`; none implies general
CloudStream behavior beyond its documented scope.

The generated `SyncRepo.library-IoAF18A(Continuation): Object` member is declared as a
Kotlin `suspend` function returning `Result<SyncAPI.LibraryMetadata>` so the compiler
retains the observed mangled ABI. `Episode` also declares Hexated Anichi's historical
constructor with an `Integer` score parameter, which is absent from both pinned current
baselines. `AesHelper` has the matrix suspend `$default` descriptor and the additional
stable v4.8.0 non-suspend `$default` overload; the latter is listed separately in
`android/tools/cs3-compat/OrbitCs3FacadeSupplementalAbi.tsv` and was confirmed from the
pinned stable library JVM classfile.

Signature-only external classes are added as `compileOnly`: Jackson databind 2.13.1,
NiceHttp 0.4.18, kotlinx.coroutines 1.11.0, kotlinx.serialization JSON 1.11.0, and
Jsoup 1.22.1. These declarations make the source signatures compilable; they do not
establish runtime dependency closure. Kotlin compilation uses AGP 9.1.1's built-in Kotlin
support (Kotlin Gradle Plugin 2.2.10, classfile metadata `mv=[2,2,0]`); the standalone
`org.jetbrains.kotlin.android` plugin is intentionally not applied because AGP 9.1.1
rejects applying it when built-in Kotlin support is active.

`Cs3FacadeAbiInventory.java` checks every matrix and supplemental descriptor against
compiled JVM classfile bytes, including class/member visibility, static default-wrapper
shape, source package declarations, source paths, and classfile internal-name paths. It
does not load classes or run plugin code. A complete inventory means exact checked
descriptor presence only; it does not claim API behavior or runtime compatibility.
