package com.lagradost.runtime.loader

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginArchiveFilterTest {
    @Test
    fun sourceScansExcludeGeneratedSecureAndJvmSidecars() {
        val directory = Files.createTempDirectory("plugin-archive-filter-").toFile()
        try {
            val sourceJar = directory.resolve("fixture.jar").apply { writeText("source") }
            val sourceCs3 = directory.resolve("fixture.cs3").apply { writeText("source") }
            val secureJar = directory.resolve("fixture-secure.jar").apply { writeText("secure") }
            val jvmJar = directory.resolve("fixture-jvm.jar").apply { writeText("jvm") }
            val otherFile = directory.resolve("readme.txt").apply { writeText("text") }

            assertTrue(PluginArchiveFilter.isLoadablePluginArchive(sourceJar))
            assertTrue(PluginArchiveFilter.isLoadablePluginArchive(sourceCs3))
            assertFalse(PluginArchiveFilter.isLoadablePluginArchive(secureJar))
            assertFalse(PluginArchiveFilter.isLoadablePluginArchive(jvmJar))
            assertFalse(PluginArchiveFilter.isLoadablePluginArchive(otherFile))
        } finally {
            directory.deleteRecursively()
        }
    }
}
