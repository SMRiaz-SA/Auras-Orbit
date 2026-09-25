# Auras Orbit

Auras Orbit is a Windows desktop media client that runs CloudStream-compatible extensions without an Android emulator. It combines a Kotlin/Compose desktop interface, a JVM extension runtime, and native MPV playback.

The application does not host or distribute media streams or catalogs. Content availability depends on extensions installed and chosen by the user.

## What it does

- Browse and play media through compatible extensions.
- Keep watch history and playback progress locally in the app.
- Use native MPV playback with a WebView2-based player interface.
- Optionally connect MAL, AniList, or Simkl to sync supported library and watch progress. External trackers are not required; see [tracker integration notes](TRACKER_INTEGRATIONS.md).

## Windows requirements

- Windows 10 or 11, 64-bit.
- Microsoft Edge WebView2 Runtime.
- For development: Git and JDK 21.
- For building the Windows distributable from source: `libmpv-2.dll` in `desktop-app/appResources/windows/mpv/`. It is not stored in Git; the release workflow downloads the pinned MPV development package and extracts it.

## Run the portable app

Extract the tester or release ZIP and launch `Auras-Orbit.exe`. Keep the `app` and `runtime` folders alongside the executable. The MPV native library is included in the packaged app; WebView2 Runtime is supplied by Windows or installed separately.

## Build from source

Clone the repository with its submodules:

```powershell
git clone --recursive https://github.com/SMRiaz-SA/Auras-Orbit.git
cd Auras-Orbit
```

Run the test suite and create the Windows distributable:

```powershell
.\gradlew.bat clean test :desktop-app:createDistributable --no-daemon
```

The distributable is written to `desktop-app/build/compose/binaries/main/app/Auras-Orbit/`.

## Project structure

| Module | Purpose |
| --- | --- |
| `desktop-app` | Compose desktop UI, playback integration, accounts, and Windows packaging. |
| `plugin-runtime` | Loads compatible extension bytecode and applies the runtime's plugin restrictions. |
| `android-stubs` | Android API compatibility classes used by extensions on the desktop JVM. |
| `player-abstraction` | Native MPV/JNA integration and local stream playback support. |
| `common` | Shared desktop persistence, platform paths, and logging. |
| `library` | Shared media/provider contracts. |

## GitHub workflow

`main` is the only working branch on GitHub. We do not create or push feature branches; changes are published to `main` when needed. The repository CI workflow runs on trusted pushes to `main` or by manual dispatch. It does not run fork pull requests on the self-hosted Windows runner.

## License and responsibility

Auras Orbit is distributed under the GNU General Public License v3; see [LICENSE](LICENSE), [NOTICE.md](NOTICE.md), and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). The project is independent and is not affiliated with the Android CloudStream project. Users are responsible for the extensions and media sources they choose to use.
