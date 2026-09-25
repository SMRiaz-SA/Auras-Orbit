package com.lagradost.cloudstream3.desktop.repo

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PluginFileUtilsTest {
    @Test
    fun `repository and plugin names cannot escape Extensions`() {
        assertEquals("_", PluginFileUtils.safeDirectoryName(".."))
        val repoDir = PluginFileUtils.repositoryDirectory("..")
        assertEquals(PluginFileUtils.getExtensionsDir().canonicalFile, repoDir.parentFile)

        val pluginFile = PluginFileUtils.pluginFile("test", "../../outside", ".jar")
        assertEquals(PluginFileUtils.repositoryDirectory("test"), pluginFile.parentFile)
    }
}
