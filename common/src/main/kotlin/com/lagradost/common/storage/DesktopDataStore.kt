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
                ((it.startsWith("auth_tokens_") || it.startsWith("tracker_credentials_v1_")) && it.endsWith(suffix))
        }.toSet()
        queries.transaction {
            queries.selectAllBookmarks().executeAsList().filter {
                it.id.startsWith(prefix) || (profileId == 0 && !PROFILE_SCOPED_BOOKMARK_ID.containsMatchIn(it.id))
            }
                .forEach { queries.deleteBookmark(it.id) }
            queries.selectAllWatchHistory().executeAsList().filter { it.parentId.startsWith(prefix) || (profileId == 0 && !it.parentId.matches(Regex("p\\d+_.*"))) }
                .forEach { queries.deleteWatchHistoryByParent(it.parentId) }
            removed.forEach { queries.deleteKeyValue(it) }
            committedValues.forEach { (key, value) -> queries.insertKeyValue(key, mapper.writeValueAsString(value)) }
        }
        removed.forEach { rawKeyCache.remove(it) }
        committedValues.forEach { (key, value) -> rawKeyCache[key] = mapper.writeValueAsString(value) }
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
        DatabaseFactory.database.cloudstreamDBQueries.deleteBookmark(resolvedId)
        if (profileId == 0 && !PROFILE_SCOPED_BOOKMARK_ID.containsMatchIn(id)) {
            DatabaseFactory.database.cloudstreamDBQueries.deleteBookmark(id)
        }
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
        val base = "p${profileId}_${apiName}_${showUrl.hashCode()}"
        return if (season != null || episode != null || !episodeData.isNullOrBlank()) {
            "${base}_s${season ?: 0}_e${episode ?: 0}_${episodeData?.hashCode() ?: 0}"
        } else {
            base
        }
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
