package com.lagradost.runtime.loader

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionLoaderCacheTest {
    @Test
    fun precompiledJvmJarIsPreparedAndCacheMarkerBindsBothArtifacts() {
        val directory = Files.createTempDirectory("plugin-precompiled-jvm-cache-").toFile()
        val sourcePlugin = directory.resolve("fixture.cs3")
        val jvmSidecar = directory.resolve("fixture-jvm.jar")

        try {
            writeArchive(sourcePlugin, "manifest.json", "{\"pluginClassName\":\"fixture.Plugin\"}")
            writeArchive(jvmSidecar, "META-INF/fixture.txt", "precompiled")

            assertFalse(ExtensionLoader.isTransformedCacheValid(sourcePlugin, jvmSidecar))

            ExtensionLoader.preparePrecompiledJvmJar(sourcePlugin, jvmSidecar)

            assertTrue(ExtensionLoader.isTransformedCacheValid(sourcePlugin, jvmSidecar))
            assertTrue(File(jvmSidecar.path + ".identity").isFile)

            writeArchive(sourcePlugin, "manifest.json", "{\"pluginClassName\":\"fixture.UpdatedPlugin\"}")
            assertFalse(ExtensionLoader.isTransformedCacheValid(sourcePlugin, jvmSidecar))

            writeArchive(sourcePlugin, "manifest.json", "{\"pluginClassName\":\"fixture.Plugin\"}")
            writeArchive(jvmSidecar, "META-INF/fixture.txt", "changed sidecar")
            assertFalse(ExtensionLoader.isTransformedCacheValid(sourcePlugin, jvmSidecar))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeArchive(file: File, entryName: String, contents: String) {
        ZipOutputStream(file.outputStream()).use { output ->
            output.putNextEntry(ZipEntry(entryName))
            output.write(contents.toByteArray())
            output.closeEntry()
        }
    }
}
