package com.lagradost.cloudstream3.desktop.ui.screens.home

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import java.util.Locale

enum class HomeDiscoveryKind {
    RECENT,
    POPULAR,
}

data class HomeDiscoveryItem(
    val providerName: String,
    val response: SearchResponse,
)

data class HomeDiscoverySectionState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val items: List<HomeDiscoveryItem> = emptyList(),
    val sourceCount: Int = 0,
    val failedSourceCount: Int = 0,
    val pageSources: List<HomeDiscoveryPageSource> = emptyList(),
)

data class HomeDiscoveryPageSource(
    val provider: MainAPI,
    val request: MainPageRequest,
    val sectionName: String,
    val nextPage: Int = 2,
    val hasNext: Boolean = true,
)

/** Classifies only explicit provider catalog labels; unknown labels stay in the normal feed. */
fun classifyHomeDiscoveryCatalog(title: String): HomeDiscoveryKind? {
    val normalized = title.lowercase(Locale.ROOT)
    val recent = listOf(
        Regex("\\brecent(?:ly)?\\b"),
        Regex("\\blatest\\b"),
        Regex("\\bnew\\b"),
        Regex("\\bupdates?\\b"),
    ).any { it.containsMatchIn(normalized) }
    if (recent) return HomeDiscoveryKind.RECENT

    val popular = listOf(
        Regex("\\btrend(?:ing)?\\b"),
        Regex("\\bpopular(?:ity)?\\b"),
        Regex("\\btop(?:\\s+(?:rated|movies|series|shows))?\\b"),
    ).any { it.containsMatchIn(normalized) }
    return if (popular) HomeDiscoveryKind.POPULAR else null
}

/**
 * Merges catalogs in provider order, preserving each provider's own ordering and keeping the
 * first copy of a normalized title/year/type. Provider IDs are intentionally not treated as
 * global IDs because their namespace is provider-specific.
 */
fun mergeHomeDiscoveryItems(items: Iterable<HomeDiscoveryItem>): List<HomeDiscoveryItem> {
    val seen = HashSet<String>()
    return buildList {
        items.forEach { candidate ->
            if (seen.add(homeMediaIdentity(candidate.response))) add(candidate)
        }
    }
}

/** Small local-only recommendation heuristic; no account, network request, or remote profile. */
fun recommendHomeItems(
    candidates: List<HomeDiscoveryItem>,
    seedTitles: List<String>,
    limit: Int = 20,
): List<HomeDiscoveryItem> {
    val uniqueSeeds = seedTitles.map(::normalizedTitle).filter(String::isNotBlank).distinct()
    if (uniqueSeeds.size < 2 || limit <= 0) return emptyList()

    val seedTokens = uniqueSeeds.flatMap(::meaningfulTokens).toSet()
    if (seedTokens.isEmpty()) return emptyList()

    val excludedTitles = uniqueSeeds.toSet()
    return candidates
        .withIndex()
        .asSequence()
        .filter { (_, candidate) -> normalizedTitle(candidate.response.name) !in excludedTitles }
        .mapNotNull { (index, candidate) ->
            val overlap = meaningfulTokens(candidate.response.name).count(seedTokens::contains)
            if (overlap == 0) null else Triple(index, candidate, overlap)
        }
        .sortedWith(compareByDescending<Triple<Int, HomeDiscoveryItem, Int>> { it.third }.thenBy { it.first })
        .take(limit)
        .map { it.second }
        .toList()
}

internal fun homeMediaIdentity(response: SearchResponse): String {
    val (cleanTitle, titleYear) = splitTitleYear(response.name)
    val year = when (response) {
        is AnimeSearchResponse -> response.year
        is MovieSearchResponse -> response.year
        is TvSeriesSearchResponse -> response.year
        else -> null
    } ?: titleYear
    val type = response.type?.toString()?.lowercase(Locale.ROOT).orEmpty()
    return "${normalizedTitle(cleanTitle)}|${year ?: ""}|$type"
}

private fun normalizedTitle(title: String): String =
    splitTitleYear(title).first
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

private fun splitTitleYear(title: String): Pair<String, Int?> {
    val yearMatch = Regex("\\s*(?:\\(|\\[)?((?:19|20)\\d{2})(?:\\)|\\])?\\s*$").find(title)
        ?: return title.trim() to null
    val year = yearMatch.groupValues[1].toIntOrNull()
    return title.substring(0, yearMatch.range.first).trim() to year
}

private fun meaningfulTokens(title: String): List<String> =
    title.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 && it !in recommendationStopWords }

private val recommendationStopWords = setOf(
    "the", "and", "for", "from", "with", "into", "about", "this", "that", "movie", "series", "season",
)
