package com.lagradost.cloudstream3.desktop.ui.screens.home

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeDiscoveryTest {
    @Test
    fun `classifies only explicit recent and popular catalog labels`() {
        assertEquals(HomeDiscoveryKind.RECENT, classifyHomeDiscoveryCatalog("Recently Updated"))
        assertEquals(HomeDiscoveryKind.RECENT, classifyHomeDiscoveryCatalog("New Releases"))
        assertEquals(HomeDiscoveryKind.POPULAR, classifyHomeDiscoveryCatalog("Trending Movies"))
        assertEquals(HomeDiscoveryKind.POPULAR, classifyHomeDiscoveryCatalog("Top rated series"))
        assertNull(classifyHomeDiscoveryCatalog("Topics"))
        assertNull(classifyHomeDiscoveryCatalog("Korean Classics"))
    }

    @Test
    fun `merges titles case insensitively but keeps different years and types`() {
        val merged = mergeHomeDiscoveryItems(
            listOf(
                candidate("Alpha (2020)", "Provider A", TvType.Movie),
                candidate("alpha [2020]", "Provider B", TvType.Movie),
                candidate("Alpha (2021)", "Provider C", TvType.Movie),
                candidate("Alpha (2020)", "Provider D", TvType.TvSeries),
            ),
        )

        assertEquals(listOf("Provider A", "Provider C", "Provider D"), merged.map { it.providerName })
    }

    @Test
    fun `recommendations need two distinct seeds and rank shared title words`() {
        val candidates = listOf(
            candidate("Amazing Spider Hero", "Provider A", TvType.Movie),
            candidate("Spider Hero", "Provider B", TvType.Movie),
            candidate("Another Story", "Provider C", TvType.Movie),
        )

        assertEquals(emptyList(), recommendHomeItems(candidates, listOf("Spider Man")))
        assertEquals(
            listOf("Amazing Spider Hero", "Spider Hero"),
            recommendHomeItems(candidates, listOf("Spider Man", "Amazing Spider"))
                .map { it.response.name },
        )
        assertEquals(
            listOf("Amazing Spider Hero"),
            recommendHomeItems(candidates, listOf("Spider Man", "Amazing Spider"), limit = 1)
                .takeLast(1)
                .map { it.response.name },
        )
    }

    private fun candidate(title: String, provider: String, type: TvType) =
        HomeDiscoveryItem(provider, TestResponse(title, provider, type))

    private data class TestResponse(
        override val name: String,
        override val apiName: String,
        override var type: TvType?,
        override val url: String = "https://example.invalid/$apiName/${name.hashCode()}",
        override var posterUrl: String? = null,
        override var posterHeaders: Map<String, String>? = null,
        override var id: Int? = null,
        override var quality: SearchQuality? = null,
        override var score: Score? = null,
    ) : SearchResponse
}
