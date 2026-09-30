package com.lagradost.cloudstream3.desktop.metadata.providers

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.metadata.MetadataConfig
import com.lagradost.cloudstream3.desktop.metadata.MetadataEnrichmentCallbacks
import com.lagradost.cloudstream3.desktop.metadata.MetadataEnrichmentContext
import com.lagradost.cloudstream3.desktop.metadata.MetadataMatch
import com.lagradost.cloudstream3.desktop.metadata.MetadataProvider
import com.lagradost.cloudstream3.desktop.ui.screens.details.TmdbEnrichmentService
import com.lagradost.cloudstream3.desktop.utils.TitleUtils
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

object TmdbMetadataProvider : MetadataProvider {
    private const val TAG = "TmdbProvider"

    override val id: String = "tmdb"
    override val displayName: String = "The Movie Database (TMDB)"
    override val priority: Int = 2
    override val supportedTypes: Set<TvType> = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Cartoon,
        TvType.Documentary,
        TvType.AsianDrama,
    )

    override suspend fun resolve(
        title: String,
        year: Int?,
        type: TvType,
        rawUrl: String?,
    ): MetadataMatch? {
        // TMDB search results are shown in the explicit network-stream metadata picker.
        // Keep automatic identity resolution unchanged until it has a confidence policy.
        return null
    }

    suspend fun searchNetworkStream(title: String, type: TvType): List<MetadataMatch> {
        if (!MetadataConfig.isProviderEnabled(id)) return emptyList()
        val isMovie = type == TvType.Movie || type == TvType.AnimeMovie
        val endpoint = if (isMovie) "movie" else "tv"
        val (cleanTitle, _) = TitleUtils.cleanProviderTitle(title)
        if (cleanTitle.isBlank()) return emptyList()

        return try {
            withContext(Dispatchers.IO) {
                val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
                val language = URLEncoder.encode(MetadataConfig.tmdbLanguage.value, "UTF-8")
                val includeAdult = MetadataConfig.tmdbIncludeAdult.value
                val requestUrl = "https://api.themoviedb.org/3/search/$endpoint?api_key=${TmdbEnrichmentService.TMDB_API_KEY}&query=$encodedTitle&page=1&language=$language&include_adult=$includeAdult"
                com.lagradost.cloudstream3.desktop.ui.screens.details.TmdbRateLimiter.acquire()
                val response = app.get(requestUrl, timeout = 7000L).parsedSafe<JsonNode>()
                val results = response?.get("results")?.takeIf { it.isArray } ?: return@withContext emptyList()
                results.mapNotNull { result ->
                    val candidateTitle = result.get(if (isMovie) "title" else "name")?.asText()
                        ?: result.get(if (isMovie) "original_title" else "original_name")?.asText()
                        ?: return@mapNotNull null
                    val releaseField = if (isMovie) "release_date" else "first_air_date"
                    val candidateYear = result.get(releaseField)?.asText()?.take(4)?.toIntOrNull()
                    MetadataMatch(
                        providerId = id,
                        matchedTitle = candidateTitle,
                        matchedYear = candidateYear,
                        tmdbId = result.get("id")?.asInt()?.takeIf { it > 0 },
                        posterUrl = TmdbEnrichmentService.tmdbImageUrl(result.get("poster_path")?.asText(), "w500"),
                        backdropUrl = TmdbEnrichmentService.tmdbImageUrl(result.get("backdrop_path")?.asText(), "w780"),
                        description = result.get("overview")?.asText()?.takeIf { it.isNotBlank() },
                        rating = result.get("vote_average")?.asDouble(),
                        rawData = result,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "TMDB search failed for '$cleanTitle'", e)
            emptyList()
        }
    }

    override suspend fun enrich(
        loaded: LoadResponse,
        match: MetadataMatch?,
        context: MetadataEnrichmentContext,
        callbacks: MetadataEnrichmentCallbacks,
    ): Boolean {
        val directTmdbId = match?.tmdbId ?: context.directTmdbId
        val directImdbId = match?.imdbId ?: context.directImdbId

        return try {
            AppLogger.i(TAG, "Enriching via TMDB | directTmdbId=$directTmdbId | directImdbId=$directImdbId")
            TmdbEnrichmentService.enrich(
                loaded = loaded,
                url = context.rawUrl,
                fetchCast = context.fetchCast,
                onLogoLoaded = callbacks.onLogoLoaded,
                onBackdropLoaded = callbacks.onBackdropLoaded,
                onScreenshotsLoaded = callbacks.onScreenshotsLoaded,
                onActorsLoaded = callbacks.onActorsLoaded,
                onTrailersLoaded = callbacks.onTrailersLoaded,
                onReviewsLoaded = callbacks.onReviewsLoaded,
                onRatingsLoaded = callbacks.onRatingsLoaded,
                onMetadataLoaded = callbacks.onMetadataLoaded,
                onEnrichmentComplete = {},
                directTmdbId = directTmdbId,
                directImdbId = directImdbId,
                overwrite = context.overwrite,
                onEpisodeThumbnailsEnriched = callbacks.onEpisodeThumbnailsEnriched,
            )
            AppLogger.i(TAG, "✓ TMDB enrichment completed successfully")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "✗ TMDB enrichment failed — ${e::class.simpleName}: ${e.message}")
            false
        }
    }
}
