# Auras Orbit Desktop App

This module contains Auras Orbit's Windows desktop client. It uses Compose for Desktop on Kotlin/JVM; it is not currently an iOS or Android application module.

## Overview

The desktop client runs in a JVM environment. To support extensions built for Android, it integrates with `:plugin-runtime` for Dalvik DEX-to-JVM translation and `:android-stubs` for Android API compatibility.

The plugin runtime handles single- and multidex archives and splits oversized methods into smaller JVM helper methods before writing class files. It rejects conversion when a class or method cannot be translated safely, so incomplete conversions are not cached as loadable plugins. The desktop app does not embed Android ART; plugins that use DEX features the converter cannot represent still require an Android CloudStream runtime.

Plugin settings use declared AndroidX preferences where supported. Custom Android settings screens require a desktop adapter. See the project-level README for the supported controls and user workflow.

## Architecture Guidelines

- **UI framework:** Write desktop UI in Compose for Desktop, using the existing reactive state and MVI patterns.
- **Dialogs:** Use `CloudstreamAlertDialog` or `CloudstreamCustomDialog` from `com.lagradost.cloudstream3.desktop.ui.components.CloudstreamDialogs` for consistent behavior and theme support.
- **Thread safety:** Run database writes and file I/O on background dispatchers (`Dispatchers.IO`).
- **Build and packaging:** Follow the root README for Windows development and release packaging instructions. The local delivery script builds and verifies the application, installer, and source archive.
