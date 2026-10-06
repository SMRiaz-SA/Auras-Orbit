package com.lagradost.cloudstream3.desktop.ui.screens.library.transfer

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.desktop.AppConfig
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopCustomList
import com.lagradost.common.storage.DesktopCustomListItem
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.DesktopWatchType
import com.lagradost.common.storage.EpisodeWatchMark
import com.lagradost.common.storage.FollowedShow
import com.lagradost.common.storage.ProfileLibraryMergeResult
import com.lagradost.common.storage.WatchHistory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

data class LibraryArchiveBookmark(
    val id: String,
    val name: String,
    val url: String,
    val apiName: String,
    val posterUrl: String? = null,
    val watchType: Int = DesktopWatchType.WATCHING.id,
    val dateAdded: Long = 0L,
)

/** Profile-independent watch-history record. The local parent ID is regenerated on import. */
data class LibraryArchiveHistory(
    val showName: String,
    val showUrl: String,
    val apiName: String,
    val posterUrl: String? = null,
    val episodeThumbnailUrl: String? = null,
    val episode: Int? = null,
    val season: Int? = null,
    val episodeId: String? = null,
    val position: Long = 0L,
    val duration: Long = 0L,
    val updateTime: Long = 0L,
    val episodeName: String? = null,
    val episodeDescription: String? = null,
)

data class LibraryArchiveFollowedShow(
    val providerName: String,
    val showUrl: String,
    val showName: String,
    val posterUrl: String? = null,
    val tmdbId: Int? = null,
    val addedAt: Long = 0L,
)

data class LibraryArchiveEpisodeWatchMark(
    val providerName: String,
    val showUrl: String,
    val episodeKey: String,
    val episodeId: String,
    val showName: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val watchedAt: Long = 0L,
)

data class LibraryArchiveCustomList(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
    val showOnHome: Boolean = false,
)

data class LibraryArchiveCustomListItem(
    val listId: String,
    val bookmarkId: String,
    val addedAt: Long = 0L,
)

data class LibraryArchive(
    val format: String,
    val formatVersion: Int,
    val exportedAt: String,
    val sourceAppVersion: String,
    val bookmarks: List<LibraryArchiveBookmark> = emptyList(),
    val watchHistory: List<LibraryArchiveHistory> = emptyList(),
    val followedShows: List<LibraryArchiveFollowedShow> = emptyList(),
    val episodeWatchMarks: List<LibraryArchiveEpisodeWatchMark> = emptyList(),
    val customLists: List<LibraryArchiveCustomList> = emptyList(),
    val customListItems: List<LibraryArchiveCustomListItem> = emptyList(),
)

data class LibraryArchivePreview(
    val exportedAt: String,
    val sourceAppVersion: String,
    val bookmarkCount: Int,
    val historyCount: Int,
    val followedShowCount: Int = 0,
    val episodeWatchMarkCount: Int = 0,
    val customListCount: Int = 0,
    val customListItemCount: Int = 0,
)

data class ValidatedLibraryArchive(
    val archive: LibraryArchive,
    val preview: LibraryArchivePreview,
)

object LibraryArchiveService {
    const val FORMAT = "auras-orbit-library"
    const val FORMAT_VERSION = 3
    private const val MAX_FILE_BYTES = 32 * 1024 * 1024
    private const val MAX_RECORDS_PER_SECTION = 100_000
    private const val MAX_TEXT_LENGTH = 32_768

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun export(profileId: Int, target: File): File {
        val snapshot = DesktopDataStore.getProfileLibrarySnapshot(profileId)
        val archive = LibraryArchive(
            format = FORMAT,
            formatVersion = FORMAT_VERSION,
            exportedAt = Instant.now().toString(),
            sourceAppVersion = AppConfig.APP_VERSION,
            bookmarks = snapshot.bookmarks.map {
                LibraryArchiveBookmark(
                    id = it.id,
                    name = it.name,
                    url = it.url,
                    apiName = it.apiName,
                    posterUrl = it.posterUrl,
                    watchType = it.watchType,
                    dateAdded = it.dateAdded,
                )
            },
            watchHistory = snapshot.history.map {
                LibraryArchiveHistory(
                    showName = it.showName,
                    showUrl = it.showUrl,
                    apiName = it.apiName,
                    posterUrl = it.posterUrl,
                    episodeThumbnailUrl = it.episodeThumbnailUrl,
                    episode = it.episode,
                    season = it.season,
                    episodeId = it.episodeId,
                    position = it.position,
                    duration = it.duration,
                    updateTime = it.updateTime,
                    episodeName = it.episodeName,
                    episodeDescription = it.episodeDescription,
                )
            },
            followedShows = snapshot.followedShows.map {
                LibraryArchiveFollowedShow(
                    providerName = it.providerName,
                    showUrl = it.showUrl,
                    showName = it.showName,
                    posterUrl = it.posterUrl,
                    tmdbId = it.tmdbId,
                    addedAt = it.addedAt,
                )
            },
            episodeWatchMarks = snapshot.episodeWatchMarks.map {
                LibraryArchiveEpisodeWatchMark(
                    providerName = it.providerName,
                    showUrl = it.showUrl,
                    episodeKey = it.episodeKey,
                    episodeId = it.episodeId,
                    showName = it.showName,
                    seasonNumber = it.seasonNumber,
                    episodeNumber = it.episodeNumber,
                    watchedAt = it.watchedAt,
                )
            },
            customLists = snapshot.customLists.map {
                LibraryArchiveCustomList(
                    id = it.id,
                    name = it.name,
                    sortOrder = it.sortOrder,
                    createdAt = it.createdAt,
                    showOnHome = it.showOnHome,
                )
            },
            customListItems = snapshot.customListItems.map {
                LibraryArchiveCustomListItem(
                    listId = it.listId,
                    bookmarkId = it.bookmarkId,
                    addedAt = it.addedAt,
                )
            },
        )
        validate(archive)
        val bytes = mapper.writeValueAsBytes(archive)
        require(bytes.size <= MAX_FILE_BYTES) { "The library is too large to export as one file" }

        val destination = withOrbitExtension(target).absoluteFile.toPath()
        val parent = destination.parent ?: error("Choose a destination folder")
        require(Files.isDirectory(parent)) { "The destination folder is unavailable" }
        val temporary = Files.createTempFile(parent, ".orbitlib-", ".part")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return destination.toFile()
    }

    fun read(file: File): ValidatedLibraryArchive {
        require(file.isFile) { "Choose a library file" }
        require(file.length() in 1L..MAX_FILE_BYTES.toLong()) { "The selected file is empty or too large" }
        val bytes = Files.newInputStream(file.toPath()).use { it.readNBytes(MAX_FILE_BYTES + 1) }
        require(bytes.size <= MAX_FILE_BYTES) { "The selected file is too large" }
        val archive = try {
            mapper.readValue<LibraryArchive>(bytes)
        } catch (_: Exception) {
            throw IllegalArgumentException("The selected file is not a valid Orbit library file")
        }
        validate(archive)
        return ValidatedLibraryArchive(
            archive = archive,
            preview = LibraryArchivePreview(
                exportedAt = archive.exportedAt,
                sourceAppVersion = archive.sourceAppVersion,
                bookmarkCount = archive.bookmarks.size,
                historyCount = archive.watchHistory.size,
                followedShowCount = archive.followedShows.size,
                episodeWatchMarkCount = archive.episodeWatchMarks.size,
                customListCount = archive.customLists.size,
                customListItemCount = archive.customListItems.size,
            ),
        )
    }

    fun import(archive: LibraryArchive, targetProfileId: Int): ProfileLibraryMergeResult {
        validate(archive)
        val bookmarks = archive.bookmarks.map {
            DesktopBookmark(
                id = it.id,
                name = it.name,
                url = it.url,
                apiName = it.apiName,
                posterUrl = it.posterUrl,
                watchType = it.watchType,
                dateAdded = it.dateAdded,
            )
        }
        val history = archive.watchHistory.map {
            WatchHistory(
                parentId = "",
                showName = it.showName,
                showUrl = it.showUrl,
                apiName = it.apiName,
                posterUrl = it.posterUrl,
                episodeThumbnailUrl = it.episodeThumbnailUrl,
                screenshotUrl = null,
                episode = it.episode,
                season = it.season,
                episodeId = it.episodeId,
                position = it.position,
                duration = it.duration,
                updateTime = it.updateTime,
                episodeName = it.episodeName,
                episodeDescription = it.episodeDescription,
            )
        }
        val followedShows = archive.followedShows.map {
            FollowedShow(
                profileId = targetProfileId,
                providerName = it.providerName,
                showUrl = it.showUrl,
                showName = it.showName,
                posterUrl = it.posterUrl,
                tmdbId = it.tmdbId,
                addedAt = it.addedAt,
            )
        }
        val episodeWatchMarks = archive.episodeWatchMarks.map {
            EpisodeWatchMark(
                profileId = targetProfileId,
                providerName = it.providerName,
                showUrl = it.showUrl,
                episodeKey = it.episodeKey,
                episodeId = it.episodeId,
                showName = it.showName,
                seasonNumber = it.seasonNumber,
                episodeNumber = it.episodeNumber,
                watchedAt = it.watchedAt,
            )
        }
        val customLists = archive.customLists.map {
            DesktopCustomList(
                id = it.id,
                name = it.name,
                sortOrder = it.sortOrder,
                createdAt = it.createdAt,
                showOnHome = it.showOnHome,
            )
        }
        val customListItems = archive.customListItems.map {
            DesktopCustomListItem(
                listId = it.listId,
                bookmarkId = it.bookmarkId,
                addedAt = it.addedAt,
            )
        }
        return DesktopDataStore.mergeProfileLibrary(
            targetProfileId,
            bookmarks,
            history,
            followedShows,
            episodeWatchMarks,
            customLists,
            customListItems,
        )
    }

    private fun validate(archive: LibraryArchive) {
        require(archive.format == FORMAT) { "This is not an Auras Orbit library file" }
        require(archive.formatVersion in 1..FORMAT_VERSION) { "This library file uses an unsupported format version" }
        require(
            archive.bookmarks.size <= MAX_RECORDS_PER_SECTION &&
                archive.watchHistory.size <= MAX_RECORDS_PER_SECTION &&
                archive.followedShows.size <= MAX_RECORDS_PER_SECTION &&
                archive.episodeWatchMarks.size <= MAX_RECORDS_PER_SECTION &&
                archive.customLists.size <= MAX_RECORDS_PER_SECTION &&
                archive.customListItems.size <= MAX_RECORDS_PER_SECTION,
        ) {
            "The library file contains too many records"
        }
        require(archive.sourceAppVersion.isNotBlank() && archive.sourceAppVersion.length <= 64 && archive.exportedAt.length <= 64) {
            "The library header is invalid"
        }
        try {
            Instant.parse(archive.exportedAt)
        } catch (_: Exception) {
            throw IllegalArgumentException("The library export date is invalid")
        }

        val bookmarkIds = mutableSetOf<String>()
        archive.bookmarks.forEach { bookmark ->
            requireText(bookmark.id, "bookmark id")
            requireText(bookmark.name, "bookmark title")
            requireText(bookmark.url, "bookmark URL")
            requireText(bookmark.apiName, "provider name")
            requireOptionalText(bookmark.posterUrl, "poster URL")
            require(bookmark.watchType in DesktopWatchType.entries.map { it.id }) { "The library contains an unknown watch status" }
            require(bookmark.dateAdded >= 0) { "The library contains an invalid added date" }
            require(bookmarkIds.add(bookmark.id)) { "The library contains duplicate bookmarks" }
        }

        val historyKeys = mutableSetOf<String>()
        archive.watchHistory.forEach { item ->
            requireText(item.showName, "show title")
            requireText(item.showUrl, "show URL")
            requireText(item.apiName, "provider name")
            requireOptionalText(item.posterUrl, "poster URL")
            requireOptionalText(item.episodeThumbnailUrl, "episode thumbnail URL")
            requireOptionalText(item.episodeId, "episode id")
            requireOptionalText(item.episodeName, "episode title")
            requireOptionalText(item.episodeDescription, "episode description")
            require(item.position >= 0 && item.duration >= 0 && item.updateTime >= 0) { "The library contains invalid playback progress" }
            require((item.season == null || item.season >= 0) && (item.episode == null || item.episode >= 0)) {
                "The library contains invalid episode coordinates"
            }
            val key = "${item.apiName}\u0000${item.showUrl}\u0000${item.episodeId.orEmpty()}"
            require(historyKeys.add(key)) { "The library contains duplicate episode progress records" }
        }

        val followedKeys = mutableSetOf<String>()
        archive.followedShows.forEach { item ->
            requireText(item.providerName, "provider name")
            requireText(item.showUrl, "show URL")
            requireText(item.showName, "show title")
            requireOptionalText(item.posterUrl, "poster URL")
            require(item.tmdbId == null || item.tmdbId > 0) { "The library contains an invalid metadata id" }
            require(item.addedAt >= 0) { "The library contains an invalid follow date" }
            require(followedKeys.add("${item.providerName}\u0000${item.showUrl}")) { "The library contains duplicate followed shows" }
        }

        val watchMarkKeys = mutableSetOf<String>()
        archive.episodeWatchMarks.forEach { item ->
            requireText(item.providerName, "provider name")
            requireText(item.showUrl, "show URL")
            requireText(item.episodeKey, "episode key")
            requireText(item.episodeId, "episode id")
            requireText(item.showName, "show title")
            require(item.seasonNumber >= 0 && item.episodeNumber >= 0 && item.watchedAt >= 0) {
                "The library contains an invalid episode watch mark"
            }
            val key = "${item.providerName}\u0000${item.showUrl}\u0000${item.episodeKey}"
            require(watchMarkKeys.add(key)) { "The library contains duplicate episode watch marks" }
        }

        val customListIds = mutableSetOf<String>()
        val customListNames = mutableSetOf<String>()
        archive.customLists.forEach { item ->
            requireText(item.id, "custom list id")
            require(item.name.isNotBlank() && item.name == item.name.trim() && item.name.length <= 64) {
                "The library contains an invalid custom list name"
            }
            require(item.sortOrder >= 0 && item.createdAt >= 0) { "The library contains invalid custom list details" }
            require(customListIds.add(item.id)) { "The library contains duplicate custom lists" }
            require(customListNames.add(item.name.lowercase(java.util.Locale.ROOT))) {
                "The library contains duplicate custom list names"
            }
        }

        val customListItemKeys = mutableSetOf<String>()
        archive.customListItems.forEach { item ->
            requireText(item.listId, "custom list id")
            requireText(item.bookmarkId, "bookmark id")
            require(item.addedAt >= 0) { "The library contains an invalid custom list item date" }
            require(item.listId in customListIds && item.bookmarkId in bookmarkIds) {
                "The library contains a custom list item with no matching list or title"
            }
            require(customListItemKeys.add("${item.listId}\u0000${item.bookmarkId}")) {
                "The library contains duplicate custom list items"
            }
        }
    }

    private fun requireText(value: String, label: String) {
        require(value.isNotBlank() && value.length <= MAX_TEXT_LENGTH) { "The library contains an invalid $label" }
    }

    private fun requireOptionalText(value: String?, label: String) {
        require(value == null || value.length <= MAX_TEXT_LENGTH) { "The library contains an invalid $label" }
    }

    private fun withOrbitExtension(file: File): File {
        if (file.name.endsWith(".orbitlib", ignoreCase = true)) return file
        return File(file.parentFile, "${file.name}.orbitlib")
    }
}
