package com.lagradost.cloudstream3.ui.explore

import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.WatchType
import org.junit.Assert.assertEquals
import org.junit.Test

class AurasCatalogFiltersTest {
    private data class Sample(
        val name: String,
        val kind: AurasCatalogMediaKind,
        val year: Int?,
    )

    @Test
    fun providerFiltersMatchKindsAndCurrentOrPreviousReleaseYear() {
        val items = listOf(
            Sample("movie", AurasCatalogMediaKind.MOVIE, 2026),
            Sample("series", AurasCatalogMediaKind.SERIES, 2025),
            Sample("anime", AurasCatalogMediaKind.ANIME, 2024),
            Sample("unknown year", AurasCatalogMediaKind.OTHER, null),
        )
        val metadata: (Sample) -> AurasCatalogMetadata = {
            AurasCatalogMetadata(it.kind, it.year)
        }

        assertEquals(items, filterAurasCatalogItems(items, AurasCatalogFilter.ALL, 2026, metadata))
        assertEquals(listOf("movie"), filterAurasCatalogItems(items, AurasCatalogFilter.MOVIES, 2026, metadata).map { it.name })
        assertEquals(listOf("series"), filterAurasCatalogItems(items, AurasCatalogFilter.SERIES, 2026, metadata).map { it.name })
        assertEquals(listOf("anime"), filterAurasCatalogItems(items, AurasCatalogFilter.ANIME, 2026, metadata).map { it.name })
        assertEquals(listOf("movie", "series"), filterAurasCatalogItems(items, AurasCatalogFilter.NEW, 2026, metadata).map { it.name })
    }

    @Test
    fun localRewatchingUsesAnUnusedIdAndKeepsLegacyAndSyncIds() {
        assertEquals(
            mapOf(
                WatchType.WATCHING to 0,
                WatchType.COMPLETED to 1,
                WatchType.ONHOLD to 2,
                WatchType.DROPPED to 3,
                WatchType.PLANTOWATCH to 4,
                WatchType.NONE to 5,
                WatchType.REWATCHING to 6,
            ),
            WatchType.entries.associateWith { it.internalId },
        )
        assertEquals(WatchType.NONE, WatchType.fromInternalId(5))
        assertEquals(WatchType.REWATCHING, WatchType.fromInternalId(6))
        assertEquals(WatchType.NONE, WatchType.fromInternalId(null))
        assertEquals(5, SyncWatchType.REWATCHING.internalId)
    }

    @Test
    fun watchStatusSelectionOnlyReturnsTheSelectedLocalStatus() {
        val items = listOf(
            "first" to WatchType.WATCHING,
            "planned" to WatchType.PLANTOWATCH,
            "rewatch" to WatchType.REWATCHING,
        )

        assertEquals(
            listOf("rewatch"),
            filterAurasWatchlistItems(items, WatchType.REWATCHING) { it.second }.map { it.first },
        )
    }
}
