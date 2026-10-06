package com.lagradost.cloudstream3.desktop.ui.screens.details

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.metadata.MetadataConfig
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.EpisodeReleaseRecord
import com.lagradost.common.storage.FollowedShow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.time.LocalDate

data class UpcomingEpisode(
    val id: String,
    val airDate: LocalDate,
    val showName: String,
    val showUrl: String,
    val providerName: String,
    val posterUrl: String?,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeName: String?,
    val overview: String?,
    val stillUrl: String?,
)

object TmdbEpisodeCalendarRepository {
    private const val STALE_AFTER_MS = 24L * 60L * 60L * 1000L
    private const val CALENDAR_HORIZON_DAYS = 90L

    fun getUpcoming(profileId: Int, from: LocalDate = LocalDate.now()): List<UpcomingEpisode> {
        val followed = DesktopDataStore.getFollowedShows(profileId)
        if (followed.isEmpty()) return emptyList()

        val followedByTmdb = followed.filter { (it.tmdbId ?: 0) > 0 }
            .groupBy { it.tmdbId!! }
        if (followedByTmdb.isEmpty()) return emptyList()

        val until = from.plusDays(CALENDAR_HORIZON_DAYS)
        return DesktopDataStore.getAllEpisodeReleases()
            .asSequence()
            .filter { it.tmdbId in followedByTmdb }
            .mapNotNull { release ->
                val date = runCatching { LocalDate.parse(release.airDate) }.getOrNull() ?: return@mapNotNull null
                if (date.isBefore(from) || date.isAfter(until)) return@mapNotNull null
                val show = followedByTmdb[release.tmdbId].orEmpty().firstOrNull() ?: return@mapNotNull null
                UpcomingEpisode(
                    id = "${release.tmdbId}:${release.seasonNumber}:${release.episodeNumber}",
                    airDate = date,
                    showName = show.showName,
                    showUrl = show.showUrl,
                    providerName = show.providerName,
                    posterUrl = show.posterUrl,
                    seasonNumber = release.seasonNumber,
                    episodeNumber = release.episodeNumber,
                    episodeName = release.episodeName,
                    overview = release.overview,
                    stillUrl = release.stillUrl,
                )
            }
            .distinctBy(UpcomingEpisode::id)
            .sortedWith(compareBy(UpcomingEpisode::airDate, UpcomingEpisode::showName, UpcomingEpisode::seasonNumber, UpcomingEpisode::episodeNumber))
            .toList()
    }

    suspend fun resolveTmdbId(loaded: LoadResponse): Int? = withContext(Dispatchers.IO) {
        loaded.syncData["tmdb"]?.toIntOrNull()?.takeIf { it > 0 }?.let { return@withContext it }
        if (!MetadataConfig.tmdbEnabled.value) return@withContext null

        val isAnime = loaded is AnimeLoadResponse || loaded.type == TvType.Anime || loaded.type == TvType.OVA
        val isTv = loaded.type in setOf(TvType.TvSeries, TvType.AsianDrama, TvType.Cartoon, TvType.Anime, TvType.OVA)
        if (!isTv) return@withContext null

        TmdbRateLimiter.acquire()
        TmdbMatchResolver.resolve(
            loaded = loaded,
            cleanName = loaded.name,
            isAnime = isAnime,
            isTv = true,
            tempYear = loaded.year,
            directTmdbId = null,
            directImdbId = null,
            apiKey = TmdbEnrichmentService.TMDB_API_KEY,
        )?.takeIf { !it.isMovie }?.id
    }

    suspend fun refreshProfile(profileId: Int, force: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!MetadataConfig.tmdbEnabled.value) return@withContext "TMDB metadata is disabled"

        var firstError: String? = null
        val now = System.currentTimeMillis()
        DesktopDataStore.getFollowedShows(profileId).forEach { followed ->
            if (!force && followed.lastRefreshAt > 0 && now - followed.lastRefreshAt < STALE_AFTER_MS) return@forEach
            try {
                refreshShow(followed, now)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = failure.message?.take(240) ?: "Could not refresh release dates"
                DesktopDataStore.updateFollowedShowRefresh(
                    profileId = followed.profileId,
                    providerName = followed.providerName,
                    showUrl = followed.showUrl,
                    refreshedAt = now,
                    error = message,
                )
                if (firstError == null) firstError = message
            }
        }
        firstError
    }

    suspend fun refreshShow(followed: FollowedShow, refreshedAt: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val tmdbId = followed.tmdbId?.takeIf { it > 0 }
            ?: throw IllegalStateException("No confident TMDB match is saved for this show")
        val language = URLEncoder.encode(MetadataConfig.tmdbLanguage.value.ifBlank { "en-US" }, "UTF-8")
        val url = "https://api.themoviedb.org/3/tv/$tmdbId?api_key=${TmdbEnrichmentService.TMDB_API_KEY}&language=$language"
        TmdbRateLimiter.acquire()
        val showData = app.get(url, timeout = 9000L).parsedSafe<JsonNode>()
            ?: throw IllegalStateException("TMDB returned no show schedule")

        val seasonNodes = showData.get("seasons")?.takeIf { it.isArray }
            ?: throw IllegalStateException("TMDB did not return seasons")
        val today = LocalDate.now()
        val seasons = seasonNodes.mapNotNull { node ->
            val seasonNumber = node.get("season_number")?.asInt() ?: return@mapNotNull null
            val airDate = node.get("air_date")?.asText()?.takeIf { it.isNotBlank() && it != "null" }
            val parsedDate = airDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            Triple(seasonNumber, airDate, parsedDate)
        }
        val regular = seasons.filter { it.first > 0 }
        val latestSeasonNumber = regular.maxOfOrNull { it.first }
        val candidates = (
            listOfNotNull(latestSeasonNumber) + seasons
                .filter { (_, _, date) -> date != null && !date.isBefore(today.minusDays(180)) }
                .map { it.first }
                .sortedDescending()
            )
            .distinct()
            .take(6)
        if (candidates.isEmpty()) throw IllegalStateException("TMDB has no seasons to refresh")

        candidates.forEach { seasonNumber ->
            val seasonUrl = "https://api.themoviedb.org/3/tv/$tmdbId/season/$seasonNumber?api_key=${TmdbEnrichmentService.TMDB_API_KEY}&language=$language"
            TmdbRateLimiter.acquire()
            val seasonData = app.get(seasonUrl, timeout = 9000L).parsedSafe<JsonNode>()
                ?: throw IllegalStateException("TMDB returned no data for season $seasonNumber")
            val episodes = seasonData.get("episodes")?.takeIf { it.isArray }
                ?: throw IllegalStateException("TMDB did not return episodes for season $seasonNumber")
            val records = episodes.mapNotNull { episode ->
                val number = episode.get("episode_number")?.asInt() ?: return@mapNotNull null
                val airDate = episode.get("air_date")?.asText()?.takeIf { it.isNotBlank() && it != "null" }
                    ?: return@mapNotNull null
                val validDate = runCatching { LocalDate.parse(airDate.take(10)) }.getOrNull() ?: return@mapNotNull null
                EpisodeReleaseRecord(
                    tmdbId = tmdbId,
                    seasonNumber = seasonNumber,
                    episodeNumber = number,
                    airDate = validDate.toString(),
                    episodeName = episode.get("name")?.asText()?.takeIf { it.isNotBlank() && it != "null" },
                    overview = episode.get("overview")?.asText()?.takeIf { it.isNotBlank() && it != "null" },
                    stillUrl = TmdbEnrichmentService.tmdbImageUrl(episode.get("still_path")?.asText(), "w500"),
                    fetchedAt = refreshedAt,
                )
            }
            DesktopDataStore.replaceEpisodeReleaseSeason(tmdbId, seasonNumber, records, refreshedAt)
        }

        DesktopDataStore.updateFollowedShowRefresh(
            profileId = followed.profileId,
            providerName = followed.providerName,
            showUrl = followed.showUrl,
            refreshedAt = refreshedAt,
            error = null,
            resolvedTmdbId = tmdbId,
        )
    }
}
