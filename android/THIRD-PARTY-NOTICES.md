# Android app third-party notices

This folder-specific third-party notice list supplements the current source-project notices in [repository third-party notices](../THIRD-PARTY-NOTICES.md).

## BoundedLevenshtein

The Orbit Android mirror matcher contains a bounded port of the `partialRatio` algorithm in
CloudStream's pinned `Levenshtein.kt`. That implementation credits FuzzyKot commit `f794d43`:

<https://github.com/terrakok/FuzzyKot/blob/f794d43/fuzzykot/src/commonMain/kotlin/com/github/terrakok/fuzzykot/Levenshtein.kt>

The pinned CloudStream source carries the following MIT notice, retained here in full:

```text
MIT License

Copyright (c) 2026 Konstantin Tskhovrebov

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Mp4Upload compatibility reference

The Orbit-owned bounded `Mp4Upload` built-in preserves the behavior of CloudStream's
[`Mp4Upload.kt` at commit `3796406e636356f556c16e04e1da4e37d6a19038`](https://github.com/recloudstream/cloudstream/blob/3796406e636356f556c16e04e1da4e37d6a19038/library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/Mp4Upload.kt).
The pinned CloudStream project is licensed under GPL-3.0; the complete license text is
already included in the repository's [`LICENSE`](../android-reference/LICENSE). The Orbit implementation
uses this source as a compatibility reference and contains Orbit-authored bounded fetch
and parsing logic.

## Odnoklassniki compatibility reference

The Orbit-owned `Odnoklassniki`, `OkRuHTTP`, `OkRuSSL`, `OkRuHTTPMobile`, and
`OkRuSSLMobile` API shapes follow the class and variant declarations in CloudStream's
pinned pre-release [`OdnoklassnikiExtractor.kt`](https://github.com/recloudstream/cloudstream/blob/5024ace2922a148d38df2a816b483b529789cf58/library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/OdnoklassnikiExtractor.kt)
and [`OkRuExtractor.kt`](https://github.com/recloudstream/cloudstream/blob/5024ace2922a148d38df2a816b483b529789cf58/library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/OkRuExtractor.kt)
at commit `5024ace2922a148d38df2a816b483b529789cf58`; media parsing and quality behavior
also use the stable v4.8.0 [`OdnoklassnikiExtractor.kt`](https://github.com/recloudstream/cloudstream/blob/3796406e636356f556c16e04e1da4e37d6a19038/library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/OdnoklassnikiExtractor.kt)
as a compatibility reference. CloudStream is GPL-3.0; the full license text is already
included in the repository's [`LICENSE`](../android-reference/LICENSE). Orbit's transport, bounded parser,
URL checks, callback bridge, and playback policy are Orbit-authored. No live provider
requests are part of this source review.

## Mirror-matcher compatibility reference

The Orbit extractor dispatcher is compared with CloudStream's pinned
[`ExtractorApi.kt`](https://github.com/recloudstream/cloudstream/blob/5024ace2922a148d38df2a816b483b529789cf58/library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt)
matcher. Its bounded raw-string score follows the FuzzyKot `partialRatio` implementation
listed above. Orbit validates HTTP(S) authorities and applies work caps, but its mirror
fallback has no host or mirror-port eligibility filter, matching the pinned cross-host
selection policy. The upstream fuzzy matcher can select unrelated lookalike hosts; this
inherited risk requires explicit acceptance before activation and is documented in
[an explicit activation gate].

## P.A.C.K.E.R. radix compatibility

Orbit's bounded Java `JsUnpacker` preserves the digit alphabets used by CloudStream's
[`JsUnpacker.kt` at commit `3796406e636356f556c16e04e1da4e37d6a19038`](https://github.com/recloudstream/cloudstream/blob/3796406e636356f556c16e04e1da4e37d6a19038/library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/JsUnpacker.kt),
including the printable ASCII radix-95 alphabet. The referenced CloudStream source is
GPL-3.0; the complete license text is already included in the repository's
[`LICENSE`](../android-reference/LICENSE). This implementation keeps Orbit's input, dictionary, output,
and count bounds. Its `Companion` ABI exposes immutable `c` and `z` values and a fixed
`load(String)` lookup for MobileAds followed by Facebook Ad. The lookup disables target
class initialization and does not use those lists or caller data to choose arbitrary code.

## TMDB provider compatibility

Orbit's `TmdbProvider` and `TmdbLink` preserve the sampled host ABI and provider behavior of
CloudStream's pinned stable [`TmdbProvider.kt`](https://github.com/recloudstream/cloudstream/blob/3796406e636356f556c16e04e1da4e37d6a19038/library/src/commonMain/kotlin/com/lagradost/cloudstream3/metaproviders/TmdbProvider.kt).
The pinned CloudStream project is GPL-3.0; the complete license text is included in the
repository's [`LICENSE`](../android-reference/LICENSE). Orbit's TMDB transport overrides the SDK client with a
fixed-host, public-DNS-pinned, redirect-disabled client with request, timeout, and response
bounds. It uses [`tmdb-java` 2.13.0](https://github.com/UweTrottmann/tmdb-java/tree/v2.13.0),
licensed Apache-2.0, plus Retrofit 2.11.0 and its Gson converter. The provider contains the
upstream public TMDB API key used by the pinned compatibility source.

## Torrentserver Orbit loopback engine

Orbit's Android torrent engine is built from the official
[`recloudstream/torrentserver` source at commit `7861970e038b35cd8c6918384e49caf26903e09e`](https://github.com/recloudstream/torrentserver/tree/7861970e038b35cd8c6918384e49caf26903e09e).
The preserved Orbit-patched source snapshot and exact patch are under
[`third-party/torrentserver`](third-party/torrentserver). The patch retains a
single IPv4 loopback listener through `http.Server.Serve`, authenticates every local HTTP request
with an in-memory token, removes wildcard CORS and request logging, and disables tracker-list
retrieval and torrent metadata persistence. The upstream wildcard-bound AAR is not a runtime
dependency.

Torrentserver is GPL-3.0. The complete license text is included in the repository's
[`LICENSE`](../android-reference/LICENSE). The packaged `torrentserver-orbit-loopback.aar` SHA-256 is
`9ED958D68E4F541060367D140DBCFEFE92DFBE9D2563FF23D552F32041C894CC`.


