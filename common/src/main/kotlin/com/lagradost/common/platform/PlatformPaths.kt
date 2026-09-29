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
        val userDir = System.getProperty("user.dir")
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
        val portable = File(userDir, "portable.txt").exists()
        resolveAppDataDirectory(File(userDir), File(basePath), portable, customDirProp)
            .also { it.mkdirs() }
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

internal fun resolveAppDataDirectory(
    userDir: File,
    basePath: File,
    portable: Boolean,
    customDir: String? = null,
): File = customDir
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
    ?: if (portable) File(userDir, "AurasOrbitData") else File(basePath, "AurasOrbit")
