package com.lagradost.common.platform

import java.io.File

/**
 * Cross-platform path resolution for Auras Orbit.
 *
 * Replaces all direct `System.getenv("APPDATA")` calls with proper
 * OS-aware paths that work on Windows, macOS, and Linux.
 *
 * Directory layout per OS:
 *   Windows: %APPDATA%/AurasOrbit/
 *   macOS:   ~/Library/Application Support/AurasOrbit/
 *   Linux:   ~/.local/share/AurasOrbit/
 * Existing installations continue using their legacy CloudStreamDesktop or AurasDesktop directory.
 */
object PlatformPaths {
    enum class OS { WINDOWS, MACOS, LINUX, UNKNOWN }

    val currentOS: OS by lazy {
        val osName = System.getProperty("os.name").lowercase()
        when {
            osName.contains("win") -> OS.WINDOWS
            osName.contains("mac") -> OS.MACOS
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> OS.LINUX
            else -> OS.UNKNOWN
        }
    }

    /** The base application data directory, OS-aware. */
    val appDataDir: File by lazy {
        val customDirProp = System.getProperty("auras.data.dir")
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("cloudstream.data.dir")
        if (!customDirProp.isNullOrBlank()) {
            return@lazy File(customDirProp).also { it.mkdirs() }
        }

        val userDir = System.getProperty("user.dir")
        if (File(userDir, "portable.txt").exists()) {
            val legacyPortableDir = File(userDir, "CloudStreamData")
            val portableDir = if (legacyPortableDir.exists()) legacyPortableDir else {
                val previousAurasDir = File(userDir, "AurasData")
                if (previousAurasDir.exists()) previousAurasDir else File(userDir, "AurasOrbitData")
            }
            return@lazy portableDir.also { it.mkdirs() }
        }

        val basePath =
            when (currentOS) {
                OS.WINDOWS -> {
                    val appData = System.getenv("APPDATA")
                    if (!appData.isNullOrEmpty()) appData else System.getProperty("user.home")
                }
                OS.MACOS -> System.getProperty("user.home") + "/Library/Application Support"
                OS.LINUX -> System.getProperty("user.home") + "/.local/share"
                OS.UNKNOWN -> System.getProperty("user.home")
            }
        // Keep existing installations on their established data root so upgrades do not
        // appear to lose profiles, history, preferences, or installed extensions.
        val legacyAppDataDir = File(basePath, "CloudStreamDesktop")
        val previousAurasAppDataDir = File(basePath, "AurasDesktop")
        val resolvedAppDataDir = when {
            legacyAppDataDir.exists() -> legacyAppDataDir
            previousAurasAppDataDir.exists() -> previousAurasAppDataDir
            else -> File(basePath, "AurasOrbit")
        }
        resolvedAppDataDir.also { it.mkdirs() }
    }

    /** Directory for persistent data store (bookmarks, history, preferences). */
    val dataDir: File by lazy {
        File(appDataDir, "data").also { it.mkdirs() }
    }

    /** Directory for SharedPreferences JSON files. */
    val sharedPrefsDir: File by lazy {
        File(appDataDir, "shared_prefs").also { it.mkdirs() }
    }

    /** Directory for installed extensions/plugins. */
    val extensionsDir: File by lazy {
        File(appDataDir, "Extensions").also { it.mkdirs() }
    }

    /** Directory for cache files. */
    val cacheDir: File by lazy {
        File(appDataDir, "cache").also { it.mkdirs() }
    }

    /** Directory for log files. */
    val logsDir: File by lazy {
        File(appDataDir, "logs").also { it.mkdirs() }
    }

    /** Directory for extracted MPV shaders. */
    val shadersDir: File by lazy {
        File(appDataDir, "shaders").also { it.mkdirs() }
    }

    /** Directory for user-provided custom fonts. */
    val fontsDir: File by lazy {
        File(appDataDir, "fonts").also { it.mkdirs() }
    }

    /** Directory for video player screenshots. */
    val screenshotsDir: File
        get() {
            val customPath = com.lagradost.common.storage.DesktopDataStore.getKey<String>("player_screenshot_dir")
            if (!customPath.isNullOrBlank()) {
                val f = File(customPath)
                if (f.exists() || f.mkdirs()) return f
            }
            val userHome = System.getProperty("user.home") ?: ""
            val picturesDir = File(userHome, "Pictures")
            val targetDir = if (picturesDir.exists() && picturesDir.isDirectory) {
                File(picturesDir, "Auras")
            } else {
                File(appDataDir, "screenshots")
            }
            return targetDir.also { it.mkdirs() }
        }
}
