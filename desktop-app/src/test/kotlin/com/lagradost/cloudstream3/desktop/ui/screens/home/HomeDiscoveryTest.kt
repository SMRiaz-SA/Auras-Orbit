package com.lagradost.cloudstream3.desktop.ui.screens.home

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `fast source publishes before delayed source and retry targets only failed source`() = runBlocking {
        val fastKey = "provider:fast"
        val delayedKey = "provider:delayed"
        val failingKey = "provider:failing"
        var shelf = aggregateHomeDiscoverySources(
            listOf(
                HomeDiscoverySourceState(fastKey, "Fast", isLoading = true),
                HomeDiscoverySourceState(delayedKey, "Delayed", isLoading = true),
                HomeDiscoverySourceState(failingKey, "Failing", isLoading = true),
            ),
        )
        val delayedGate = CompletableDeferred<Unit>()
        val delayedStarted = CompletableDeferred<Unit>()
        val calls = mutableMapOf(fastKey to 0, delayedKey to 0, failingKey to 0)

        suspend fun loadFixtures(keys: List<String>) {
            loadHomeDiscoverySourcesProgressively(
                sources = keys,
                load = { key ->
                    calls[key] = calls.getValue(key) + 1
                    when (key) {
                        delayedKey -> {
                            delayedStarted.complete(Unit)
                            delayedGate.await()
                        }
                        failingKey -> if (calls.getValue(key) == 1) error("fixture failure")
                    }
                    when (key) {
                        fastKey -> TestResponse("Fast title", "Fast", TvType.Movie)
                        delayedKey -> TestResponse("Delayed title", "Delayed", TvType.Movie)
                        else -> TestResponse("Recovered title", "Failing", TvType.Movie)
                    }
                },
                onResult = { key, result ->
                    val next = shelf.sources.map { source ->
                        if (source.sourceKey != key) {
                            source
                        } else if (result.isSuccess) {
                            source.copy(
                                isLoading = false,
                                hasLoaded = true,
                                error = null,
                                items = listOf(HomeDiscoveryItem(source.sourceName, result.getOrThrow())),
                            )
                        } else {
                            source.copy(isLoading = false, hasLoaded = true, error = result.exceptionOrNull()?.message)
                        }
                    }
                    shelf = aggregateHomeDiscoverySources(next)
                },
            )
        }

        val initialLoad = async { loadFixtures(listOf(fastKey, delayedKey, failingKey)) }

        withTimeout(2_000) {
            while (shelf.sources.none { it.sourceKey == fastKey && it.items.isNotEmpty() } ||
                shelf.failedSourceKeys().isEmpty()
                ) delay(5)
            delayedStarted.await()
        }
        assertEquals(listOf("Fast title"), shelf.items.map { it.response.name })
        assertTrue(shelf.sources.first { it.sourceKey == delayedKey }.isLoading)
        assertEquals(listOf(failingKey), shelf.failedSourceKeys())

        loadFixtures(shelf.failedSourceKeys())
        assertEquals(1, calls.getValue(fastKey))
        assertEquals(1, calls.getValue(delayedKey))
        assertEquals(2, calls.getValue(failingKey))

        delayedGate.complete(Unit)
        initialLoad.await()
        assertEquals(listOf("Fast title", "Delayed title", "Recovered title"), shelf.items.map { it.response.name })
        assertFalse(shelf.isLoading)
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
