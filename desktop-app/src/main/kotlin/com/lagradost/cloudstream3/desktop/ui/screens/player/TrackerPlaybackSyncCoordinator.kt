package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.desktop.domain.history.interactor.UpsertWatchHistory
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
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

    suspend fun syncWatchedHistory(
        response: LoadResponse?,
        parentId: String,
        recentlySaved: WatchHistory? = null,
    ) = withContext(Dispatchers.IO) {
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
            val savedAccount = AccountManager.accounts(api.idPrefix).firstOrNull() ?: return@forEach
            try {
                val auth = currentAuth(api, savedAccount) ?: run {
                    AppLogger.w(TAG, "Skipped ${api.name} playback sync: its saved sign-in needs attention.")
                    return@forEach
                }
                if (api.supportsWatchedEpisodeEvents) {
                    if (mappedEvents.isEmpty()) return@forEach
                    val ok = api.recordWatchedEpisodes(auth, media, mappedEvents)
                    if (!ok) AppLogger.w(TAG, "${api.name} did not accept exact playback history for '${media.title}'.")
                } else {
                    syncCountBased(api, auth, media, response, watched)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
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
    ) {
        val anime = response as? AnimeLoadResponse ?: return
        if (media.mediaType != SyncAPI.SyncMediaType.ANIME) return
        val trackerId = resolveTrackerId(api, auth, media, anime) ?: run {
            AppLogger.w(TAG, "Could not unambiguously match '${media.title}' to ${api.name}; no progress was changed.")
            return
        }
        val localIndexes = TrackerPlaybackMapper.watchedEpisodeIndexes(watched, anime) ?: run {
            AppLogger.w(TAG, "Skipped ${api.name} progress for '${media.title}': local episode numbering is incomplete.")
            return
        }
        val current = api.status(auth, trackerId) ?: return
        val oldCount = current.watchedEpisodes ?: return
        val targetCount = TrackerPlaybackMapper.safeSequentialTarget(localIndexes, oldCount) ?: run {
            AppLogger.w(TAG, "Skipped ${api.name} progress for '${media.title}': its count-only API cannot represent this non-contiguous episode set.")
            return
        }
        if (targetCount == oldCount) return

        val status = current.status.takeUnless { it == SyncWatchType.NONE } ?: SyncWatchType.WATCHING
        val updated = SyncAPI.SyncStatus(
            status = status,
            score = current.score,
            watchedEpisodes = targetCount,
            isFavorite = current.isFavorite,
            maxEpisodes = current.maxEpisodes,
        )
        if (!api.updateStatus(auth, trackerId, updated)) {
            AppLogger.w(TAG, "${api.name} rejected playback progress for '${media.title}'.")
        }
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

    private suspend fun currentAuth(api: SyncAPI, saved: AuthData): AuthData? {
        if (!saved.token.isAccessTokenExpired()) return saved
        if (saved.token.isRefreshTokenExpired()) return null
        val refreshed = api.refreshToken(saved.token) ?: return null
        val updated = saved.copy(token = refreshed)
        val accounts = AccountManager.accounts(api.idPrefix)
        AccountManager.updateAccounts(
            api.idPrefix,
            accounts.map { account -> if (account.user.id == saved.user.id) updated else account }.toTypedArray(),
        )
        return updated
    }

    private fun normalizeTitle(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

}
