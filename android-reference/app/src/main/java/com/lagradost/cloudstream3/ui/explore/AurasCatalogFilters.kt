package com.lagradost.cloudstream3.ui.explore

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.WatchType

internal enum class AurasCatalogFilter {
    ALL,
    MOVIES,
    SERIES,
    ANIME,
    NEW,
}

internal enum class AurasCatalogMediaKind {
    MOVIE,
    SERIES,
    ANIME,
    OTHER,
}

internal data class AurasCatalogMetadata(
    val mediaKind: AurasCatalogMediaKind,
    val year: Int?,
)

internal data class AurasLocalWatchlistEntry(
    val status: WatchType,
    val item: SearchResponse,
    val latestUpdatedTime: Long,
)

internal data class AurasWatchlistLoadState(
    val items: List<AurasLocalWatchlistEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** Keep provider-only filtering deterministic and independently testable. */
internal fun <T> filterAurasCatalogItems(
    items: List<T>,
    filter: AurasCatalogFilter,
    currentYear: Int,
    metadataOf: (T) -> AurasCatalogMetadata,
): List<T> = items.filter { item ->
    val metadata = metadataOf(item)
    when (filter) {
        AurasCatalogFilter.ALL -> true
        AurasCatalogFilter.MOVIES -> metadata.mediaKind == AurasCatalogMediaKind.MOVIE
        AurasCatalogFilter.SERIES -> metadata.mediaKind == AurasCatalogMediaKind.SERIES
        AurasCatalogFilter.ANIME -> metadata.mediaKind == AurasCatalogMediaKind.ANIME
        AurasCatalogFilter.NEW -> metadata.year?.let { it in (currentYear - 1)..currentYear } == true
    }
}

internal fun SearchResponse.toAurasCatalogMetadata(): AurasCatalogMetadata {
    val mediaKind = when {
        this is AnimeSearchResponse || type == TvType.Anime || type == TvType.OVA || type == TvType.AnimeMovie ->
            AurasCatalogMediaKind.ANIME

        this is MovieSearchResponse || type == TvType.Movie -> AurasCatalogMediaKind.MOVIE

        this is TvSeriesSearchResponse ||
            type == TvType.TvSeries || type == TvType.Cartoon || type == TvType.AsianDrama || type == TvType.Documentary ->
            AurasCatalogMediaKind.SERIES

        else -> AurasCatalogMediaKind.OTHER
    }
    val year = when (this) {
        is AnimeSearchResponse -> year
        is MovieSearchResponse -> year
        is TvSeriesSearchResponse -> year
        is com.lagradost.cloudstream3.utils.DataStoreHelper.BookmarkedData -> year
        else -> null
    }
    return AurasCatalogMetadata(mediaKind, year)
}

/** Status selection only filters the supplied local collection; it has no persistence side effects. */
internal fun <T> filterAurasWatchlistItems(
    items: List<T>,
    status: WatchType,
    statusOf: (T) -> WatchType,
): List<T> = items.filter { statusOf(it) == status }
