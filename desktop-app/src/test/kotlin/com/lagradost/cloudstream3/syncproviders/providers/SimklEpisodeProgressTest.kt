package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.SyncAPI
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SimklEpisodeProgressTest {
    @Test
    fun `count increase maps across seasons and excludes specials and unaired episodes`() {
        val catalog = listOf(
            SimklCatalogEpisode(season = 1, episode = 1, type = "episode", aired = true),
            SimklCatalogEpisode(type = "special", aired = true),
            SimklCatalogEpisode(season = 1, episode = 2, type = "episode", aired = true),
            SimklCatalogEpisode(season = 2, episode = 1, type = "episode", aired = true),
            SimklCatalogEpisode(season = 2, episode = 2, type = "episode", aired = false),
        )

        val plan = SimklEpisodeProgress.plan(catalog, previousCount = 1, targetCount = 3, anime = false)

        assertEquals(
            listOf(SimklEpisodeRef(1, 2), SimklEpisodeRef(2, 1)),
            plan?.add,
        )
        assertEquals(emptyList(), plan?.remove)
    }

    @Test
    fun `count decrease removes only the suffix from canonical progress`() {
        val catalog = listOf(
            SimklCatalogEpisode(season = 2, episode = 1, type = "episode"),
            SimklCatalogEpisode(season = 1, episode = 2, type = "episode"),
            SimklCatalogEpisode(season = 1, episode = 1, type = "episode"),
        )

        val plan = SimklEpisodeProgress.plan(catalog, previousCount = 3, targetCount = 1, anime = false)

        assertEquals(listOf(SimklEpisodeRef(1, 2), SimklEpisodeRef(2, 1)), plan?.remove)
        assertEquals(emptyList(), plan?.add)
    }

    @Test
    fun `anime uses sequential catalog numbering and excludes special entries`() {
        val catalog = listOf(
            SimklCatalogEpisode(episode = 2, type = "episode", aired = true),
            SimklCatalogEpisode(type = "special", aired = true),
            SimklCatalogEpisode(episode = 1, type = "episode", aired = true),
        )

        val plan = SimklEpisodeProgress.plan(catalog, previousCount = 0, targetCount = 2, anime = true)

        assertEquals(listOf(SimklEpisodeRef(null, 1), SimklEpisodeRef(null, 2)), plan?.add)
    }

    @Test
    fun `unknown and out of range progress is rejected`() {
        val catalog = listOf(SimklCatalogEpisode(season = 1, episode = 1, type = "episode"))

        assertNull(SimklEpisodeProgress.plan(catalog, previousCount = 2, targetCount = 1, anime = false))
        assertNull(SimklEpisodeProgress.plan(catalog, previousCount = 0, targetCount = 2, anime = false))
        assertNull(SimklEpisodeProgress.plan(catalog, previousCount = -1, targetCount = 0, anime = false))
    }

    @Test
    fun `exact noncontiguous selection computes only the changed episodes`() {
        val current = setOf(SyncAPI.SyncEpisode(1, 1), SyncAPI.SyncEpisode(1, 3))
        val target = setOf(SyncAPI.SyncEpisode(1, 2), SyncAPI.SyncEpisode(1, 3))

        val plan = SimklEpisodeProgress.exactPlan(current, target, anime = false)

        assertEquals(listOf(SimklEpisodeRef(1, 2)), plan?.add)
        assertEquals(listOf(SimklEpisodeRef(1, 1)), plan?.remove)
    }

    @Test
    fun `exact selection accepts aired catalog episodes including TV specials`() {
        val catalog = listOf(
            SimklCatalogEpisode(season = 0, episode = 1, type = "special", aired = true),
            SimklCatalogEpisode(season = 1, episode = 1, type = "episode", aired = true),
            SimklCatalogEpisode(season = 1, episode = 2, type = "episode", aired = false),
        )

        assertEquals(
            setOf(SyncAPI.SyncEpisode(0, 1), SyncAPI.SyncEpisode(1, 1)),
            SimklEpisodeProgress.canonicalSelection(catalog, anime = false),
        )
        kotlin.test.assertTrue(
            SimklEpisodeProgress.selectionIsInCatalog(setOf(SyncAPI.SyncEpisode(0, 1)), catalog, anime = false),
        )
        kotlin.test.assertFalse(
            SimklEpisodeProgress.selectionIsInCatalog(setOf(SyncAPI.SyncEpisode(1, 2)), catalog, anime = false),
        )
        kotlin.test.assertFalse(
            SimklEpisodeProgress.selectionIsInCatalog(setOf(SyncAPI.SyncEpisode(null, 1)), catalog, anime = false),
        )
    }

    @Test
    fun `anime history payload uses sequential episode shorthand`() {
        assertEquals(
            mapOf("anime" to listOf(mapOf("ids" to mapOf("simkl" to 42), "episodes" to listOf(mapOf("number" to 3))))),
            SimklEpisodeProgress.historyBody(42, listOf(SimklEpisodeRef(null, 3)), anime = true),
        )
    }

    @Test
    fun `TV history payload groups exact episodes by season`() {
        assertEquals(
            mapOf(
                "shows" to listOf(
                    mapOf(
                        "ids" to mapOf("simkl" to 42),
                        "seasons" to listOf(
                            mapOf("number" to 0, "episodes" to listOf(mapOf("number" to 1))),
                            mapOf("number" to 2, "episodes" to listOf(mapOf("number" to 4))),
                        ),
                    ),
                ),
            ),
            SimklEpisodeProgress.historyBody(
                42,
                listOf(SimklEpisodeRef(2, 4), SimklEpisodeRef(0, 1)),
                anime = false,
            ),
        )
    }

    @Test
    fun `playback event sends only exact anime episodes with source identifiers`() {
        val identity = SyncAPI.SyncMediaIdentity(
            mediaType = SyncAPI.SyncMediaType.ANIME,
            title = "Example Anime",
            year = 2024,
            externalIds = mapOf("mal" to "123", "anilist" to "456", "ignored" to "unsafe"),
        )

        assertEquals(
            mapOf(
                "anime" to listOf(
                    mapOf(
                        "ids" to mapOf("mal" to "123", "anilist" to "456"),
                        "title" to "Example Anime",
                        "year" to 2024,
                        "episodes" to listOf(
                            mapOf("number" to 3, "watched_at" to "2026-01-02T03:04:05Z"),
                            mapOf("number" to 7),
                        ),
                    ),
                ),
            ),
            SimklEpisodeProgress.historyEventBody(
                identity,
                listOf(
                    SyncAPI.WatchedEpisodeEvent(SyncAPI.SyncEpisode(null, 3), 1_767_323_045_000L),
                    SyncAPI.WatchedEpisodeEvent(SyncAPI.SyncEpisode(null, 7)),
                ),
            ),
        )
    }

    @Test
    fun `TV playback event retains exact season episode coordinates`() {
        val identity = SyncAPI.SyncMediaIdentity(
            mediaType = SyncAPI.SyncMediaType.SHOW,
            title = "Example Show",
            externalIds = mapOf("tmdb" to "42"),
        )

        assertEquals(
            mapOf(
                "shows" to listOf(
                    mapOf(
                        "ids" to mapOf("tmdb" to "42"),
                        "title" to "Example Show",
                        "seasons" to listOf(
                            mapOf("number" to 0, "episodes" to listOf(mapOf("number" to 1))),
                            mapOf("number" to 2, "episodes" to listOf(mapOf("number" to 4))),
                        ),
                    ),
                ),
            ),
            SimklEpisodeProgress.historyEventBody(
                identity,
                listOf(
                    SyncAPI.WatchedEpisodeEvent(SyncAPI.SyncEpisode(2, 4)),
                    SyncAPI.WatchedEpisodeEvent(SyncAPI.SyncEpisode(0, 1)),
                ),
            ),
        )
    }

    @Test
    fun `event payload rejects episode numbering that does not match media type`() {
        val anime = SyncAPI.SyncMediaIdentity(SyncAPI.SyncMediaType.ANIME, "Anime")
        val show = SyncAPI.SyncMediaIdentity(SyncAPI.SyncMediaType.SHOW, "Show")

        assertNull(SimklEpisodeProgress.historyEventBody(anime, listOf(SyncAPI.WatchedEpisodeEvent(SyncAPI.SyncEpisode(1, 2)))))
        assertNull(SimklEpisodeProgress.historyEventBody(show, listOf(SyncAPI.WatchedEpisodeEvent(SyncAPI.SyncEpisode(null, 2)))))
    }
}
