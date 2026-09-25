# Auras Orbit

Auras Orbit is a portable Windows desktop media client for CloudStream-compatible extensions. It gives you a native Kotlin/Compose desktop interface, local profiles and watch history, and MPV playback without an Android emulator.

Orbit does not host or distribute media streams or catalogs. You choose which extension repositories and sources to use, and availability depends on those providers.

**Portable desktop line:** `0.2.0.00`<br>
**Platform:** Windows 10 or 11, 64-bit<br>
**Release format:** extract-and-run portable folder; no installer is required

## Navigate this guide

[What you get](#what-you-get) · [Visual tour](#visual-tour) · [Quick start](#quick-start) · [Playback requirements](#playback-requirements) · [Tracker accounts](#tracker-accounts) · [Privacy](#privacy) · [Build from source](#build-from-source)

## What you get

- Browse, search, and play titles through compatible extensions you install.
- Home shelves for discovery, recent activity, bookmarks, and provider-backed catalogs when an extension supplies them.
- Genre Browser and topic filters for providers that expose the supported catalog data.
- Local profiles, bookmarks, watch history, and playback progress.
- Native MPV playback with a WebView2-based player interface.
- Optional MAL, AniList, and Simkl accounts, including a connected Tracker Library and explicit editing of fields supported by each provider.
- Per-tracker playback-sync controls. External tracker accounts are optional.

Extensions are user-selected. Adding a repository makes its catalog available for review; it does not install every extension in that repository.

## Visual tour

These screenshots were captured from the running desktop application using a clean local demo state. The Home image shows the first-run onboarding card before an extension repository has been configured; the profile image shows Orbit's local profile selector.

![Auras Orbit Home with the extension onboarding card](assets/auras-orbit-home.png)

![Auras Orbit profile selector](assets/auras-orbit-profile-picker.png)

## Quick start

1. Extract the portable ZIP to a folder you control.
2. Keep `Auras-Orbit.exe`, the `app` folder, and the `runtime` folder together.
3. Start `Auras-Orbit.exe`.
4. Choose or create a local profile.
5. Open **Extensions**, add a repository you trust, and install only the providers you want to use.
6. Return to **Home** or **Explore** to browse. Use **Library** for bookmarks and local history.

The portable folder stores the application runtime and legal notices only. Your profiles, settings, extension files, credentials, watch history, and bookmarks are created in the app's data location at runtime; they are not part of the public source or portable deliverables.

## Playback requirements

Orbit includes its MPV native library in the portable package. Windows must provide the Microsoft Edge **WebView2 Evergreen Runtime** for the embedded player interface. WebView2 is an app component, not the Edge browser, and it does not change your default browser.

If Orbit reports that WebView2 is missing, install the [Evergreen Bootstrapper](https://developer.microsoft.com/microsoft-edge/webview2/#download-section) while online, or the x64 Evergreen Standalone Installer on an offline PC. Return to Orbit and choose **Check again after installing**; restart Orbit if the player still cannot start.

## Tracker accounts

MAL, AniList, and Simkl are optional integrations. Connect an account from **Settings → Accounts**, then open **Library → Tracker Library** to search and filter the connected list. Orbit only submits fields that the selected provider exposes, keeps rejected values unchanged, and refreshes the displayed entry after an accepted save.

Provider APIs can reject or ambiguously report a change. A rejected or uncertain provider response is shown as such; it is not treated as a successful write.

See [tracker integration notes](TRACKER_INTEGRATIONS.md) for the supported fields and provider-specific behavior.

## Privacy

Orbit keeps profiles, local history, bookmarks, settings, extension data, and tracker credentials in local application data. Do not copy those folders into a bug report or source archive. The project's portable packaging checks intentionally exclude profile databases, account identities, watchlist titles, credentials, tokens, and other private app data.

## Build from source

Source builds require Git with submodules and JDK 21. The Windows distributable also needs the pinned MPV development package, which is downloaded and hash-verified by the repository script; the runtime binary is not stored in Git.

```powershell
git clone --recursive https://github.com/SMRiaz-SA/Auras-Orbit.git
cd Auras-Orbit
pwsh -File .\.github\scripts\fetch-mpv.ps1
.\gradlew.bat clean test :desktop-app:createDistributable --no-daemon
```

The portable directory is written to `desktop-app/build/compose/binaries/main/app/Auras-Orbit/`. It includes the project, CloudStream upstream, WebView2 SDK, and MPV license/provenance notices in `legal/`.

## License and support

Auras Orbit is distributed under the [GNU GPL v3](LICENSE). See [NOTICE.md](NOTICE.md) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for attribution and dependency notices. The project is independent and is not affiliated with the Android CloudStream project.

For bugs and feature requests, use the project's [GitHub issue tracker](https://github.com/SMRiaz-SA/Auras-Orbit/issues). Users are responsible for the extensions and media sources they choose to use.
