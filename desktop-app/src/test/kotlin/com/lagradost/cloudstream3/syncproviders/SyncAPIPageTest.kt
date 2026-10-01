package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.UiText
import org.junit.jupiter.api.Test
import java.util.Date
import kotlin.test.assertEquals

class SyncAPIPageTest {
    @Test
    fun `sort applies rating ordering and leaves unrated items last`() {
        val page = page(
            item("Naruto", score = 8),
            item("One Piece", score = 10),
            item("Alpha", score = null),
        )

        page.sort(ListSorting.RatingHigh)

        assertEquals(listOf("One Piece", "Naruto", "Alpha"), page.items.map { it.name })
    }

    @Test
    fun `query sort filters and ranks title matches by relevance`() {
        val page = page(item("Naruto"), item("One Piece"), item("Alpha"))

        page.sort(ListSorting.Query, "o")

        assertEquals(listOf("One Piece", "Naruto"), page.items.map { it.name })
    }

    @Test
    fun `date sorts keep missing dates last in both directions`() {
        val old = item("Old", updated = 10, releaseDate = Date(10))
        val recent = item("Recent", updated = 20, releaseDate = Date(20))
        val missing = item("Missing")
        val page = page(old, missing, recent)

        page.sort(ListSorting.UpdatedNew)
        assertEquals(listOf("Recent", "Old", "Missing"), page.items.map { it.name })
        page.sort(ListSorting.ReleaseDateOld)
        assertEquals(listOf("Old", "Recent", "Missing"), page.items.map { it.name })
    }

    private fun page(vararg items: SyncAPI.LibraryItem) =
        SyncAPI.Page(UiText.PlainText("Test"), items.toList())

    private fun item(
        name: String,
        score: Int? = null,
        updated: Long? = null,
        releaseDate: Date? = null,
    ) = SyncAPI.LibraryItem(
        name = name,
        url = "https://example.invalid/$name",
        syncId = name,
        episodesCompleted = null,
        episodesTotal = null,
        personalRating = score?.let { Score.from10(it) },
        lastUpdatedUnixTime = updated,
        apiName = "Test",
        type = TvType.Anime,
        posterUrl = null,
        posterHeaders = null,
        quality = null,
        releaseDate = releaseDate,
    )
}
