package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.desktop.domain.history.interactor.UpsertWatchHistory
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.TrackerAccountAccess
import com.lagradost.cloudstream3.syncproviders.TrackerAuthResult
import com.lagradost.cloudstream3.syncproviders.TrackerSyncHealth
import com.lagradost.cloudstream3.syncproviders.TrackerSyncOutcome
import com.lagradost.cloudstream3.syncproviders.TrackerSyncPreferences
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Pushes the local watched-episode set after playback; local history remains authoritative. */
internal object TrackerPlaybackSyncCoordinator {
    private const val TAG = "TrackerPlaybackSync"
    private var lastRequest: RetryRequest? = null

    private data class RetryRequest(
        val response: LoadResponse,
        val parentId: String,
    )

    suspend fun retryLast(): Boolean {
        val request = lastRequest ?: return false
        syncWatchedHistory(request.response, request.parentId)
        return true
    }

    suspend fun syncWatchedHistory(
        response: LoadResponse?,
        parentId: String,
        recentlySaved: WatchHistory? = null,
    ) = withContext(Dispatchers.IO) {
        if (response != null) lastRequest = RetryRequest(response, parentId)
        val media = response?.let(TrackerPlaybackMapper::mediaIdentity) ?: return@withContext
        val local = DesktopDataStore.getWatchHistoryByParent(parentId).toMutableList()
        if (recentlySaved != null && local.none { it.episodeId == recentlySaved.episodeId }) {
            local += recentlySaved
        }
        val watched = local.filter { UpsertWatchHistory.isWatched(it.position, it.duration) }
        if (watched.isEmpty()) return@withContext

        val mappedEvents = watched.mapNotNull { history ->
            val coordinate = TrackerPlaybackMapper.episodeCoordinate(history, response)
            if (coordinate == null) {
                AppLogger.w(TAG, "Omitted one episode from tracker sync for '${history.showName}': its coordinates are ambiguous.")
                null
            } else {
                SyncAPI.WatchedEpisodeEvent(coordinate, history.updateTime.takeIf { it > 0 })
            }
        }.distinctBy { it.episode }

        val trackers = listOf(AccountManager.simklApi, AccountManager.malApi, AccountManager.aniListApi)
        trackers.forEach { api ->
            if (!TrackerSyncPreferences.isAutomaticSyncEnabled(api.idPrefix)) {
                TrackerSyncHealth.record(api.idPrefix, TrackerSyncOutcome.SKIPPED, "Automatic playback sync is disabled")
                return@forEach
            }
            val savedAccount = AccountManager.accounts(api.idPrefix).firstOrNull() ?: return@forEach
            try {
                val auth = when (val authResult = TrackerAccountAccess.current(api, savedAccount)) {
                    is TrackerAuthResult.Ready -> authResult.account
                    TrackerAuthResult.Missing -> return@forEach
                    TrackerAuthResult.ReauthorizationRequired -> {
                        TrackerSyncHealth.record(api.idPrefix, TrackerSyncOutcome.SIGN_IN_REQUIRED, "Reconnect ${api.name} to resume sync")
                        AppLogger.w(TAG, "Skipped ${api.name} playback sync: its saved sign-in needs attention.")
                        return@forEach
                    }
                }
                val outcome = if (api.supportsWatchedEpisodeEvents) {
                    if (mappedEvents.isEmpty()) {
                        TrackerSyncOutcome.SKIPPED to "No unambiguous episode coordinates were available"
                    } else {
                        val ok = api.recordWatchedEpisodes(auth, media, mappedEvents)
                        if (ok) {
                            TrackerSyncOutcome.SYNCED to "Playback history accepted"
                        } else {
                            TrackerSyncOutcome.PROVIDER_REJECTED to "Provider rejected playback history"
                        }
                    }
                } else {
                    syncCountBased(api, auth, media, response, watched)
                }
                TrackerSyncHealth.record(api.idPrefix, outcome.first, outcome.second)
                if (outcome.first != TrackerSyncOutcome.SYNCED) {
                    AppLogger.w(TAG, "${api.name} playback sync: ${outcome.second}.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                TrackerSyncHealth.record(api.idPrefix, TrackerSyncOutcome.RETRYABLE, error.message ?: "Temporary provider error")
                AppLogger.w(TAG, "${api.name} playback sync failed for '${media.title}': ${error.message}")
            }
        }
    }

    private suspend fun syncCountBased(
        api: SyncAPI,
        auth: AuthData,
        media: SyncAPI.SyncMediaIdentity,
        response: LoadResponse,
        watched: List<WatchHistory>,
    ): Pair<TrackerSyncOutcome, String> {
        val anime = response as? AnimeLoadResponse
            ?: return TrackerSyncOutcome.SKIPPED to "Count-only progress is supported for anime only"
        if (media.mediaType != SyncAPI.SyncMediaType.ANIME) {
            return TrackerSyncOutcome.SKIPPED to "Provider supports count progress for anime only"
        }
        val trackerId = resolveTrackerId(api, auth, media, anime)
            ?: return TrackerSyncOutcome.SKIPPED to "No unambiguous tracker title match"
        val localIndexes = TrackerPlaybackMapper.watchedEpisodeIndexes(watched, anime) ?: run {
            return TrackerSyncOutcome.SKIPPED to "Local episode numbering is incomplete"
        }
        val current = api.status(auth, trackerId)
            ?: return TrackerSyncOutcome.RETRYABLE to "Could not read current tracker progress"
        val oldCount = current.watchedEpisodes
            ?: return TrackerSyncOutcome.SKIPPED to "Tracker did not expose a progress count"
        val targetCount = TrackerPlaybackMapper.safeSequentialTarget(localIndexes, oldCount) ?: run {
            return TrackerSyncOutcome.SKIPPED to "Count-only provider cannot represent non-contiguous progress"
        }
        if (targetCount == oldCount) return TrackerSyncOutcome.SYNCED to "Already up to date"

        val status = current.status.takeUnless { it == SyncWatchType.NONE } ?: SyncWatchType.WATCHING
        val updated = SyncAPI.SyncStatus(
            status = status,
            score = current.score,
            watchedEpisodes = targetCount,
            isFavorite = current.isFavorite,
            maxEpisodes = current.maxEpisodes,
        )
        if (!api.updateStatus(auth, trackerId, updated)) {
            return TrackerSyncOutcome.PROVIDER_REJECTED to "Provider rejected playback progress"
        }
        return TrackerSyncOutcome.SYNCED to "Playback progress accepted"
    }

    private suspend fun resolveTrackerId(
        api: SyncAPI,
        auth: AuthData,
        media: SyncAPI.SyncMediaIdentity,
        response: AnimeLoadResponse,
    ): String? {
        media.externalIds[api.idPrefix]?.takeIf(String::isNotBlank)?.let { return it }

        val titles = buildList {
            add(response.name)
            response.engName?.let(::add)
            response.japName?.let(::add)
            response.synonyms.orEmpty().take(5).forEach(::add)
        }.filter(String::isNotBlank).distinctBy(::normalizeTitle)
        val expected = titles.map(::normalizeTitle).filter(String::isNotBlank).toSet()
        if (expected.isEmpty()) return null

        val candidates = linkedMapOf<String, SyncAPI.SyncSearchResult>()
        for (query in titles) {
            val results = api.search(auth, query) ?: continue
            results.forEach { result ->
                if ((result.alternativeNames + result.name).none { normalizeTitle(it) in expected }) return@forEach
                if (result.type != null && result.type != com.lagradost.cloudstream3.TvType.Anime) return@forEach
                if (media.year != null && result.year != null && media.year != result.year) return@forEach
                candidates.putIfAbsent(result.syncId, result)
            }
        }
        return candidates.values.singleOrNull()?.syncId
    }

    private fun normalizeTitle(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
