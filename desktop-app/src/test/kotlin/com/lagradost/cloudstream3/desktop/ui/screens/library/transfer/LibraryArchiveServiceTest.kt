package com.lagradost.cloudstream3.desktop.ui.screens.library.transfer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.FollowedShow
import com.lagradost.common.storage.WatchHistory
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryArchiveServiceTest {
    @Test
    fun exportImportRoundTripPreservesProfileLibraryData() {
        val sourceProfileId = newProfileId()
        val targetProfileId = newProfileId()
        val token = UUID.randomUUID().toString()
        val bookmark = DesktopBookmark(
            id = "archive-$token",
            name = "Archive fixture",
            url = "https://fixture.invalid/$token",
            apiName = "Fixture",
            posterUrl = null,
        )
        val history = WatchHistory(
            parentId = DesktopDataStore.watchHistoryId("Fixture", bookmark.url, profileId = sourceProfileId),
            showName = bookmark.name,
            showUrl = bookmark.url,
            apiName = "Fixture",
            posterUrl = null,
            episodeThumbnailUrl = null,
            screenshotUrl = "fixture-screenshot",
            episode = 2,
            season = 1,
            episodeId = "episode-$token",
            position = 9_500,
            duration = 10_000,
            updateTime = 100L,
        )
        val directory = Files.createTempDirectory("auras-library-roundtrip").toFile()

        try {
            DesktopDataStore.addBookmark(bookmark, sourceProfileId)
            DesktopDataStore.setLastWatched(history)
            DesktopDataStore.markEpisodeWatchedFromHistory(history)
            DesktopDataStore.followShow(
                FollowedShow(
                    profileId = sourceProfileId,
                    providerName = "Fixture",
                    showUrl = bookmark.url,
                    showName = bookmark.name,
                    addedAt = 101L,
                ),
            )
            val customList = DesktopDataStore.createCustomList("Fixture $token", sourceProfileId)
            assertTrue(DesktopDataStore.setBookmarkInCustomList(customList.id, bookmark.id, true, sourceProfileId))
            assertTrue(DesktopDataStore.setCustomListShownOnHome(customList.id, true, sourceProfileId))

            val exportedFile = LibraryArchiveService.export(sourceProfileId, File(directory, "library"))
            val validated = LibraryArchiveService.read(exportedFile)
            val result = LibraryArchiveService.import(validated.archive, targetProfileId)

            assertEquals(1, result.bookmarksAdded)
            assertEquals(1, result.historyAdded)
            assertEquals(1, result.followedShowsAdded)
            assertEquals(1, result.episodeWatchMarksAdded)
            assertEquals(1, result.customListsAdded)
            assertEquals(1, result.customListItemsAdded)
            assertEquals(bookmark.id, DesktopDataStore.getBookmarks(targetProfileId).single().id)
            val importedHistory = DesktopDataStore.getAllWatchHistory(targetProfileId).single()
            assertEquals(history.episodeId, importedHistory.episodeId)
            assertEquals(history.position, importedHistory.position)
            assertEquals(null, importedHistory.screenshotUrl)
            assertEquals(bookmark.url, DesktopDataStore.getFollowedShows(targetProfileId).single().showUrl)
            assertTrue(DesktopDataStore.getEpisodeWatchMarks(targetProfileId).any { it.episodeId == history.episodeId })
            val importedList = DesktopDataStore.getCustomLists(targetProfileId).single()
            assertTrue(importedList.showOnHome)
            assertEquals(bookmark.id, DesktopDataStore.getCustomListItems(targetProfileId).single().bookmarkId)
            assertTrue(DesktopDataStore.getBookmarks(sourceProfileId).any { it.id == bookmark.id })
        } finally {
            DesktopDataStore.deleteProfileData(sourceProfileId, emptyMap())
            DesktopDataStore.deleteProfileData(targetProfileId, emptyMap())
            directory.deleteRecursively()
        }
    }

    @Test
    fun readRejectsNegativeHistorySeasonAndEpisode() {
        val directory = Files.createTempDirectory("auras-library-invalid-coordinates").toFile()
        try {
            listOf(
                LibraryArchiveHistory("Show", "fixture://show", "Fixture", episode = -1, season = 0),
                LibraryArchiveHistory("Show", "fixture://show", "Fixture", episode = 1, season = -1),
            ).forEachIndexed { index, history ->
                val archive = validArchive(history)
                val file = File(directory, "$index.orbitlib")
                jacksonObjectMapper().writeValue(file, archive)
                assertTrue(runCatching { LibraryArchiveService.read(file) }.isFailure)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedImportRollsBackEarlierBookmarkWrites() {
        val profileId = newProfileId()
        val token = UUID.randomUUID().toString()
        val bookmark = LibraryArchiveBookmark(
            id = "rollback-$token",
            name = "Rollback fixture",
            url = "https://fixture.invalid/rollback/$token",
            apiName = "Fixture",
        )
        val history = LibraryArchiveHistory(
            showName = bookmark.name,
            showUrl = bookmark.url,
            apiName = bookmark.apiName,
            episodeId = "episode-$token",
            updateTime = 100L,
        )
        val triggerName = "fail_archive_history_${token.replace('-', '_')}"
        val archive = validArchive(history).copy(bookmarks = listOf(bookmark))
        val databasePath = File(PlatformPaths.dataDir, "cloudstream.db").absolutePath

        try {
            java.sql.DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TRIGGER $triggerName BEFORE INSERT ON WatchHistory " +
                            "BEGIN SELECT RAISE(ABORT, 'injected archive failure'); END",
                    )
                }
            }
            assertTrue(runCatching { LibraryArchiveService.import(archive, profileId) }.isFailure)
            assertFalse(DesktopDataStore.getBookmarks(profileId).any { it.id == bookmark.id })
            assertTrue(DesktopDataStore.getAllWatchHistory(profileId).isEmpty())
        } finally {
            java.sql.DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                connection.createStatement().use { it.execute("DROP TRIGGER IF EXISTS $triggerName") }
            }
            DesktopDataStore.deleteProfileData(profileId, emptyMap())
        }
    }

    @Test
    fun importRejectsDeletedTargetProfile() {
        val profileId = newProfileId()
        try {
            DesktopDataStore.deleteProfileData(profileId, emptyMap())
            assertTrue(runCatching { LibraryArchiveService.import(validArchive(), profileId) }.isFailure)
        } finally {
            DesktopDataStore.deleteProfileData(profileId, emptyMap())
        }
    }

    private fun validArchive(history: LibraryArchiveHistory? = null) = LibraryArchive(
        format = LibraryArchiveService.FORMAT,
        formatVersion = LibraryArchiveService.FORMAT_VERSION,
        exportedAt = Instant.now().toString(),
        sourceAppVersion = "fixture",
        watchHistory = listOfNotNull(history),
    )

    private fun newProfileId(): Int {
        while (true) {
            val id = (UUID.randomUUID().hashCode() and 0x3fffffff) + 1
            if (!DesktopDataStore.isProfileDeleted(id)) return id
        }
    }
}
