package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.LinkedHashMap

object LinkCache {
    data class CachedLinks(
        val links: List<ExtractorLink>,
        val subtitles: List<SubtitleFile>,
        val timestamp: Long,
    )

    private const val CACHE_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    private const val MAX_ENTRIES = 20
    private val cache = object : LinkedHashMap<String, CachedLinks>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLinks>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(episodeId: String): CachedLinks? {
        val entry = cache[episodeId] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > CACHE_DURATION_MS) {
            cache.remove(episodeId)
            return null
        }
        return entry
    }

    @Synchronized
    fun remove(episodeId: String) {
        cache.remove(episodeId)
    }

    @Synchronized
    fun clearAll() {
        cache.clear()
    }

    @Synchronized
    fun set(episodeId: String, links: List<ExtractorLink>, subtitles: List<SubtitleFile>) {
        val now = System.currentTimeMillis()

        cache.entries.removeIf { now - it.value.timestamp > CACHE_DURATION_MS }
        cache[episodeId] = CachedLinks(
            links = links,
            subtitles = subtitles,
            timestamp = now,
        )
    }
}
