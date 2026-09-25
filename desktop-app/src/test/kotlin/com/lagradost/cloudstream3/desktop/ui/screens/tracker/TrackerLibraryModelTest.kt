package com.lagradost.cloudstream3.desktop.ui.screens.tracker

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.library.ListSorting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TrackerLibraryModelTest {
    @Test
    fun parsesNonContiguousAnimeAndSeasonCoordinates() {
        val parsed = TrackerLibraryModel.parseEpisodeSelection("1, 3, 2x5")
            .getOrThrow()

        assertEquals(
            setOf(
                SyncAPI.SyncEpisode(null, 1),
                SyncAPI.SyncEpisode(null, 3),
                SyncAPI.SyncEpisode(2, 5),
            ),
            parsed,
        )
        assertEquals("1, 3, 2x5", TrackerLibraryModel.formatEpisodeSelection(parsed))
    }

    @Test
    fun rejectsInvalidEpisodeCoordinates() {
        assertFailsWith<IllegalArgumentException> {
            TrackerLibraryModel.parseEpisodeSelection("0").getOrThrow()
        }
        assertFailsWith<IllegalArgumentException> {
            TrackerLibraryModel.parseEpisodeSelection("1x0").getOrThrow()
        }
    }

    @Test
    fun filtersStatusAndSortsWithoutMutatingSource() {
        val entries = listOf(
            entry("Beta", "Watching", 2),
            entry("Alpha", "Completed", 1),
            entry("Gamma", "Watching", 3),
        )

        val filtered = TrackerLibraryModel.filterAndSort(
            entries = entries,
            query = "",
            status = "Watching",
            sorting = ListSorting.AlphabeticalA,
        )

        assertEquals(listOf("Beta", "Gamma"), filtered.map { it.item.name })
        assertEquals(listOf("Beta", "Alpha", "Gamma"), entries.map { it.item.name })
    }

    private fun entry(name: String, status: String, id: Int) = TrackerLibraryEntry(
        item = SyncAPI.LibraryItem(
            name = name,
            url = "https://example.test/$id",
            syncId = id.toString(),
            episodesCompleted = null,
            episodesTotal = null,
            personalRating = null,
            lastUpdatedUnixTime = null,
            apiName = "Test",
            type = TvType.Anime,
            posterUrl = null,
            posterHeaders = null,
            quality = null,
            releaseDate = null,
        ),
        listName = status,
    )
}
