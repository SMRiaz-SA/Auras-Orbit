package com.lagradost.cloudstream3.syncproviders

import androidx.annotation.WorkerThread
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
import java.util.Locale

/**
 * Stateless synchronization class, used for syncing status about a specific movie/show.
 *
 * All non-null `AuthToken` will be non-expired when each function is called.
 */
abstract class SyncAPI : AuthAPI() {
    /**
     * Set this to true if the user updates something on the list like watch status or score
     **/
    open var requireLibraryRefresh: Boolean = true
    open val mainUrl: String = "NONE"

    /** Currently unused, but will be used to correctly render the UI.
     * This should specify what sync watch types can be used with this service. */
    open val supportedWatchTypes: Set<SyncWatchType> = SyncWatchType.entries.toSet()

    /** Media kinds this provider can search, list, and mutate through this adapter. */
    open val supportedMediaTypes: Set<SyncMediaType> = setOf(SyncMediaType.ANIME)

    /** Whether playback can advance a provider's count without exact episode coordinates. */
    open val supportsCountBasedProgress: Boolean = false

    /** Whether this provider can preserve a set of individually watched episodes. */
    open val supportsExactEpisodeProgress: Boolean = false

    /** Whether this provider can accept exact, additive playback watch events. */
    open val supportsWatchedEpisodeEvents: Boolean = false

    /**
     * Allows certain providers to open pages from
     * library links.
     **/
    open val syncIdName: SyncIdName? = null

    /** Modify the current status of an item */
    @Throws
    @WorkerThread
    open suspend fun updateStatus(
        auth: AuthData?,
        id: String,
        newStatus: AbstractSyncStatus,
    ): Boolean = throw NotImplementedError()

    /** Record playback as an additive watch event when the provider supports episode-level history. */
    open suspend fun recordWatchedEpisodes(
        auth: AuthData?,
        media: SyncMediaIdentity,
        episodes: List<WatchedEpisodeEvent>,
    ): Boolean = false

    /**
     * Returns the provider's exact watched episode set when the provider exposes one.
     * Count-only providers intentionally return null instead of manufacturing coordinates.
     */
    open suspend fun watchedEpisodeSelection(auth: AuthData?, id: String): Set<SyncEpisode>? = null

    /** Get the current status of an item */
    @Throws
    @WorkerThread
    open suspend fun status(auth: AuthData?, id: String): AbstractSyncStatus? =
        throw NotImplementedError()

    /** Get metadata about an item */
    @Throws
    @WorkerThread
    open suspend fun load(auth: AuthData?, id: String): SyncResult? = throw NotImplementedError()

    /** Search this service for any results for a given query */
    @Throws
    @WorkerThread
    open suspend fun search(auth: AuthData?, query: String): List<SyncSearchResult>? =
        throw NotImplementedError()

    /** Get the current library/bookmarks of this service */
    @Throws
    @WorkerThread
    open suspend fun library(auth: AuthData?): LibraryMetadata? = throw NotImplementedError()

    /** Helper function, may be used in the future */
    @Throws
    open fun urlToId(url: String): String? = null

    data class SyncSearchResult(
        override val name: String,
        override val apiName: String,
        var syncId: String,
        override val url: String,
        override var posterUrl: String?,
        override var type: TvType? = null,
        override var quality: SearchQuality? = null,
        override var posterHeaders: Map<String, String>? = null,
        override var id: Int? = null,
        override var score: Score? = null,
        var mediaType: SyncMediaType? = null,
        var year: Int? = null,
        var alternativeNames: Set<String> = emptySet(),
    ) : SearchResponse

    enum class SyncMediaType {
        ANIME,
        SHOW,
        MOVIE,
    }

    /** Canonical episode coordinate; anime may omit season for sequential numbering. */
    data class SyncEpisode(val season: Int?, val number: Int) {
        init {
            require(season == null || season >= 0) { "Season cannot be negative" }
            require(number > 0) { "Episode number must be positive" }
        }
    }

    data class SyncMediaIdentity(
        val mediaType: SyncMediaType,
        val title: String,
        val year: Int? = null,
        /** Provider-neutral external IDs such as simkl, mal, anilist, imdb, tmdb, or tvdb. */
        val externalIds: Map<String, String> = emptyMap(),
    )

    data class WatchedEpisodeEvent(
        val episode: SyncEpisode,
        /** Epoch milliseconds. Null lets the provider use the time it receives the event. */
        val watchedAt: Long? = null,
    )

    abstract class AbstractSyncStatus {
        abstract var status: SyncWatchType
        abstract var score: Score?
        abstract var watchedEpisodes: Int?
        abstract var isFavorite: Boolean?
        abstract var maxEpisodes: Int?

        /**
         * Optional exact selection. Count-only providers must reject non-null selections they
         * cannot represent instead of silently flattening them to a count.
         */
        open var watchedEpisodeSelection: Set<SyncEpisode>? = null

        /** Required when adding a title that is not already present in a provider library. */
        open var mediaType: SyncMediaType? = null
    }

    data class SyncStatus(
        override var status: SyncWatchType,
        override var score: Score?,
        override var watchedEpisodes: Int?,
        override var isFavorite: Boolean? = null,
        override var maxEpisodes: Int? = null,
        override var watchedEpisodeSelection: Set<SyncEpisode>? = null,
        override var mediaType: SyncMediaType? = null,
    ) : AbstractSyncStatus()

    data class SyncResult(
        /**Used to verify*/
        var id: String,

        var totalEpisodes: Int? = null,

        var title: String? = null,
        var publicScore: Score? = null,
        /**In minutes*/
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

        /** In unixtime */
        var startDate: Long? = null,
        /** In unixtime */
        var endDate: Long? = null,
        var recommendations: List<SyncSearchResult>? = null,
        var nextSeason: SyncSearchResult? = null,
        var prevSeason: SyncSearchResult? = null,
        var actors: List<ActorData>? = null,
    )

    data class Page(
        val title: UiText,
        var items: List<LibraryItem>,
    ) {
        fun sort(method: ListSorting?, query: String? = null) {
            val normalizedQuery = query?.trim()?.takeIf(String::isNotEmpty)
            val filtered = if (normalizedQuery == null) items else items.filter {
                it.name.contains(normalizedQuery, ignoreCase = true)
            }
            val alphabetical = compareBy<LibraryItem> { it.name.lowercase(Locale.ROOT) }
            val comparator = when (method) {
                null -> null
                ListSorting.Query -> normalizedQuery?.let { value ->
                    compareBy<LibraryItem> { it.name.indexOf(value, ignoreCase = true) }
                        .then(alphabetical)
                }
                ListSorting.RatingHigh -> compareByDescending<LibraryItem> { it.personalRating?.toDouble(100) }
                    .then(alphabetical)
                ListSorting.RatingLow -> compareBy<LibraryItem> { it.personalRating == null }
                    .thenBy { it.personalRating?.toDouble(100) ?: 0.0 }
                    .then(alphabetical)
                ListSorting.AlphabeticalA -> alphabetical
                ListSorting.AlphabeticalZ -> compareByDescending<LibraryItem> { it.name.lowercase(Locale.ROOT) }
                ListSorting.UpdatedNew -> compareBy<LibraryItem> { it.lastUpdatedUnixTime == null }
                    .thenByDescending { it.lastUpdatedUnixTime ?: 0L }
                    .then(alphabetical)
                ListSorting.UpdatedOld -> compareBy<LibraryItem> { it.lastUpdatedUnixTime == null }
                    .thenBy { it.lastUpdatedUnixTime ?: 0L }
                    .then(alphabetical)
                ListSorting.ReleaseDateNew -> compareBy<LibraryItem> { it.releaseDate == null }
                    .thenByDescending { it.releaseDate?.time ?: 0L }
                    .then(alphabetical)
                ListSorting.ReleaseDateOld -> compareBy<LibraryItem> { it.releaseDate == null }
                    .thenBy { it.releaseDate?.time ?: 0L }
                    .then(alphabetical)
            }
            items = comparator?.let(filtered::sortedWith) ?: filtered
        }
    }

    data class LibraryMetadata(
        val allLibraryLists: List<LibraryList>,
        val supportedListSorting: Set<ListSorting>,
    )

    data class LibraryList(
        val name: UiText,
        val items: List<LibraryItem>,
    )

    data class LibraryItem(
        override val name: String,
        override val url: String,
        /**
         * Unique unchanging string used for data storage.
         * This should be the actual id when you change scores and status
         * since score changes from library might get added in the future.
         **/
        val syncId: String,
        val episodesCompleted: Int?,
        val episodesTotal: Int?,
        val personalRating: Score?,
        val lastUpdatedUnixTime: Long?,
        override val apiName: String,
        override var type: TvType?,
        override var posterUrl: String?,
        override var posterHeaders: Map<String, String>?,
        override var quality: SearchQuality?,
        val releaseDate: Date?,
        override var id: Int? = null,
        val plot: String? = null,
        override var score: Score? = null,
        val tags: List<String>? = null,
        /** Required by providers such as Simkl when a title is edited or added. */
        val mediaType: SyncMediaType? = null,
    ) : SearchResponse
}
