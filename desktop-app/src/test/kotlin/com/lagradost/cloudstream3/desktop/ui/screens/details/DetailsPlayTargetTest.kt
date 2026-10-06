package com.lagradost.cloudstream3.desktop.ui.screens.details

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.common.storage.WatchHistory
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailsPlayTargetTest {
    private val provider = object : MainAPI() {}

    @Test
    fun `queued next episode advances across season boundary`() {
        val previous = episode("s1e8", season = 1, episode = 8)
        val next = episode("s2e1", season = 2, episode = 1)
        val queuedHistory = history(
            episodeId = previous.data,
            season = 2,
            episode = 1,
            position = 0,
            duration = 0,
        )

        val target = DetailsWatchCoordinator.determineAutoPlayTarget(
            provider = provider,
            resp = response(previous, next),
            watchHistory = mapOf(previous.data to queuedHistory),
            targetEpisodeId = queuedHistory.episodeId,
            targetSeason = queuedHistory.season,
            targetEpisode = queuedHistory.episode,
            playNextEpisode = true,
        )

        assertEquals(next, target)
    }

    @Test
    fun `completed current episode advances to next episode`() {
        val completed = episode("s1e8", season = 1, episode = 8)
        val next = episode("s2e1", season = 2, episode = 1)
        val completedHistory = history(
            episodeId = completed.data,
            season = completed.season,
            episode = completed.episode,
            position = 900,
            duration = 900,
        )

        val target = DetailsWatchCoordinator.determineAutoPlayTarget(
            provider = provider,
            resp = response(completed, next),
            watchHistory = mapOf(completed.data to completedHistory),
            targetEpisodeId = completedHistory.episodeId,
            targetSeason = completedHistory.season,
            targetEpisode = completedHistory.episode,
            playNextEpisode = true,
        )

        assertEquals(next, target)
    }

    @Test
    fun `incomplete current episode remains the resume target`() {
        val current = episode("s1e8", season = 1, episode = 8)
        val next = episode("s2e1", season = 2, episode = 1)
        val progressHistory = history(
            episodeId = current.data,
            season = current.season,
            episode = current.episode,
            position = 300,
            duration = 900,
        )

        val target = DetailsWatchCoordinator.determineAutoPlayTarget(
            provider = provider,
            resp = response(current, next),
            watchHistory = mapOf(current.data to progressHistory),
            targetEpisodeId = progressHistory.episodeId,
            targetSeason = progressHistory.season,
            targetEpisode = progressHistory.episode,
            playNextEpisode = true,
        )

        assertEquals(current, target)
    }

    private fun history(
        episodeId: String,
        season: Int?,
        episode: Int?,
        position: Long,
        duration: Long,
    ) = WatchHistory(
        parentId = "parent",
        showName = "Show",
        showUrl = "show-url",
        apiName = "Provider",
        posterUrl = null,
        episodeThumbnailUrl = null,
        screenshotUrl = null,
        episode = episode,
        season = season,
        episodeId = episodeId,
        position = position,
        duration = duration,
    )

    @Suppress("DEPRECATION_ERROR")
    private fun episode(id: String, season: Int, episode: Int) = Episode(
        data = id,
        name = id,
        season = season,
        episode = episode,
    )

    @Suppress("DEPRECATION_ERROR")
    private fun response(vararg episodes: Episode) = TvSeriesLoadResponse(
        name = "Show",
        url = "show-url",
        apiName = "Provider",
        type = TvType.TvSeries,
        episodes = episodes.toList(),
    )
}
