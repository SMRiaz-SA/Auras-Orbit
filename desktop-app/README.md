# Auras Orbit Desktop App

This module contains the Auras Orbit desktop client, built using Compose for Desktop and Kotlin Multiplatform.

## Overview

Unlike the Android application, this module operates in a standard JVM desktop environment. To run plugins designed for Android, the client integrates with `:plugin-runtime` for Dalvik DEX-to-JVM transpilation and `:android-stubs` for Android platform compatibility.

The plugin runtime handles single- and multidex archives and splits oversized methods into smaller JVM helper methods before writing class files. It rejects the entire conversion if any class or method cannot be translated safely, so an incomplete plugin is never cached as loadable. The desktop app does not embed Android ART; plugins that use DEX features the converter cannot represent still need to run in an Android Cloudstream runtime.

## Architecture Guidelines

- **UI Framework:** All UI is written in Compose Multiplatform following an MVI architecture with reactive StateFlows.
- **Unified Dialog System:** All popups and dialogs MUST use `CloudstreamAlertDialog` or `CloudstreamCustomDialog` from `com.lagradost.cloudstream3.desktop.ui.components.CloudstreamDialogs` to maintain visual consistency and Amoled Pure Black theme support.
- **Thread Safety:** Database writes and file I/O must always run on background dispatchers (`Dispatchers.IO`).
- **Compilation:** Use `launch.bat` (or `launch.bat dev` / `launch.bat build`) in the root directory for development and packaging.
