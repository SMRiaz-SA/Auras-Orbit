package com.lagradost.cloudstream3.desktop.ui.screens.details

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.desktop.domain.history.interactor.RemoveWatchHistory
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import com.lagradost.player.impl.PlayerLinkHandler

internal object DetailsWatchCoordinator {

    fun patchEpisodeData(ep: Episode, data: LoadResponse): String {
        val patchedData = ep.data
        if (patchedData.startsWith("{") && patchedData.endsWith("}")) {
            return try {
                val map = mapper.readValue(patchedData, object : TypeReference<MutableMap<String, Any>>() {})
                if (!map.containsKey("title")) {
                    map["title"] = data.name
                }
                if (!map.containsKey("tvtype")) {
                    map["tvtype"] = ""
                }
                mapper.writeValueAsString(map)
            } catch (e: Exception) {
                AppLogger.e("Failed to patch episode data", e)
                patchedData
            }
        }
        return patchedData
    }

    fun buildWatchHistory(providerName: String, ep: Episode, data: LoadResponse): WatchHistory {
        val parentId = DesktopDataStore.watchHistoryId(
            apiName = providerName,
            showUrl = data.url,
        )
        val saved = DesktopDataStore.getEpisodeWatched(parentId, ep.data)
            ?: if (data is MovieLoadResponse && ep.data != data.url) {
                DesktopDataStore.getEpisodeWatched(parentId, data.url)?.also { corrupted ->
                    DesktopDataStore.setLastWatched(
                        corrupted.copy(episodeId = ep.data),
                    )
                    DesktopDataStore.removeEpisodeWatched(parentId, data.url)
                }
            } else {
                null
            }
        val isMovie = data is MovieLoadResponse
        return WatchHistory(
            parentId = parentId,
            showName = data.name,
            showUrl = data.url,
            apiName = providerName,
            posterUrl = data.posterUrl,
            episodeThumbnailUrl = ep.posterUrl ?: data.posterUrl,
            screenshotUrl = saved?.screenshotUrl,
            episode = if (isMovie) null else ep.episode,
            season = if (isMovie) null else ep.season,
            episodeId = ep.data,
            // Keep the persisted position raw; the player launch path applies the resume adjustment once.
            position = saved?.position ?: 0L,
            duration = saved?.duration ?: 0L,
            episodeName = if (isMovie) null else ep.name,
            episodeDescription = ep.description ?: data.plot,
        )
    }

    fun determineAutoPlayTarget(
        provider: MainAPI,
        resp: LoadResponse,
        watchHistory: Map<String, WatchHistory>,
        targetEpisodeId: String? = null,
        targetSeason: Int? = null,
        targetEpisode: Int? = null,
        playNextEpisode: Boolean = false,
    ): Episode? {
        val allEpisodes = when (resp) {
            is TvSeriesLoadResponse -> resp.episodes
            is AnimeLoadResponse -> resp.episodes.values.flatten()
            else -> emptyList()
        }
        val sortedEpisodes = allEpisodes.sortedWith(
            compareBy<Episode> { it.season ?: 1 }
                .thenBy { it.episode ?: 1 },
        )

        val episodeById = targetEpisodeId?.let { id -> sortedEpisodes.find { it.data == id } }
        if (playNextEpisode && episodeById != null) {
            // Synthetic Up Next rows retain the completed episode ID but advance their episode coordinates.
            val isRequestedEpisode = (targetSeason == null || episodeById.season == targetSeason) &&
                (targetEpisode == null || episodeById.episode == targetEpisode)
            val historyForEpisode = watchHistory.values.firstOrNull { it.episodeId == episodeById.data }
            val isCompleted = historyForEpisode != null && historyForEpisode.duration > 0L &&
                PlayerLinkHandler.isCompleted(historyForEpisode.position, historyForEpisode.duration)
            if (!isRequestedEpisode || isCompleted) {
                val currentIndex = sortedEpisodes.indexOf(episodeById)
                val nextEpisode = sortedEpisodes.getOrNull(currentIndex + 1)
                if (nextEpisode != null || !isRequestedEpisode) return nextEpisode
            }
        }

        if (targetEpisode != null) {
            val coordinateMatch = sortedEpisodes.find { episode ->
                episode.episode == targetEpisode &&
                    (targetSeason == null || episode.season == targetSeason)
            }
            if (coordinateMatch != null) return coordinateMatch
        }

        if (episodeById != null) {
            return episodeById
        }

        val latestHistory = watchHistory.values.maxByOrNull { it.updateTime }
        val isLatestCompleted = latestHistory != null && latestHistory.duration > 0 &&
            PlayerLinkHandler.isCompleted(latestHistory.position, latestHistory.duration)

        return if (latestHistory != null && sortedEpisodes.isNotEmpty()) {
            if (isLatestCompleted) {
                val currentIdx = sortedEpisodes.indexOfFirst { it.matchesHistory(latestHistory) }
                if (currentIdx != -1 && currentIdx + 1 < sortedEpisodes.size) {
                    sortedEpisodes[currentIdx + 1]
                } else {
                    sortedEpisodes.find { it.matchesHistory(latestHistory) } ?: sortedEpisodes.firstOrNull()
                }
            } else {
                sortedEpisodes.find { it.matchesHistory(latestHistory) } ?: sortedEpisodes.firstOrNull()
            }
        } else if (sortedEpisodes.isNotEmpty()) {
            sortedEpisodes.firstOrNull()
        } else if (resp is MovieLoadResponse) {
            resp.dataUrl.takeIf { it.isNotBlank() }?.let { dataUrl ->
                provider.newEpisode(dataUrl) {
                    this.name = resp.name
                    this.posterUrl = resp.backgroundPosterUrl ?: resp.posterUrl
                    this.description = resp.plot
                }
            }
        } else if (resp is TorrentLoadResponse) {
            val dataUrl = resp.torrent?.takeIf { it.isNotBlank() }
                ?: resp.magnet?.takeIf { it.isNotBlank() }
            dataUrl?.let { torrentUrl ->
                provider.newEpisode(torrentUrl) {
                    this.name = resp.name
                    this.posterUrl = resp.posterUrl
                    this.description = resp.plot
                }
            }
        } else if (resp is LiveStreamLoadResponse) {
            resp.dataUrl.takeIf { it.isNotBlank() }?.let { dataUrl ->
                provider.newEpisode(dataUrl) {
                    this.name = resp.name
                    this.posterUrl = resp.backgroundPosterUrl ?: resp.posterUrl
                    this.description = resp.plot
                }
            }
        } else {
            null
        }
    }

    suspend fun removeEpisodeWatched(
        providerName: String,
        currentDataUrl: String,
        fallbackUrl: String,
        epData: String,
        removeWatchHistory: RemoveWatchHistory,
        season: Int? = null,
        episode: Int? = null,
        extraEpisodeIds: List<String> = emptyList(),
    ) {
        val currentParentId = DesktopDataStore.watchHistoryId(providerName, currentDataUrl)
        val fallbackParentId = DesktopDataStore.watchHistoryId(providerName, fallbackUrl)

        removeWatchHistory.awaitByEpisode(
            parentId = currentParentId,
            episodeId = epData,
            season = season,
            episode = episode,
            extraEpisodeIds = extraEpisodeIds,
        )
        if (fallbackParentId != currentParentId) {
            removeWatchHistory.awaitByEpisode(
                parentId = fallbackParentId,
                episodeId = epData,
                season = season,
                episode = episode,
                extraEpisodeIds = extraEpisodeIds,
            )
        }
        cleanupOrphanWatchHistory(listOf(currentParentId, fallbackParentId))
    }

    suspend fun queueNextEpisode(providerName: String, data: LoadResponse, ep: Episode, profileId: Int) {
        val allEpisodes = when (data) {
            is TvSeriesLoadResponse -> data.episodes
            is AnimeLoadResponse -> data.episodes.values.flatten()
            else -> emptyList()
        }.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 })
        val currentIndex = allEpisodes.indexOfFirst { it.data == ep.data }.takeIf { it >= 0 }
            ?: allEpisodes.indexOfFirst { it.season == ep.season && it.episode == ep.episode }.takeIf { it >= 0 }
            ?: return
        val parentId = DesktopDataStore.watchHistoryId(providerName, data.url, profileId = profileId)
        val watchedMarks = DesktopDataStore.getEpisodeWatchMarks(
            profileId,
            providerName,
            data.url,
        )
        val nextEpisode = allEpisodes.drop(currentIndex + 1).firstOrNull { candidate ->
            !candidate.data.startsWith("unreleased_") &&
                !parseEpisodeReleaseStatus(candidate, providerName).isUnreleased &&
                watchedMarks.none(candidate::matchesWatchMark) &&
                DesktopDataStore.getEpisodeWatched(parentId, candidate.data)?.let {
                    it.duration <= 0L || !PlayerLinkHandler.isCompleted(it.position, it.duration)
                } != false
        } ?: return

        val existing = DesktopDataStore.getEpisodeWatched(parentId, nextEpisode.data)
        val queued = if (existing == null) {
            WatchHistory(
                parentId = parentId,
                showName = data.name,
                showUrl = data.url,
                apiName = providerName,
                posterUrl = data.posterUrl,
                episodeThumbnailUrl = nextEpisode.posterUrl ?: data.posterUrl,
                screenshotUrl = null,
                episode = nextEpisode.episode,
                season = nextEpisode.season,
                episodeId = nextEpisode.data,
                position = 0,
                duration = 0,
                updateTime = System.currentTimeMillis() + 1000,
                episodeName = nextEpisode.name,
                episodeDescription = nextEpisode.description ?: data.plot,
            )
        } else if (existing.duration <= 0L || !PlayerLinkHandler.isCompleted(existing.position, existing.duration)) {
            existing.copy(
                updateTime = System.currentTimeMillis() + 1000,
                episodeThumbnailUrl = existing.episodeThumbnailUrl ?: nextEpisode.posterUrl ?: data.posterUrl,
                episodeName = existing.episodeName ?: nextEpisode.name,
                episodeDescription = existing.episodeDescription ?: nextEpisode.description ?: data.plot,
            )
        } else {
            return
        }
        DesktopDataStore.setLastWatched(queued, forceNotify = true)
    }

    suspend fun toggleEpisodeWatched(
        providerName: String,
        data: LoadResponse,
        fallbackUrl: String,
        ep: Episode,
        isWatched: Boolean,
        extraEpisodeIds: List<String> = emptyList(),
    ) {
        val currentParentId = DesktopDataStore.watchHistoryId(
            apiName = providerName,
            showUrl = data.url,
        )
        val fallbackParentId = DesktopDataStore.watchHistoryId(
            apiName = providerName,
            showUrl = fallbackUrl,
        )

        if (!isWatched) {
            DesktopDataStore.removeEpisodeWatched(
                parentId = currentParentId,
                episodeId = ep.data,
                season = ep.season,
                episode = ep.episode,
                extraEpisodeIds = extraEpisodeIds,
            )
            if (fallbackParentId != currentParentId) {
                DesktopDataStore.removeEpisodeWatched(
                    parentId = fallbackParentId,
                    episodeId = ep.data,
                    season = ep.season,
                    episode = ep.episode,
                    extraEpisodeIds = extraEpisodeIds,
                )
            }
            cleanupOrphanWatchHistory(listOf(currentParentId, fallbackParentId))
            return
        }

        val saved = DesktopDataStore.getEpisodeWatched(currentParentId, ep.data)
            ?: DesktopDataStore.getEpisodeWatched(fallbackParentId, ep.data)
        val dur = if (saved != null && saved.duration > 0L) saved.duration else 60L
        val isMovie = data is MovieLoadResponse
        val history = WatchHistory(
            parentId = currentParentId,
            showName = data.name,
            showUrl = data.url,
            apiName = providerName,
            posterUrl = data.posterUrl,
            episodeThumbnailUrl = ep.posterUrl,
            screenshotUrl = saved?.screenshotUrl,
            episode = if (isMovie) null else ep.episode,
            season = if (isMovie) null else ep.season,
            episodeId = ep.data,
            position = dur,
            duration = dur,
            episodeName = if (isMovie) null else ep.name,
            episodeDescription = ep.description ?: data.plot,
        )
        DesktopDataStore.setLastWatched(history, forceNotify = true)

        val allEps = when (data) {
            is TvSeriesLoadResponse -> data.episodes
            is AnimeLoadResponse -> data.episodes.values.flatten()
            else -> emptyList()
        }
        val currentIdx = allEps.indexOfFirst { it.data == ep.data }
        if (currentIdx != -1 && currentIdx + 1 < allEps.size) {
            val nextEp = allEps[currentIdx + 1]
            val existingNext = DesktopDataStore.getEpisodeWatched(currentParentId, nextEp.data)
                ?: DesktopDataStore.getEpisodeWatched(fallbackParentId, nextEp.data)
            if (existingNext == null) {
                val nextEpHistory = WatchHistory(
                    parentId = currentParentId,
                    showName = data.name,
                    showUrl = data.url,
                    apiName = providerName,
                    posterUrl = data.posterUrl,
                    episodeThumbnailUrl = nextEp.posterUrl ?: data.posterUrl,
                    screenshotUrl = null,
                    episode = nextEp.episode,
                    season = nextEp.season,
                    episodeId = nextEp.data,
                    position = 0,
                    duration = 0,
                    updateTime = System.currentTimeMillis() + 1000,
                    episodeName = nextEp.name,
                    episodeDescription = nextEp.description ?: data.plot,
                )
                DesktopDataStore.setLastWatched(nextEpHistory, forceNotify = true)
            } else if (existingNext.position < (existingNext.duration * 0.9)) {
                DesktopDataStore.setLastWatched(
                    existingNext.copy(
                        updateTime = System.currentTimeMillis() + 1000,
                        episodeThumbnailUrl = existingNext.episodeThumbnailUrl ?: nextEp.posterUrl ?: data.posterUrl,
                        episodeName = nextEp.name,
                        episodeDescription = nextEp.description ?: data.plot,
                    ),
                    forceNotify = true,
                )
            }
        }
    }

    suspend fun toggleSeasonWatched(
        providerName: String,
        data: LoadResponse,
        fallbackUrl: String,
        episodes: List<Episode>,
        isWatched: Boolean,
        currentWatchHistory: Map<String, WatchHistory>,
        backupSeasonHistory: Map<String, WatchHistory>,
    ): Map<String, WatchHistory> {
        val currentDataUrl = data.url
        val currentParentId = DesktopDataStore.watchHistoryId(providerName, currentDataUrl)
        val fallbackParentId = DesktopDataStore.watchHistoryId(providerName, fallbackUrl)

        if (isWatched) {
            val newBackupMap = mutableMapOf<String, WatchHistory>()
            val historiesToSave = mutableListOf<WatchHistory>()

            episodes.forEach { ep ->
                val hist = currentWatchHistory.values.find { (it.episodeId ?: "") == ep.data }
                if (hist != null) {
                    newBackupMap[ep.data] = hist
                }
                val saved = DesktopDataStore.getEpisodeWatched(currentParentId, ep.data)
                    ?: DesktopDataStore.getEpisodeWatched(fallbackParentId, ep.data)
                val dur = if (saved != null && saved.duration > 0L) saved.duration else 60L
                val isMovie = data is MovieLoadResponse
                historiesToSave.add(
                    WatchHistory(
                        parentId = currentParentId,
                        showName = data.name,
                        showUrl = data.url,
                        apiName = providerName,
                        posterUrl = data.posterUrl,
                        episodeThumbnailUrl = ep.posterUrl,
                        screenshotUrl = saved?.screenshotUrl,
                        episode = if (isMovie) null else ep.episode,
                        season = if (isMovie) null else ep.season,
                        episodeId = ep.data,
                        position = dur,
                        duration = dur,
                        episodeName = if (isMovie) null else ep.name,
                        episodeDescription = ep.description ?: data.plot,
                    ),
                )
            }

            if (episodes.isNotEmpty()) {
                val allEps = when (data) {
                    is TvSeriesLoadResponse -> data.episodes
                    is AnimeLoadResponse -> data.episodes.values.flatten()
                    else -> emptyList()
                }
                val lastWatchedEp = episodes.last()
                val lastIdx = allEps.indexOfFirst { it.data == lastWatchedEp.data }
                if (lastIdx != -1 && lastIdx + 1 < allEps.size) {
                    val nextEp = allEps[lastIdx + 1]
                    val existingNext = DesktopDataStore.getEpisodeWatched(currentParentId, nextEp.data)
                        ?: DesktopDataStore.getEpisodeWatched(fallbackParentId, nextEp.data)
                    if (existingNext == null) {
                        historiesToSave.add(
                            WatchHistory(
                                parentId = currentParentId,
                                showName = data.name,
                                showUrl = data.url,
                                apiName = providerName,
                                posterUrl = data.posterUrl,
                                episodeThumbnailUrl = nextEp.posterUrl ?: data.posterUrl,
                                screenshotUrl = null,
                                episode = nextEp.episode,
                                season = nextEp.season,
                                episodeId = nextEp.data,
                                position = 0,
                                duration = 0,
                                updateTime = System.currentTimeMillis() + 1000,
                                episodeName = nextEp.name,
                                episodeDescription = nextEp.description ?: data.plot,
                            ),
                        )
                    } else if (existingNext.position < (existingNext.duration * 0.9)) {
                        historiesToSave.add(
                            existingNext.copy(
                                updateTime = System.currentTimeMillis() + 1000,
                                episodeThumbnailUrl = existingNext.episodeThumbnailUrl ?: nextEp.posterUrl ?: data.posterUrl,
                                episodeName = nextEp.name,
                                episodeDescription = nextEp.description ?: data.plot,
                            ),
                        )
                    }
                }
            }

            DesktopDataStore.setMultipleLastWatched(historiesToSave)
            return newBackupMap
        } else {
            val historiesToRestore = mutableListOf<WatchHistory>()
            val episodesToRemove = mutableListOf<String>()

            episodes.forEach { ep ->
                val backup = backupSeasonHistory[ep.data]
                if (backup != null) {
                    val dur = if (backup.duration > 0L) backup.duration else 60L
                    val isMovie = data is MovieLoadResponse
                    historiesToRestore.add(
                        WatchHistory(
                            parentId = currentParentId,
                            showName = data.name,
                            showUrl = data.url,
                            apiName = providerName,
                            posterUrl = data.posterUrl,
                            episodeThumbnailUrl = ep.posterUrl,
                            screenshotUrl = backup.screenshotUrl,
                            episode = if (isMovie) null else ep.episode,
                            season = if (isMovie) null else ep.season,
                            episodeId = ep.data,
                            position = backup.position,
                            duration = dur,
                            episodeName = backup.episodeName ?: if (isMovie) null else ep.name,
                            episodeDescription = backup.episodeDescription ?: ep.description ?: data.plot,
                        ),
                    )
                } else {
                    val matchedHist = currentWatchHistory.values.find { ep.matchesHistory(it) || it.episodeId == ep.data }
                    val matchedEpId = matchedHist?.episodeId
                    if (!matchedEpId.isNullOrBlank()) {
                        episodesToRemove.add(matchedEpId)
                    }
                    episodesToRemove.add(ep.data)
                }
            }
            if (historiesToRestore.isNotEmpty()) {
                DesktopDataStore.setMultipleLastWatched(historiesToRestore)
            }
            if (episodesToRemove.isNotEmpty()) {
                DesktopDataStore.removeMultipleEpisodesWatched(currentParentId, episodesToRemove)
                if (fallbackParentId != currentParentId) {
                    DesktopDataStore.removeMultipleEpisodesWatched(fallbackParentId, episodesToRemove)
                }
                cleanupOrphanWatchHistory(listOf(currentParentId, fallbackParentId))
            }
            return emptyMap()
        }
    }

    private fun cleanupOrphanWatchHistory(parentIds: List<String>) {
        parentIds.distinct().forEach { pid ->
            val remaining = DesktopDataStore.getWatchHistoryByParent(pid)
            if (remaining.isNotEmpty()) {
                val hasActualProgress = remaining.any {
                    it.position > 0L || (it.duration > 0L && PlayerLinkHandler.isCompleted(it.position, it.duration))
                }
                if (!hasActualProgress) {
                    DesktopDataStore.removeWatchHistory(pid)
                }
            }
        }
    }
}
