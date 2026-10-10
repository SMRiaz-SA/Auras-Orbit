# Torrentserver AAR provenance

`torrentserver-7861970-16kb.aar` is based on the official [recloudstream/torrentserver source at commit `7861970e038b35cd8c6918384e49caf26903e09e`](https://github.com/recloudstream/torrentserver/tree/7861970e038b35cd8c6918384e49caf26903e09e), which is the source commit for the app's former JitPack dependency `com.github.recloudstream:torrentserver:7861970`.

The AAR manifest, `classes.jar`, and 32-bit native libraries are byte-for-byte from the upstream JitPack artifact. The `arm64-v8a` and `x86_64` `libgojni.so` files were rebuilt from the same source commit with Go 1.23.5 and Android NDK 28.2.13676358. No torrentserver source code or Java API was changed. NDK r28 produces 16 KB-aligned 64-bit ELF load segments by default.

The packaged artifact SHA-256 is `4B930F67496C40794039F63C1EAAEEE9E5A9273EF8D85C66CE08B788CC78116E`. The stable APK verifier checks every 64-bit shared library's ELF load segments and the APK ZIP alignment, so a future dependency change cannot silently reintroduce this warning.

Torrentserver is distributed under GPL-3.0 as noted by the upstream project; the corresponding license text is in [`../../LICENSE`](../../LICENSE). The upstream repository and exact source commit remain the source of the native library.
