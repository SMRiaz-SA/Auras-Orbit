package com.lagradost.cloudstream3.desktop.ui.screens.tracker

import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import java.util.Locale

data class TrackerLibraryEntry(
    val item: SyncAPI.LibraryItem,
    val listName: String,
)

object TrackerLibraryModel {
    fun filterAndSort(
        entries: List<TrackerLibraryEntry>,
        query: String,
        status: String?,
        sorting: ListSorting,
    ): List<TrackerLibraryEntry> {
        val normalized = query.trim()
        val filtered = entries.asSequence()
            .filter { status == null || it.listName == status }
            .filter { normalized.isBlank() || it.item.name.contains(normalized, ignoreCase = true) }
            .toList()
        val alphabetical = compareBy<TrackerLibraryEntry> { it.item.name.lowercase(Locale.ROOT) }
        return when (sorting) {
            ListSorting.Query -> filtered.sortedWith(
                compareBy<TrackerLibraryEntry> { it.item.name.indexOf(normalized, ignoreCase = true).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                    .then(alphabetical),
            )
            ListSorting.RatingHigh -> filtered.sortedWith(compareByDescending<TrackerLibraryEntry> { it.item.personalRating?.toDouble(100) }.then(alphabetical))
            ListSorting.RatingLow -> filtered.sortedWith(compareBy<TrackerLibraryEntry> { it.item.personalRating == null }.thenBy { it.item.personalRating?.toDouble(100) ?: 0.0 }.then(alphabetical))
            ListSorting.AlphabeticalA -> filtered.sortedWith(alphabetical)
            ListSorting.AlphabeticalZ -> filtered.sortedWith(alphabetical.reversed())
            ListSorting.UpdatedNew -> filtered.sortedWith(compareBy<TrackerLibraryEntry> { it.item.lastUpdatedUnixTime == null }.thenByDescending { it.item.lastUpdatedUnixTime ?: 0L }.then(alphabetical))
            ListSorting.UpdatedOld -> filtered.sortedWith(compareBy<TrackerLibraryEntry> { it.item.lastUpdatedUnixTime == null }.thenBy { it.item.lastUpdatedUnixTime ?: 0L }.then(alphabetical))
            ListSorting.ReleaseDateNew -> filtered.sortedWith(compareBy<TrackerLibraryEntry> { it.item.releaseDate == null }.thenByDescending { it.item.releaseDate?.time ?: 0L }.then(alphabetical))
            ListSorting.ReleaseDateOld -> filtered.sortedWith(compareBy<TrackerLibraryEntry> { it.item.releaseDate == null }.thenBy { it.item.releaseDate?.time ?: 0L }.then(alphabetical))
        }
    }

    fun parseEpisodeSelection(value: String): Result<Set<SyncAPI.SyncEpisode>> {
        if (value.isBlank()) return Result.success(emptySet())
        return runCatching {
            value.split(',', '\n', ';')
                .map(String::trim)
                .filter(String::isNotBlank)
                .map { token ->
                    val parts = token.split('x', 'X', 'e', 'E', limit = 2)
                    when (parts.size) {
                        1 -> SyncAPI.SyncEpisode(null, parts[0].toInt().also { require(it > 0) })
                        2 -> SyncAPI.SyncEpisode(parts[0].toInt().also { require(it >= 0) }, parts[1].toInt().also { require(it > 0) })
                        else -> error("Invalid episode coordinate")
                    }
                }
                .toSet()
        }
    }

    fun formatEpisodeSelection(selection: Set<SyncAPI.SyncEpisode>): String = selection
        .sortedWith(compareBy({ it.season ?: -1 }, { it.number }))
        .joinToString(", ") { episode ->
            if (episode.season == null) episode.number.toString() else "${episode.season}x${episode.number}"
        }

    fun statusLabel(status: SyncWatchType): String = when (status) {
        SyncWatchType.NONE -> "Not on list"
        SyncWatchType.WATCHING -> "Watching"
        SyncWatchType.COMPLETED -> "Completed"
        SyncWatchType.ON_HOLD -> "On Hold"
        SyncWatchType.DROPPED -> "Dropped"
        SyncWatchType.PLAN_TO_WATCH -> "Plan to Watch"
    }
}
