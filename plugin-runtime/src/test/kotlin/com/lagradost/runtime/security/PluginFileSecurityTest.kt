package com.lagradost.runtime.security

import com.lagradost.runtime.loader.stubs.PluginFileSecurityStub
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginFileSecurityTest {
    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        PluginFileSecurityStub.customBaseDir = tempDir
    }

    @AfterEach
    fun teardown() {
        PluginFileSecurityStub.customBaseDir = null
        PluginFileSecurityStub.clearGrantedPaths()
    }

    @Test
    fun `relative and absolute paths retain normal JVM semantics`() {
        val relative = "cache/sub/data.json"
        val absolute = File(tempDir, "outside/video.mp4")

        assertEquals(File(relative).path, PluginFileSecurityStub.checkPath(relative))
        assertEquals(absolute.path, PluginFileSecurityStub.checkPath(absolute.path))
    }

    @Test
    fun `file objects and file URIs pass through unchanged`() {
        val file = File(tempDir, "outside.bin")
        val uri = file.toURI()

        assertSame(file, PluginFileSecurityStub.checkFile(file))
        assertEquals(uri, PluginFileSecurityStub.checkUri(uri))
    }

    @Test
    fun `roots and temporary files use standard JVM locations`() {
        assertEquals(File.listRoots().toList(), PluginFileSecurityStub.listRoots().toList())

        val tempFile = PluginFileSecurityStub.createTempFile("plugin", ".tmp", tempDir)
        assertEquals(tempDir.canonicalFile, tempFile.parentFile.canonicalFile)
        assertTrue(tempFile.delete())
    }

    @Test
    fun `plugin storage helper stays under its configured Extensions directory`() {
        val root = PluginFileSecurityStub.getStorageRootForPlugin("..")
        assertEquals(File(tempDir, "Extensions/_/storage").path, root.path)
    }
}
