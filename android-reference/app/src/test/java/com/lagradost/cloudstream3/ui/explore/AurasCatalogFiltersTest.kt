package com.lagradost.cloudstream3.ui.explore

import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.WatchType
import org.junit.Assert.assertEquals
import org.junit.Test

class AurasCatalogFiltersTest {
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
