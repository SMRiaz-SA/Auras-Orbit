package com.lagradost.cloudstream3.ui.explore

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.WatchType

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

/** Status selection only filters the supplied local collection; it has no persistence side effects. */
internal fun <T> filterAurasWatchlistItems(
    items: List<T>,
    status: WatchType,
    statusOf: (T) -> WatchType,
): List<T> = items.filter { statusOf(it) == status }
