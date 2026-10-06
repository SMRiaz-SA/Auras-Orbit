package com.lagradost.common.storage

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.common.db.DatabaseFactory
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import java.util.UUID

enum class DesktopWatchType(val id: Int, val stringRes: String) {
    WATCHING(0, "Watching"),
    COMPLETED(1, "Completed"),
    ONHOLD(2, "On Hold"),
    DROPPED(3, "Dropped"),
    PLANTOWATCH(4, "Plan to Watch"),
    REWATCHING(5, "Re-watching"),
}

private val PROFILE_SCOPED_BOOKMARK_ID = Regex("""^p\d+_""")

data class DesktopBookmark(
    val id: String,
    val name: String,
    val url: String,
    val apiName: String,
    val posterUrl: String?,
    val watchType: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
)

data class WatchHistory(
    val parentId: String,
    val showName: String,
    val showUrl: String,
    val apiName: String,
    val posterUrl: String?,
    val episodeThumbnailUrl: String?,
    val screenshotUrl: String?,
    val episode: Int?,
    val season: Int?,
    val episodeId: String?,
    val position: Long,
    val duration: Long,
    val updateTime: Long = System.currentTimeMillis(),
    val episodeName: String? = null,
    val episodeDescription: String? = null,
)

data class FollowedShow(
    val profileId: Int,
    val providerName: String,
    val showUrl: String,
    val showName: String,
    val posterUrl: String? = null,
    val tmdbId: Int? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastRefreshAt: Long = 0L,
    val lastRefreshError: String? = null,
)

data class EpisodeReleaseRecord(
    val tmdbId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val airDate: String,
    val episodeName: String? = null,
    val overview: String? = null,
    val stillUrl: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
)

data class EpisodeWatchMark(
    val profileId: Int,
    val providerName: String,
    val showUrl: String,
    val episodeKey: String,
    val episodeId: String,
    val showName: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val watchedAt: Long = System.currentTimeMillis(),
)

data class DesktopCustomList(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val createdAt: Long,
    val showOnHome: Boolean = false,
)

data class DesktopCustomListItem(
    val listId: String,
    val bookmarkId: String,
    val addedAt: Long,
)

data class DesktopCustomListsSnapshot(
    val lists: List<DesktopCustomList> = emptyList(),
    val items: List<DesktopCustomListItem> = emptyList(),
)

data class ProfileLibrarySnapshot(
    val bookmarks: List<DesktopBookmark>,
    val history: List<WatchHistory>,
    val followedShows: List<FollowedShow> = emptyList(),
    val episodeWatchMarks: List<EpisodeWatchMark> = emptyList(),
    val customLists: List<DesktopCustomList> = emptyList(),
    val customListItems: List<DesktopCustomListItem> = emptyList(),
)

data class ProfileLibraryMergeResult(
    val bookmarksAdded: Int,
    val bookmarksSkipped: Int,
    val historyAdded: Int,
    val historyUpdated: Int,
    val historyUnchanged: Int,
    val followedShowsAdded: Int = 0,
    val followedShowsSkipped: Int = 0,
    val episodeWatchMarksAdded: Int = 0,
    val episodeWatchMarksSkipped: Int = 0,
    val customListsAdded: Int = 0,
    val customListsMerged: Int = 0,
    val customListItemsAdded: Int = 0,
    val customListItemsSkipped: Int = 0,
)

data class PluginUpdateRecord(
    val pluginName: String,
    val version: Int,
    val iconUrl: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val isSuccess: Boolean = true,
    val errorMessage: String? = null,
)

object DesktopDataStore {
    @PublishedApi internal val mapper: ObjectMapper =
        jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val dataFile = File(PlatformPaths.dataDir, "datastore.json")

    val rawKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile @PublishedApi
    internal var isPreCacheLoaded = false

    val historyUpdates = MutableStateFlow(0)
    val episodeTrackingUpdates = MutableStateFlow(0)
    val customListUpdates = MutableStateFlow(0)
    val pluginUpdatesFlow = MutableStateFlow(0)

    fun init() {
        // Initialize the database
        val db = DatabaseFactory.database

        // Pre-load all key-values into RAM cache for zero-latency O(1) reads
        try {
            db.cloudstreamDBQueries.selectAllKeyValues().executeAsList().forEach { row ->
                rawKeyCache[row.key] = row.value_
            }
            isPreCacheLoaded = true
        } catch (e: Exception) {
            AppLogger.e("Failed to pre-cache key-values", e)
        }

        // Migration from old datastore.json
        if (dataFile.exists() && dataFile.length() > 0L) {
            try {
                AppLogger.i("Migrating legacy datastore.json to SQLDelight...")
                val cache: Map<String, String> = mapper.readValue(dataFile)

                db.cloudstreamDBQueries.transaction {
                    for ((key, jsonStr) in cache) {
                        when (key) {
                            "user_bookmarks" -> {
                                try {
                                    val bookmarks: List<DesktopBookmark> = mapper.readValue(jsonStr, object : TypeReference<List<DesktopBookmark>>() {})
                                    bookmarks.forEach { b ->
                                        db.cloudstreamDBQueries.insertBookmark(b.id, b.name, b.url, b.apiName, b.posterUrl, b.watchType.toLong(), b.dateAdded)
                                    }
                                } catch (e: Exception) {
                                    AppLogger.e("Failed to migrate bookmarks", e)
                                    throw e
                                }
                            }
                            "user_watch_history" -> {
                                try {
                                    val history: List<WatchHistory> = mapper.readValue(jsonStr, object : TypeReference<List<WatchHistory>>() {})
                                    history.forEach { h ->
                                        db.cloudstreamDBQueries.insertWatchHistory(
                                            h.parentId, h.episodeId ?: "", h.showName, h.showUrl, h.apiName, h.posterUrl,
                                            h.episodeThumbnailUrl, h.screenshotUrl,
                                            h.episode?.toLong(), h.season?.toLong(), h.position, h.duration, h.updateTime,
                                            h.episodeName, h.episodeDescription,
                                        )
                                    }
                                } catch (e: Exception) {
                                    AppLogger.e("Failed to migrate watch history", e)
                                    throw e
                                }
                            }
                            "plugin_updates_history_v2" -> {
                                try {
                                    val updates: List<PluginUpdateRecord> = mapper.readValue(jsonStr, object : TypeReference<List<PluginUpdateRecord>>() {})
                                    updates.forEach { u ->
                                        db.cloudstreamDBQueries.insertPluginUpdate(u.pluginName, u.version.toLong(), u.iconUrl, u.timestamp)
                                    }
                                } catch (e: Exception) {
                                    AppLogger.e("Failed to migrate plugin updates", e)
                                    throw e
                                }
                            }
                            else -> {
                                db.cloudstreamDBQueries.insertKeyValue(key, jsonStr)
                            }
                        }
                    }
                }
                val bakFile = File(PlatformPaths.dataDir, "datastore.json.bak")
                db.cloudstreamDBQueries.selectAllKeyValues().executeAsList().forEach { rawKeyCache[it.key] = it.value_ }
                java.nio.file.Files.move(dataFile.toPath(), bakFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                AppLogger.i("Migration complete. Old file renamed to datastore.json.bak")
            } catch (e: Exception) {
                AppLogger.e("Critical failure migrating datastore.json", e)
                throw IllegalStateException("Legacy data migration failed; the original datastore.json was preserved for recovery", e)
            }
        }
    }

    fun <T> setKey(key: String, value: T) {
        setKeys(mapOf(key to value))
    }

    /** Returns only after the complete mutation is committed. Failures propagate to the caller. */
    @Synchronized
    fun setKeys(values: Map<String, Any?>, removed: Set<String> = emptySet()) {
        val deleted = getKey<List<Int>>("deleted_profile_ids_v1").orEmpty().toSet()
        check(values.keys.none { key -> deleted.any { key.startsWith("$it/") || key.endsWith("_profile_$it") } }) {
            "Cannot write data for a deleted profile"
        }
        val serialized = values.mapValues { mapper.writeValueAsString(it.value) }
        val queries = DatabaseFactory.database.cloudstreamDBQueries
        queries.transaction {
            removed.forEach { queries.deleteKeyValue(it) }
            serialized.forEach { (key, json) -> queries.insertKeyValue(key, json) }
        }
        removed.forEach { rawKeyCache.remove(it) }
        rawKeyCache.putAll(serialized)
    }

    fun <T> getKey(key: String, clazz: Class<T>): T? {
        val json = rawKeyCache[key] ?: if (!isPreCacheLoaded) {
            val dbJson = DatabaseFactory.database.cloudstreamDBQueries.selectKeyValue(key).executeAsOneOrNull()
            if (dbJson != null) {
                rawKeyCache[key] = dbJson
            }
            dbJson
        } else {
            null
        } ?: return null
        return try {
            mapper.readValue(json, clazz)
        } catch (e: Exception) {
            null
        }
    }

    inline fun <reified T> getKey(key: String): T? {
        val json = rawKeyCache[key] ?: if (!isPreCacheLoaded) {
            val dbJson = DatabaseFactory.database.cloudstreamDBQueries.selectKeyValue(key).executeAsOneOrNull()
            if (dbJson != null) {
                rawKeyCache[key] = dbJson
            }
            dbJson
        } else {
            null
        } ?: return null
        return try {
            mapper.readValue(json)
        } catch (e: Exception) {
            null
        }
    }

    fun containsKey(key: String): Boolean {
        if (rawKeyCache.containsKey(key)) return true
        if (isPreCacheLoaded) return false
        return DatabaseFactory.database.cloudstreamDBQueries.selectKeyValue(key).executeAsOneOrNull() != null
    }

    fun removeKey(key: String) {
        setKeys(emptyMap(), setOf(key))
    }

    @Synchronized
    fun deleteProfileData(profileId: Int, profileValues: Map<String, Any?>) {
        val committedValues = profileValues + ("deleted_profile_ids_v1" to (getKey<List<Int>>("deleted_profile_ids_v1").orEmpty() + profileId).distinct())
        val prefix = "p${profileId}_"
        val suffix = if (profileId == 0) "_default" else "_profile_$profileId"
        val queries = DatabaseFactory.database.cloudstreamDBQueries
        val removed = queries.selectAllKeyValues().executeAsList().map { it.key }.filter {
            it.startsWith("$profileId/") ||
                (it.startsWith("auth_tokens_") || it.startsWith("tracker_credentials_v1_")) && it.endsWith(suffix)
        }.toSet()
        queries.transaction {
            queries.selectAllBookmarks().executeAsList().filter {
                it.id.startsWith(prefix) || (profileId == 0 && !PROFILE_SCOPED_BOOKMARK_ID.containsMatchIn(it.id))
            }
                .forEach { queries.deleteBookmark(it.id) }
            queries.selectAllWatchHistory().executeAsList().filter { it.parentId.startsWith(prefix) || (profileId == 0 && !it.parentId.matches(Regex("p\\d+_.*"))) }
                .forEach { queries.deleteWatchHistoryByParent(it.parentId) }
            queries.deleteFollowedShowsByProfile(profileId.toLong())
            queries.deleteEpisodeWatchMarksByProfile(profileId.toLong())
            queries.deleteCustomListItemsByProfile(profileId.toLong())
            queries.deleteCustomListsByProfile(profileId.toLong())
            removed.forEach { queries.deleteKeyValue(it) }
            committedValues.forEach { (key, value) -> queries.insertKeyValue(key, mapper.writeValueAsString(value)) }
        }
        removed.forEach { rawKeyCache.remove(it) }
        committedValues.forEach { (key, value) -> rawKeyCache[key] = mapper.writeValueAsString(value) }
        notifyCustomListsChanged()
    }

    fun isProfileDeleted(profileId: Int): Boolean = profileId in getKey<List<Int>>("deleted_profile_ids_v1").orEmpty()

    private fun isHistoryOwnerDeleted(parentId: String): Boolean {
        val profileId = Regex("^p(\\d+)_").find(parentId)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return isProfileDeleted(profileId)
    }

    var activeProfileProvider: () -> Int = { 0 }
    val activeProfileId: Int
        get() = activeProfileProvider()

    fun getProfileKey(key: String, profileId: Int = activeProfileId): String = "$profileId/$key"

    fun <T> setProfileKey(key: String, value: T, profileId: Int = activeProfileId) {
        setKey(getProfileKey(key, profileId), value)
    }

    fun <T> getProfileKey(key: String, clazz: Class<T>, profileId: Int = activeProfileId): T? {
        return getKey(getProfileKey(key, profileId), clazz)
    }

    inline fun <reified T> getProfileKey(key: String, profileId: Int = activeProfileId): T? {
        return getKey<T>(getProfileKey(key, profileId))
    }

    fun removeProfileKey(key: String, profileId: Int = activeProfileId) {
        removeKey(getProfileKey(key, profileId))
    }

    fun getAllKeysWithPrefix(prefix: String): List<String> {
        return DatabaseFactory.database.cloudstreamDBQueries.selectAllKeyValues()
            .executeAsList()
            .map { it.key }
            .filter { it.startsWith(prefix) }
    }

    /** Reads both profile-owned library tables from one SQLite snapshot. */
    @Synchronized
    fun getProfileLibrarySnapshot(profileId: Int = activeProfileId): ProfileLibrarySnapshot {
        val queries = DatabaseFactory.database.cloudstreamDBQueries
        var bookmarks: List<DesktopBookmark> = emptyList()
        var history: List<WatchHistory> = emptyList()
        var followedShows: List<FollowedShow> = emptyList()
        var episodeWatchMarks: List<EpisodeWatchMark> = emptyList()
        var customLists: List<DesktopCustomList> = emptyList()
        var customListItems: List<DesktopCustomListItem> = emptyList()
        queries.transaction {
            bookmarks = getBookmarks(profileId)
            history = getAllWatchHistory(profileId)
            followedShows = getFollowedShows(profileId)
            episodeWatchMarks = getEpisodeWatchMarks(profileId)
            customLists = getCustomLists(profileId)
            customListItems = getCustomListItems(profileId)
        }
        return ProfileLibrarySnapshot(
            bookmarks = bookmarks,
            history = history,
            followedShows = followedShows,
            episodeWatchMarks = episodeWatchMarks,
            customLists = customLists,
            customListItems = customListItems,
        )
    }

    /**
     * Merges a validated portable library into one profile in a single transaction.
     * Existing bookmarks are preserved. Episode progress only moves forward by update time.
     */
    @Synchronized
    fun mergeProfileLibrary(
        profileId: Int,
        bookmarks: List<DesktopBookmark>,
        history: List<WatchHistory>,
        followedShows: List<FollowedShow> = emptyList(),
        episodeWatchMarks: List<EpisodeWatchMark> = emptyList(),
        customLists: List<DesktopCustomList> = emptyList(),
        customListItems: List<DesktopCustomListItem> = emptyList(),
    ): ProfileLibraryMergeResult {
        check(!isProfileDeleted(profileId)) { "The target profile no longer exists" }

        val queries = DatabaseFactory.database.cloudstreamDBQueries
        var bookmarksAdded = 0
        var bookmarksSkipped = 0
        var historyAdded = 0
        var historyUpdated = 0
        var historyUnchanged = 0
        var followedShowsAdded = 0
        var followedShowsSkipped = 0
        var episodeWatchMarksAdded = 0
        var episodeWatchMarksSkipped = 0
        var customListsAdded = 0
        var customListsMerged = 0
        var customListItemsAdded = 0
        var customListItemsSkipped = 0
        val legacyCompletedMarks = mutableListOf<EpisodeWatchMark>()

        queries.transaction {
            val existingBookmarkIds = getBookmarks(profileId).mapTo(mutableSetOf()) { it.id }
            bookmarks.forEach { bookmark ->
                if (!existingBookmarkIds.add(bookmark.id)) {
                    bookmarksSkipped++
                } else {
                    queries.insertBookmark(
                        "p${profileId}_${bookmark.id}",
                        bookmark.name,
                        bookmark.url,
                        bookmark.apiName,
                        bookmark.posterUrl,
                        bookmark.watchType.toLong(),
                        bookmark.dateAdded,
                    )
                    bookmarksAdded++
                }
            }

            val listIdMapping = mutableMapOf<String, String>()
            val existingLists = queries.selectCustomListsByProfile(profileId.toLong()).executeAsList()
            val usedNames = existingLists.mapTo(mutableSetOf()) { it.normalizedName }
            val existingListIds = existingLists.mapTo(mutableSetOf()) { it.id }
            customLists.sortedBy { it.sortOrder }.forEach { imported ->
                val safeName = validateCustomListName(imported.name)
                if (imported.id in existingListIds) {
                    listIdMapping[imported.id] = imported.id
                    customListsMerged++
                } else {
                    val normalized = normalizeCustomListName(safeName)
                    val uniqueName = if (normalized in usedNames) uniqueImportedListName(safeName, usedNames) else safeName
                    val uniqueNormalized = normalizeCustomListName(uniqueName)
                    queries.insertCustomList(
                        profileId = profileId.toLong(),
                        id = imported.id,
                        name = uniqueName,
                        normalizedName = uniqueNormalized,
                        sortOrder = queries.selectCustomListsByProfile(profileId.toLong()).executeAsList().size.toLong(),
                        createdAt = imported.createdAt.coerceAtLeast(0L),
                        showOnHome = if (imported.showOnHome) 1L else 0L,
                    )
                    usedNames += uniqueNormalized
                    existingListIds += imported.id
                    listIdMapping[imported.id] = imported.id
                    customListsAdded++
                }
            }

            val knownMemberships = queries.selectCustomListItemsByProfile(profileId.toLong()).executeAsList()
                .mapTo(mutableSetOf()) { "${it.listId}\u0000${it.bookmarkId}" }
            customListItems.forEach { imported ->
                val targetListId = listIdMapping[imported.listId]
                if (targetListId == null || imported.bookmarkId !in existingBookmarkIds) {
                    customListItemsSkipped++
                } else {
                    val key = "$targetListId\u0000${imported.bookmarkId}"
                    if (!knownMemberships.add(key)) {
                        customListItemsSkipped++
                    } else {
                        queries.insertCustomListItem(
                            profileId = profileId.toLong(),
                            listId = targetListId,
                            bookmarkId = imported.bookmarkId,
                            addedAt = imported.addedAt.coerceAtLeast(0L),
                        )
                        customListItemsAdded++
                    }
                }
            }

            history.forEach { imported ->
                val parentId = watchHistoryId(imported.apiName, imported.showUrl, profileId = profileId)
                val episodeId = imported.episodeId.orEmpty()
                val existing = queries.selectWatchHistoryByEpisode(parentId, episodeId).executeAsOneOrNull()
                if (existing != null && existing.updateTime >= imported.updateTime) {
                    historyUnchanged++
                    return@forEach
                }

                val duration = imported.duration.coerceAtLeast(0)
                val position = if (duration > 0) imported.position.coerceIn(0, duration) else imported.position.coerceAtLeast(0)
                queries.insertWatchHistory(
                    parentId = parentId,
                    episodeId = episodeId,
                    showName = imported.showName,
                    showUrl = imported.showUrl,
                    apiName = imported.apiName,
                    posterUrl = imported.posterUrl,
                    episodeThumbnailUrl = imported.episodeThumbnailUrl ?: existing?.episodeThumbnailUrl,
                    screenshotUrl = existing?.screenshotUrl,
                    episode = imported.episode?.toLong(),
                    season = imported.season?.toLong(),
                    position = position,
                    duration = duration,
                    updateTime = imported.updateTime,
                    episodeName = imported.episodeName,
                    episodeDescription = imported.episodeDescription,
                )
                if (existing == null) historyAdded++ else historyUpdated++
                if (duration > 0 && position.toDouble() / duration >= 0.9 &&
                    imported.season != null && imported.episode != null && episodeId.isNotBlank()
                ) {
                    legacyCompletedMarks += EpisodeWatchMark(
                        profileId = profileId,
                        providerName = imported.apiName,
                        showUrl = imported.showUrl,
                        episodeKey = "s${imported.season}:e${imported.episode}",
                        episodeId = episodeId,
                        showName = imported.showName,
                        seasonNumber = imported.season,
                        episodeNumber = imported.episode,
                        watchedAt = imported.updateTime,
                    )
                }
            }

            val existingFollowedKeys = getFollowedShows(profileId)
                .mapTo(mutableSetOf()) { "${it.providerName}\u0000${it.showUrl}" }
            followedShows.forEach { imported ->
                val key = "${imported.providerName}\u0000${imported.showUrl}"
                if (!existingFollowedKeys.add(key)) {
                    followedShowsSkipped++
                } else {
                    queries.upsertFollowedShow(
                        profileId = profileId.toLong(),
                        providerName = imported.providerName,
                        showUrl = imported.showUrl,
                        showName = imported.showName,
                        posterUrl = imported.posterUrl,
                        tmdbId = imported.tmdbId?.toLong(),
                        addedAt = imported.addedAt,
                        lastRefreshAt = 0L,
                        lastRefreshError = null,
                    )
                    followedShowsAdded++
                }
            }

            val existingMarkKeys = getEpisodeWatchMarks(profileId)
                .mapTo(mutableSetOf()) { "${it.providerName}\u0000${it.showUrl}\u0000${it.episodeKey}" }
            (episodeWatchMarks + legacyCompletedMarks).forEach { imported ->
                val key = "${imported.providerName}\u0000${imported.showUrl}\u0000${imported.episodeKey}"
                if (!existingMarkKeys.add(key)) {
                    episodeWatchMarksSkipped++
                } else {
                    queries.upsertEpisodeWatchMark(
                        profileId = profileId.toLong(),
                        providerName = imported.providerName,
                        showUrl = imported.showUrl,
                        episodeKey = imported.episodeKey,
                        episodeId = imported.episodeId,
                        showName = imported.showName,
                        seasonNumber = imported.seasonNumber.toLong(),
                        episodeNumber = imported.episodeNumber.toLong(),
                        watchedAt = imported.watchedAt,
                    )
                    episodeWatchMarksAdded++
                }
            }
        }

        if (historyAdded > 0 || historyUpdated > 0) notifyHistoryChanged(force = true)
        if (followedShowsAdded > 0 || episodeWatchMarksAdded > 0) notifyEpisodeTrackingChanged()
        if (customListsAdded > 0 || customListItemsAdded > 0) notifyCustomListsChanged()
        return ProfileLibraryMergeResult(
            bookmarksAdded = bookmarksAdded,
            bookmarksSkipped = bookmarksSkipped,
            historyAdded = historyAdded,
            historyUpdated = historyUpdated,
            historyUnchanged = historyUnchanged,
            followedShowsAdded = followedShowsAdded,
            followedShowsSkipped = followedShowsSkipped,
            episodeWatchMarksAdded = episodeWatchMarksAdded,
            episodeWatchMarksSkipped = episodeWatchMarksSkipped,
            customListsAdded = customListsAdded,
            customListsMerged = customListsMerged,
            customListItemsAdded = customListItemsAdded,
            customListItemsSkipped = customListItemsSkipped,
        )
    }

    fun getFollowedShows(profileId: Int = activeProfileId): List<FollowedShow> =
        DatabaseFactory.database.cloudstreamDBQueries.selectAllFollowedShows().executeAsList()
            .filter { it.profileId.toInt() == profileId }
            .map {
                FollowedShow(
                    profileId = it.profileId.toInt(),
                    providerName = it.providerName,
                    showUrl = it.showUrl,
                    showName = it.showName,
                    posterUrl = it.posterUrl,
                    tmdbId = it.tmdbId?.toInt(),
                    addedAt = it.addedAt,
                    lastRefreshAt = it.lastRefreshAt,
                    lastRefreshError = it.lastRefreshError,
                )
            }

    @Synchronized
    fun followShow(show: FollowedShow) {
        if (isProfileDeleted(show.profileId)) return
        DatabaseFactory.database.cloudstreamDBQueries.upsertFollowedShow(
            profileId = show.profileId.toLong(),
            providerName = show.providerName,
            showUrl = show.showUrl,
            showName = show.showName,
            posterUrl = show.posterUrl,
            tmdbId = show.tmdbId?.toLong(),
            addedAt = show.addedAt,
            lastRefreshAt = show.lastRefreshAt,
            lastRefreshError = show.lastRefreshError,
        )
        notifyEpisodeTrackingChanged()
    }

    @Synchronized
    fun unfollowShow(profileId: Int, providerName: String, showUrl: String) {
        DatabaseFactory.database.cloudstreamDBQueries.deleteFollowedShow(profileId.toLong(), providerName, showUrl)
        notifyEpisodeTrackingChanged()
    }

    fun updateFollowedShowRefresh(
        profileId: Int,
        providerName: String,
        showUrl: String,
        refreshedAt: Long,
        error: String?,
        resolvedTmdbId: Int? = null,
    ) {
        val current = getFollowedShows(profileId).firstOrNull { it.providerName == providerName && it.showUrl == showUrl } ?: return
        followShow(
            current.copy(
                tmdbId = resolvedTmdbId ?: current.tmdbId,
                lastRefreshAt = refreshedAt,
                lastRefreshError = error,
            ),
        )
    }

    fun getAllEpisodeReleases(): List<EpisodeReleaseRecord> {
        val queries = DatabaseFactory.database.cloudstreamDBQueries
        queries.deleteStaleEpisodeReleases(System.currentTimeMillis() - 180L * 24L * 60L * 60L * 1000L)
        return queries.selectAllEpisodeReleases().executeAsList().map {
            EpisodeReleaseRecord(
                tmdbId = it.tmdbId.toInt(),
                seasonNumber = it.seasonNumber.toInt(),
                episodeNumber = it.episodeNumber.toInt(),
                airDate = it.airDate,
                episodeName = it.episodeName,
                overview = it.overview,
                stillUrl = it.stillUrl,
                fetchedAt = it.fetchedAt,
            )
        }
    }

    @Synchronized
    fun replaceEpisodeReleaseSeason(tmdbId: Int, seasonNumber: Int, episodes: List<EpisodeReleaseRecord>, fetchedAt: Long) {
        val queries = DatabaseFactory.database.cloudstreamDBQueries
        queries.transaction {
            queries.deleteEpisodeReleasesBySeason(tmdbId.toLong(), seasonNumber.toLong())
            episodes.filter { it.airDate.isNotBlank() }.forEach { episode ->
                queries.upsertEpisodeRelease(
                    tmdbId = tmdbId.toLong(),
                    seasonNumber = seasonNumber.toLong(),
                    episodeNumber = episode.episodeNumber.toLong(),
                    airDate = episode.airDate,
                    episodeName = episode.episodeName,
                    overview = episode.overview,
                    stillUrl = episode.stillUrl,
                    fetchedAt = fetchedAt,
                )
            }
        }
    }

    fun getEpisodeWatchMarks(profileId: Int = activeProfileId): List<EpisodeWatchMark> =
        DatabaseFactory.database.cloudstreamDBQueries.selectAllEpisodeWatchMarks().executeAsList()
            .filter { it.profileId.toInt() == profileId }
            .map {
                EpisodeWatchMark(
                    profileId = it.profileId.toInt(),
                    providerName = it.providerName,
                    showUrl = it.showUrl,
                    episodeKey = it.episodeKey,
                    episodeId = it.episodeId,
                    showName = it.showName,
                    seasonNumber = it.seasonNumber.toInt(),
                    episodeNumber = it.episodeNumber.toInt(),
                    watchedAt = it.watchedAt,
                )
            }

    fun getEpisodeWatchMarks(profileId: Int, providerName: String, showUrl: String): List<EpisodeWatchMark> =
        getEpisodeWatchMarks(profileId).filter { it.providerName == providerName && it.showUrl == showUrl }

    @Synchronized
    fun setEpisodeWatchMarks(marks: List<EpisodeWatchMark>, watched: Boolean) {
        if (marks.isEmpty()) return
        val queries = DatabaseFactory.database.cloudstreamDBQueries
        queries.transaction {
            marks.forEach { mark ->
                if (watched) {
                    if (isProfileDeleted(mark.profileId)) return@forEach
                    if (mark.hasStableEpisodeCoordinates()) {
                        queries.deleteEpisodeWatchMarksByEpisode(
                            mark.profileId.toLong(),
                            mark.providerName,
                            mark.showUrl,
                            mark.seasonNumber.toLong(),
                            mark.episodeNumber.toLong(),
                        )
                    }
                    queries.upsertEpisodeWatchMark(
                        profileId = mark.profileId.toLong(),
                        providerName = mark.providerName,
                        showUrl = mark.showUrl,
                        episodeKey = mark.episodeKey,
                        episodeId = mark.episodeId,
                        showName = mark.showName,
                        seasonNumber = mark.seasonNumber.toLong(),
                        episodeNumber = mark.episodeNumber.toLong(),
                        watchedAt = mark.watchedAt,
                    )
                } else {
                    queries.deleteEpisodeWatchMark(mark.profileId.toLong(), mark.providerName, mark.showUrl, mark.episodeKey)
                    if (mark.hasStableEpisodeCoordinates()) {
                        queries.deleteEpisodeWatchMarksByEpisode(
                            mark.profileId.toLong(),
                            mark.providerName,
                            mark.showUrl,
                            mark.seasonNumber.toLong(),
                            mark.episodeNumber.toLong(),
                        )
                    }
                }
            }
        }
        notifyEpisodeTrackingChanged()
    }

    fun markEpisodeWatchedFromHistory(history: WatchHistory) {
        val season = history.season ?: return
        val episode = history.episode ?: return
        val episodeId = history.episodeId?.takeIf { it.isNotBlank() } ?: return
        val profileId = Regex("^p(\\d+)_").find(history.parentId)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (getEpisodeWatchMarks(profileId, history.apiName, history.showUrl).any {
                it.episodeId == episodeId ||
                    (it.seasonNumber == season && it.episodeNumber == episode)
            }
        ) {
            return
        }
        setEpisodeWatchMarks(
            listOf(
                EpisodeWatchMark(
                    profileId = profileId,
                    providerName = history.apiName,
                    showUrl = history.showUrl,
                    episodeKey = "s$season:e$episode",
                    episodeId = episodeId,
                    showName = history.showName,
                    seasonNumber = season,
                    episodeNumber = episode,
                    watchedAt = history.updateTime,
                ),
            ),
            watched = true,
        )
    }

    fun notifyEpisodeTrackingChanged() {
        episodeTrackingUpdates.value++
    }

    private fun EpisodeWatchMark.hasStableEpisodeCoordinates(): Boolean =
        episodeKey == "s$seasonNumber:e$episodeNumber"

    fun getBookmarks(profileId: Int = activeProfileId): List<DesktopBookmark> {
        val prefix = "p${profileId}_"
        val rows = DatabaseFactory.database.cloudstreamDBQueries.selectAllBookmarks().executeAsList()
        val legacyDefaultRows = if (profileId == 0) {
            rows.filter { !it.id.startsWith(prefix) && !PROFILE_SCOPED_BOOKMARK_ID.containsMatchIn(it.id) }
        } else {
            emptyList()
        }
        val scopedRows = rows.filter { it.id.startsWith(prefix) }
        return (legacyDefaultRows + scopedRows)
            .map { row ->
                DesktopBookmark(
                    id = row.id.removePrefix(prefix),
                    name = row.name,
                    url = row.url,
                    apiName = row.apiName,
                    posterUrl = row.posterUrl,
                    watchType = row.watchType?.toInt() ?: 0,
                    dateAdded = row.dateAdded ?: 0L,
                )
            }
            .associateBy { it.id }
            .values
            .toList()
    }

    fun getCustomLists(profileId: Int = activeProfileId): List<DesktopCustomList> =
        DatabaseFactory.database.cloudstreamDBQueries.selectCustomListsByProfile(profileId.toLong()).executeAsList()
            .map {
                DesktopCustomList(
                    id = it.id,
                    name = it.name,
                    sortOrder = it.sortOrder.toInt(),
                    createdAt = it.createdAt,
                    showOnHome = it.showOnHome != 0L,
                )
            }

    fun getCustomListItems(profileId: Int = activeProfileId): List<DesktopCustomListItem> =
        DatabaseFactory.database.cloudstreamDBQueries.selectCustomListItemsByProfile(profileId.toLong()).executeAsList()
            .map { DesktopCustomListItem(listId = it.listId, bookmarkId = it.bookmarkId, addedAt = it.addedAt) }

    @Synchronized
    fun createCustomList(name: String, profileId: Int = activeProfileId): DesktopCustomList {
        check(!isProfileDeleted(profileId)) { "This profile no longer exists" }
        val safeName = validateCustomListName(name)
        val normalizedName = normalizeCustomListName(safeName)
        require(getCustomLists(profileId).none { normalizeCustomListName(it.name) == normalizedName }) {
            "A list with this name already exists"
        }
        val lists = getCustomLists(profileId)
        val list = DesktopCustomList(
            id = UUID.randomUUID().toString(),
            name = safeName,
            sortOrder = lists.size,
            createdAt = System.currentTimeMillis(),
            showOnHome = false,
        )
        DatabaseFactory.database.cloudstreamDBQueries.insertCustomList(
            profileId = profileId.toLong(),
            id = list.id,
            name = list.name,
            normalizedName = normalizedName,
            sortOrder = list.sortOrder.toLong(),
            createdAt = list.createdAt,
            showOnHome = 0L,
        )
        notifyCustomListsChanged()
        return list
    }

    @Synchronized
    fun renameCustomList(listId: String, name: String, profileId: Int = activeProfileId): Boolean {
        if (isProfileDeleted(profileId)) return false
        val safeName = validateCustomListName(name)
        val normalizedName = normalizeCustomListName(safeName)
        val lists = getCustomLists(profileId)
        if (lists.none { it.id == listId } || lists.any { it.id != listId && normalizeCustomListName(it.name) == normalizedName }) {
            return false
        }
        DatabaseFactory.database.cloudstreamDBQueries.updateCustomListName(
            name = safeName,
            normalizedName = normalizedName,
            profileId = profileId.toLong(),
            id = listId,
        )
        notifyCustomListsChanged()
        return true
    }

    @Synchronized
    fun reorderCustomLists(listIds: List<String>, profileId: Int = activeProfileId): Boolean {
        if (isProfileDeleted(profileId)) return false
        val lists = getCustomLists(profileId)
        if (listIds.size != lists.size || listIds.toSet().size != listIds.size || listIds.toSet() != lists.map { it.id }.toSet()) {
            return false
        }
        DatabaseFactory.database.cloudstreamDBQueries.transaction {
            listIds.forEachIndexed { index, listId ->
                DatabaseFactory.database.cloudstreamDBQueries.updateCustomListSortOrder(
                    sortOrder = index.toLong(),
                    profileId = profileId.toLong(),
                    id = listId,
                )
            }
        }
        notifyCustomListsChanged()
        return true
    }

    @Synchronized
    fun setCustomListShownOnHome(listId: String, showOnHome: Boolean, profileId: Int = activeProfileId): Boolean {
        if (isProfileDeleted(profileId)) return false
        val queries = DatabaseFactory.database.cloudstreamDBQueries
        if (queries.selectCustomListById(profileId.toLong(), listId).executeAsOneOrNull() == null) return false
        queries.updateCustomListShowOnHome(
            showOnHome = if (showOnHome) 1L else 0L,
            profileId = profileId.toLong(),
            id = listId,
        )
        notifyCustomListsChanged()
        return true
    }

    @Synchronized
    fun deleteCustomList(listId: String, profileId: Int = activeProfileId): Boolean {
        if (isProfileDeleted(profileId)) return false
        val queries = DatabaseFactory.database.cloudstreamDBQueries
        if (queries.selectCustomListById(profileId.toLong(), listId).executeAsOneOrNull() == null) return false
        queries.transaction {
            queries.deleteCustomListItemsForList(profileId.toLong(), listId)
            queries.deleteCustomList(profileId.toLong(), listId)
        }
        notifyCustomListsChanged()
        return true
    }

    @Synchronized
    fun setBookmarkInCustomList(
        listId: String,
        bookmarkId: String,
        included: Boolean,
        profileId: Int = activeProfileId,
    ): Boolean {
        if (isProfileDeleted(profileId)) return false
        val queries = DatabaseFactory.database.cloudstreamDBQueries
        if (queries.selectCustomListById(profileId.toLong(), listId).executeAsOneOrNull() == null) return false
        if (included) {
            if (getBookmarks(profileId).none { it.id == bookmarkId }) return false
            queries.insertCustomListItem(profileId.toLong(), listId, bookmarkId, System.currentTimeMillis())
        } else {
            queries.deleteCustomListItem(profileId.toLong(), listId, bookmarkId)
        }
        notifyCustomListsChanged()
        return true
    }

    fun notifyCustomListsChanged() {
        customListUpdates.value++
    }

    private fun validateCustomListName(name: String): String {
        val safeName = name.trim()
        require(safeName.isNotEmpty()) { "Enter a list name" }
        require(safeName.length <= 64) { "List names can be up to 64 characters" }
        return safeName
    }

    private fun normalizeCustomListName(name: String): String = name.trim().lowercase(Locale.ROOT)

    private fun uniqueImportedListName(name: String, usedNames: Set<String>): String {
        val suffix = " (Imported)"
        var candidate = name.take(64 - suffix.length).trimEnd() + suffix
        var index = 2
        while (normalizeCustomListName(candidate) in usedNames) {
            val numberedSuffix = " (Imported $index)"
            candidate = name.take(64 - numberedSuffix.length).trimEnd() + numberedSuffix
            index++
        }
        return candidate
    }

    @Synchronized
    fun addBookmark(bookmark: DesktopBookmark, profileId: Int = activeProfileId) {
        if (isProfileDeleted(profileId)) return
        val resolvedId = "p${profileId}_${bookmark.id}"
        DatabaseFactory.database.cloudstreamDBQueries.insertBookmark(
            resolvedId,
            bookmark.name,
            bookmark.url,
            bookmark.apiName,
            bookmark.posterUrl,
            bookmark.watchType.toLong(),
            bookmark.dateAdded,
        )
    }

    fun removeBookmark(id: String, profileId: Int = activeProfileId) {
        val resolvedId = "p${profileId}_$id"
        val queries = DatabaseFactory.database.cloudstreamDBQueries
        queries.transaction {
            queries.deleteBookmark(resolvedId)
            if (profileId == 0 && !PROFILE_SCOPED_BOOKMARK_ID.containsMatchIn(id)) {
                queries.deleteBookmark(id)
            }
            queries.deleteCustomListItemsForBookmark(profileId.toLong(), id)
        }
        notifyCustomListsChanged()
    }

    fun isBookmarked(id: String, profileId: Int = activeProfileId): Boolean {
        val resolvedId = "p${profileId}_$id"
        val exists = DatabaseFactory.database.cloudstreamDBQueries.selectBookmarkById(resolvedId).executeAsOneOrNull() != null
        if (exists) return true
        return profileId == 0 && !PROFILE_SCOPED_BOOKMARK_ID.containsMatchIn(id) &&
            DatabaseFactory.database.cloudstreamDBQueries.selectBookmarkById(id).executeAsOneOrNull() != null
    }

    fun getAllWatchHistory(profileId: Int = activeProfileId): List<WatchHistory> {
        val prefix = "p${profileId}_"
        return DatabaseFactory.database.cloudstreamDBQueries.selectAllWatchHistory().executeAsList()
            .filter {
                if (profileId == 0) {
                    it.parentId.startsWith(prefix) || !it.parentId.startsWith("p")
                } else {
                    it.parentId.startsWith(prefix)
                }
            }
            .map {
                WatchHistory(
                    parentId = it.parentId,
                    showName = it.showName,
                    showUrl = it.showUrl,
                    apiName = it.apiName,
                    posterUrl = it.posterUrl,
                    episodeThumbnailUrl = it.episodeThumbnailUrl,
                    screenshotUrl = it.screenshotUrl,
                    episode = it.episode?.toInt(),
                    season = it.season?.toInt(),
                    episodeId = it.episodeId.takeIf { id -> id.isNotEmpty() },
                    position = it.position,
                    duration = it.duration,
                    updateTime = it.updateTime,
                    episodeName = it.episodeName,
                    episodeDescription = it.episodeDescription,
                )
            }
    }

    private var lastHistoryNotifyMs = 0L

    fun notifyHistoryChanged(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastHistoryNotifyMs >= 1000L) {
            lastHistoryNotifyMs = now
            historyUpdates.value++
        }
    }

    fun clearAllWatchHistory(profileId: Int = activeProfileId) {
        val history = getAllWatchHistory(profileId)
        DatabaseFactory.database.cloudstreamDBQueries.transaction {
            history.forEach {
                DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByParent(it.parentId)
            }
        }
        notifyHistoryChanged(force = true)
    }

    fun removeWatchHistory(parentId: String) {
        DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByParent(parentId)
        val legacyId = if (parentId.startsWith("p") && parentId.contains("_")) {
            parentId.substringAfter("_")
        } else {
            null
        }
        if (legacyId != null && legacyId != parentId) {
            DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByParent(legacyId)
        }
        notifyHistoryChanged(force = true)
    }

    fun removeEpisodeWatched(
        parentId: String,
        episodeId: String,
        season: Int? = null,
        episode: Int? = null,
        extraEpisodeIds: List<String> = emptyList(),
    ) {
        val allParentIds = mutableListOf(parentId)
        val legacyId = if (parentId.startsWith("p") && parentId.contains("_")) {
            parentId.substringAfter("_")
        } else {
            null
        }
        if (legacyId != null && legacyId != parentId) {
            allParentIds.add(legacyId)
        }

        val allEpisodeIds = (listOf(episodeId) + extraEpisodeIds).filter { it.isNotBlank() }.distinct()

        DatabaseFactory.database.cloudstreamDBQueries.transaction {
            allParentIds.forEach { pid ->
                allEpisodeIds.forEach { eid ->
                    DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByEpisode(pid, eid)
                }
                if (episode != null) {
                    val rows = DatabaseFactory.database.cloudstreamDBQueries.selectWatchHistoryByParent(pid).executeAsList()
                    rows.forEach { row ->
                        val rowSeason = row.season?.toInt() ?: 1
                        val targetSeason = season ?: 1
                        if (row.episode?.toInt() == episode && rowSeason == targetSeason) {
                            DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByEpisode(pid, row.episodeId)
                        }
                    }
                }
            }
        }
        notifyHistoryChanged(force = true)
    }

    fun removeMultipleEpisodesWatched(
        parentId: String,
        episodeIds: List<String>,
        extraParentIds: List<String> = emptyList(),
    ) {
        if (episodeIds.isEmpty()) return
        val allParentIds = (listOf(parentId) + extraParentIds).toMutableList()
        val legacyId = if (parentId.startsWith("p") && parentId.contains("_")) {
            parentId.substringAfter("_")
        } else {
            null
        }
        if (legacyId != null && !allParentIds.contains(legacyId)) {
            allParentIds.add(legacyId)
        }

        val targetEpisodeIds = episodeIds.filter { it.isNotBlank() }.distinct()
        DatabaseFactory.database.cloudstreamDBQueries.transaction {
            allParentIds.forEach { pid ->
                targetEpisodeIds.forEach { episodeId ->
                    DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByEpisode(pid, episodeId)
                }
            }
        }
        notifyHistoryChanged(force = true)
    }

    fun watchHistoryId(
        apiName: String,
        showUrl: String,
        season: Int? = null,
        episode: Int? = null,
        episodeData: String? = null,
        profileId: Int = activeProfileId,
    ): String {
        return WatchHistoryKey.create(profileId, apiName, showUrl, season, episode, episodeData)
    }

    @Synchronized
    fun setLastWatched(history: WatchHistory, forceNotify: Boolean = false) {
        if (isHistoryOwnerDeleted(history.parentId)) return
        val normalizedDuration = history.duration.coerceAtLeast(0)
        val normalizedPosition = if (normalizedDuration > 0) {
            history.position.coerceIn(0, normalizedDuration)
        } else {
            history.position.coerceAtLeast(0)
        }

        DatabaseFactory.database.cloudstreamDBQueries.insertWatchHistory(
            parentId = history.parentId,
            episodeId = history.episodeId ?: "",
            showName = history.showName,
            showUrl = history.showUrl,
            apiName = history.apiName,
            posterUrl = history.posterUrl,
            episodeThumbnailUrl = history.episodeThumbnailUrl,
            screenshotUrl = history.screenshotUrl,
            episode = history.episode?.toLong(),
            season = history.season?.toLong(),
            position = normalizedPosition,
            duration = normalizedDuration,
            updateTime = history.updateTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            episodeName = history.episodeName,
            episodeDescription = history.episodeDescription,
        )
        notifyHistoryChanged(force = forceNotify)
    }

    @Synchronized
    fun setMultipleLastWatched(histories: List<WatchHistory>) {
        if (histories.isEmpty()) return
        DatabaseFactory.database.cloudstreamDBQueries.transaction {
            histories.forEach { history ->
                if (isHistoryOwnerDeleted(history.parentId)) return@forEach
                val normalizedDuration = history.duration.coerceAtLeast(0)
                val normalizedPosition = if (normalizedDuration > 0) {
                    history.position.coerceIn(0, normalizedDuration)
                } else {
                    history.position.coerceAtLeast(0)
                }

                DatabaseFactory.database.cloudstreamDBQueries.insertWatchHistory(
                    parentId = history.parentId,
                    episodeId = history.episodeId ?: "",
                    showName = history.showName,
                    showUrl = history.showUrl,
                    apiName = history.apiName,
                    posterUrl = history.posterUrl,
                    episodeThumbnailUrl = history.episodeThumbnailUrl,
                    screenshotUrl = history.screenshotUrl,
                    episode = history.episode?.toLong(),
                    season = history.season?.toLong(),
                    position = normalizedPosition,
                    duration = normalizedDuration,
                    updateTime = history.updateTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    episodeName = history.episodeName,
                    episodeDescription = history.episodeDescription,
                )
            }
        }
        notifyHistoryChanged(force = true)
    }

    fun getLastWatched(parentId: String): WatchHistory? {
        return DatabaseFactory.database.cloudstreamDBQueries
            .selectWatchHistoryByParent(parentId)
            .executeAsList()
            .firstOrNull()
            ?.let {
                WatchHistory(
                    parentId = it.parentId,
                    showName = it.showName,
                    showUrl = it.showUrl,
                    apiName = it.apiName,
                    posterUrl = it.posterUrl,
                    episodeThumbnailUrl = it.episodeThumbnailUrl,
                    screenshotUrl = it.screenshotUrl,
                    episode = it.episode?.toInt(),
                    season = it.season?.toInt(),
                    episodeId = it.episodeId.takeIf { id -> id.isNotEmpty() },
                    position = it.position,
                    duration = it.duration,
                    updateTime = it.updateTime,
                    episodeName = it.episodeName,
                    episodeDescription = it.episodeDescription,
                )
            }
    }

    fun getWatchHistoryByParent(parentId: String): List<WatchHistory> {
        return DatabaseFactory.database.cloudstreamDBQueries
            .selectWatchHistoryByParent(parentId)
            .executeAsList()
            .map {
                WatchHistory(
                    parentId = it.parentId,
                    showName = it.showName,
                    showUrl = it.showUrl,
                    apiName = it.apiName,
                    posterUrl = it.posterUrl,
                    episodeThumbnailUrl = it.episodeThumbnailUrl,
                    screenshotUrl = it.screenshotUrl,
                    episode = it.episode?.toInt(),
                    season = it.season?.toInt(),
                    episodeId = it.episodeId.takeIf { id -> id.isNotEmpty() },
                    position = it.position,
                    duration = it.duration,
                    updateTime = it.updateTime,
                    episodeName = it.episodeName,
                    episodeDescription = it.episodeDescription,
                )
            }
    }

    fun getLatestWatchHistoryForShow(showUrl: String): WatchHistory? {
        return DatabaseFactory.database.cloudstreamDBQueries
            .selectLatestWatchHistoryForShow(showUrl)
            .executeAsOneOrNull()
            ?.let {
                WatchHistory(
                    parentId = it.parentId,
                    showName = it.showName,
                    showUrl = it.showUrl,
                    apiName = it.apiName,
                    posterUrl = it.posterUrl,
                    episodeThumbnailUrl = it.episodeThumbnailUrl,
                    screenshotUrl = it.screenshotUrl,
                    episode = it.episode?.toInt(),
                    season = it.season?.toInt(),
                    episodeId = it.episodeId.takeIf { id -> id.isNotEmpty() },
                    position = it.position,
                    duration = it.duration,
                    updateTime = it.updateTime,
                    episodeName = it.episodeName,
                    episodeDescription = it.episodeDescription,
                )
            }
    }

    fun getEpisodeWatched(
        parentId: String,
        episodeId: String?,
    ): WatchHistory? {
        val searchId = episodeId ?: ""
        return DatabaseFactory.database.cloudstreamDBQueries
            .selectWatchHistoryByEpisode(parentId, searchId)
            .executeAsOneOrNull()
            ?.let {
                WatchHistory(
                    parentId = it.parentId,
                    showName = it.showName,
                    showUrl = it.showUrl,
                    apiName = it.apiName,
                    posterUrl = it.posterUrl,
                    episodeThumbnailUrl = it.episodeThumbnailUrl,
                    screenshotUrl = it.screenshotUrl,
                    episode = it.episode?.toInt(),
                    season = it.season?.toInt(),
                    episodeId = it.episodeId.takeIf { id -> id.isNotEmpty() },
                    position = it.position,
                    duration = it.duration,
                    updateTime = it.updateTime,
                    episodeName = it.episodeName,
                    episodeDescription = it.episodeDescription,
                )
            }
    }

    private const val UNREAD_UPDATES_KEY = "unread_plugin_updates"

    fun getUpdatesHistory(): List<PluginUpdateRecord> {
        return DatabaseFactory.database.cloudstreamDBQueries.selectAllPluginUpdates().executeAsList().map {
            PluginUpdateRecord(it.pluginName, it.version.toInt(), it.iconUrl, it.timestamp)
        }
    }

    fun addUpdateHistory(history: List<PluginUpdateRecord>) {
        if (history.isEmpty()) return

        DatabaseFactory.database.cloudstreamDBQueries.transaction {
            history.forEach {
                DatabaseFactory.database.cloudstreamDBQueries.insertPluginUpdate(
                    it.pluginName,
                    it.version.toLong(),
                    it.iconUrl,
                    it.timestamp,
                )
            }
            DatabaseFactory.database.cloudstreamDBQueries.deleteOldPluginUpdates()
        }
        pluginUpdatesFlow.value++
    }

    fun clearUpdatesHistory() {
        DatabaseFactory.database.cloudstreamDBQueries.deleteAllPluginUpdates()
        pluginUpdatesFlow.value++
    }

    fun hasUnreadUpdates(): Boolean {
        return getKey<Boolean>(UNREAD_UPDATES_KEY) ?: false
    }

    fun setUnreadUpdates(hasUnread: Boolean) {
        setKey(UNREAD_UPDATES_KEY, hasUnread)
        pluginUpdatesFlow.value++
    }

    const val PREF_ALLOW_EXTERNAL_BROWSER = "ALLOW_EXTERNAL_BROWSER"
    const val PREF_ALLOW_CF_BYPASS = "ALLOW_CF_BYPASS"
    const val PREF_ISOLATED_EXTERNAL_BROWSER = "ISOLATED_EXTERNAL_BROWSER"
    const val PREF_DONT_ASK_EXTERNAL_LINKS = "DONT_ASK_EXTERNAL_LINKS"

    const val PREF_DISCORD_RPC_ENABLED = "DISCORD_RPC_ENABLED"
    const val PREF_DISCORD_RPC_SHOW_TITLE = "DISCORD_RPC_SHOW_TITLE"
    const val PREF_DISCORD_RPC_SHOW_PROGRESS = "DISCORD_RPC_SHOW_PROGRESS"
    const val PREF_DISCORD_RPC_SHOW_BROWSING = "DISCORD_RPC_SHOW_BROWSING"
    const val PREF_DISCORD_CUSTOM_APP_ID = "DISCORD_CUSTOM_APP_ID"

    const val PREF_P2P_ENABLED = "p2p_torrent_enabled"
    const val PREF_P2P_PORT = "p2p_torrent_port"
    const val PREF_P2P_CACHE_SIZE_GB = "p2p_torrent_cache_gb"
    const val PREF_P2P_SHOW_HUD = "p2p_torrent_show_hud"

    const val PREF_ENABLE_DOWNLOAD_BUTTONS = "ENABLE_DOWNLOAD_BUTTONS"
    const val PREF_DOWNLOAD_THREADS = "DOWNLOAD_THREADS"
    const val PREF_DOWNLOAD_MAX_CONCURRENT = "DOWNLOAD_MAX_CONCURRENT"
    const val PREF_DOWNLOAD_PATH = "DOWNLOAD_PATH"

    private const val TRUSTED_PLUGINS_KEY = "trusted_plugins_set"

    fun getTrustedPlugins(): Set<String> {
        val json = rawKeyCache[TRUSTED_PLUGINS_KEY] ?: DatabaseFactory.database.cloudstreamDBQueries.selectKeyValue(TRUSTED_PLUGINS_KEY).executeAsOneOrNull() ?: return emptySet()
        return try {
            val list: List<String> = mapper.readValue(json, object : TypeReference<List<String>>() {})
            list.map { it.lowercase().trim() }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun isPluginTrusted(internalName: String): Boolean {
        val cleanName = internalName.removeSuffix("-jvm").lowercase().trim()
        val trusted = getTrustedPlugins()
        if (trusted.contains(cleanName) || trusted.contains(internalName.lowercase().trim())) return true
        val stripped = cleanName.removeSuffix("provider").removeSuffix("plugin").removePrefix("com.")
        if (stripped.isNotBlank() && (trusted.contains(stripped) || trusted.contains(stripped.substringAfterLast('.')))) return true
        val lastSegment = cleanName.substringAfterLast('.')
        if (lastSegment.isNotBlank() && (trusted.contains(lastSegment) || trusted.contains(lastSegment.removeSuffix("provider").removeSuffix("plugin")))) return true
        return false
    }

    fun setPluginTrusted(internalName: String, trusted: Boolean) {
        val cleanName = internalName.removeSuffix("-jvm").lowercase().trim()
        val current = getTrustedPlugins().toMutableSet()
        if (trusted) {
            current.add(cleanName)
            current.add(internalName.lowercase().trim())
        } else {
            current.remove(cleanName)
            current.remove(internalName.lowercase().trim())
        }
        // Keep memory and disk aligned: setKeys commits before updating the cache and propagates
        // SQLite errors so callers cannot treat a failed trust change as successful.
        setKey(TRUSTED_PLUGINS_KEY, current.toList())
    }
}
