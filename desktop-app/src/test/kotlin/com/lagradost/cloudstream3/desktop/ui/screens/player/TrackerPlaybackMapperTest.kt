package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackerPlaybackMapperTest {
    private val api = object : MainAPI() {
        override var name = "TrackerMapperTest"
        override var mainUrl = "https://test.example.com"
    }

    @Test
    fun `safe count update only advances over a contiguous prefix`() {
        assertEquals(3, TrackerPlaybackMapper.safeSequentialTarget(setOf(1, 2, 3), 0))
        assertEquals(3, TrackerPlaybackMapper.safeSequentialTarget(setOf(1, 3), 2))
        assertEquals(5, TrackerPlaybackMapper.safeSequentialTarget(setOf(1, 2), 5))
        assertNull(TrackerPlaybackMapper.safeSequentialTarget(setOf(1, 3), 0))
        assertNull(TrackerPlaybackMapper.safeSequentialTarget(setOf(1, 4), 2))
        assertNull(TrackerPlaybackMapper.safeSequentialTarget(setOf(0, 1), 0))
    }

    @Test
    fun `anime later-season episode maps to sequential tracker episode`() = runBlocking {
        val firstSeason = listOf(1, 2).map { episode("s1e$it", season = 1, number = it) }
        val secondSeasonEpisode = episode("s2e1", season = 2, number = 1)
        val response = api.newAnimeLoadResponse("Series", "https://test.example.com/series", TvType.Anime) {
            episodes[DubStatus.Subbed] = firstSeason + secondSeasonEpisode
        }

        assertEquals(
            3,
            TrackerPlaybackMapper.animeEpisodeNumber(history(secondSeasonEpisode.data, 1, 2), response),
        )
    }

    @Test
    fun `anime already-global numbering is not offset a second time`() = runBlocking {
        val episodes = listOf(1, 2).map { episode("s1e$it", season = 1, number = it) } +
            listOf(3, 4).map { episode("s2e$it", season = 2, number = it) }
        val response = api.newAnimeLoadResponse("Series", "https://test.example.com/series", TvType.Anime) {
            this.episodes[DubStatus.Subbed] = episodes
        }

        assertEquals(3, TrackerPlaybackMapper.animeEpisodeNumber(history(episodes[2].data, 3, 2), response))
    }

    @Test
    fun `anime numbering refuses incomplete earlier-season catalogs`() = runBlocking {
        val episodes = listOf(
            episode("s1e1", season = 1, number = 1),
            episode("s1e3", season = 1, number = 3),
            episode("s2e1", season = 2, number = 1),
        )
        val response = api.newAnimeLoadResponse("Series", "https://test.example.com/series", TvType.Anime) {
            this.episodes[DubStatus.Subbed] = episodes
        }

        assertNull(TrackerPlaybackMapper.animeEpisodeNumber(history("s2e1", 1, 2), response))
    }

    private fun episode(id: String, season: Int, number: Int) = api.newEpisode("https://test.example.com/$id") {
        name = "Episode $number"
        this.season = season
        episode = number
    }

    private fun history(id: String, number: Int, season: Int) = WatchHistory(
        parentId = "series",
        showName = "Series",
        showUrl = "https://test.example.com/series",
        apiName = api.name,
        posterUrl = null,
        episodeThumbnailUrl = null,
        screenshotUrl = null,
        episode = number,
        season = season,
        episodeId = id,
        position = 900,
        duration = 1000,
    )
}
