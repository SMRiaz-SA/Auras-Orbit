package com.lagradost.cloudstream3.desktop.repo

import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopRepositoryManagerUpdateSelectionTest {
    @Test
    fun repositoryFolderCollisionUsesStableStorageNameAfterManifestRename() {
        val existing = RepositoryData(name = "Renamed Catalog", url = "https://old.example/repo.json")
            .apply { storageName = "Original Catalog" }

        assertEquals(
            existing,
            repositoryWithConflictingStorageFolder(
                repositories = listOf(existing),
                incomingName = "Original/Catalog",
                incomingUrl = "https://new.example/repo.json",
            ),
        )
        assertEquals(
            existing,
            repositoryWithConflictingStorageFolder(
                repositories = listOf(existing),
                incomingName = "Renamed Catalog",
                incomingUrl = "https://another.example/repo.json",
            ),
        )
        assertNull(
            repositoryWithConflictingStorageFolder(
                repositories = listOf(existing),
                incomingName = "Original/Catalog",
                incomingUrl = existing.url,
            ),
        )
    }

    @Test
    fun selectPluginUpdateArchivePrefersLoadedCopyThenNewestFallback() {
        val directory = Files.createTempDirectory("plugin-update-selection-").toFile()
        try {
            val older = directory.resolve("a.jar").apply { writeText("old") }
            val newer = directory.resolve("z.jar").apply { writeText("new") }
            older.setLastModified(1_000L)
            newer.setLastModified(2_000L)

            assertEquals(newer.canonicalFile, selectPluginUpdateArchive(listOf(older, newer), null)?.canonicalFile)
            assertEquals(older.canonicalFile, selectPluginUpdateArchive(listOf(newer, older), older)?.canonicalFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun updatedArchiveGetsTimestampNewerThanDuplicateCopies() {
        val directory = Files.createTempDirectory("plugin-update-priority-").toFile()
        try {
            val duplicate = directory.resolve("old.jar").apply { writeText("old") }
            val updated = directory.resolve("updated.jar").apply { writeText("updated") }
            duplicate.setLastModified(1_000L)
            updated.setLastModified(500L)

            markUpdatedPluginArchivePreferred(updated, duplicate.lastModified())

            assertTrue(updated.lastModified() > duplicate.lastModified())
        } finally {
            directory.deleteRecursively()
        }
    }
}
