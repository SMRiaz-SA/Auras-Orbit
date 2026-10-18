package com.lagradost.cloudstream3.ui.player

/** Keeps subtitle search results interleaved across providers, including the empty-result case. */
internal fun <T> interleaveSubtitleResults(results: List<List<T>>): List<T> {
    val maxResults = results.maxOfOrNull { it.size } ?: return emptyList()
    val interleaved = ArrayList<T>()
    for (index in 0 until maxResults) {
        for (providerResults in results) {
            providerResults.getOrNull(index)?.let(interleaved::add)
        }
    }
    return interleaved
}
