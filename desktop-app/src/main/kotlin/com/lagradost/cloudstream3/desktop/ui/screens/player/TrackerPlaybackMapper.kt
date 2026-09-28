package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.desktop.domain.history.interactor.UpsertWatchHistory
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.common.storage.WatchHistory

/** Converts the app's persisted episode coordinates to tracker coordinates without guessing. */
internal object TrackerPlaybackMapper {
    fun mediaIdentity(response: LoadResponse): SyncAPI.SyncMediaIdentity? {
        val mediaType = when (response) {
            is AnimeLoadResponse -> SyncAPI.SyncMediaType.ANIME
            is TvSeriesLoadResponse -> SyncAPI.SyncMediaType.SHOW
            else -> return null
        }

        val malPrefix = LoadResponse.malIdPrefix.ifBlank { "mal" }
        val aniListPrefix = LoadResponse.aniListIdPrefix.ifBlank { "anilist" }
        val simklPrefix = LoadResponse.simklIdPrefix.ifBlank { "simkl" }
        val ids = linkedMapOf<String, String>()
        response.syncData[malPrefix]?.takeIf(String::isNotBlank)?.let { ids["mal"] = it }
        response.syncData[aniListPrefix]?.takeIf(String::isNotBlank)?.let { ids["anilist"] = it }
        response.syncData["imdb"]?.takeIf(String::isNotBlank)?.let { ids["imdb"] = it }
        response.syncData["tmdb"]?.takeIf(String::isNotBlank)?.let { ids["tmdb"] = it }

        val simklValue = response.syncData[simklPrefix]
        val simklIds = LoadResponse.readIdFromString(simklValue)
        simklIds.forEach { (service, id) -> ids[service.originalName] = id }
        simklValue?.toIntOrNull()?.let { ids["simkl"] = it.toString() }

        return SyncAPI.SyncMediaIdentity(
            mediaType = mediaType,
            title = response.name,
            year = response.year,
            externalIds = ids,
        )
    }

    fun episodeCoordinate(history: WatchHistory, response: LoadResponse): SyncAPI.SyncEpisode? {
        val number = history.episode?.takeIf { it > 0 } ?: return null
        return when (response) {
            is AnimeLoadResponse -> animeEpisodeNumber(history, response)?.let { SyncAPI.SyncEpisode(null, it) }
            is TvSeriesLoadResponse -> {
                val season = history.season?.takeIf { it >= 0 } ?: return null
                SyncAPI.SyncEpisode(season, number)
            }
            else -> null
        }
    }

    fun animeEpisodeNumber(history: WatchHistory, response: AnimeLoadResponse): Int? {
        val historyNumber = history.episode?.takeIf { it > 0 } ?: return null
        val episodes = response.episodes.values.flatten().distinctBy(Episode::data)
        val matched = history.episodeId?.let { id -> episodes.singleOrNull { it.data == id } }
        val number = matched?.episode?.takeIf { it > 0 } ?: historyNumber
        val rawSeason = matched?.season ?: history.season

        // Season zero contains specials and cannot safely be flattened into anime episode progress.
        if (rawSeason == 0) return null
        val displaySeasonBySourceSeason = response.seasonNames.orEmpty()
            .associate { it.season to (it.displaySeason ?: it.season) }
        fun displaySeason(episode: Episode): Int =
            episode.season?.let { displaySeasonBySourceSeason[it] ?: it } ?: 1

        val targetSeason = rawSeason?.let { displaySeasonBySourceSeason[it] ?: it } ?: 1
        if (targetSeason <= 1) return number
        val targetExists = matched != null && matched.episode == number
        if (!targetExists) return null

        val priorSeasonCounts = (1 until targetSeason).map { wantedSeason ->
            val priorNumbers = episodes.asSequence()
                .filter { displaySeason(it) == wantedSeason }
                .mapNotNull { it.episode?.takeIf { value -> value > 0 } }
                .toSet()
            // Flatten only complete, sequential preceding seasons; partial catalogs are ambiguous.
            if (priorNumbers.isEmpty() || priorNumbers != (1..priorNumbers.max()).toSet()) return null
            priorNumbers.size
        }
        val priorCount = priorSeasonCounts.sum()
        val targetNumbers = episodes.asSequence()
            .filter { displaySeason(it) == targetSeason }
            .mapNotNull { it.episode?.takeIf { value -> value > 0 } }
            .toSet()
        if (number !in targetNumbers || targetNumbers.isEmpty()) return null

        val localSeasonNumbering = targetNumbers == (1..targetNumbers.max()).toSet()
        val globalSequentialNumbering = targetNumbers == ((priorCount + 1)..targetNumbers.max()).toSet()
        return when {
            localSeasonNumbering -> priorCount + number
            globalSequentialNumbering -> number
            else -> null
        }
    }

    fun watchedEpisodeIndexes(
        history: List<WatchHistory>,
        response: AnimeLoadResponse,
    ): Set<Int>? {
        val watched = history.filter { UpsertWatchHistory.isWatched(it.position, it.duration) }
        if (watched.isEmpty()) return emptySet()
        val indexes = watched.map { animeEpisodeNumber(it, response) ?: return null }.toSet()
        return indexes
    }

    /** MAL/AniList accept a count, so only update when merging local episodes with remote count is a prefix. */
    fun safeSequentialTarget(localEpisodes: Set<Int>, remoteCount: Int): Int? {
        if (remoteCount < 0 || localEpisodes.any { it <= 0 }) return null
        val target = maxOf(remoteCount, localEpisodes.maxOrNull() ?: 0)
        if ((remoteCount + 1..target).any { it !in localEpisodes }) return null
        return target
    }
}
