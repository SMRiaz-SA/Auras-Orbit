# Local `.06` to `.10` signed update check

**Date:** 2026-11-18  
**Scope:** disposable Android phone AVD only; no GitHub release, updater feed, physical phone, or TV activity.

## Artifacts

| Build | Package/version | SHA-256 | Signing certificate |
|---|---|---|---|
| Legacy reference APK | `com.auras.orbit` `1.0.0.06` / code `100000006` | `64F9CF2C0F3A91EB96FF8911DF0321316376B54EF16A90A9A05C4523D255CF34` | `92647A8D29D7EECA8D51BA91344FE1EB46AC46BCCB46D2A88BE3998A9B39B344` |
| Refreshed local candidate APK | `com.auras.orbit` `1.0.0.10` / code `100000010` | `E1B3A4DD3FB312C175CB10DBC3C7689FED8C5D2CA04535FC0C6E5F1EE6650802` | `92647A8D29D7EECA8D51BA91344FE1EB46AC46BCCB46D2A88BE3998A9B39B344` |

The legacy APK was built from `android-reference/`; `.10` was built from canonical `android/`. Both artifacts use the same application ID and certificate. The `.10` APK also passed the local package/version, signer, and 16 KB alignment verifier.

## Phone AVD result

On `emulator-5556` (Android 16, API 36), the `.06` reference APK was installed on a clean app data container. A synthetic local profile named `UpdateQA` was created and selected in the legacy Settings screen. Before replacement, the legacy `rebuild_preference.xml` had SHA-256 `f2871df62679cf5baadf57ac0255def4bc157c9ce0a19878fa9946409ba9e112`. The refreshed `.10` APK from `android/app/build/outputs/apk/release/app-release.apk` was then installed over `.06` with Android package replacement (`adb install -r`); no uninstall or clear-data step occurred between those installs. Android reported success and the installed package became version `1.0.0.10` / code `100000010`.

After replacement, the legacy preference file had the same SHA-256, `f2871df62679cf5baadf57ac0255def4bc157c9ce0a19878fa9946409ba9e112`, byte-for-byte. The `.10` app opened to its first-run **Who’s watching?** screen rather than importing `UpdateQA`. Rooted AVD inspection found `shared_prefs/rebuild_preference.xml` still present in the package-private data directory. The new Orbit `orbit_local_library.db` and `orbit_download_queue.db` were created separately by `.10`.

This matches the accepted no-auto-import migration policy: Android retains the old package data directory, while Orbit does not guess at legacy keys, activate old plugins, copy credentials, or automatically import legacy profiles. `.orbitlib` remains the explicit, user-selected profile/library import path. The test establishes package replacement, signer/version change, and retention of the legacy preference file; it does **not** claim that legacy profile values, provider state, settings, downloads, or user-selected SAF contents are imported or semantically usable by Orbit. No provider or user data was present in this synthetic fixture.

## Limits and disposition

- This was a local direct APK installation, not a run through the legacy updater feed or GitHub numeric asset path.
- The public `.06` release remains unchanged. The `.10` candidate is unpublished, so its feed cannot be tested without violating the user's no-release instruction.
- No TV testing was performed. The user will do TV testing later.
- The AVD is disposable. The physical Huawei phone and its app data were not touched.
