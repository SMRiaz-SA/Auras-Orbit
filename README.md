# Auras Orbit

### Your extensions. Your library. One desktop app.

Browse with the CloudStream-compatible extensions you choose, watch with MPV, and keep your progress, history, and bookmarks in local Auras profiles. Auras Orbit brings it together in a Windows desktop app, with no Android emulator or separate watch-tracking account.

Orbit does not host media or curate provider catalogs. You choose which extensions to install, and what you can browse depends on those providers.

**Current source version:** `0.2.0.15`<br>
**Development status:** Pre-alpha<br>
**Platform:** Windows 10 or 11, 64-bit<br>
**Downloads:** Published builds are listed on the [GitHub Releases page](https://github.com/SMRiaz-SA/Auras-Orbit/releases)

## Navigate this guide

[Why Orbit](#why-orbit) · [Visual tour](#visual-tour) · [Quick start](#quick-start) · [Playback requirements](#playback-requirements) · [Privacy](#privacy) · [Build from source](#build-from-source)

## Why Orbit

- **Pick your extensions.** Browse, search, and play through providers you install. Adding a repository lets you review its catalog; it does not install every extension in it.
- **Keep your library in Auras.** Local profiles hold your watch history, playback progress, and bookmarks. There is no external watch-tracking service or watch-sync account.
- **Find your next watch.** Home and Explore bring together discovery shelves, recent activity, provider catalogs, genres, and topics when an extension supplies that data.
- **Made for the desktop.** MPV handles playback, with a WebView2-based player interface. No Android emulator needed.

Orbit is a client, not a streaming service: you decide which providers to use, and their catalogs and availability can change.

## Visual tour

Take a look around the running app. Home shows the first-run extension setup; the profile selector shows the active local profile and its management options.

![Auras Orbit Home with the extension onboarding card](assets/auras-orbit-home.jpg)

![Auras Orbit profile selector with the active profile and management options](assets/auras-orbit-profile-picker.jpg)

## Quick start

Download the portable ZIP or Windows installer from the [GitHub Releases page](https://github.com/SMRiaz-SA/Auras-Orbit/releases) when a build is published. The `main` branch contains source code, not a ready-to-run application package.

1. Extract the portable ZIP to a folder you control.
2. Keep `Auras-Orbit.exe`, the `app` folder, and the `runtime` folder together.
3. Start `Auras-Orbit.exe`.
4. Choose or create a local profile.
5. Open **Extensions**, add a repository you trust, and install only the providers you want to use.
6. Return to **Home** or **Explore** to browse. Use **Library** for bookmarks and local history.

The portable folder stores the application runtime and legal notices only. Your profiles, settings, extension files, credentials, watch history, and bookmarks are created in the app's data location at runtime; they are not part of the public source or portable deliverables. Standard Windows installs use `%APPDATA%\AurasOrbit`; portable copies store data in `AurasOrbitData` beside the app.

## Playback requirements

Orbit includes its MPV native library in the portable package. Windows must provide the Microsoft Edge **WebView2 Evergreen Runtime** for the embedded player interface. WebView2 is an app component, not the Edge browser, and it does not change your default browser.

If Orbit reports that WebView2 is missing, install the [Evergreen Bootstrapper](https://developer.microsoft.com/microsoft-edge/webview2/#download-section) while online, or the x64 Evergreen Standalone Installer on an offline PC. Return to Orbit and choose **Check again after installing**; restart Orbit if the player still cannot start.

## Watch history and lists

Auras Orbit manages watch history, playback progress, and bookmarks locally for each profile. External watch-tracking accounts and playback syncing are not supported.

## Privacy

Orbit keeps profiles, local history, bookmarks, settings, extension data, and subtitle-service credentials in local application data. Do not copy those folders into a bug report or source archive. The project's portable packaging checks intentionally exclude profile databases, account identities, watchlist titles, credentials, tokens, and other private app data.

## Build from source

Source builds require Git with submodules, JDK 21, PowerShell 7, and an internet connection to retrieve the hash-pinned native build tools and MPV development package. The MPV runtime binary is downloaded and verified for packaging; it is not stored in Git.

```powershell
git clone --recursive https://github.com/SMRiaz-SA/Auras-Orbit.git
cd Auras-Orbit
pwsh -File .\.github\scripts\build-local-deliverables.ps1
```

The script runs formatting checks, compilation, tests, native tests, and distribution verification on your computer. It creates one versioned portable ZIP and one source ZIP under `desktop-app/build/outputs/`. The portable application tree is written to `desktop-app/build/compose/binaries/main/app/Auras-Orbit/` and includes the WebView2 SDK and required license/provenance notices under `legal/`.

The Gradle wrapper pins its version and distribution checksum. Local Maven repositories are disabled unless explicitly enabled with `-PuseMavenLocal=true`. Set `APP_VERSION` in `gradle.properties` to change the app version; installer version metadata is generated under `desktop-app/build/generated/installer/`.

## License and support

Auras Orbit is distributed under the [GNU GPL v3](LICENSE). See [NOTICE.md](NOTICE.md) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for attribution and dependency notices. The project is independent and is not affiliated with the Android CloudStream project.

For bugs and feature requests, use the project's [GitHub issue tracker](https://github.com/SMRiaz-SA/Auras-Orbit/issues). Users are responsible for the extensions and media sources they choose to use.
