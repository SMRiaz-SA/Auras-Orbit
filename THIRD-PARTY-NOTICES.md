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
| `libmpv-2.dll` | Auras Orbit Windows distributions pin `mpv-dev-x86_64-20261007-git-eb0ee10315.7z` from [shinchiro/mpv-winbuild-cmake](https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/20261007) (archive SHA-256 `3fd93055d437310ad094d3f80c9b04f9a56e8b138bcb7d9c5cf01870abbfe5a5`). The extracted DLL SHA-256 is `872827614ed0adfca11e68def5273bcfcaea6acf38bbf1950c35980b59f43a5f`; its product version is `v0.41.0-1104-geb0ee1031`. mpv is GPL-2.0-or-later for this build; the matching [upstream license](https://github.com/mpv-player/mpv/blob/eb0ee1031590b3f369a5b783e8aa91eeaaded7e6/LICENSE.GPL) and build/source provenance are packaged under `legal/`. `.github/scripts/fetch-mpv.ps1` verifies both hashes. |
| `player_bridge.dll` | Auras Orbit native bridge source is under this repository's GPLv3-compatible project terms; it links against Windows system libraries and uses the WebView2 SDK headers. |
| WebView2 SDK and `WebView2Loader.dll` | Microsoft `LICENSE.txt` and `NOTICE.txt` are retained under `desktop-app/src/main/cpp/webview2/` and copied into the packaged `legal/` directory. |
| Microsoft WebView2 Runtime | Normally supplied by the user's Windows installation or installed separately; it is not the same artifact as the loader DLL. Follow Microsoft's runtime distribution terms. |

## Direct JVM dependencies

The portable application contains third-party JARs from the Gradle dependency graph, including Kotlin, Compose Multiplatform, JNA, OkHttp, Jackson, Gson, Bouncy Castle, Conscrypt, Coil, Decompose, SQLDelight, Logback, SLF4J, ASM, Rhino, NewPipe Extractor, NiceHttp, and related transitive components. Their license and notice files are retained in their JAR metadata where supplied and in the generated JDK runtime `runtime/legal` directory where applicable.

`plugin-runtime` includes an adapted copy of Raku/nqp's `AutosplitMethodWriter` to split JVM methods at safe control-flow boundaries. It is licensed under Artistic License 2.0; the full license is packaged at `META-INF/third-party/LICENSE-NQP-Artistic-2.0.txt`. Auras changes the ASM API level, reserves helper names against plugin methods, and corrects `PUTFIELD` and `NEWARRAY` stack effects.

The build copies this inventory plus the project, upstream CloudStream, WebView2 SDK, and exact MPV license texts into the portable app's `legal/` directory; the installer consumes the same tree. The JDK runtime retains its generated `runtime/legal` notices, and third-party JAR metadata remains in the bundled application. Review this inventory against the resolved dependency graph again whenever dependencies or native vendor builds change. Do not treat this summary as permission to remove upstream notices.
