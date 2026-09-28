package com.lagradost.cloudstream3.desktop.repo

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginFileUtilsTest {
    @Test
    fun `repository and plugin names cannot escape Extensions`() {
        assertEquals("_", PluginFileUtils.safeDirectoryName(".."))
        val repoDir = PluginFileUtils.repositoryDirectory("..")
        assertEquals(PluginFileUtils.getExtensionsDir().canonicalFile, repoDir.parentFile)

        val pluginFile = PluginFileUtils.pluginFile("test", "../../outside", ".jar")
        assertEquals(PluginFileUtils.repositoryDirectory("test"), pluginFile.parentFile)
    }

    @Test
    fun `repository folder collisions account for Windows case and sanitization`() {
        assertEquals(
            PluginFileUtils.repositoryDirectoryCollisionKey("A/B"),
            PluginFileUtils.repositoryDirectoryCollisionKey("a:b"),
        )
        assertEquals(
            PluginFileUtils.repositoryDirectoryCollisionKey("CloudRepo"),
            PluginFileUtils.repositoryDirectoryCollisionKey("cloudrepo"),
        )
    }

    @Test
    fun `failed plugin replacement restores old files and removes new sidecars`() = runBlocking {
        val directory = Files.createTempDirectory("plugin-update-rollback-test").toFile()
        val primary = directory.resolve("plugin.jar").apply { writeText("old-plugin") }
        val jvmSidecar = directory.resolve("plugin-jvm.jar").apply { writeText("old-jvm-sidecar") }
        val dexPackage = directory.resolve("plugin.cs3")
        val callbacks = mutableListOf<String>()

        try {
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    PluginFileUtils.withFileRollback(
                        files = listOf(primary, jvmSidecar, dexPackage),
                        onBeforeRestore = { callbacks.add("unload") },
                        onAfterRestore = { callbacks.add("reload") },
                    ) {
                        primary.writeText("broken-plugin")
                        jvmSidecar.writeText("broken-jvm-sidecar")
                        dexPackage.writeText("broken-dex-package")
                        error("load failed")
                    }
                }
            }

            assertEquals("old-plugin", primary.readText())
            assertEquals("old-jvm-sidecar", jvmSidecar.readText())
            assertFalse(dexPackage.exists())
            assertEquals(listOf("unload", "reload"), callbacks)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `successful plugin replacement keeps new files`() = runBlocking {
        val directory = Files.createTempDirectory("plugin-update-success-test").toFile()
        val primary = directory.resolve("plugin.jar").apply { writeText("old-plugin") }

        try {
            PluginFileUtils.withFileRollback(listOf(primary)) {
                primary.writeText("new-plugin")
            }
            assertEquals("new-plugin", primary.readText())
            assertTrue(primary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
