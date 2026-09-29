package com.lagradost.common.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformPathsTest {
    @Test
    fun defaultStorageUsesCanonicalDirectory() {
        val basePath = Files.createTempDirectory("auras-platform-paths").toFile()
        try {
            assertEquals(
                File(basePath, "AurasOrbit"),
                resolveAppDataDirectory(File(basePath, "portable"), basePath, portable = false),
            )
        } finally {
            basePath.deleteRecursively()
        }
    }

    @Test
    fun portableStorageUsesCanonicalDirectory() {
        val userDir = Files.createTempDirectory("auras-portable-paths").toFile()
        try {
            assertEquals(
                File(userDir, "AurasOrbitData"),
                resolveAppDataDirectory(userDir, userDir, portable = true),
            )
        } finally {
            userDir.deleteRecursively()
        }
    }

    @Test
    fun explicitAurasDataDirectoryStillOverridesDefault() {
        val basePath = Files.createTempDirectory("auras-custom-paths").toFile()
        try {
            val customPath = File(basePath, "custom-data")
            assertEquals(
                customPath,
                resolveAppDataDirectory(File(basePath, "portable"), basePath, portable = true, customDir = customPath.path),
            )
        } finally {
            basePath.deleteRecursively()
        }
    }
}
