package com.lagradost.cloudstream3.syncproviders

import androidx.annotation.WorkerThread
import com.auras.orbit.cs3.BoundedLevenshtein
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.UiText
import java.util.Date

/** Stateless synchronization contract for per-item and library data. */
abstract class SyncAPI : AuthAPI() {
    open val mainUrl: String = "NONE"
    open var requireLibraryRefresh: Boolean = true
    open val supportedWatchTypes: Set<SyncWatchType> = SyncWatchType.entries.toSet()
    open val syncIdName: SyncIdName? = null

    @Throws
    @WorkerThread
    open suspend fun updateStatus(
        auth: AuthData?,
        id: String,
        newStatus: AbstractSyncStatus,
    ): Boolean = throw NotImplementedError()

    @Throws
    @WorkerThread
    open suspend fun status(auth: AuthData?, id: String): AbstractSyncStatus? =
        throw NotImplementedError()

    @Throws
    @WorkerThread
    open suspend fun load(auth: AuthData?, id: String): SyncResult? = throw NotImplementedError()

    @Throws
    @WorkerThread
    open suspend fun search(auth: AuthData?, query: String): List<SyncSearchResult>? =
        throw NotImplementedError()

    @Throws
    @WorkerThread
    open suspend fun library(auth: AuthData?): LibraryMetadata? = throw NotImplementedError()

    @Throws
    open fun urlToId(url: String): String? = null

    data class SyncSearchResult(
        @JvmField val name: String,
        @JvmField val apiName: String,
        var syncId: String,
        @JvmField val url: String,
        @JvmField var posterUrl: String?,
        @JvmField var type: TvType? = null,
        @JvmField var quality: SearchQuality? = null,
        @JvmField var posterHeaders: Map<String, String>? = null,
        @JvmField var id: Int? = null,
        @JvmField var score: Score? = null,
    ) : SearchResponse {
        override fun getName(): String = name
        override fun getUrl(): String = url
        override fun getApiName(): String = apiName
        override fun getType(): TvType? = type
        override fun setType(value: TvType?) { type = value }
        override fun getPosterUrl(): String? = posterUrl
        override fun setPosterUrl(value: String?) { posterUrl = value }
        override fun getPosterHeaders(): Map<String, String>? = posterHeaders
        override fun setPosterHeaders(value: Map<String, String>?) { posterHeaders = value }
        override fun getId(): Int? = id
        override fun setId(value: Int?) { id = value }
        override fun getQuality(): SearchQuality? = quality
        override fun setQuality(value: SearchQuality?) { quality = value }
        override fun getScore(): Score? = score
        override fun setScore(value: Score?) { score = value }
    }

    abstract class AbstractSyncStatus {
        abstract var status: SyncWatchType
        abstract var score: Score?
        abstract var watchedEpisodes: Int?
        abstract var isFavorite: Boolean?
        abstract var maxEpisodes: Int?
    }

    data class SyncStatus(
        override var status: SyncWatchType,
        override var score: Score?,
        override var watchedEpisodes: Int?,
        override var isFavorite: Boolean? = null,
        override var maxEpisodes: Int? = null,
    ) : AbstractSyncStatus()

    data class SyncResult(
        var id: String,
        var totalEpisodes: Int? = null,
        var title: String? = null,
        var publicScore: Score? = null,
        var duration: Int? = null,
        var synopsis: String? = null,
        var airStatus: ShowStatus? = null,
        var nextAiring: NextAiring? = null,
        var studio: List<String>? = null,
        var genres: List<String>? = null,
        var synonyms: List<String>? = null,
        var trailers: List<String>? = null,
        var isAdult: Boolean? = null,
        var posterUrl: String? = null,
        var backgroundPosterUrl: String? = null,
        var startDate: Long? = null,
        var endDate: Long? = null,
        var recommendations: List<SyncSearchResult>? = null,
        var nextSeason: SyncSearchResult? = null,
        var prevSeason: SyncSearchResult? = null,
        var actors: List<ActorData>? = null,
    )

    data class Page(val title: UiText, var items: List<LibraryItem>) {
        fun sort(method: ListSorting?, query: String? = null) {
            items = when (method) {
                ListSorting.Query -> if (query != null) {
                    val remainingMatrixCells = longArrayOf(MAX_QUERY_MATRIX_CELLS_TOTAL)
                    val remainingScoreCells = longArrayOf(MAX_QUERY_SCORE_CELLS_TOTAL)
                    val normalizedQuery = query.takeIf { it.length <= MAX_QUERY_CHARS }?.lowercase()
                    val scoredItems = mutableListOf<Pair<LibraryItem, Int>>()
                    var rankingComplete = true
                    for (item in items) {
                        val score = if (normalizedQuery == null || item.name.length > MAX_QUERY_CHARS) {
                            0
                        } else {
                            partialRatio(
                                normalizedQuery,
                                item.name.lowercase(),
                                remainingMatrixCells,
                                remainingScoreCells,
                            )
                        }
                        if (score == null) {
                            rankingComplete = false
                            break
                        }
                        scoredItems.add(item to score)
                    }
                    if (rankingComplete) {
                        scoredItems.sortedBy { -it.second }.map { it.first }
                    } else {
                        items
                    }
                } else items

                ListSorting.RatingHigh -> items.sortedBy { -(it.personalRating?.toInt(100) ?: 0) }
                ListSorting.RatingLow -> items.sortedBy { it.personalRating?.toInt(100) ?: 0 }
                ListSorting.AlphabeticalA -> items.sortedBy { it.name }
                ListSorting.AlphabeticalZ -> items.sortedBy { it.name }.reversed()
                ListSorting.UpdatedNew -> items.sortedBy { it.lastUpdatedUnixTime?.times(-1) }
                ListSorting.UpdatedOld -> items.sortedBy { it.lastUpdatedUnixTime }
                ListSorting.ReleaseDateNew -> items.sortedByDescending { it.releaseDate }
                ListSorting.ReleaseDateOld -> items.sortedBy { it.releaseDate }
                else -> items
            }
        }

        private fun partialRatio(
            first: String,
            second: String,
            remainingMatrixCells: LongArray,
            remainingScoreCells: LongArray,
        ): Int? =
            BoundedLevenshtein.partialRatio(
                first,
                second,
                512,
                MAX_QUERY_MATRIX_CELLS_PER_ITEM,
                remainingMatrixCells,
                remainingScoreCells,
            )

        private companion object {
            const val MAX_QUERY_CHARS = 512
            const val MAX_QUERY_MATRIX_CELLS_PER_ITEM = 1_000_000L
            const val MAX_QUERY_MATRIX_CELLS_TOTAL = 4_000_000L
            const val MAX_QUERY_SCORE_CELLS_TOTAL = 8_000_000L
        }
    }

    data class LibraryMetadata(
        val allLibraryLists: List<LibraryList>,
        val supportedListSorting: Set<ListSorting>,
    )

    data class LibraryList(val name: UiText, val items: List<LibraryItem>)

    data class LibraryItem(
        @JvmField val name: String,
        @JvmField val url: String,
        val syncId: String,
        val episodesCompleted: Int?,
        val episodesTotal: Int?,
        val personalRating: Score?,
        val lastUpdatedUnixTime: Long?,
        @JvmField val apiName: String,
        @JvmField var type: TvType?,
        @JvmField var posterUrl: String?,
        @JvmField var posterHeaders: Map<String, String>?,
        @JvmField var quality: SearchQuality?,
        val releaseDate: Date?,
        @JvmField var id: Int? = null,
        val plot: String? = null,
        @JvmField var score: Score? = null,
        val tags: List<String>? = null,
    ) : SearchResponse {
        override fun getName(): String = name
        override fun getUrl(): String = url
        override fun getApiName(): String = apiName
        override fun getType(): TvType? = type
        override fun setType(value: TvType?) { type = value }
        override fun getPosterUrl(): String? = posterUrl
        override fun setPosterUrl(value: String?) { posterUrl = value }
        override fun getPosterHeaders(): Map<String, String>? = posterHeaders
        override fun setPosterHeaders(value: Map<String, String>?) { posterHeaders = value }
        override fun getId(): Int? = id
        override fun setId(value: Int?) { id = value }
        override fun getQuality(): SearchQuality? = quality
        override fun setQuality(value: SearchQuality?) { quality = value }
        override fun getScore(): Score? = score
        override fun setScore(value: Score?) { score = value }
    }
}
