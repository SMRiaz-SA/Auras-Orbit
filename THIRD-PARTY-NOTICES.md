# Auras Orbit third-party notices

This file records the principal third-party components used by the Auras Orbit desktop client. Each component remains under its own license. The exact resolved dependency versions and the native binary provenance must be checked for every public binary release.

## Project and upstream components

| Component | License / notice location |
| --- | --- |
| Auras Orbit desktop-specific code | GNU GPL v3; see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md). |
| CloudStream upstream/reference code | Retained upstream notices and GPL text in `android-reference/LICENSE`. |

## Bundled/native components

| Component | License / notice location |
| --- | --- |
| `libmpv-2.dll` | The exact vendor build must be recorded for each release. mpv is GPLv2-or-later by default; its LGPL mode is a separate build configuration. Include the vendor's license and corresponding source/provenance with the binary. |
| `player_bridge.dll` | Auras Orbit native bridge source is under this repository's GPLv3-compatible project terms; it links against Windows system libraries and uses the WebView2 SDK headers. |
| WebView2 SDK and `WebView2Loader.dll` | Microsoft `LICENSE.txt` and `NOTICE.txt` are retained under `desktop-app/src/main/cpp/webview2/`. Reproduce the required notices with any binary distribution. |
| Microsoft WebView2 Runtime | Normally supplied by the user's Windows installation or installed separately; it is not the same artifact as the loader DLL. Follow Microsoft's runtime distribution terms. |

## Direct JVM dependencies

The portable application contains third-party JARs from the Gradle dependency graph, including Kotlin, Compose Multiplatform, JNA, OkHttp, Jackson, Gson, Bouncy Castle, Conscrypt, Coil, Decompose, SQLDelight, Logback, SLF4J, ASM, Rhino, NewPipe Extractor, NiceHttp, and related transitive components. Their license and notice files are retained in their JAR metadata where supplied and in the generated JDK runtime `runtime/legal` directory where applicable.

Before publishing a public portable release, regenerate or review this inventory against the exact resolved dependency graph and add any license text not already present in the portable bundle. Do not treat this summary as permission to remove upstream notices.
