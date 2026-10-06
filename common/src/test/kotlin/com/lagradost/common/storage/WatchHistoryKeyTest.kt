package com.lagradost.common.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WatchHistoryKeyTest {
    @Test
    fun collidingJavaHashesProduceDifferentHistoryKeys() {
        val firstUrl = "https://fixture.invalid/Aa"
        val secondUrl = "https://fixture.invalid/BB"
        assertEquals(firstUrl.hashCode(), secondUrl.hashCode())
        assertNotEquals(
            WatchHistoryKey.create(7, "Fixture", firstUrl),
            WatchHistoryKey.create(7, "Fixture", secondUrl),
        )

        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(
            WatchHistoryKey.create(7, "Fixture", firstUrl, season = 1, episode = 2, episodeData = "Aa"),
            WatchHistoryKey.create(7, "Fixture", firstUrl, season = 1, episode = 2, episodeData = "BB"),
        )
    }

    @Test
    fun migrationRetainsLegacyEpisodeSuffixSemantics() {
        val showUrl = "stremio://media/series/fixture"
        val episodeId = "episode-fixture"
        val oldParentId = "p17_Stremio_${showUrl.hashCode()}_s2_e8_${episodeId.hashCode()}"

        assertEquals(
            WatchHistoryKey.create(17, "Stremio", showUrl, season = 2, episode = 8, episodeData = episodeId),
            WatchHistoryKey.migrateLegacyId(
                parentId = oldParentId,
                apiName = "Stremio",
                showUrl = showUrl,
                season = 2L,
                episode = 8L,
                episodeId = episodeId,
            ),
        )
    }
}
