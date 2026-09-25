package com.lagradost.cloudstream3.desktop.genre

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.ui.screens.details.TmdbEnrichmentService
import com.lagradost.cloudstream3.desktop.ui.screens.details.TmdbRateLimiter

enum class GenreBrowseMediaType(val tmdbPath: String, val label: String) {
    Movies("movie", "Movies"),
    Series("tv", "Series"),
}

enum class GenreBrowseTopic(val label: String, val keywordSearchTerms: List<String>) {
    Murder(
        label = "Murder",
        keywordSearchTerms = listOf("murder", "murder investigation", "murder mystery", "murder plot", "homicide", "serial killer"),
    ),
    TrueCrime(
        label = "True Crime",
        keywordSearchTerms = listOf("true crime", "crime documentary"),
    ),
    Heist(
        label = "Heist",
        keywordSearchTerms = listOf("heist", "robbery", "bank robbery", "caper"),
    ),
    Revenge(
        label = "Revenge",
        keywordSearchTerms = listOf("revenge", "vengeance", "vigilante"),
    ),
    Survival(
        label = "Survival",
        keywordSearchTerms = listOf("survival", "stranded", "wilderness survival"),
    ),
    TimeTravel(
        label = "Time Travel",
        keywordSearchTerms = listOf("time travel", "time loop", "time paradox"),
    ),
    Supernatural(
        label = "Supernatural",
        keywordSearchTerms = listOf("supernatural", "ghosts", "haunting", "possession"),
    ),
    PostApocalyptic(
        label = "Post-apocalyptic",
        keywordSearchTerms = listOf("apocalypse", "post-apocalyptic"),
    ),
}

enum class GenreBrowseSort(val label: String) {
    Popularity("Popular"),
    Newest("Newest first"),
    Oldest("Oldest first"),
    TopRated("Top rated · 200+ votes"),
    MostVotes("Most votes");

    fun apiSortBy(mediaType: GenreBrowseMediaType): String = when (this) {
        Popularity -> "popularity.desc"
        Newest -> if (mediaType == GenreBrowseMediaType.Movies) "primary_release_date.desc" else "first_air_date.desc"
        Oldest -> if (mediaType == GenreBrowseMediaType.Movies) "primary_release_date.asc" else "first_air_date.asc"
        TopRated -> "vote_average.desc"
        MostVotes -> "vote_count.desc"
    }

    val minimumVoteCount: Int?
        get() = if (this == TopRated) TOP_RATED_MINIMUM_VOTES else null

    private companion object {
        const val TOP_RATED_MINIMUM_VOTES = 200
    }
}

data class GenreBrowseOption(
    val id: Int,
    val name: String,
)

data class GenreBrowseResult(
    val id: Int,
    val title: String,
    val mediaType: GenreBrowseMediaType,
    val posterUrl: String?,
    val overview: String?,
    val year: String?,
    val rating: Double?,
) {
    /** StreamPlay's TMDB-backed load() payload, kept local to this experiment. */
    val pluginLoadUrl: String
        get() = """{"id":$id,"type":"${mediaType.tmdbPath}"}"""
}

data class GenreBrowsePage(
    val results: List<GenreBrowseResult>,
    val page: Int,
    val totalPages: Int,
)

/**
 * TMDB catalog adapter for StreamPlay genre browsing.
 * Catalog metadata comes from TMDB; opening a card hands the TMDB ID to StreamPlay.
 */
internal object GenreBrowseClient {
    private const val API_BASE = "https://api.themoviedb.org/3"
    private const val GENRE_CACHE_MINUTES = 24 * 60
    private const val PAGE_CACHE_MINUTES = 20
    private val mapper = jacksonObjectMapper()
    private val keywordIdsByTopic = mutableMapOf<GenreBrowseTopic, List<Int>>()

    suspend fun fetchGenres(mediaType: GenreBrowseMediaType): List<GenreBrowseOption> {
        TmdbRateLimiter.acquire()
        val response = app.get(
            "$API_BASE/genre/${mediaType.tmdbPath}/list",
            params = mapOf(
                "api_key" to TmdbEnrichmentService.TMDB_API_KEY,
                "language" to "en-US",
            ),
            cacheTime = GENRE_CACHE_MINUTES,
        )
        val genres = mapper.readTree(response.text)["genres"] ?: return emptyList()
        if (!genres.isArray) return emptyList()

        val loadedGenres = genres.mapNotNull { node ->
            val id = node["id"]?.asInt() ?: return@mapNotNull null
            val name = node["name"]?.asText()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            GenreBrowseOption(id = id, name = name)
        }
        val preferredOrder = when (mediaType) {
            GenreBrowseMediaType.Movies -> listOf(
                "comedy", "action", "adventure", "crime", "mystery", "thriller",
                "science fiction", "fantasy", "documentary", "romance", "horror", "drama",
            )
            GenreBrowseMediaType.Series -> listOf(
                "comedy", "action & adventure", "crime", "drama", "mystery",
                "sci-fi & fantasy", "documentary",
            )
        }
        val rank = preferredOrder.withIndex().associate { (index, name) -> name to index }
        return loadedGenres.withIndex()
            .sortedWith(compareBy<IndexedValue<GenreBrowseOption>> { rank[it.value.name.lowercase()] ?: Int.MAX_VALUE }.thenBy { it.index })
            .map { it.value }
    }

    suspend fun fetchPage(
        mediaType: GenreBrowseMediaType,
        genreId: Int?,
        topic: GenreBrowseTopic?,
        sort: GenreBrowseSort,
        page: Int,
    ): GenreBrowsePage {
        val topicKeywordIds = if (topic == null) emptyList() else fetchKeywordIds(topic)
        if (topic != null && topicKeywordIds.isEmpty()) {
            throw IllegalStateException("No TMDB keywords are available for ${topic.label}.")
        }
        TmdbRateLimiter.acquire()
        val params = buildMap {
            put("api_key", TmdbEnrichmentService.TMDB_API_KEY)
            put("language", "en-US")
            put("include_adult", "false")
            put("sort_by", sort.apiSortBy(mediaType))
            put("page", page.toString())
            genreId?.let { put("with_genres", it.toString()) }
            sort.minimumVoteCount?.let { put("vote_count.gte", it.toString()) }
            topicKeywordIds.takeIf { it.isNotEmpty() }?.let { ids ->
                put("with_keywords", ids.distinct().joinToString("|"))
            }
        }
        val response = app.get(
            "$API_BASE/discover/${mediaType.tmdbPath}",
            params = params,
            cacheTime = PAGE_CACHE_MINUTES,
        )
        val root = mapper.readTree(response.text)
        val resultsNode = root["results"]
        val results = if (resultsNode != null && resultsNode.isArray) {
            resultsNode.mapNotNull { node ->
                val id = node["id"]?.asInt() ?: return@mapNotNull null
                val title = (node["title"] ?: node["name"])
                    ?.asText()
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val posterPath = node["poster_path"]?.asText()?.takeIf(String::isNotBlank)
                val releaseDate = (node["release_date"] ?: node["first_air_date"])
                    ?.asText()
                    ?.takeIf(String::isNotBlank)
                val rating = node["vote_average"]?.takeIf { it.isNumber }?.asDouble()

                GenreBrowseResult(
                    id = id,
                    title = title,
                    mediaType = mediaType,
                    posterUrl = TmdbEnrichmentService.tmdbImageUrl(posterPath, "w500"),
                    overview = node["overview"]?.asText()?.takeIf(String::isNotBlank),
                    year = releaseDate?.take(4),
                    rating = rating,
                )
            }
        } else {
            emptyList()
        }

        return GenreBrowsePage(
            results = results,
            page = root["page"]?.asInt() ?: page,
            totalPages = root["total_pages"]?.asInt() ?: page,
        )
    }

    private suspend fun fetchKeywordIds(topic: GenreBrowseTopic): List<Int> {
        synchronized(keywordIdsByTopic) {
            keywordIdsByTopic[topic]?.let { return it }
        }

        val exactNames = topic.keywordSearchTerms.map { it.normalizeKeyword() }.toSet()
        val keywordIds = topic.keywordSearchTerms.flatMap { term ->
            TmdbRateLimiter.acquire()
            val response = app.get(
                "$API_BASE/search/keyword",
                params = mapOf(
                    "api_key" to TmdbEnrichmentService.TMDB_API_KEY,
                    "query" to term,
                    "page" to "1",
                ),
                cacheTime = GENRE_CACHE_MINUTES,
            )
            val results = mapper.readTree(response.text)["results"]
            if (results?.isArray != true) emptyList() else results.mapNotNull { node ->
                val name = node["name"]?.asText()?.normalizeKeyword() ?: return@mapNotNull null
                if (name !in exactNames) return@mapNotNull null
                node["id"]?.asInt()
            }
        }.distinct()

        synchronized(keywordIdsByTopic) {
            keywordIdsByTopic[topic] = keywordIds
        }
        return keywordIds
    }

    private fun String.normalizeKeyword(): String =
        lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
