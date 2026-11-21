# Orbit Torrentserver source and build

## Provenance

The pristine upstream base is the official [`recloudstream/torrentserver` commit
`7861970e038b35cd8c6918384e49caf26903e09e`](https://github.com/recloudstream/torrentserver/tree/7861970e038b35cd8c6918384e49caf26903e09e),
which matches the read-only compatibility record at
[`android-reference/app/libs/torrentserver-7861970-16kb.md`](../../../android-reference/app/libs/torrentserver-7861970-16kb.md).

The checked-in `upstream/` directory is **not pristine upstream**. It is the Orbit-patched
working snapshot based on that exact commit. `orbit-loopback.patch` is the complete diff from a
clean checkout of the pinned commit to this snapshot. To reproduce it, check out the pinned
commit and apply the patch from the source repository root:

```sh
git clone https://github.com/recloudstream/torrentserver.git torrentserver
cd torrentserver
git checkout --detach 7861970e038b35cd8c6918384e49caf26903e09e
git apply /path/to/orbit-loopback.patch
```

The upstream prebuilt AAR is intentionally omitted and is not a runtime dependency. Orbit builds
the native library from the patched Go source.

## Orbit changes

- Bind `net.Listen` to `127.0.0.1` and retain that same listener through startup. Pass it directly
to `http.Server.Serve`; do not close and rebind the selected port. Startup returns success only
after an authenticated `/echo` readiness request reaches the serving HTTP server. Orbit's Java
wrapper performs its own authenticated `/echo` check as well.
- Require the in-memory `X-Orbit-Torrent-Token` on every local HTTP route. The random token is not
written to a URL, preference, or log. Wildcard CORS and request logging are removed.
- Keep only the Orbit add and stream routes. Disable tracker-list retrieval, tracker mutation,
metadata migration, viewed-state writes, and torrent metadata persistence. Magnets and query
strings are not logged or persisted by the engine.
- Keep native loading explicit through the pinned `upstream/fixed_classes`; the Orbit binding
stub exports only `startTorrentServer(String, long, String)` and has no native stop method.

## Rebuild and packaging

Pinned build inputs:

- Go 1.23.5, matching the upstream `go.mod` toolchain directive
- `golang.org/x/mobile` `v0.0.0-20250218173827-cd096645fcd3`
- Android NDK 28.2.13676358 and Android API 21
- `gomobile bind -target android -androidapi 21`, producing `arm`, `arm64`, `386`, and `amd64`

With the pinned Go, gomobile/gobind, and Android NDK configured in the environment, run from the
patched source root:

```sh
gomobile bind -target android -androidapi 21 -o /tmp/torrentserver-generated.aar ./bindings
```

For the checked-in Orbit AAR, keep the four generated `jni/<abi>/libgojni.so` files. Overlay the
pinned `go` and `torrServer` classes from `upstream/fixed_classes` into the generated
`classes.jar`, then compile and overlay the Orbit stub:

```sh
jar uf classes.jar -C upstream/fixed_classes go -C upstream/fixed_classes torrServer
javac --release 8 -d /tmp/orbit-stub-classes orbit/TorrServer.java
jar uf classes.jar -C /tmp/orbit-stub-classes torrServer/TorrServer.class
```

Place that `classes.jar` into the generated AAR and repackage it without changing the four native
libraries. This preserves explicit `go.Seq.load()` and prevents class loading from starting the
native runtime before an explicit playback request.

- Orbit patch SHA-256: `B3E93EA2050B1D9321F949E1E06B707A3C76C437E5092F2D1524B9DEE4A31721`
- Packaged AAR SHA-256: `9ED958D68E4F541060367D140DBCFEFE92DFBE9D2563FF23D552F32041C894CC`

Torrentserver is GPL-3.0. The full license text is in the repository root [`LICENSE`](../../../LICENSE).
