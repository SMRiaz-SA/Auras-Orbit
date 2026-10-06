package com.lagradost.cloudstream3.desktop.ui.screens.details.contract

data class TrailerData(
    val id: String,
    val name: String,
    val url: String,
    val rawKey: String? = null,
    val thumbnailUrl: String? = null,
    val site: String = "YouTube",
    val isOfficial: Boolean = true,
    val publishedAt: String? = null,
    val type: String = "Trailer",
    val languageCode: String? = null,
    val source: String? = null,
)

object TrailerUtils {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val supportedPathPrefixes = setOf("embed", "v", "shorts", "live")

    fun youtubeId(value: String?): String? {
        val input = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (videoIdPattern.matches(input)) return input

        val uri = runCatching { java.net.URI(input) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val isYouTubeHost = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
        if (!isYouTubeHost) return null

        val queryVideoId = uri.rawQuery
            ?.split('&')
            ?.firstOrNull { it.startsWith("v=") }
            ?.substringAfter("v=")
            ?.takeIf { videoIdPattern.matches(it) }
        if (queryVideoId != null) return queryVideoId

        val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val pathVideoId = if (host == "youtu.be") {
            pathSegments.firstOrNull()
        } else {
            val markerIndex = pathSegments.indexOfFirst { it.lowercase() in supportedPathPrefixes }
            pathSegments.getOrNull(markerIndex + 1)
        }
        return pathVideoId?.takeIf { videoIdPattern.matches(it) }
    }

    fun mergeAndRank(
        candidates: Iterable<TrailerData>,
        preferredLanguage: String? = null,
        maxItems: Int = 50,
    ): List<TrailerData> {
        val comparator = candidateComparator(preferredLanguage)
        val byVideoId = linkedMapOf<String, TrailerData>()
        candidates.forEach { candidate ->
            val videoId = youtubeId(candidate.rawKey) ?: youtubeId(candidate.url) ?: youtubeId(candidate.id) ?: return@forEach
            val normalized = candidate.copy(
                id = videoId,
                url = "https://www.youtube.com/watch?v=$videoId",
                rawKey = videoId,
                site = "YouTube",
            )
            val current = byVideoId[videoId]
            if (current == null) {
                byVideoId[videoId] = normalized
            } else {
                val preferred = if (comparator.compare(normalized, current) < 0) normalized else current
                val secondary = if (preferred === normalized) current else normalized
                byVideoId[videoId] = preferred.copy(
                    thumbnailUrl = preferred.thumbnailUrl ?: secondary.thumbnailUrl,
                    publishedAt = preferred.publishedAt ?: secondary.publishedAt,
                    languageCode = preferred.languageCode ?: secondary.languageCode,
                    source = preferred.source ?: secondary.source,
                )
            }
        }
        return byVideoId.values.sortedWith(comparator).take(maxItems.coerceIn(1, 50))
    }

    fun youtubeSearchUrl(title: String, year: Int?): String {
        val query = listOfNotNull(
            title.trim().takeIf { it.isNotEmpty() },
            year?.toString(),
            "trailer",
        ).joinToString(" ")
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        return "https://www.youtube.com/results?search_query=$encoded"
    }

    private fun candidateComparator(preferredLanguage: String?): Comparator<TrailerData> {
        val preferred = preferredLanguage?.substringBefore('-')?.lowercase().orEmpty()
        return compareBy<TrailerData> { typePriority(it.type) }
            .thenBy { languagePriority(it.languageCode, preferred) }
            .thenByDescending { it.isOfficial }
            .thenByDescending { it.publishedAt.orEmpty() }
            .thenBy { it.name.lowercase() }
    }

    private fun typePriority(type: String): Int = when (type.trim().lowercase()) {
        "trailer" -> 0
        "teaser" -> 1
        else -> 2
    }

    private fun languagePriority(languageCode: String?, preferred: String): Int {
        val language = languageCode?.substringBefore('-')?.lowercase().orEmpty()
        return when {
            language.isNotBlank() && language == preferred -> 0
            language == "en" -> 1
            language.isBlank() -> 2
            else -> 3
        }
    }
}
