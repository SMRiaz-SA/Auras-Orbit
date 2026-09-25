# Auras Orbit ecosystem direction

Status: locked product direction for future discussion

This document preserves the ecosystem ideas agreed during the Auras Orbit study of Nuvio. Nuvio is a reference point, not a product blueprint. Orbit should learn from existing systems, especially CloudStream, and make them simple for ordinary users.

## Product idea

> Find media, save it to your library, and play it on any connected device.

Users should think in terms of shows, movies, libraries, and devices—not manifests, repositories, API keys, or provider configuration.

Orbit should be a simple media layer with advanced capabilities underneath: simple by default, powerful when requested, and compatible with what already exists.

## Ecosystem layers

- **Orbit App:** The main desktop experience first, followed later by mobile, TV, and web clients.
- **Orbit Sources:** CloudStream compatibility first, followed by other useful adapters such as Stremio, local files, or personal media servers.
- **Orbit Library:** A unified model for bookmarks, history, playback progress, profiles, and metadata regardless of the source.
- **Orbit Connect:** Device pairing, synchronization, playback handoff, and remote control.
- **Orbit Relay:** An optional home server or relay for true remote streaming later.
- **Orbit Registry:** A curated source and extension directory with compatibility, health, and trust information.

CloudStream is an input to Orbit, not Orbit's identity. Orbit owns the library, user experience, device protocol, and source-management experience.

## Simple onboarding

The normal first-launch path should offer only three choices:

1. Use recommended sources.
2. Add a source by link.
3. Scan a QR code from another device.

Orbit should automatically test a source, identify what it provides, and explain the result in plain language. Provider-specific settings should remain hidden unless they are actually needed. Advanced controls can exist behind deliberate progressive disclosure.

## Remote-play sequence

Remote play should be built in stages:

1. **LAN remote control:** A phone controls Orbit on Windows—play, pause, seek, volume, subtitles, source switching, and queue control.
2. **Playback handoff:** Start on desktop and choose “Play on TV” or another paired device, continuing from the same position.
3. **Library and progress sync:** Pair devices with QR and synchronize profiles, bookmarks, history, and playback progress. An account must not be mandatory.
4. **Remote streaming:** An optional Orbit Relay streams from a home PC or server while the user is away.
5. **Watch parties:** Consider only later; this adds synchronization, permissions, chat, and relay complexity.

The first remote milestone should be remote control and handoff, not cloud video tunneling.

## What Orbit should own

- A clean, normalized media model.
- Source compatibility and health checks.
- Profiles, library, history, and playback progress.
- QR pairing and remote-control protocols.
- Extension trust, permissions, and compatibility information.
- Developer tools and documentation.

## What Orbit should reuse

- Existing CloudStream extensions.
- Existing metadata services.
- MPV and other playback engines.
- Standard protocols such as HTTP, WebSocket, WebRTC, and QR pairing.
- Personal media systems where they improve the user's experience.

Users should not need to understand how these pieces work.

## Near-term ecosystem milestone

The first meaningful ecosystem foundation is:

**Orbit Sources + Orbit Library + QR-based Orbit Connect.**

Everything else can grow from that foundation without complicating the desktop release.
