package com.lagradost.cloudstream3.desktop.repo

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginUpdateSelectionTest {
    @Test
    fun prefersLoadedArchiveAndUsesNewestFallback() {
        val directory = Files.createTempDirectory("orbit-plugin-update-selection").toFile()
        try {
            val older = File(directory, "older.cs3").apply {
                writeText("older")
                setLastModified(1_000L)
            }
            val newer = File(directory, "newer.cs3").apply {
                writeText("newer")
                setLastModified(2_000L)
            }

            assertEquals(newer.canonicalFile, selectPluginUpdateArchive(listOf(older, newer), newer)?.canonicalFile)
            assertEquals(newer.canonicalFile, selectPluginUpdateArchive(listOf(older, newer), null)?.canonicalFile)
        } finally {
            directory.deleteRecursively()
        }
    }
}
