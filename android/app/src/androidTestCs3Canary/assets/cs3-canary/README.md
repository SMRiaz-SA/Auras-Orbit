# Pinned StreamPlay v694 canary fixture

These test-only assets are copied byte-for-byte from the `builds/` content at upstream commit [`4bf1a1d463dc266163faf5d0a504d174b1e81285`](https://github.com/phisher98/cloudstream-extensions-phisher/commit/4bf1a1d463dc266163faf5d0a504d174b1e81285). This source-set asset is packaged in the CS3-canary test APK only. The package is served from the instrumentation process over loopback and installed through Orbit's regular CS3 installer; it is not included in the production APK.

- `StreamPlay_v694_1e4c7749_pinned.cs3`: 2,106,784 bytes; SHA-256 `1e4c77492069b3b8da2c2825c4d40e813436c0c2feed73b193b264ba4e1736ea`.
- `StreamPlay_v694_catalog_4bf1a1_plugins.json`: SHA-256 `d93280b14cb167ce3aabed165f67131fd1945458839bbbea426fe528f68fe0588`.

The test-only StreamPlay `.cs3` binary is GPL-3.0-or-later per the upstream project's current README. The pinned `builds/` commit contains neither StreamPlay source code nor a license file. The license statement is from the repository's current README, not the pinned commit; see its [license section](https://github.com/phisher98/cloudstream-extensions-phisher#license) and the [GNU GPL v3](https://www.gnu.org/licenses/gpl-3.0.html).
