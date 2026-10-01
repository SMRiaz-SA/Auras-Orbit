package com.lagradost.cloudstream3.desktop.genre

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GenreBrowseSortTest {
    @Test
    fun `release date sorting uses the correct field for each media type`() {
        assertEquals("primary_release_date.desc", GenreBrowseSort.Newest.apiSortBy(GenreBrowseMediaType.Movies))
        assertEquals("primary_release_date.asc", GenreBrowseSort.Oldest.apiSortBy(GenreBrowseMediaType.Movies))
        assertEquals("first_air_date.desc", GenreBrowseSort.Newest.apiSortBy(GenreBrowseMediaType.Series))
        assertEquals("first_air_date.asc", GenreBrowseSort.Oldest.apiSortBy(GenreBrowseMediaType.Series))
    }

    @Test
    fun `rating and popularity sorts use meaningful vote safeguards`() {
        assertEquals("popularity.desc", GenreBrowseSort.Popularity.apiSortBy(GenreBrowseMediaType.Movies))
        assertEquals("vote_average.desc", GenreBrowseSort.TopRated.apiSortBy(GenreBrowseMediaType.Series))
        assertEquals(200, GenreBrowseSort.TopRated.minimumVoteCount)
        assertEquals("vote_count.desc", GenreBrowseSort.MostVotes.apiSortBy(GenreBrowseMediaType.Movies))
        assertEquals(null, GenreBrowseSort.MostVotes.minimumVoteCount)
    }
}
