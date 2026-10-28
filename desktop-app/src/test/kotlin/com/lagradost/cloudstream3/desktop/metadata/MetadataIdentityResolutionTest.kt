package com.lagradost.cloudstream3.desktop.metadata

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataIdentityResolutionTest {
    @AfterEach
    fun clearIdentityCache() {
        MetadataPipeline.clearCache()
    }

    @Test
    fun verifiedSuppliedIdsSkipResolversAndAreCached() = runBlocking {
        val resolveCalls = AtomicInteger()
        val resolver = fixtureResolver("resolver") {
            resolveCalls.incrementAndGet()
            MetadataMatch("resolver", "Supplied Show", imdbId = "tt7654321")
        }
        val supplied = MetadataPipeline.suppliedIdentityMatch(
            title = "Supplied Show",
            year = 2024,
            imdbId = "TT1234567",
            tmdbId = 765,
        )

        assertEquals("tt1234567", supplied?.imdbId)
        assertEquals(765, supplied?.tmdbId)
        val first = MetadataPipeline.resolveIdentityMatch(
            identityKey = "supplied_show_2024",
            title = "Supplied Show",
            year = 2024,
            type = TvType.Movie,
            rawUrl = null,
            resolvers = listOf(resolver),
            suppliedMatch = supplied,
        )
        val cached = MetadataPipeline.resolveIdentityMatch(
            identityKey = "supplied_show_2024",
            title = "Supplied Show",
            year = 2024,
            type = TvType.Movie,
            rawUrl = null,
            resolvers = listOf(resolver),
        )

        assertEquals("supplied", first.source)
        assertEquals("cache", cached.source)
        assertEquals(supplied, cached.match)
        assertEquals(0, resolveCalls.get())
    }

    @Test
    fun verifiedSuppliedIdsOverrideConflictingTitleCacheWithoutResolvingAgain() = runBlocking {
        val resolveCalls = AtomicInteger()
        val staleResolver = fixtureResolver("stale-resolver") {
            resolveCalls.incrementAndGet()
            MetadataMatch("stale-resolver", "Cached Show", 2023, imdbId = "tt1111111", tmdbId = 111)
        }
        val identityKey = "cached_show_2023"
        val cachedBefore = MetadataPipeline.resolveIdentityMatch(
            identityKey = identityKey,
            title = "Cached Show",
            year = 2023,
            type = TvType.Movie,
            rawUrl = null,
            resolvers = listOf(staleResolver),
        )
        val supplied = MetadataPipeline.suppliedIdentityMatch(
            title = "Cached Show",
            year = 2023,
            imdbId = "tt2222222",
            tmdbId = 222,
        )
        val preferred = MetadataPipeline.resolveIdentityMatch(
            identityKey = identityKey,
            title = "Cached Show",
            year = 2023,
            type = TvType.Movie,
            rawUrl = null,
            resolvers = listOf(staleResolver),
            suppliedMatch = supplied,
        )
        val cachedAfter = MetadataPipeline.resolveIdentityMatch(
            identityKey = identityKey,
            title = "Cached Show",
            year = 2023,
            type = TvType.Movie,
            rawUrl = null,
            resolvers = listOf(staleResolver),
        )

        assertEquals("tt1111111", cachedBefore.match?.imdbId)
        assertEquals("supplied", preferred.source)
        assertEquals("tt2222222", preferred.match?.imdbId)
        assertEquals(222, preferred.match?.tmdbId)
        assertEquals("cache", cachedAfter.source)
        assertEquals(supplied, cachedAfter.match)
        assertEquals(1, resolveCalls.get())
    }

    @Test
    fun malformedOrPlaceholderSuppliedIdsAreIgnored() {
        assertNull(MetadataPipeline.suppliedIdentityMatch("Show", 2024, "tt1", null))
        assertNull(MetadataPipeline.suppliedIdentityMatch("Show", 2024, "tt0000000", 0))
        assertNull(MetadataPipeline.suppliedIdentityMatch("Show", 2024, null, -3))
    }

    @Test
    fun resolverTimeoutFallsBackToLowerPriorityMetadataProvider() = runBlocking {
        val slowResolver = fixtureResolver("slow") { awaitCancellation() }
        val fallbackResolver = fixtureResolver("fallback") {
            MetadataMatch("fallback", "Fallback Show", 2022, tmdbId = 42)
        }

        val resolved = MetadataPipeline.resolveFromProviders(
            resolvers = listOf(slowResolver, fallbackResolver),
            title = "Fallback Show",
            year = 2022,
            type = TvType.Movie,
            rawUrl = null,
            resolverTimeoutMs = 50L,
        )

        assertEquals("fallback", resolved?.providerId)
        assertEquals(42, resolved?.tmdbId)
    }

    @Test
    fun resolverPriorityWinsEvenWhenLowerPriorityReturnsFirst() = runBlocking {
        val firstResolver = fixtureResolver("first") {
            delay(40L)
            MetadataMatch("first", "Priority Show", 2021, tmdbId = 101)
        }
        val secondResolver = fixtureResolver("second") {
            MetadataMatch("second", "Priority Show", 2021, tmdbId = 202)
        }

        val resolved = MetadataPipeline.resolveFromProviders(
            resolvers = listOf(firstResolver, secondResolver),
            title = "Priority Show",
            year = 2021,
            type = TvType.Movie,
            rawUrl = null,
            resolverTimeoutMs = 500L,
        )

        assertEquals("first", resolved?.providerId)
        assertEquals(101, resolved?.tmdbId)
    }

    private fun fixtureResolver(
        providerId: String,
        resolveMatch: suspend () -> MetadataMatch?,
    ): MetadataProvider = object : MetadataProvider {
        override val id: String = providerId
        override val displayName: String = providerId
        override val priority: Int = 0
        override val supportedTypes: Set<TvType> = setOf(TvType.Movie)

        override suspend fun resolve(title: String, year: Int?, type: TvType, rawUrl: String?): MetadataMatch? = resolveMatch()

        override suspend fun enrich(
            loaded: LoadResponse,
            match: MetadataMatch?,
            context: MetadataEnrichmentContext,
            callbacks: MetadataEnrichmentCallbacks,
        ): Boolean = false
    }
}
