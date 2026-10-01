package com.lagradost.cloudstream3.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.metadata.MetadataMatch
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.cloudstream3.desktop.utils.NativeFileDialog
import com.lagradost.cloudstream3.desktop.utils.TitleUtils
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.DesktopWatchType
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

data class NetworkStreamMetadataRecord(
    val providerId: String,
    val mediaType: String,
    val matchedTitle: String,
    val matchedYear: Int?,
    val tmdbId: Int?,
    val imdbId: String?,
    val anilistId: Int?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val description: String?,
    val rating: Double?,
)

object GlobalMediaLauncher {
    const val NETWORK_STREAM_API_NAME = "Network"
    private const val NETWORK_STREAM_EPISODE_ID = "network"

    var showNetworkStreamDialog by mutableStateOf(false)
    var networkStreamMetadataTarget by mutableStateOf<WatchHistory?>(null)
    val globalPlayerLauncher = AtomicReference<((VideoLaunchData) -> Unit)?>(null)

    fun openLocalFileDialog(
        scope: CoroutineScope = com.lagradost.cloudstream3.desktop.utils.appScope,
        launcher: ((VideoLaunchData) -> Unit)? = globalPlayerLauncher.get(),
    ) {
        val selectedFile = NativeFileDialog.open(
            title = "Select Video File",
            allowedExtensions = listOf(".mp4", ".mkv", ".m3u8", ".webm", ".avi", ".mov", ".ts", ".flv", ".mp3", ".flac", ".m4a"),
            category = NativeFileDialog.Category.MEDIA,
        )
        if (selectedFile != null) {
            playLocalFile(selectedFile, scope, launcher)
        }
    }

    fun playLocalFile(
        file: File,
        scope: CoroutineScope = com.lagradost.cloudstream3.desktop.utils.appScope,
        launcher: ((VideoLaunchData) -> Unit)? = globalPlayerLauncher.get(),
    ) {
        val filePath = file.absolutePath
        val targetLauncher = launcher ?: globalPlayerLauncher.get() ?: return
        scope.launch(Dispatchers.IO) {
            if (!file.exists()) return@launch
            targetLauncher(
                VideoLaunchData(
                    links = listOf(
                        newExtractorLink(
                            source = "Local File",
                            name = file.name,
                            url = filePath,
                            type = if (filePath.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        ) {
                            this.quality = Qualities.Unknown.value
                        },
                    ),
                    initialIndex = 0,
                    title = file.name,
                    subtitles = emptyList(),
                    startPositionMs = 0L,
                    history = WatchHistory(
                        parentId = "local",
                        showName = file.name,
                        showUrl = filePath,
                        apiName = "Local",
                        posterUrl = null,
                        episodeThumbnailUrl = null,
                        screenshotUrl = null,
                        episode = null,
                        season = null,
                        episodeId = "local",
                        position = 0L,
                        duration = 0L,
                    ),
                ),
            )
        }
    }

    fun playStreamUrl(
        url: String,
        scope: CoroutineScope = com.lagradost.cloudstream3.desktop.utils.appScope,
        launcher: ((VideoLaunchData) -> Unit)? = globalPlayerLauncher.get(),
        resumeHistory: WatchHistory? = null,
        displayName: String? = null,
        posterUrl: String? = null,
    ) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        val targetLauncher = launcher ?: globalPlayerLauncher.get() ?: return
        val isM3u8 = trimmed.contains(".m3u8", ignoreCase = true)
        val isDash = trimmed.contains(".mpd", ignoreCase = true)
        val isMagnet = trimmed.startsWith("magnet:", ignoreCase = true)
        val linkType = when {
            isMagnet -> ExtractorLinkType.MAGNET
            isM3u8 -> ExtractorLinkType.M3U8
            isDash -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        val streamName = streamDisplayName(trimmed)
        val preferredName = displayName?.takeIf { it.isNotBlank() } ?: streamName

        scope.launch(Dispatchers.IO) {
            val historyId = networkStreamHistoryId(trimmed)
            val savedHistory = DesktopDataStore.getEpisodeWatched(historyId, NETWORK_STREAM_EPISODE_ID)
                ?: resumeHistory?.takeIf { isNetworkStreamHistory(it) && it.showUrl == trimmed }
                ?: DesktopDataStore.getEpisodeWatched("network", NETWORK_STREAM_EPISODE_ID)
                    ?.takeIf { isNetworkStreamHistory(it) && it.showUrl == trimmed }
            val history = (
                savedHistory ?: WatchHistory(
                    parentId = historyId,
                    showName = preferredName,
                    showUrl = trimmed,
                    apiName = NETWORK_STREAM_API_NAME,
                    posterUrl = posterUrl,
                    episodeThumbnailUrl = null,
                    screenshotUrl = null,
                    episode = null,
                    season = null,
                    episodeId = NETWORK_STREAM_EPISODE_ID,
                    position = 0L,
                    duration = 0L,
                )
                ).copy(
                parentId = historyId,
                showName = savedHistory?.showName?.takeIf { it.isNotBlank() } ?: preferredName,
                showUrl = trimmed,
                apiName = NETWORK_STREAM_API_NAME,
                posterUrl = savedHistory?.posterUrl ?: posterUrl,
                episodeId = NETWORK_STREAM_EPISODE_ID,
                updateTime = System.currentTimeMillis(),
            )
            val isCompleted = history.duration > 0L && history.position >= history.duration - 15L
            val resumePositionMs = if (history.duration > 0L && !isCompleted) history.position * 1000L else 0L
            val launchHistory = history.copy(position = if (isCompleted) 0L else history.position)
            DesktopDataStore.setLastWatched(launchHistory)
            if (savedHistory?.parentId == "network") {
                DesktopDataStore.removeWatchHistory("network")
            }

            targetLauncher(
                VideoLaunchData(
                    links = listOf(
                        newExtractorLink(
                            source = "Network Stream",
                            name = launchHistory.showName,
                            url = trimmed,
                            type = linkType,
                        ) {
                            this.quality = Qualities.Unknown.value
                        },
                    ),
                    initialIndex = 0,
                    title = launchHistory.showName,
                    subtitles = emptyList(),
                    startPositionMs = resumePositionMs,
                    history = launchHistory,
                ),
            )
        }
    }

    fun isNetworkStreamHistory(history: WatchHistory): Boolean =
        history.apiName.equals(NETWORK_STREAM_API_NAME, ignoreCase = true) &&
            (
                history.showUrl.startsWith("http://", ignoreCase = true) ||
                    history.showUrl.startsWith("https://", ignoreCase = true) ||
                    history.showUrl.startsWith("magnet:", ignoreCase = true)
                )

    fun playNetworkStreamHistory(
        history: WatchHistory,
        scope: CoroutineScope = com.lagradost.cloudstream3.desktop.utils.appScope,
    ) {
        if (isNetworkStreamHistory(history)) {
            playStreamUrl(history.showUrl, scope = scope, resumeHistory = history)
        }
    }

    fun playNetworkStreamBookmark(
        bookmark: DesktopBookmark,
        scope: CoroutineScope = com.lagradost.cloudstream3.desktop.utils.appScope,
    ) {
        if (bookmark.apiName == NETWORK_STREAM_API_NAME) {
            playStreamUrl(bookmark.url, scope = scope, displayName = bookmark.name, posterUrl = bookmark.posterUrl)
        }
    }

    fun showNetworkStreamMetadata(history: WatchHistory) {
        if (isNetworkStreamHistory(history)) networkStreamMetadataTarget = history
    }

    fun getNetworkStreamMetadata(history: WatchHistory): NetworkStreamMetadataRecord? =
        DesktopDataStore.getKey(networkStreamMetadataKey(history.showUrl))

    fun saveNetworkStreamMetadata(history: WatchHistory, match: MetadataMatch, mediaType: String) {
        com.lagradost.cloudstream3.desktop.utils.appScope.launch(Dispatchers.IO) {
            try {
                DesktopDataStore.setKey(
                    networkStreamMetadataKey(history.showUrl),
                    NetworkStreamMetadataRecord(
                        providerId = match.providerId,
                        mediaType = mediaType,
                        matchedTitle = match.matchedTitle,
                        matchedYear = match.matchedYear,
                        tmdbId = match.tmdbId,
                        imdbId = match.imdbId,
                        anilistId = match.anilistId,
                        posterUrl = match.posterUrl,
                        backdropUrl = match.backdropUrl,
                        description = match.description,
                        rating = match.rating,
                    ),
                )
                val current = DesktopDataStore.getEpisodeWatched(history.parentId, history.episodeId)
                    ?: history
                val updated = current.copy(
                    showName = match.matchedTitle,
                    posterUrl = match.posterUrl ?: current.posterUrl,
                    updateTime = System.currentTimeMillis(),
                )
                DesktopDataStore.setLastWatched(
                    updated,
                )
                val bookmarkId = "Network_${networkStreamFingerprint(history.showUrl)}"
                DesktopDataStore.getBookmarks().firstOrNull { it.id == bookmarkId }?.let { existing ->
                    DesktopDataStore.addBookmark(
                        existing.copy(
                            name = match.matchedTitle,
                            posterUrl = match.posterUrl ?: existing.posterUrl,
                        ),
                    )
                }
            } catch (e: Exception) {
                com.lagradost.common.logging.AppLogger.e("GlobalMediaLauncher", "Could not save network stream metadata", e)
            }
        }
    }

    fun addNetworkStreamToLibrary(
        history: WatchHistory,
        name: String = history.showName,
        posterUrl: String? = history.posterUrl,
    ) {
        if (!isNetworkStreamHistory(history)) return
        val profileId = ProfileManager.activeProfileId
        com.lagradost.cloudstream3.desktop.utils.appScope.launch(Dispatchers.IO) {
            try {
                val bookmarkId = "Network_${networkStreamFingerprint(history.showUrl)}"
                val existing = DesktopDataStore.getBookmarks(profileId).firstOrNull { it.id == bookmarkId }
                com.lagradost.cloudstream3.desktop.di.AppContainerHolder.container.bookmarksRepository.addBookmark(
                    DesktopBookmark(
                        id = bookmarkId,
                        name = name.ifBlank { history.showName },
                        url = history.showUrl,
                        apiName = NETWORK_STREAM_API_NAME,
                        posterUrl = posterUrl,
                        watchType = existing?.watchType ?: DesktopWatchType.WATCHING.id,
                        dateAdded = existing?.dateAdded ?: System.currentTimeMillis(),
                    ),
                    profileId,
                )
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showInfo("Added to Library")
                }
            } catch (e: Exception) {
                com.lagradost.common.logging.AppLogger.e("GlobalMediaLauncher", "Could not add network stream to Library", e)
            }
        }
    }

    private fun networkStreamHistoryId(url: String): String =
        "p${DesktopDataStore.activeProfileId}_Network_${networkStreamFingerprint(url)}"

    private fun networkStreamMetadataKey(url: String): String =
        "${DesktopDataStore.activeProfileId}/network_stream_metadata_v1_${networkStreamFingerprint(url)}"

    private fun streamDisplayName(url: String): String {
        val segment = runCatching { URI(url).path?.substringAfterLast('/') }.getOrNull()
            ?: url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(segment, Charsets.UTF_8.name()) }.getOrDefault(segment)
        val withoutExtension = decoded.replace(Regex("(?i)\\.(m3u8|mpd|mp4|mkv|webm|mov|avi|ts)$"), "")
        val (cleanTitle, year) = TitleUtils.cleanProviderTitle(withoutExtension)
        val cleaned = cleanTitle
            .replace(Regex("[._]+"), " ")
            .trim()
        return if (cleaned.isBlank() || cleaned.lowercase() in setOf("master", "playlist", "index", "stream", "video", "hls", "dash")) {
            "Network Stream"
        } else {
            if (year != null) "$cleaned ($year)" else cleaned
        }
    }

    private fun networkStreamFingerprint(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    @androidx.compose.runtime.Composable
    fun GlobalNetworkStreamDialog() {
        if (!showNetworkStreamDialog) return
        var streamUrl by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog(
            show = showNetworkStreamDialog,
            onDismissRequest = { showNetworkStreamDialog = false },
            title = { androidx.compose.material3.Text("Open Stream URL or Magnet") },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Paste a direct HTTP / HLS stream link or a magnet link below:")
                    androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = streamUrl,
                        onValueChange = { streamUrl = it },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { androidx.compose.material3.Text("https://…/video.m3u8 or magnet:?xt=urn:btih:…") },
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        if (streamUrl.isNotBlank()) {
                            playStreamUrl(streamUrl, scope)
                        }
                        showNetworkStreamDialog = false
                    },
                ) {
                    androidx.compose.material3.Text("Play")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showNetworkStreamDialog = false }) {
                    androidx.compose.material3.Text("Cancel")
                }
            },
        )
    }
}
