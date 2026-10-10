package com.lagradost.cloudstream3.desktop.ui.screens.details

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.ui.screens.person.model.PersonDetail
import com.lagradost.cloudstream3.desktop.ui.screens.person.model.PersonMediaCredit
import com.lagradost.cloudstream3.desktop.ui.screens.person.model.TmdbPersonCandidate
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

object TmdbPersonFetcher {

    private val dummyApi = object : MainAPI() {
        override var name = "TMDB"
        override var mainUrl = "https://www.themoviedb.org"
    }

    data class DesktopActorDetails(
        val id: Int,
        val name: String,
        val profilePath: String?,
        val biography: String?,
        val birthday: String?,
        val placeOfBirth: String?,
        val deathday: String?,
        val knownFor: List<SearchResponse>,
    )

    suspend fun searchPeople(query: String): List<TmdbPersonCandidate> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                TmdbRateLimiter.acquire()
                val apiKey = TmdbEnrichmentService.TMDB_API_KEY
                val searchUrl = "https://api.themoviedb.org/3/search/person?api_key=$apiKey&query=${URLEncoder.encode(trimmed, "UTF-8")}&page=1&language=en-US"
                val searchData = app.get(searchUrl).parsedSafe<JsonNode>()
                val results = searchData?.get("results")
                if (results == null || !results.isArray) return@withContext emptyList()

                results.mapNotNull { result ->
                    val id = result.get("id")?.asInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                    val name = result.get("name")?.asText()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val knownFor = result.get("known_for")
                        ?.takeIf { it.isArray }
                        ?.mapNotNull { credit ->
                            (credit.get("title")?.asText() ?: credit.get("name")?.asText())
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                        }
                        ?.distinct()
                        ?.take(3)
                        .orEmpty()

                    TmdbPersonCandidate(
                        tmdbId = id,
                        name = name,
                        profileUrl = TmdbEnrichmentService.tmdbImageUrl(
                            result.get("profile_path")?.asText()?.takeIf { it.isNotBlank() && it != "null" },
                            "w500",
                        ),
                        knownForDepartment = result.get("known_for_department")?.asText()?.takeIf { it.isNotBlank() },
                        knownFor = knownFor,
                    )
                }.distinctBy { it.tmdbId }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogger.e("TmdbPersonFetcher: Failed to search people", e)
                throw e
            }
        }
    }

    suspend fun getActorDetails(personId: Int): DesktopActorDetails? {
        if (personId <= 0) return null
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = TmdbEnrichmentService.TMDB_API_KEY
                TmdbRateLimiter.acquire()
                val detailsUrl = "https://api.themoviedb.org/3/person/$personId?api_key=$apiKey&append_to_response=combined_credits"
                val detailsData = app.get(detailsUrl).parsedSafe<JsonNode>() ?: return@withContext null

                val profilePath = detailsData.get("profile_path")?.asText()?.takeIf { it.isNotBlank() && it != "null" }
                val castList = detailsData.get("combined_credits")?.get("cast")
                val knownFor = mutableListOf<SearchResponse>()
                if (castList != null && castList.isArray) {
                    val sortedCast = castList.toList().sortedByDescending { it.get("popularity")?.asDouble() ?: 0.0 }
                    sortedCast.take(15).forEach { credit ->
                        val mediaType = credit.get("media_type")?.asText()
                        val title = credit.get("title")?.asText() ?: credit.get("name")?.asText() ?: return@forEach
                        val posterUrl = TmdbEnrichmentService.tmdbImageUrl(credit.get("poster_path")?.asText(), "original")
                        val recId = credit.get("id")?.asInt()
                        val recUrl = if (recId != null) "https://www.themoviedb.org/$mediaType/$recId" else ""
                        if (mediaType == "movie") {
                            knownFor.add(
                                dummyApi.newMovieSearchResponse(title, url = recUrl, TvType.Movie, false) {
                                    this.posterUrl = posterUrl
                                    if (recId != null) this.id = recId
                                },
                            )
                        } else if (mediaType == "tv") {
                            knownFor.add(
                                dummyApi.newTvSeriesSearchResponse(title, url = recUrl, TvType.TvSeries, false) {
                                    this.posterUrl = posterUrl
                                    if (recId != null) this.id = recId
                                },
                            )
                        }
                    }
                }

                DesktopActorDetails(
                    id = personId,
                    name = detailsData.get("name")?.asText()?.takeIf { it.isNotBlank() } ?: "",
                    profilePath = TmdbEnrichmentService.tmdbImageUrl(profilePath, "original"),
                    biography = detailsData.get("biography")?.asText()?.takeIf { it.isNotBlank() },
                    birthday = detailsData.get("birthday")?.asText()?.takeIf { it.isNotBlank() },
                    placeOfBirth = detailsData.get("place_of_birth")?.asText()?.takeIf { it.isNotBlank() },
                    deathday = detailsData.get("deathday")?.asText()?.takeIf { it.isNotBlank() && it != "null" },
                    knownFor = knownFor,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogger.e("TmdbPersonFetcher: Failed to load actor details", e)
                null
            }
        }
    }

    suspend fun fetchPersonDetail(
        name: String,
        tmdbId: Int? = null,
    ): PersonDetail? {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = TmdbEnrichmentService.TMDB_API_KEY
                val resolvedId = tmdbId?.takeIf { it > 0 } ?: return@withContext null

                TmdbRateLimiter.acquire()
                val detailsUrl = "https://api.themoviedb.org/3/person/$resolvedId?api_key=$apiKey&append_to_response=combined_credits"
                val detailsData = app.get(detailsUrl).parsedSafe<JsonNode>() ?: return@withContext null

                val personName = detailsData.get("name")?.asText()?.takeIf { it.isNotBlank() } ?: name
                val bio = detailsData.get("biography")?.asText()?.takeIf { it.isNotBlank() }
                val bday = detailsData.get("birthday")?.asText()?.takeIf { it.isNotBlank() }
                val pob = detailsData.get("place_of_birth")?.asText()?.takeIf { it.isNotBlank() }
                val dday = detailsData.get("deathday")?.asText()?.takeIf { it.isNotBlank() && it != "null" }
                val profilePath = detailsData.get("profile_path")?.asText()?.takeIf { it.isNotBlank() && it != "null" }
                val profileUrl = TmdbEnrichmentService.tmdbImageUrl(profilePath, "original")
                val department = detailsData.get("known_for_department")?.asText()?.takeIf { it.isNotBlank() }

                val castNode = detailsData.get("combined_credits")?.get("cast")
                val movieCredits = mutableListOf<PersonMediaCredit>()
                val tvCredits = mutableListOf<PersonMediaCredit>()

                if (castNode != null && castNode.isArray) {
                    val sortedList = castNode.toList().sortedByDescending { it.get("popularity")?.asDouble() ?: 0.0 }
                    val seenIds = mutableSetOf<String>()

                    sortedList.forEach { credit ->
                        val creditId = credit.get("id")?.asInt() ?: return@forEach
                        val mediaTypeStr = credit.get("media_type")?.asText() ?: "movie"
                        val key = "$mediaTypeStr-$creditId"
                        if (!seenIds.add(key)) return@forEach

                        val title = credit.get("title")?.asText() ?: credit.get("name")?.asText() ?: return@forEach
                        val posterPath = credit.get("poster_path")?.asText()
                        val backdropPath = credit.get("backdrop_path")?.asText()
                        val releaseDate = credit.get("release_date")?.asText() ?: credit.get("first_air_date")?.asText()
                        val releaseYear = releaseDate?.take(4)?.takeIf { it.isNotBlank() && it != "null" }
                        val character = credit.get("character")?.asText()?.takeIf { it.isNotBlank() && it != "null" }
                        val voteAverage = credit.get("vote_average")?.asDouble()?.takeIf { it > 0.0 }
                        val popularity = credit.get("popularity")?.asDouble() ?: 0.0
                        val overview = credit.get("overview")?.asText()?.takeIf { it.isNotBlank() && it != "null" }

                        val item = PersonMediaCredit(
                            tmdbId = creditId,
                            title = title,
                            posterUrl = TmdbEnrichmentService.tmdbImageUrl(posterPath, "w500"),
                            backdropUrl = TmdbEnrichmentService.tmdbImageUrl(backdropPath, "original"),
                            releaseYear = releaseYear,
                            characterOrJob = character,
                            mediaType = if (mediaTypeStr == "tv") TvType.TvSeries else TvType.Movie,
                            voteAverage = voteAverage,
                            popularity = popularity,
                            overview = overview,
                        )

                        if (mediaTypeStr == "tv") {
                            tvCredits.add(item)
                        } else {
                            movieCredits.add(item)
                        }
                    }
                }

                PersonDetail(
                    tmdbId = resolvedId,
                    name = personName,
                    biography = bio,
                    birthday = bday,
                    deathday = dday,
                    placeOfBirth = pob,
                    profileUrl = profileUrl,
                    knownForDepartment = department,
                    movieCredits = movieCredits,
                    tvCredits = tvCredits,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogger.e("TmdbPersonFetcher: Failed to fetch person detail", e)
                null
            }
        }
    }
}
