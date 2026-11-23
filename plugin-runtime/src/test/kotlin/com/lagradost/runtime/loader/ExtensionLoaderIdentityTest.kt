package com.lagradost.runtime.loader

import com.lagradost.cloudstream3.plugins.BasePlugin
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionLoaderIdentityTest {
    @Test
    fun rescanLoadsNewestArchiveForAnAlreadyLoadedManifestIdentity() {
        val directory = Files.createTempDirectory("plugin-duplicate-identity-").toFile()
        val firstArchive = directory.resolve("first.jar")
        val duplicateArchive = directory.resolve("duplicate.jar")
        val loadedPaths = mutableListOf<String>()
        var factoryCalls = 0
        val previousFactory = ExtensionLoader.isolatedPluginFactory
        val previousOnLoaded = ExtensionLoader.onPluginLoaded
        val previousOnUnloaded = ExtensionLoader.onPluginUnloaded

        try {
            writePluginArchive(firstArchive)
            writePluginArchive(duplicateArchive)
            firstArchive.setLastModified(1_000_000L)
            duplicateArchive.setLastModified(2_000_000L)

            ExtensionLoader.isolatedPluginFactory = { _, _ ->
                factoryCalls++
                IsolatedFixturePlugin()
            }
            ExtensionLoader.onPluginLoaded = { loadedPaths += it.canonicalPath }
            ExtensionLoader.onPluginUnloaded = null

            assertEquals(1, ExtensionLoader.rescanAndLoadNewPlugins(directory))
            assertEquals(1, factoryCalls)
            assertEquals(listOf(duplicateArchive.canonicalPath), loadedPaths)
            assertEquals(duplicateArchive.canonicalFile, ExtensionLoader.getLoadedPluginPath("shared.plugin")?.canonicalFile)
            assertFalse(ExtensionLoader.isPluginLoaded(firstArchive.absolutePath))
            assertTrue(ExtensionLoader.isPluginLoaded(duplicateArchive.absolutePath))

            assertFailsWith<IllegalStateException> {
                ExtensionLoader.loadAndInit(firstArchive)
            }
            assertEquals(1, factoryCalls)
            assertEquals(listOf(duplicateArchive.canonicalPath), loadedPaths)
        } finally {
            ExtensionLoader.unloadPlugin(firstArchive.absolutePath)
            ExtensionLoader.unloadPlugin(duplicateArchive.absolutePath)
            ExtensionLoader.isolatedPluginFactory = previousFactory
            ExtensionLoader.onPluginLoaded = previousOnLoaded
            ExtensionLoader.onPluginUnloaded = previousOnUnloaded
            directory.deleteRecursively()
        }
    }

    private fun writePluginArchive(file: File) {
        ZipOutputStream(file.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("manifest.json"))
            output.write(
                """{"pluginClassName":"fixture.Plugin","internalName":"shared.plugin","name":"Shared Plugin"}""".toByteArray(),
            )
            output.closeEntry()
            output.putNextEntry(ZipEntry("fixture.txt"))
            output.write("fixture".toByteArray())
            output.closeEntry()
        }
    }

    private class IsolatedFixturePlugin : BasePlugin(), IsolatedPluginHandle
}
