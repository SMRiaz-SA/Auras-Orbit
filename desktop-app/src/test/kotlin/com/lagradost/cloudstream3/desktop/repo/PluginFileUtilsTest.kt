package com.lagradost.cloudstream3.desktop.repo

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginFileUtilsTest {
    @Test
    fun `plugin archive downloads have transfer timeouts separate from repository requests`() {
        assertEquals(15_000, PluginNetworkClient.redirectClient.callTimeoutMillis)
        assertEquals(300_000, PluginNetworkClient.pluginDownloadClient.callTimeoutMillis)
        assertEquals(15_000, PluginNetworkClient.pluginDownloadClient.connectTimeoutMillis)
        assertEquals(60_000, PluginNetworkClient.pluginDownloadClient.readTimeoutMillis)
    }

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
        val primaryIdentity = directory.resolve("plugin.jar.identity").apply { writeText("old-primary-identity") }
        val jvmSidecar = directory.resolve("plugin-jvm.jar").apply { writeText("old-jvm-sidecar") }
        val jvmIdentity = directory.resolve("plugin-jvm.jar.identity").apply { writeText("old-jvm-identity") }
        val secureJar = directory.resolve("plugin-secure.jar")
        val dexPackage = directory.resolve("plugin.cs3").apply { writeText("old-dex-package") }
        val extractedDex = directory.resolve("plugin.dex").apply { writeText("old-extracted-dex") }
        val callbacks = mutableListOf<String>()

        try {
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    PluginFileUtils.withFileRollback(
                        files = PluginFileUtils.pluginArtifactFiles(primary),
                        onBeforeRestore = { callbacks.add("unload") },
                        onAfterRestore = { callbacks.add("reload") },
                    ) {
                        primary.writeText("broken-plugin")
                        primaryIdentity.writeText("broken-primary-identity")
                        jvmSidecar.writeText("broken-jvm-sidecar")
                        jvmIdentity.writeText("broken-jvm-identity")
                        secureJar.writeText("broken-secure-jar")
                        File(secureJar.path + ".identity").writeText("broken-secure-identity")
                        dexPackage.writeText("broken-dex-package")
                        extractedDex.writeText("broken-extracted-dex")
                        error("load failed")
                    }
                }
            }

            assertEquals("old-plugin", primary.readText())
            assertEquals("old-primary-identity", primaryIdentity.readText())
            assertEquals("old-jvm-sidecar", jvmSidecar.readText())
            assertEquals("old-jvm-identity", jvmIdentity.readText())
            assertFalse(secureJar.exists())
            assertFalse(File(secureJar.path + ".identity").exists())
            assertEquals("old-dex-package", dexPackage.readText())
            assertEquals("old-extracted-dex", extractedDex.readText())
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

    @Test
    fun `plugin artifact listing includes generated JVM cache markers`() {
        val directory = Files.createTempDirectory("plugin-cache-artifacts-test").toFile()
        try {
            val artifacts = PluginFileUtils.pluginArtifactFiles(directory.resolve("StreamPlay.jar"))

            assertTrue(artifacts.contains(directory.resolve("StreamPlay.jar")))
            assertTrue(artifacts.contains(directory.resolve("StreamPlay-jvm.jar")))
            assertTrue(artifacts.contains(directory.resolve("StreamPlay-jvm.jar.identity")))
            assertTrue(artifacts.contains(directory.resolve("StreamPlay-secure.jar.identity")))
            assertFalse(artifacts.contains(directory.resolve("StreamPlay-jvm-jvm.jar")))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `installed plugin identity is global across repository directories`() {
        val installedIdentity = PluginFileUtils.installedPluginIdentity("shared.plugin")
        val candidateFromAnotherRepository = PluginFileUtils.installedPluginIdentity("shared.plugin")

        assertEquals(installedIdentity, candidateFromAnotherRepository)
        assertEquals("shared.plugin", installedIdentity)
    }
}
