package com.lagradost.cloudstream3.desktop.ui.screens.details

import com.lagradost.cloudstream3.Episode

data class EpisodeReleaseStatus(
    val isUnreleased: Boolean,
    val isMissingFromProvider: Boolean = false,
    val formattedDate: String?,
    val rawDate: String?,
    val statusBadgeText: String?,
    val daysUntilRelease: Long?,
)

internal val EPISODE_DATE_REGEX = Regex("""\|\|DATE:(.*?)\|\|""")
internal val EPISODE_E_PREFIX_REGEX = Regex("""^(?i)(E[0-9]+[\s\-:]*)+""")
internal val EPISODE_WORD_PREFIX_REGEX = Regex("""^(?i)(Episode[\s]*[0-9]+[\s\-:]*)+""")

private const val MAX_RELEASE_STATUS_CACHE_SIZE = 500
private data class ParsedEpisodeReleaseDate(
    val instant: java.time.Instant? = null,
    val dateOnly: java.time.LocalDate? = null,
)

private val releaseDateCache = object : java.util.LinkedHashMap<Pair<String, String>, ParsedEpisodeReleaseDate>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, ParsedEpisodeReleaseDate>?): Boolean {
        return size > MAX_RELEASE_STATUS_CACHE_SIZE
    }
}
private val releaseDateCacheLock = Any()

fun parseEpisodeReleaseStatus(ep: Episode, providerName: String? = null): EpisodeReleaseStatus {
    val isSynthetic = ep.data.startsWith("unreleased_") || ep.data.startsWith("synthetic_") || ep.data.isBlank()
    val rawDesc = ep.description ?: ""
    val rawDate = ep.date?.let {
        runCatching {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        }.getOrNull()
    } ?: EPISODE_DATE_REGEX.find(rawDesc)?.groupValues?.get(1)?.trim()

    val baseStatus = if (rawDate.isNullOrBlank()) {
        EpisodeReleaseStatus(
            isUnreleased = false,
            isMissingFromProvider = isSynthetic,
            formattedDate = null,
            rawDate = null,
            statusBadgeText = if (isSynthetic) "Unavailable" else null,
            daysUntilRelease = null,
        )
    } else {
        computeEpisodeReleaseStatus(rawDate, java.time.Instant.now(), java.time.ZoneId.systemDefault())
    }

    val isMissing = isSynthetic && !baseStatus.isUnreleased
    val effectiveBadge = when {
        baseStatus.isUnreleased -> baseStatus.statusBadgeText
        isMissing -> if (!providerName.isNullOrBlank()) "Missing from $providerName" else "Unavailable"
        else -> null
    }

    return baseStatus.copy(
        isMissingFromProvider = isMissing,
        statusBadgeText = effectiveBadge,
    )
}

private val OUTPUT_DATE_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)

internal fun computeEpisodeReleaseStatus(
    rawDate: String,
    now: java.time.Instant,
    localZone: java.time.ZoneId,
): EpisodeReleaseStatus {
    val cacheKey = rawDate to localZone.id
    val parsed = synchronized(releaseDateCacheLock) {
        releaseDateCache.getOrPut(cacheKey) { parseEpisodeReleaseDate(rawDate, localZone) }
    }
    val localToday = now.atZone(localZone).toLocalDate()
    val displayDate = parsed.instant?.atZone(localZone)?.toLocalDate() ?: parsed.dateOnly
    val formattedOut = displayDate?.let(OUTPUT_DATE_FORMATTER::format)
    val isFuture = when {
        parsed.instant != null -> parsed.instant.isAfter(now)
        parsed.dateOnly != null -> parsed.dateOnly.isAfter(localToday)
        else -> false
    }
    val daysUntil = displayDate?.let { java.time.temporal.ChronoUnit.DAYS.between(localToday, it) }
        ?.takeIf { isFuture }
        ?.coerceAtLeast(1L)

    val badgeText = when {
        !isFuture -> null
        daysUntil != null && daysUntil > 1 -> "Airs in $daysUntil days"
        daysUntil == 1L -> "Airs tomorrow"
        formattedOut != null -> "Airs $formattedOut"
        else -> "Unreleased"
    }

    return EpisodeReleaseStatus(
        isUnreleased = isFuture,
        formattedDate = formattedOut ?: rawDate,
        rawDate = rawDate,
        statusBadgeText = badgeText,
        daysUntilRelease = daysUntil,
    )
}

private fun parseEpisodeReleaseDate(rawDate: String, localZone: java.time.ZoneId): ParsedEpisodeReleaseDate {
    val instant = runCatching {
        java.time.Instant.parse(rawDate)
    }.getOrNull() ?: runCatching {
        java.time.OffsetDateTime.parse(rawDate).toInstant()
    }.getOrNull() ?: runCatching {
        java.time.LocalDateTime.parse(rawDate).atZone(localZone).toInstant()
    }.getOrNull()
    if (instant != null) return ParsedEpisodeReleaseDate(instant = instant)

    return ParsedEpisodeReleaseDate(dateOnly = runCatching { java.time.LocalDate.parse(rawDate.take(10)) }.getOrNull())
}
