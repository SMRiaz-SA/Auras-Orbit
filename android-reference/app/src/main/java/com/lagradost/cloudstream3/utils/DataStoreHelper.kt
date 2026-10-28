package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeyClass
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKeyClass
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.NEXT_WATCH_EPISODE_PERCENTAGE
import com.lagradost.cloudstream3.ui.result.EpisodeSortType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.serializers.WriteOnlySerializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.security.MessageDigest
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

const val VIDEO_POS_DUR = "video_pos_dur"
const val VIDEO_WATCH_STATE = "video_watch_state"
const val RESULT_WATCH_STATE = "result_watch_state"
const val RESULT_WATCH_STATE_DATA = "result_watch_state_data"
const val RESULT_SUBSCRIBED_STATE_DATA = "result_subscribed_state_data"
const val RESULT_FAVORITES_STATE_DATA = "result_favorites_state_data"
const val RESULT_RESUME_WATCHING = "result_resume_watching_2" // Changed due to id changes
const val RESULT_RESUME_WATCHING_OLD = "result_resume_watching"
const val RESULT_RESUME_WATCHING_HAS_MIGRATED = "result_resume_watching_migrated"
const val RESULT_EPISODE = "result_episode"
const val RESULT_SEASON = "result_season"
const val RESULT_DUB = "result_dub"
const val KEY_RESULT_SORT = "result_sort"
const val USER_PINNED_PROVIDERS = "user_pinned_providers" // Key for pinned user set

internal fun resolveResumeMetadata(
    currentEpisode: Any?,
    nextEpisode: Any?,
    shouldAdvance: Boolean,
    isNextEpisodeKnown: Boolean,
): Any? = when {
    !shouldAdvance -> currentEpisode
    nextEpisode != null -> nextEpisode
    !isNextEpisodeKnown -> currentEpisode
    else -> null
}

internal fun normalizeAccountProfileIds(
    accounts: List<DataStoreHelper.Account>,
    createProfileId: () -> String = { UUID.randomUUID().toString() },
): List<DataStoreHelper.Account> = accounts.map { account ->
    val profileId = normalizeProfileId(account.profileId) ?: createProfileId()
    account.copy(profileId = profileId)
}

internal fun reconcileAccountProfileIds(
    existingAccounts: List<DataStoreHelper.Account>,
    requestedAccounts: List<DataStoreHelper.Account>,
    createProfileId: () -> String = { UUID.randomUUID().toString() },
): List<DataStoreHelper.Account> = requestedAccounts.map { account ->
    val previousProfileId = existingAccounts
        .firstOrNull { it.keyIndex == account.keyIndex }
        ?.profileId
        ?.let(::normalizeProfileId)
    val profileId = previousProfileId
        ?: normalizeProfileId(account.profileId)
        ?: createProfileId()
    account.copy(profileId = profileId)
}

internal fun normalizeProfileId(profileId: String): String? =
    runCatching { UUID.fromString(profileId).toString() }.getOrNull()

internal fun getOrCreateProfileId(existingProfileId: String?, createProfileId: () -> String): String =
    existingProfileId?.let(::normalizeProfileId) ?: createProfileId()

class UserPreferenceDelegate<T : Any>(
    private val key: String,
    private val default: T,
) {
    private val klass: KClass<out T> = default::class
    private val realKey get() = "${DataStoreHelper.currentAccount}/$key"
    operator fun getValue(self: Any?, property: KProperty<*>) =
        getKeyClass(realKey, klass.java) ?: default

    operator fun setValue(
        self: Any?,
        property: KProperty<*>,
        t: T?,
    ) {
        if (t == null) {
            removeKey(realKey)
        } else {
            setKeyClass(realKey, t)
        }
    }
}

object DataStoreHelper {
    private const val CAST_EPISODE_POSTER_HEADERS = "cast_episode_poster_headers_v3"
    private const val MAX_CAST_EPISODE_HEADER_RECORDS = 5_000
    private const val MAX_CAST_EPISODE_HEADER_BYTES = 512 * 1024
    private const val MAX_CAST_EPISODE_HEADER_PAIRS = 20_000
    private const val MAX_CAST_EPISODE_HEADER_TEXT_CHARS = 64 * 1024
    private const val MAX_CAST_EPISODE_HEADER_CACHE_ENTRIES = 16
    private const val MAX_CAST_EPISODE_HEADER_CACHE_BYTES = 4 * 1024 * 1024
    private const val CAST_EPISODE_HEADERS_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L

    @Serializable
    private data class CastEpisodePosterHeaders(
        @JsonProperty("headersByEpisodeId") @SerialName("headersByEpisodeId")
        val headersByEpisodeId: Map<String, Map<String, String>>,
        @JsonProperty("updatedAt") @SerialName("updatedAt") val updatedAt: Long,
        @JsonProperty("profileToken") @SerialName("profileToken") val profileToken: String,
    )

    private val castEpisodePosterHeadersCache = ConcurrentHashMap<String, CastEpisodePosterHeaders>()
    private val castEpisodePosterHeadersWriteLock = Any()

    // be aware, don't change the index of these as Account uses the index for the art
    val profileImages = arrayOf(
        R.drawable.profile_bg_dark_blue,
        R.drawable.profile_bg_blue,
        R.drawable.profile_bg_orange,
        R.drawable.profile_bg_pink,
        R.drawable.profile_bg_purple,
        R.drawable.profile_bg_red,
        R.drawable.profile_bg_teal,
    )

    private var searchPreferenceProvidersStrings: List<String> by UserPreferenceDelegate(
        /** java moment right here, as listOf()::class.java != List(0) { "" }::class.java */
        "search_pref_providers", List(0) { "" }
    )

    private fun serializeTv(data: List<TvType>): List<String> = data.map { it.name }

    private fun deserializeTv(data: List<String>): List<TvType> {
        return data.mapNotNull { listName ->
            TvType.values().firstOrNull { it.name == listName }
        }
    }

    var searchPreferenceProviders: List<String>
        get() {
            val ret = searchPreferenceProvidersStrings
            return ret.ifEmpty {
                context?.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
                    ?.map { it.name } ?: emptyList()
            }
        }
        set(value) {
            searchPreferenceProvidersStrings = value
        }

    /** True when a non-empty provider filter is stored; an empty list means use defaults. */
    val hasSearchPreferenceProviders: Boolean
        get() = searchPreferenceProvidersStrings.isNotEmpty()

    private var searchPreferenceTagsStrings: List<String> by UserPreferenceDelegate(
        "search_pref_tags",
        listOf(TvType.Movie, TvType.TvSeries).map { it.name })

    var searchPreferenceTags: List<TvType>
        get() = deserializeTv(searchPreferenceTagsStrings)
        set(value) {
            searchPreferenceTagsStrings = serializeTv(value)
        }

    private var homePreferenceStrings: List<String> by UserPreferenceDelegate(
        "home_pref_homepage",
        listOf(TvType.Movie, TvType.TvSeries).map { it.name })

    var homePreference: List<TvType>
        get() = deserializeTv(homePreferenceStrings)
        set(value) {
            homePreferenceStrings = serializeTv(value)
        }

    var homeBookmarkedList: IntArray by UserPreferenceDelegate(
        "home_bookmarked_last_list",
        IntArray(0)
    )

    var playBackSpeed: Float by UserPreferenceDelegate("playback_speed", 1.0f)
    var resizeMode: Int by UserPreferenceDelegate("resize_mode", 0)
    var librarySortingMode: Int by UserPreferenceDelegate(
        "library_sorting_mode",
        ListSorting.AlphabeticalA.ordinal
    )

    private var _resultsSortingMode: Int by UserPreferenceDelegate(
        "results_sorting_mode",
        EpisodeSortType.NUMBER_ASC.ordinal
    )

    var resultsSortingMode: EpisodeSortType
        get() = EpisodeSortType.entries.getOrNull(_resultsSortingMode) ?: EpisodeSortType.NUMBER_ASC
        set(value) {
            _resultsSortingMode = value.ordinal
        }

    @Serializable
    data class Account(
        @JsonProperty("keyIndex") @SerialName("keyIndex") val keyIndex: Int,
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("customImage") @SerialName("customImage") val customImage: String? = null,
        @JsonProperty("defaultImageIndex") @SerialName("defaultImageIndex") val defaultImageIndex: Int,
        @JsonProperty("lockPin") @SerialName("lockPin") val lockPin: String? = null,
        @JsonProperty("profileId") @SerialName("profileId") val profileId: String = "",
    ) {
        @get:JsonIgnore
        val image get() = customImage?.let { UiImage.Image(it) } ?:
            profileImages.getOrNull(defaultImageIndex)?.let {
                UiImage.Drawable(it)
            } ?: UiImage.Drawable(profileImages.first())
    }

    const val TAG = "data_store_helper"
    private const val ACCOUNTS_PREFERENCE = "$TAG/account"
    private const val DEFAULT_PROFILE_ID_PREFERENCE = "$TAG/default_profile_id"
    private val accountProfilePersistenceLock = Any()
    @Volatile private var cachedDefaultProfileId: String? = null

    var accounts: Array<Account>
        get() = synchronized(accountProfilePersistenceLock) {
            val stored = readStoredAccounts()
            val normalized = normalizeAccountProfileIds(stored.toList())
            if (!stored.contentEquals(normalized.toTypedArray())) {
                setKeyClass(ACCOUNTS_PREFERENCE, normalized.toTypedArray())
            }
            normalized.toTypedArray()
        }
        set(value) = synchronized(accountProfilePersistenceLock) {
            val existing = readStoredAccounts()
            val normalized = reconcileAccountProfileIds(existing.toList(), value.toList())
            setKeyClass(ACCOUNTS_PREFERENCE, normalized.toTypedArray())
        }

    private fun readStoredAccounts(): Array<Account> =
        getKeyClass(ACCOUNTS_PREFERENCE, arrayOf<Account>().javaClass) ?: emptyArray()

    var selectedKeyIndex by PreferenceDelegate("$TAG/account_key_index", 0)
    val currentAccount: String get() = selectedKeyIndex.toString()

    fun getCurrentAccountProfileToken(): String =
        getAccountProfileToken(selectedKeyIndex) ?: hashProfileToken("missing:$selectedKeyIndex")

    private fun getAccountProfileToken(keyIndex: Int): String? {
        val account = accounts.firstOrNull { it.keyIndex == keyIndex }
        return account?.profileId?.let(::normalizeProfileId)
            ?: if (keyIndex == 0) getOrCreateDefaultProfileId() else null
    }

    private fun getOrCreateDefaultProfileId(): String = synchronized(accountProfilePersistenceLock) {
        val persisted = getKey<String>(DEFAULT_PROFILE_ID_PREFERENCE)
        val profileId = getOrCreateProfileId(persisted ?: cachedDefaultProfileId) {
            UUID.randomUUID().toString()
        }
        cachedDefaultProfileId = profileId
        if (persisted != profileId) setKey(DEFAULT_PROFILE_ID_PREFERENCE, profileId)
        profileId
    }

    private fun hashProfileToken(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private val _selectedAccountNumberFlow = MutableStateFlow(0)
    /** What account instance we are on, this number changes whenever anything about local accounts changes */
    val selectedAccountNumberFlow : StateFlow<Int> = _selectedAccountNumberFlow

    /**
     * Get or set the current account homepage.
     * Setting this does not automatically reload the homepage.
     */
    var currentHomePage: String?
        get() = getKey<String>("$currentAccount/$USER_SELECTED_HOMEPAGE_API")
        set(value) {
            val key = "$currentAccount/$USER_SELECTED_HOMEPAGE_API"
            if (value == null) {
                removeKey(key)
            } else {
                setKey(key, value)
            }
        }

    fun setAccount(account: Account, reloadHome: Boolean = true) {
        selectedKeyIndex = account.keyIndex
        AccountManager.updateAccountIds()
        showToast(context?.getString(R.string.logged_account, account.name) ?: account.name)
        MainActivity.bookmarksUpdatedEvent(true)
        MainActivity.reloadLibraryEvent(true)
        _selectedAccountNumberFlow.value += 1
        // Reissue the page request for every selected profile. Even when the source name
        // matches, HomeViewModel binds requests to a profile identity and invalidates old work.
        if (reloadHome) MainActivity.reloadHomeEvent(true)
    }

    fun getDefaultAccount(context: Context): Account {
        return accounts.let { currentAccounts ->
            currentAccounts.getOrNull(currentAccounts.indexOfFirst { it.keyIndex == 0 }) ?: Account(
                keyIndex = 0,
                name = context.getString(R.string.default_account),
                defaultImageIndex = 0,
                profileId = getOrCreateDefaultProfileId(),
            )
        }
    }

    fun getAccounts(context: Context): List<Account> {
        return accounts.toMutableList().apply {
            val item = getDefaultAccount(context)
            remove(item)
            add(0, item)
        }
    }

    /** Gets the current selected account (or default), may return null if context is null and the user is using the default account */
    fun getCurrentAccount(): Account? {
        return (context?.let {
            getAccounts(it)
        } ?: accounts.toList()).firstNotNullOfOrNull { account ->
            if (account.keyIndex == selectedKeyIndex) {
                account
            } else {
                null
            }
        }
    }

    @Serializable
    data class PosDur(
        @JsonProperty("position") @SerialName("position") val position: Long,
        @JsonProperty("duration") @SerialName("duration") val duration: Long,
    )

    fun PosDur.fixVisual(): PosDur {
        if (duration <= 0) return PosDur(0, duration)
        val percentage = position * 100 / duration
        return when {
            percentage <= 1 -> PosDur(0, duration)
            percentage <= 5 -> PosDur(5 * duration / 100, duration)
            percentage >= 95 -> PosDur(duration, duration)
            else -> this
        }
    }

    fun Int.toYear(): Date =
        GregorianCalendar.getInstance().also { it.set(Calendar.YEAR, this) }.time

    /**
     * Used to display notifications on new episodes and posters in library.
     */
    @Serializable
    abstract class LibrarySearchResponse(
       /**
        * These fields are marked @Transient because this class is only ever serialized through
        * through its subclasses, which redeclare each property with their own @SerialName
        * annotations. Without @Transient here, kotlinx.serialization would try to
        * generate a serializer for the abstract base class itself (or double-serialize
        * these fields), which fails/conflicts since these are meant to be overridden,
        * not serialized directly from the parent.
        */
        @Transient override var id: Int? = null,
        @Transient open val latestUpdatedTime: Long = 0L,
        @Transient override val name: String = "",
        @Transient override val url: String = "",
        @Transient override val apiName: String = "",
        @Transient override var type: TvType? = null,
        @Transient override var posterUrl: String? = null,
        @Transient open val year: Int? = null,
        @Transient open val syncData: Map<String, String>? = null,
        @Transient override var quality: SearchQuality? = null,
        @Transient override var posterHeaders: Map<String, String>? = null,
        @Transient open val plot: String? = null,
        @Transient override var score: Score? = null,
        @Transient open val tags: List<String>? = null,
    ) : SearchResponse {
        @JsonProperty("rating", access = JsonProperty.Access.WRITE_ONLY)
        @SerialName("rating")
        @Deprecated(
            "`rating` is the old scoring system, use score instead",
            replaceWith = ReplaceWith("score"),
            level = DeprecationLevel.ERROR,
        )
        var rating: Int? = null
            set(value) {
                if (value != null) {
                    @Suppress("DEPRECATION_ERROR")
                    score = Score.fromOld(value)
                }
            }
    }

    @OptIn(ExperimentalSerializationApi::class) // KeepGeneratedSerializer is an experimental annotation for now
    @KeepGeneratedSerializer
    @Serializable(with = SubscribedData.Serializer::class)
    data class SubscribedData(
        @JsonProperty("subscribedTime") @SerialName("subscribedTime") val subscribedTime: Long,
        @JsonProperty("lastSeenEpisodeCount") @SerialName("lastSeenEpisodeCount") val lastSeenEpisodeCount: Map<DubStatus, Int?>,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") @SerialName("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType?,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") @SerialName("year") override val year: Int?,
        @JsonProperty("syncData") @SerialName("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") @SerialName("plot") override val plot: String? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        @JsonProperty("tags") @SerialName("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse(
        id,
        latestUpdatedTime,
        name,
        url,
        apiName,
        type,
        posterUrl,
        year,
        syncData,
        quality,
        posterHeaders,
        plot,
        score,
        tags,
    ) {
        object Serializer : WriteOnlySerializer<SubscribedData>(
            SubscribedData.generatedSerializer(),
            setOf("rating"),
        )

        fun toLibraryItem(): SyncAPI.LibraryItem? {
            return SyncAPI.LibraryItem(
                name,
                url,
                id?.toString() ?: return null,
                null,
                null,
                null,
                latestUpdatedTime,
                apiName,
                type,
                posterUrl,
                posterHeaders,
                quality,
                year?.toYear(),
                this.id,
                plot = this.plot,
                score = this.score,
                tags = this.tags,
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class) // KeepGeneratedSerializer is an experimental annotation for now
    @KeepGeneratedSerializer
    @Serializable(with = BookmarkedData.Serializer::class)
    data class BookmarkedData(
        @JsonProperty("bookmarkedTime") @SerialName("bookmarkedTime") val bookmarkedTime: Long,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") @SerialName("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType?,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") @SerialName("year") override val year: Int?,
        @JsonProperty("syncData") @SerialName("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") @SerialName("plot") override val plot: String? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        @JsonProperty("tags") @SerialName("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse(
        id,
        latestUpdatedTime,
        name,
        url,
        apiName,
        type,
        posterUrl,
        year,
        syncData,
        quality,
        posterHeaders,
        plot,
    ) {
        object Serializer : WriteOnlySerializer<BookmarkedData>(
            BookmarkedData.generatedSerializer(),
            setOf("rating"),
        )

        fun toLibraryItem(id: String): SyncAPI.LibraryItem {
            return SyncAPI.LibraryItem(
                name,
                url,
                id,
                null,
                null,
                null,
                latestUpdatedTime,
                apiName,
                type,
                posterUrl,
                posterHeaders,
                quality,
                year?.toYear(),
                this.id,
                plot = this.plot,
                score = this.score,
                tags = this.tags,
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class) // KeepGeneratedSerializer is an experimental annotation for now
    @KeepGeneratedSerializer
    @Serializable(with = FavoritesData.Serializer::class)
    data class FavoritesData(
        @JsonProperty("favoritesTime") @SerialName("favoritesTime") val favoritesTime: Long,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") @SerialName("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType?,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") @SerialName("year") override val year: Int?,
        @JsonProperty("syncData") @SerialName("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") @SerialName("plot") override val plot: String? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        @JsonProperty("tags") @SerialName("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse(
        id,
        latestUpdatedTime,
        name,
        url,
        apiName,
        type,
        posterUrl,
        year,
        syncData,
        quality,
        posterHeaders,
        plot,
    ) {
        object Serializer : WriteOnlySerializer<FavoritesData>(
            FavoritesData.generatedSerializer(),
            setOf("rating"),
        )

        fun toLibraryItem(): SyncAPI.LibraryItem? {
            return SyncAPI.LibraryItem(
                name,
                url,
                id?.toString() ?: return null,
                null,
                null,
                null,
                latestUpdatedTime,
                apiName,
                type,
                posterUrl,
                posterHeaders,
                quality,
                year?.toYear(),
                this.id,
                plot = this.plot,
                score = this.score,
                tags = this.tags,
            )
        }
    }

    @Serializable
    data class ResumeWatchingResult(
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType? = null,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("watchPos") @SerialName("watchPos") val watchPos: PosDur?,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("parentId") @SerialName("parentId") val parentId: Int?,
        @JsonProperty("episode") @SerialName("episode") val episode: Int?,
        @JsonProperty("season") @SerialName("season") val season: Int?,
        @JsonProperty("isFromDownload") @SerialName("isFromDownload") val isFromDownload: Boolean,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        @JsonProperty("episodePosterUrl") @SerialName("episodePosterUrl") val episodePosterUrl: String? = null,
        @JsonProperty("episodePosterHeaders") @SerialName("episodePosterHeaders")
        val episodePosterHeaders: Map<String, String>? = null,
    ) : SearchResponse

    /**
     * A datastore wide account for future implementations of a multiple account system
     */

    fun getAllWatchStateIds(): List<Int>? {
        val folder = "$currentAccount/$RESULT_WATCH_STATE"
        return getKeys(folder)?.mapNotNull {
            it.removePrefix("$folder/").toIntOrNull()
        }
    }

    fun deleteAllResumeStateIds() {
        val folder = "$currentAccount/$RESULT_RESUME_WATCHING"
        removeKeys(folder)
    }

    fun deleteBookmarkedData(id: Int?) {
        if (id == null) return
        AccountManager.localListApi.requireLibraryRefresh = true
        removeKey("$currentAccount/$RESULT_WATCH_STATE", id.toString())
        removeKey("$currentAccount/$RESULT_WATCH_STATE_DATA", id.toString())
    }

    fun getAllResumeStateIds(): List<Int>? {
        val folder = "$currentAccount/$RESULT_RESUME_WATCHING"
        return getKeys(folder)?.mapNotNull {
            it.removePrefix("$folder/").toIntOrNull()
        }
    }

    private fun getAllResumeStateIdsOld(): List<Int>? {
        val folder = "$currentAccount/$RESULT_RESUME_WATCHING_OLD"
        return getKeys(folder)?.mapNotNull {
            it.removePrefix("$folder/").toIntOrNull()
        }
    }

    fun migrateResumeWatching() {
        // if (getKey<Boolean>(RESULT_RESUME_WATCHING_HAS_MIGRATED, false) != true) {
        setKey(RESULT_RESUME_WATCHING_HAS_MIGRATED, true)
        getAllResumeStateIdsOld()?.forEach { id ->
            getLastWatchedOld(id)?.let {
                setLastWatched(
                    it.parentId,
                    null,
                    it.episode,
                    it.season,
                    it.isFromDownload,
                    it.updateTime,
                )
                removeLastWatchedOld(it.parentId)
            }
        }
        // }
    }

    fun setLastWatched(
        parentId: Int?,
        episodeId: Int?,
        episode: Int?,
        season: Int?,
        isFromDownload: Boolean = false,
        updateTime: Long? = null,
        episodePoster: String? = null,
        episodePosterHeaders: Map<String, String>? = null,
    ) {
        if (parentId == null) return
        setKey(
            "$currentAccount/$RESULT_RESUME_WATCHING",
            parentId.toString(),
            DownloadObjects.ResumeWatching(
                parentId,
                episodeId,
                episode,
                season,
                updateTime ?: System.currentTimeMillis(),
                isFromDownload,
                episodePoster,
                episodePosterHeaders,
            )
        )
    }

    private fun removeLastWatchedOld(parentId: Int?) {
        if (parentId == null) return
        removeKey("$currentAccount/$RESULT_RESUME_WATCHING_OLD", parentId.toString())
    }

    fun removeLastWatched(parentId: Int?) {
        if (parentId == null) return
        removeKey("$currentAccount/$RESULT_RESUME_WATCHING", parentId.toString())
    }

    fun getLastWatched(id: Int?): DownloadObjects.ResumeWatching? {
        if (id == null) return null
        return getKey<DownloadObjects.ResumeWatching>(
            "$currentAccount/$RESULT_RESUME_WATCHING",
            id.toString(),
        )
    }

    /** Store one bounded header map per series so Cast metadata never carries provider headers. */
    fun cacheCastEpisodePosterHeaders(
        accountId: String,
        profileToken: String,
        apiName: String,
        episodes: List<ResultEpisode>,
        isRequestCurrent: () -> Boolean,
    ): Boolean {
        if (!isRequestCurrent()) return false
        if (episodes.size > MAX_CAST_EPISODE_HEADER_RECORDS) return false

        val now = System.currentTimeMillis()
        val episodesByParent = episodes.groupBy { it.parentId }
        if (episodesByParent.size > MAX_CAST_EPISODE_HEADER_CACHE_ENTRIES) return false
        val pendingRecords = LinkedHashMap<String, CastEpisodePosterHeaders>(episodesByParent.size)
        var totalSerializedBytes = 0L

        for ((parentId, seriesEpisodes) in episodesByParent) {
            val headersByEpisodeId = HashMap<String, Map<String, String>>()
            var headerPairCount = 0
            var headerTextChars = 0L
            for (episode in seriesEpisodes) {
                val headers = episode.posterHeaders?.takeIf { it.isNotEmpty() } ?: continue
                headerPairCount += headers.size
                if (headerPairCount > MAX_CAST_EPISODE_HEADER_PAIRS) return false
                for ((key, value) in headers) {
                    headerTextChars += key.length.toLong() + value.length.toLong()
                    if (headerTextChars > MAX_CAST_EPISODE_HEADER_TEXT_CHARS) return false
                }
                headersByEpisodeId[episode.id.toString()] = headers.toMap()
            }
            if (headersByEpisodeId.size > MAX_CAST_EPISODE_HEADER_RECORDS) return false

            val seriesKey = castEpisodePosterHeadersKey(apiName, parentId)
            val record = CastEpisodePosterHeaders(headersByEpisodeId, now, profileToken)
            val serializedBytes = record.toJson().toByteArray(Charsets.UTF_8).size
            if (serializedBytes > MAX_CAST_EPISODE_HEADER_BYTES) return false
            totalSerializedBytes += serializedBytes
            if (totalSerializedBytes > MAX_CAST_EPISODE_HEADER_BYTES) return false
            pendingRecords[seriesKey] = record
        }

        val preferences = context?.getSharedPrefs() ?: return false
        return synchronized(castEpisodePosterHeadersWriteLock) {
            if (!isRequestCurrent()) return@synchronized false
            val existingPaths = preferences.all.keys.filter(::isCastEpisodePosterHeaderPath)
            val protectedPaths = pendingRecords.keys
                .map { "$accountId/$CAST_EPISODE_POSTER_HEADERS/$it" }
                .toHashSet()
            val recordsByPath = LinkedHashMap<String, Pair<String, CastEpisodePosterHeaders>>()
            val serializedBytesByPath = HashMap<String, Long>()
            val pathsToRemove = LinkedHashSet<String>()

            // Cache pruning runs on the IO caller. If old versions left an oversized cache,
            // retain only this sequence's protected records rather than parsing an unbounded set.
            if (existingPaths.size <= MAX_CAST_EPISODE_HEADER_CACHE_ENTRIES) {
                for (path in existingPaths) {
                    val seriesKey = path.substringAfterLast('/')
                    if (path in protectedPaths) continue
                    val record = readCastEpisodePosterHeaders(preferences, path)
                    val pathAccountId = path.substringBefore('/')
                    val pathProfileToken = pathAccountId.toIntOrNull()?.let(::getAccountProfileToken)
                    if (record == null || !record.isFresh() || pathProfileToken == null ||
                        record.profileToken != pathProfileToken
                    ) {
                        pathsToRemove.add(path)
                    } else {
                        recordsByPath[path] = seriesKey to record
                        serializedBytesByPath[path] = record.toJson().toByteArray(Charsets.UTF_8).size.toLong()
                    }
                }
            } else {
                pathsToRemove.addAll(existingPaths.filterNot(protectedPaths::contains))
            }

            val folder = "$accountId/$CAST_EPISODE_POSTER_HEADERS"
            for ((seriesKey, record) in pendingRecords) {
                val path = "$folder/$seriesKey"
                recordsByPath[path] = seriesKey to record
                serializedBytesByPath[path] = record.toJson().toByteArray(Charsets.UTF_8).size.toLong()
            }

            fun retainedCacheBytes(): Long = recordsByPath.keys.sumOf { path ->
                serializedBytesByPath.getValue(path) + path.toByteArray(Charsets.UTF_8).size
            }

            while (recordsByPath.size > MAX_CAST_EPISODE_HEADER_CACHE_ENTRIES ||
                retainedCacheBytes() > MAX_CAST_EPISODE_HEADER_CACHE_BYTES
            ) {
                val oldestUnprotected = recordsByPath.entries
                    .asSequence()
                    .filterNot { it.key in protectedPaths }
                    .minByOrNull { it.value.second.updatedAt }
                    ?: return@synchronized false
                recordsByPath.remove(oldestUnprotected.key)
                serializedBytesByPath.remove(oldestUnprotected.key)
                pathsToRemove.add(oldestUnprotected.key)
            }

            // A newer start/profile must not be overwritten by work that completed late.
            if (!isRequestCurrent()) return@synchronized false
            val editor = preferences.edit()
            pathsToRemove.forEach { path -> editor.remove(path) }
            for ((seriesKey, record) in pendingRecords) {
                editor.putString("$folder/$seriesKey", record.toJson())
            }
            if (!editor.commit()) return@synchronized false

            pathsToRemove.forEach { path ->
                removeCastEpisodePosterHeaderMemoryPath(path)
            }
            pendingRecords.forEach { (seriesKey, record) ->
                castEpisodePosterHeadersCache[castEpisodePosterHeadersCacheKey(accountId, profileToken, seriesKey)] = record
            }
            pruneCastEpisodePosterHeaderMemoryCache()
            true
        }
    }

    /** Remove stale cached headers when the current sequence's optional header cache cannot fit. */
    fun clearCastEpisodePosterHeaders(
        accountId: String,
        profileToken: String,
        apiName: String,
        episodes: List<ResultEpisode>,
        isRequestCurrent: () -> Boolean,
    ) {
        val seriesKeys = episodes.map { castEpisodePosterHeadersKey(apiName, it.parentId) }.toSet()
        if (seriesKeys.isEmpty()) return

        val folder = "$accountId/$CAST_EPISODE_POSTER_HEADERS"
        val preferences = context?.getSharedPrefs() ?: return
        synchronized(castEpisodePosterHeadersWriteLock) {
            if (!isRequestCurrent()) return
            val paths = seriesKeys.map { "$folder/$it" }
                .filter { path -> readCastEpisodePosterHeaders(preferences, path)?.profileToken == profileToken }
            val editor = preferences.edit()
            paths.forEach { editor.remove(it) }
            if (!isRequestCurrent()) return
            if (!editor.commit()) {
                logError(IllegalStateException("Could not clear stale Cast poster header cache records."))
            }
            seriesKeys.forEach { seriesKey ->
                castEpisodePosterHeadersCache.remove(
                    castEpisodePosterHeadersCacheKey(accountId, profileToken, seriesKey),
                )
            }
        }
    }

    /** Prune expired and over-budget records across all profiles before restoring a sequence. */
    fun pruneCastEpisodePosterHeaderCache(
        protectedAccountId: String,
        protectedProfileToken: String,
        apiName: String,
        protectedParentIds: List<Int>,
    ) {
        val preferences = context?.getSharedPrefs() ?: return
        val protectedFolder = "$protectedAccountId/$CAST_EPISODE_POSTER_HEADERS"
        val protectedPaths = protectedParentIds.map { parentId ->
            "$protectedFolder/${castEpisodePosterHeadersKey(apiName, parentId)}"
        }.toSet()
        synchronized(castEpisodePosterHeadersWriteLock) {
            val paths = preferences.all.keys.filter(::isCastEpisodePosterHeaderPath)
            val records = LinkedHashMap<String, CastEpisodePosterHeaders>()
            val serializedBytes = HashMap<String, Long>()
            val pathsToRemove = LinkedHashSet<String>()

            if (paths.size <= MAX_CAST_EPISODE_HEADER_CACHE_ENTRIES) {
                for (path in paths) {
                    val record = readCastEpisodePosterHeaders(preferences, path)
                    val profileToken = path.substringBefore('/').toIntOrNull()?.let(::getAccountProfileToken)
                    if (record == null || !record.isFresh() || profileToken == null ||
                        record.profileToken != profileToken
                    ) {
                        pathsToRemove.add(path)
                    } else {
                        records[path] = record
                        serializedBytes[path] = record.toJson().toByteArray(Charsets.UTF_8).size.toLong()
                    }
                }
            } else {
                pathsToRemove.addAll(paths.filterNot(protectedPaths::contains))
                for (path in paths.filter(protectedPaths::contains)) {
                    readCastEpisodePosterHeaders(preferences, path)?.let { record ->
                        if (record.isFresh() && record.profileToken == protectedProfileToken) {
                            records[path] = record
                            serializedBytes[path] = record.toJson().toByteArray(Charsets.UTF_8).size.toLong()
                        } else {
                            pathsToRemove.add(path)
                        }
                    } ?: pathsToRemove.add(path)
                }
            }

            while (records.size > MAX_CAST_EPISODE_HEADER_CACHE_ENTRIES ||
                records.keys.sumOf { path ->
                    serializedBytes.getValue(path) + path.toByteArray(Charsets.UTF_8).size
                } > MAX_CAST_EPISODE_HEADER_CACHE_BYTES
            ) {
                val oldest = records.entries
                    .asSequence()
                    .filterNot { it.key in protectedPaths }
                    .minByOrNull { it.value.updatedAt }
                    ?: return
                records.remove(oldest.key)
                serializedBytes.remove(oldest.key)
                pathsToRemove.add(oldest.key)
            }

            if (pathsToRemove.isNotEmpty()) {
                val editor = preferences.edit()
                pathsToRemove.forEach { editor.remove(it) }
                if (!editor.commit()) {
                    logError(IllegalStateException("Could not prune Cast poster header cache records."))
                    return
                }
                pathsToRemove.forEach(::removeCastEpisodePosterHeaderMemoryPath)
            }
        }
    }

    /** Load a series header map on a worker before using it to restore Cast episode metadata. */
    fun preloadCachedCastEpisodePosterHeaders(
        accountId: String,
        profileToken: String,
        apiName: String,
        parentId: Int,
    ): Boolean {
        val seriesKey = castEpisodePosterHeadersKey(apiName, parentId)
        val cacheKey = castEpisodePosterHeadersCacheKey(accountId, profileToken, seriesKey)
        if (castEpisodePosterHeadersCache[cacheKey]?.let { it.isFresh() && it.profileToken == profileToken } == true) {
            return true
        }

        val preferences = context?.getSharedPrefs() ?: return false
        return synchronized(castEpisodePosterHeadersWriteLock) {
            if (castEpisodePosterHeadersCache[cacheKey]?.let { it.isFresh() && it.profileToken == profileToken } == true) {
                return@synchronized true
            }
            val path = "$accountId/$CAST_EPISODE_POSTER_HEADERS/$seriesKey"
            val record = readCastEpisodePosterHeaders(preferences, path)
            if (record == null || !record.isFresh() || record.profileToken != profileToken) {
                if (record != null) {
                    preferences.edit().remove(path).commit()
                }
                castEpisodePosterHeadersCache.remove(cacheKey)
                return@synchronized false
            }

            castEpisodePosterHeadersCache[cacheKey] = record
            pruneCastEpisodePosterHeaderMemoryCache()
            true
        }
    }

    fun getCachedCastEpisodePosterHeaders(
        accountId: String,
        profileToken: String,
        apiName: String,
        parentId: Int,
        episodeId: Int,
    ): Map<String, String>? {
        val seriesKey = castEpisodePosterHeadersKey(apiName, parentId)
        val record = castEpisodePosterHeadersCache[
            castEpisodePosterHeadersCacheKey(accountId, profileToken, seriesKey),
        ]
            ?: return null
        return record.headersByEpisodeId[episodeId.toString()].takeIf {
            record.isFresh() && record.profileToken == profileToken
        }
    }

    private fun pruneCastEpisodePosterHeaderMemoryCache() {
        val entries = castEpisodePosterHeadersCache.entries
            .map { it.key to it.value }
            .sortedBy { it.second.updatedAt }
        var totalBytes = entries.sumOf { (key, record) ->
            record.toJson().toByteArray(Charsets.UTF_8).size.toLong() +
                key.toByteArray(Charsets.UTF_8).size
        }
        var remainingEntries = entries.size
        for ((key, record) in entries) {
            if (remainingEntries <= MAX_CAST_EPISODE_HEADER_CACHE_ENTRIES &&
                totalBytes <= MAX_CAST_EPISODE_HEADER_CACHE_BYTES
            ) break
            if (castEpisodePosterHeadersCache.remove(key, record)) {
                remainingEntries--
                totalBytes -= record.toJson().toByteArray(Charsets.UTF_8).size.toLong() +
                    key.toByteArray(Charsets.UTF_8).size
            }
        }
    }

    private fun CastEpisodePosterHeaders.isFresh(): Boolean =
        System.currentTimeMillis() - updatedAt <= CAST_EPISODE_HEADERS_MAX_AGE_MS

    private fun readCastEpisodePosterHeaders(
        preferences: SharedPreferences,
        path: String,
    ): CastEpisodePosterHeaders? {
        val raw = preferences.getString(path, null) ?: return null
        if (raw.length > MAX_CAST_EPISODE_HEADER_BYTES ||
            raw.toByteArray(Charsets.UTF_8).size > MAX_CAST_EPISODE_HEADER_BYTES
        ) {
            return null
        }
        return runCatching { getKey<CastEpisodePosterHeaders>(path) }.getOrNull()
    }

    private fun castEpisodePosterHeadersCacheKey(
        accountId: String,
        profileToken: String,
        seriesKey: String,
    ): String = "$accountId/$CAST_EPISODE_POSTER_HEADERS/$profileToken/$seriesKey"

    private fun isCastEpisodePosterHeaderPath(path: String): Boolean {
        val parts = path.split('/')
        return parts.size == 3 &&
            parts[0].toIntOrNull() != null &&
            parts[1] == CAST_EPISODE_POSTER_HEADERS &&
            parts[2].length == 64 &&
            parts[2].all { it in '0'..'9' || it in 'a'..'f' }
    }

    private fun removeCastEpisodePosterHeaderMemoryPath(path: String) {
        val parts = path.split('/')
        if (parts.size != 3) return
        val prefix = "${parts[0]}/$CAST_EPISODE_POSTER_HEADERS/"
        castEpisodePosterHeadersCache.keys
            .filter { it.startsWith(prefix) && it.endsWith("/${parts[2]}") }
            .forEach { key -> castEpisodePosterHeadersCache.remove(key) }
    }

    private fun castEpisodePosterHeadersKey(apiName: String, parentId: Int): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$apiName|$parentId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun getLastWatchedOld(id: Int?): DownloadObjects.ResumeWatching? {
        if (id == null) return null
        return getKey<DownloadObjects.ResumeWatching>(
            "$currentAccount/$RESULT_RESUME_WATCHING_OLD",
            id.toString(),
        )
    }

    fun setBookmarkedData(id: Int?, data: BookmarkedData) {
        if (id == null) return
        setKey("$currentAccount/$RESULT_WATCH_STATE_DATA", id.toString(), data)
        AccountManager.localListApi.requireLibraryRefresh = true
    }

    fun getBookmarkedData(id: Int?): BookmarkedData? {
        if (id == null) return null
        return getKey<BookmarkedData>("$currentAccount/$RESULT_WATCH_STATE_DATA", id.toString())
    }

    fun getAllBookmarkedData(): List<BookmarkedData> {
        return getKeys("$currentAccount/$RESULT_WATCH_STATE_DATA")?.mapNotNull {
            getKey<BookmarkedData>(it)
        } ?: emptyList()
    }

    fun getAllSubscriptions(): List<SubscribedData> {
        return getKeys("$currentAccount/$RESULT_SUBSCRIBED_STATE_DATA")?.mapNotNull {
            getKey<SubscribedData>(it)
        } ?: emptyList()
    }

    fun removeSubscribedData(id: Int?) {
        if (id == null) return
        AccountManager.localListApi.requireLibraryRefresh = true
        removeKey("$currentAccount/$RESULT_SUBSCRIBED_STATE_DATA", id.toString())
    }

    /**
     * Set new seen episodes and update time
     */
    fun updateSubscribedData(id: Int?, data: SubscribedData?, episodeResponse: EpisodeResponse?) {
        if (id == null || data == null || episodeResponse == null) return
        val newData = data.copy(
            latestUpdatedTime = unixTimeMS,
            lastSeenEpisodeCount = episodeResponse.getLatestEpisodes(),
        )
        setKey("$currentAccount/$RESULT_SUBSCRIBED_STATE_DATA", id.toString(), newData)
    }

    fun setSubscribedData(id: Int?, data: SubscribedData) {
        if (id == null) return
        setKey("$currentAccount/$RESULT_SUBSCRIBED_STATE_DATA", id.toString(), data)
        AccountManager.localListApi.requireLibraryRefresh = true
    }

    fun getSubscribedData(id: Int?): SubscribedData? {
        if (id == null) return null
        return getKey<SubscribedData>("$currentAccount/$RESULT_SUBSCRIBED_STATE_DATA", id.toString())
    }

    fun getAllFavorites(): List<FavoritesData> {
        return getKeys("$currentAccount/$RESULT_FAVORITES_STATE_DATA")?.mapNotNull {
            getKey<FavoritesData>(it)
        } ?: emptyList()
    }

    fun removeFavoritesData(id: Int?) {
        if (id == null) return
        AccountManager.localListApi.requireLibraryRefresh = true
        removeKey("$currentAccount/$RESULT_FAVORITES_STATE_DATA", id.toString())
    }

    fun setFavoritesData(id: Int?, data: FavoritesData) {
        if (id == null) return
        setKey("$currentAccount/$RESULT_FAVORITES_STATE_DATA", id.toString(), data)
        AccountManager.localListApi.requireLibraryRefresh = true
    }

    fun getFavoritesData(id: Int?): FavoritesData? {
        if (id == null) return null
        return getKey<FavoritesData>("$currentAccount/$RESULT_FAVORITES_STATE_DATA", id.toString())
    }

    fun setViewPos(id: Int?, pos: Long, dur: Long) {
        if (id == null) return
        if (dur < 30_000) return // too short
        setKey("$currentAccount/$VIDEO_POS_DUR", id.toString(), PosDur(pos, dur))
    }

    /**
     * Sets the position, duration, and resume data of an episode/movie.
     * When the next episode is unknown, progress past the resume threshold stays on the current episode.
     * A known null next episode means the current episode is final and may remove its resume entry.
     */
    fun setViewPosAndResume(
        id: Int?,
        position: Long,
        duration: Long,
        currentEpisode: Any?,
        nextEpisode: Any?,
        isNextEpisodeKnown: Boolean = true,
    ) {
        if (duration <= 0L || position < 0L) return
        setViewPos(id, position, duration)
        if (id != null) {
            when (val meta = currentEpisode) {
                is ResultEpisode -> {
                    if (meta.videoWatchState == VideoWatchState.Watched) {
                        setVideoWatchState(id, VideoWatchState.None)
                    }
                }
            }
        }

        val percentage = position * 100L / duration
        val nextEp = percentage >= NEXT_WATCH_EPISODE_PERCENTAGE
        val resumeMeta = resolveResumeMetadata(currentEpisode, nextEpisode, nextEp, isNextEpisodeKnown)
        if (resumeMeta == null && nextEp && isNextEpisodeKnown) {
            // remove last watched as it is the last episode and you have watched too much
            when (val newMeta = currentEpisode) {
                is ResultEpisode -> {
                    removeLastWatched(newMeta.parentId)
                }

                is ExtractorUri -> {
                    removeLastWatched(newMeta.parentId)
                }
            }
        } else {
            // save resume
            when (resumeMeta) {
                is ResultEpisode -> {
                    setLastWatched(
                        resumeMeta.parentId,
                        resumeMeta.id,
                        resumeMeta.episode,
                        resumeMeta.season,
                        isFromDownload = false,
                        episodePoster = resumeMeta.poster,
                        episodePosterHeaders = resumeMeta.posterHeaders,
                    )
                }

                is ExtractorUri -> {
                    setLastWatched(
                        resumeMeta.parentId,
                        resumeMeta.id,
                        resumeMeta.episode,
                        resumeMeta.season,
                        isFromDownload = true,
                    )
                }
            }
        }
    }

    fun getViewPos(id: Int?): PosDur? {
        if (id == null) return null
        return getKey<PosDur>("$currentAccount/$VIDEO_POS_DUR", id.toString(), null)
    }

    fun getVideoWatchState(id: Int?): VideoWatchState? {
        if (id == null) return null
        return getKey<VideoWatchState>("$currentAccount/$VIDEO_WATCH_STATE", id.toString(), null)
    }

    fun setVideoWatchState(id: Int?, watchState: VideoWatchState) {
        if (id == null) return
        // None == No key
        if (watchState == VideoWatchState.None) {
            removeKey("$currentAccount/$VIDEO_WATCH_STATE", id.toString())
        } else {
            setKey("$currentAccount/$VIDEO_WATCH_STATE", id.toString(), watchState)
        }
    }

    fun getDub(id: Int): DubStatus? {
        return DubStatus.entries
            .getOrNull(getKey<Int>("$currentAccount/$RESULT_DUB", id.toString(), -1) ?: -1)
    }

    fun setDub(id: Int, status: DubStatus) {
        setKey("$currentAccount/$RESULT_DUB", id.toString(), status.ordinal)
    }

    fun setResultWatchState(id: Int?, status: Int) {
        if (id == null) return
        if (status == WatchType.NONE.internalId) {
            deleteBookmarkedData(id)
        } else {
            setKey("$currentAccount/$RESULT_WATCH_STATE", id.toString(), status)
        }
    }

    fun getResultWatchState(id: Int): WatchType {
        return WatchType.fromInternalId(
            getKey<Int>(
                "$currentAccount/$RESULT_WATCH_STATE",
                id.toString(),
                null,
            )
        )
    }

    fun getResultSeason(id: Int): Int? {
        return getKey<Int>("$currentAccount/$RESULT_SEASON", id.toString(), null)
    }

    fun setResultSeason(id: Int, value: Int?) {
        setKey("$currentAccount/$RESULT_SEASON", id.toString(), value)
    }

    fun getResultEpisode(id: Int): Int? {
        return getKey<Int>("$currentAccount/$RESULT_EPISODE", id.toString(), null)
    }

    fun setResultEpisode(id: Int, value: Int?) {
        setKey("$currentAccount/$RESULT_EPISODE", id.toString(), value)
    }

    fun addSync(id: Int, idPrefix: String, url: String) {
        setKey("${idPrefix}_sync", id.toString(), url)
    }

    fun getSync(id: Int, idPrefixes: List<String>): List<String?> {
        return idPrefixes.map { idPrefix ->
            getKey<String>("${idPrefix}_sync", id.toString())
        }
    }

    var pinnedProviders: Array<String>
        get() = getKey<Array<String>>(USER_PINNED_PROVIDERS) ?: emptyArray<String>()
        set(value) = setKey(USER_PINNED_PROVIDERS, value)
}
