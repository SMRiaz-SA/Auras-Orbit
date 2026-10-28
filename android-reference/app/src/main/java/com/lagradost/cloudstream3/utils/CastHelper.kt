package com.lagradost.cloudstream3.utils

import android.content.Context
import android.os.Looper
import android.widget.Toast
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import com.google.android.gms.cast.*
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.images.WebImage
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.MetadataHolder
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

object CastHelper {
    internal const val MAX_CAST_METADATA_EPISODES = 40
    private const val MAX_CAST_LOCAL_EPISODES = 5_000
    private const val MAX_CAST_LOCAL_SEQUENCE_BYTES = 4 * 1024 * 1024
    internal const val MAX_CAST_CUSTOM_DATA_BYTES = 48 * 1024
    private const val CAST_EPISODE_SEQUENCE_DIRECTORY = "cast_episode_sequences"
    private const val MAX_CACHED_CAST_SEQUENCE_FILES = 4

    @kotlinx.serialization.Serializable
    private data class CastEpisodeSequenceFile(
        val accountId: String,
        val profileToken: String = "",
        val episodes: List<ResultEpisode>,
    )

    private data class CachedCastEpisodeSequence(
        val accountId: String,
        val profileToken: String,
        val episodes: List<ResultEpisode>,
    )

    internal data class CastEpisodeProfileBinding(
        val accountId: String,
        val profileToken: String,
    )

    private val castEpisodeSequences = LinkedHashMap<String, CachedCastEpisodeSequence>()
    private val castEpisodeSequenceAccounts = LinkedHashMap<String, CastEpisodeProfileBinding>()
    private val castEpisodeSequenceCacheLock = Any()
    private val latestCastStartRequestToken = AtomicLong(0L)

    internal data class CastStartMediaIdentity(
        val itemId: Int,
        val contentId: String?,
        val customData: String?,
    )

    internal data class CastStartAttemptMediaIdentity(
        val contentId: String,
        val customData: String?,
    )

    internal data class CastStartRequestGuard(
        val requestToken: Long,
        val accountId: String,
        val profileToken: String,
        val profileRevision: Int,
        val session: Any,
        val remoteMediaClient: Any,
        val mediaIdentity: CastStartMediaIdentity?,
    )

    private data class PreparedCastStartLoad(
        val request: MediaLoadRequestData,
        val mediaIdentity: CastStartAttemptMediaIdentity,
    )

    internal fun isCurrentCastStartRequest(
        guard: CastStartRequestGuard,
        latestRequestToken: Long,
        currentAccountId: String,
        currentProfileToken: String,
        currentProfileRevision: Int,
        activeSession: Any?,
        activeRemoteMediaClient: Any?,
        currentMediaIdentity: CastStartMediaIdentity?,
        expectedMediaIdentity: CastStartMediaIdentity? = guard.mediaIdentity,
    ): Boolean = isCurrentCastStartRequestToken(
        guard = guard,
        latestRequestToken = latestRequestToken,
        currentAccountId = currentAccountId,
        currentProfileToken = currentProfileToken,
        currentProfileRevision = currentProfileRevision,
    ) &&
            guard.session === activeSession &&
            guard.remoteMediaClient === activeRemoteMediaClient &&
            expectedMediaIdentity == currentMediaIdentity

    /** Worker-safe guard for sequence/header persistence; it never touches Cast SDK objects. */
    internal fun isCurrentCastStartRequestToken(
        guard: CastStartRequestGuard,
        latestRequestToken: Long,
        currentAccountId: String,
        currentProfileToken: String,
        currentProfileRevision: Int,
    ): Boolean = guard.requestToken == latestRequestToken &&
        guard.accountId == currentAccountId &&
        guard.profileToken == currentProfileToken &&
        guard.profileRevision == currentProfileRevision

    internal fun isExpectedCastStartFailureTransition(
        expectedBeforeLoad: CastStartMediaIdentity?,
        currentAfterFailure: CastStartMediaIdentity?,
        attemptedMediaIdentity: CastStartAttemptMediaIdentity,
    ): Boolean =
        currentAfterFailure == expectedBeforeLoad ||
            currentAfterFailure == null ||
            (currentAfterFailure.contentId == attemptedMediaIdentity.contentId &&
                currentAfterFailure.customData == attemptedMediaIdentity.customData)

    internal data class CastEpisodeWindow<T>(
        val offset: Int,
        val episodes: List<T>,
    )

    internal fun <T> createCastEpisodeWindow(
        episodes: List<T>,
        startIndex: Int,
        limit: Int = MAX_CAST_METADATA_EPISODES,
    ): CastEpisodeWindow<T>? {
        if (startIndex !in episodes.indices || limit <= 0) return null
        val endIndex = minOf(episodes.size, startIndex + limit)
        return CastEpisodeWindow(startIndex, episodes.subList(startIndex, endIndex))
    }

    internal fun getCachedCastEpisodeSequence(
        sequenceId: String,
        accountId: String,
        profileToken: String,
    ): List<ResultEpisode>? =
        synchronized(castEpisodeSequenceCacheLock) {
            castEpisodeSequences[sequenceId]
                ?.takeIf { it.accountId == accountId && it.profileToken == profileToken }
                ?.episodes
        }

    internal fun getCastEpisodeSequenceProfileBinding(sequenceId: String): CastEpisodeProfileBinding? =
        synchronized(castEpisodeSequenceCacheLock) { castEpisodeSequenceAccounts[sequenceId] }

    internal fun isCastEpisodeSequenceProfileAllowed(
        binding: CastEpisodeProfileBinding,
        currentAccountId: String,
        currentProfileToken: String,
    ): Boolean = binding.accountId == currentAccountId && binding.profileToken == currentProfileToken

    private fun cacheCastEpisodeSequence(
        sequenceId: String,
        accountId: String,
        profileToken: String,
        episodes: List<ResultEpisode>,
    ) {
        synchronized(castEpisodeSequenceCacheLock) {
            castEpisodeSequences[sequenceId] = CachedCastEpisodeSequence(accountId, profileToken, episodes)
            castEpisodeSequenceAccounts[sequenceId] = CastEpisodeProfileBinding(accountId, profileToken)
            while (castEpisodeSequences.size > MAX_CACHED_CAST_SEQUENCE_FILES) {
                val oldestSequenceId = castEpisodeSequences.keys.first()
                castEpisodeSequences.remove(oldestSequenceId)
                castEpisodeSequenceAccounts.remove(oldestSequenceId)
            }
            while (castEpisodeSequenceAccounts.size > MAX_CACHED_CAST_SEQUENCE_FILES) {
                castEpisodeSequenceAccounts.remove(castEpisodeSequenceAccounts.keys.first())
            }
        }
    }

    private fun rememberCastEpisodeSequenceAccount(sequenceId: String, accountId: String, profileToken: String) {
        synchronized(castEpisodeSequenceCacheLock) {
            castEpisodeSequenceAccounts[sequenceId] = CastEpisodeProfileBinding(accountId, profileToken)
            while (castEpisodeSequenceAccounts.size > MAX_CACHED_CAST_SEQUENCE_FILES) {
                castEpisodeSequenceAccounts.remove(castEpisodeSequenceAccounts.keys.first())
            }
        }
    }

    private fun getCastEpisodeSequenceFile(context: Context, sequenceId: String): File? {
        val isCanonicalUuid = runCatching { UUID.fromString(sequenceId).toString() == sequenceId }
            .getOrDefault(false)
        if (!isCanonicalUuid) return null
        return runCatching {
            val directory = File(context.filesDir, CAST_EPISODE_SEQUENCE_DIRECTORY).canonicalFile
            val file = File(directory, "$sequenceId.json").canonicalFile
            if (file.parentFile != directory) null else file
        }.getOrNull()
    }

    /** Persist the sanitized full sequence and local headers before loading the first Cast item. */
    private fun storeCastEpisodeSequence(
        context: Context,
        sequenceId: String,
        accountId: String,
        profileToken: String,
        apiName: String,
        episodes: List<ResultEpisode>,
        isRequestCurrent: () -> Boolean,
    ): Boolean {
        if (!isRequestCurrent()) return false
        if (episodes.isEmpty() || episodes.size > MAX_CAST_LOCAL_EPISODES) return false

        val sanitizedEpisodes = episodes.map { it.copy(posterHeaders = null) }
        val json = CastEpisodeSequenceFile(accountId, profileToken, sanitizedEpisodes).toJson()
        val encoded = json.toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_CAST_LOCAL_SEQUENCE_BYTES) return false

        val sequenceFile = getCastEpisodeSequenceFile(context, sequenceId) ?: return false
        val directory = sequenceFile.parentFile ?: return false
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) return false

        val temporaryFile = File(directory, "$sequenceId.tmp")
        return try {
            temporaryFile.writeBytes(encoded)
            val headersCached = runCatching {
                DataStoreHelper.cacheCastEpisodePosterHeaders(
                    accountId,
                    profileToken,
                    apiName,
                    episodes,
                    isRequestCurrent,
                )
            }.getOrElse {
                logError(it)
                false
            }
            if (!headersCached) {
                runCatching {
                    DataStoreHelper.clearCastEpisodePosterHeaders(
                        accountId,
                        profileToken,
                        apiName,
                        episodes,
                        isRequestCurrent,
                    )
                }.onFailure { logError(it) }
                logError(IllegalStateException("Cast poster headers were not cached; continuing without them."))
            }
            if (!temporaryFile.renameTo(sequenceFile)) {
                temporaryFile.delete()
                return false
            }
            if (!isRequestCurrent()) {
                sequenceFile.delete()
                return false
            }
            cacheCastEpisodeSequence(sequenceId, accountId, profileToken, episodes.map { it.copy() })
            pruneCastEpisodeSequenceFiles(directory, sequenceFile.name)
            true
        } catch (error: Exception) {
            temporaryFile.delete()
            logError(error)
            false
        }
    }

    private fun pruneCastEpisodeSequenceFiles(directory: File, currentFileName: String) {
        directory.listFiles()
            ?.filter { it.isFile && it.extension == "json" && it.name != currentFileName }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_CACHED_CAST_SEQUENCE_FILES - 1)
            ?.forEach(File::delete)
    }

    /** Restores a previously started local sequence without reading files or prefs on Main. */
    internal suspend fun restoreCastEpisodeSequence(
        context: Context,
        accountId: String,
        profileToken: String,
        apiName: String,
        sequenceId: String,
        protectedParentIds: List<Int> = emptyList(),
    ): List<ResultEpisode>? = withContext(Dispatchers.IO) {
        getCachedCastEpisodeSequence(sequenceId, accountId, profileToken)?.let { cached ->
            val binding = getCastEpisodeSequenceProfileBinding(sequenceId)
            if (binding != null && isCastEpisodeSequenceProfileAllowed(binding, accountId, profileToken)) {
                return@withContext cached
            }
        }
        val sequenceFile = getCastEpisodeSequenceFile(context, sequenceId)
            ?.takeIf { it.isFile && it.length() <= MAX_CAST_LOCAL_SEQUENCE_BYTES }
            ?: return@withContext null
        val storedSequence = runCatching {
            parseJson<CastEpisodeSequenceFile>(sequenceFile.readText(Charsets.UTF_8))
        }.getOrElse {
            logError(it)
            return@withContext null
        }
        val binding = CastEpisodeProfileBinding(storedSequence.accountId, storedSequence.profileToken)
        rememberCastEpisodeSequenceAccount(sequenceId, binding.accountId, binding.profileToken)
        if (!isCastEpisodeSequenceProfileAllowed(binding, accountId, profileToken)) {
            return@withContext null
        }
        val sanitizedEpisodes = storedSequence.episodes
        if (sanitizedEpisodes.isEmpty() || sanitizedEpisodes.size > MAX_CAST_LOCAL_EPISODES) {
            return@withContext null
        }

        val protectedParents = (protectedParentIds.asSequence() + sanitizedEpisodes.asSequence().map { it.parentId })
            .distinct()
            .take(16)
            .toList()
        DataStoreHelper.pruneCastEpisodePosterHeaderCache(accountId, profileToken, apiName, protectedParents)
        protectedParents.forEach { parentId ->
            DataStoreHelper.preloadCachedCastEpisodePosterHeaders(accountId, profileToken, apiName, parentId)
        }
        val restoredEpisodes = sanitizedEpisodes.map { episode ->
            episode.copy(
                posterHeaders = DataStoreHelper.getCachedCastEpisodePosterHeaders(
                    accountId,
                    profileToken,
                    apiName,
                    episode.parentId,
                    episode.id,
                ),
            )
        }
        cacheCastEpisodeSequence(sequenceId, accountId, profileToken, restoredEpisodes)
        restoredEpisodes
    }

    internal fun MetadataHolder.toCastMetadataWindow(): MetadataHolder? {
        val absoluteStart = episodeOffset + currentEpisodeIndex
        val window = createCastEpisodeWindow(episodes, currentEpisodeIndex) ?: return null
        var windowEpisodes = window.episodes

        while (windowEpisodes.isNotEmpty()) {
            val candidate = copy(
                currentEpisodeIndex = 0,
                episodeOffset = absoluteStart,
                episodes = windowEpisodes.map { it.copy(posterHeaders = null) },
            )
            if (candidate.toJson().toByteArray(Charsets.UTF_8).size <= MAX_CAST_CUSTOM_DATA_BYTES) {
                return candidate
            }
            if (windowEpisodes.size == 1) return null
            windowEpisodes = windowEpisodes.dropLast(1)
        }
        return null
    }

    fun getMediaInfo(
        epData: ResultEpisode,
        holder: MetadataHolder,
        index: Int,
        data: JSONObject?,
        subtitles: List<SubtitleData>
    ): MediaInfo {
        val link = holder.currentLinks[index]
        val movieMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
        movieMetadata.putString(
            MediaMetadata.KEY_SUBTITLE,
            if (holder.isMovie)
                "${link.name} ${Qualities.getStringByInt(link.quality)}"
            else
                (epData.name ?: "Episode ${epData.episode}") + " - ${link.name} ${Qualities.getStringByInt(link.quality)}"
        )

        holder.title?.let {
            movieMetadata.putString(MediaMetadata.KEY_TITLE, it)
        }

        val srcPoster = epData.poster ?: holder.poster
        if (srcPoster != null) {
            movieMetadata.addImage(WebImage(srcPoster.toUri()))
        }

        var subIndex = 0
        val tracks = subtitles.map {
            MediaTrack.Builder(subIndex++.toLong(), MediaTrack.TYPE_TEXT)
                .setName(it.name)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(it.url)
                .build()
        }

        val builder = MediaInfo.Builder(link.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(when(link.type) {
                ExtractorLinkType.M3U8 -> MimeTypes.APPLICATION_M3U8
                ExtractorLinkType.DASH -> MimeTypes.APPLICATION_MPD
                else -> MimeTypes.VIDEO_MP4
            })
            .setMetadata(movieMetadata)
            .setMediaTracks(tracks)
        data?.let {
            builder.setCustomData(data)
        }

        return builder.build()
    }

    fun awaitLinks(
        pending: PendingResult<RemoteMediaClient.MediaChannelResult>?,
        callback: (Boolean) -> Unit
    ) {
        if (pending == null) return
        main {
            val res = withContext(Dispatchers.IO) { pending.await() }
            when (res.status.statusCode) {
                CastStatusCodes.FAILED -> {
                    callback.invoke(true)
                    println("FAILED AND LOAD NEXT")
                }
                else -> Unit //IDK DO SMTH HERE
            }
        }
    }

    private fun RemoteMediaClient.currentCastStartMediaIdentity(): CastStartMediaIdentity? =
        runCatching {
            currentItem?.let { item ->
                CastStartMediaIdentity(
                    itemId = item.itemId,
                    contentId = item.media?.contentId,
                    customData = item.media?.customData?.toString(),
                )
            }
        }.getOrNull()

    private fun isCurrentCastStartRequestNow(
        guard: CastStartRequestGuard,
        activeSessionProvider: () -> CastSession?,
        expectedMediaIdentity: CastStartMediaIdentity? = guard.mediaIdentity,
    ): Boolean {
        if (Looper.myLooper() !== Looper.getMainLooper()) return false
        val activeSession = runCatching(activeSessionProvider).getOrNull()
        val activeClient = activeSession?.remoteMediaClient
        val initiatingClient = guard.remoteMediaClient as? RemoteMediaClient ?: return false
        return isCurrentCastStartRequest(
            guard = guard,
            latestRequestToken = latestCastStartRequestToken.get(),
            currentAccountId = DataStoreHelper.currentAccount,
            currentProfileToken = DataStoreHelper.getCurrentAccountProfileToken(),
            currentProfileRevision = DataStoreHelper.selectedAccountNumberFlow.value,
            activeSession = activeSession,
            activeRemoteMediaClient = activeClient,
            currentMediaIdentity = initiatingClient.currentCastStartMediaIdentity(),
            expectedMediaIdentity = expectedMediaIdentity,
        )
    }

    private fun isCurrentCastStartRequestTokenNow(guard: CastStartRequestGuard): Boolean =
        isCurrentCastStartRequestToken(
            guard = guard,
            latestRequestToken = latestCastStartRequestToken.get(),
            currentAccountId = DataStoreHelper.currentAccount,
            currentProfileToken = DataStoreHelper.getCurrentAccountProfileToken(),
            currentProfileRevision = DataStoreHelper.selectedAccountNumberFlow.value,
        )

    fun CastSession?.startCast(
        context: Context,
        activeSessionProvider: () -> CastSession?,
        apiName: String,
        isMovie: Boolean,
        title: String?,
        poster: String?,
        currentEpisodeIndex: Int,
        episodes: List<ResultEpisode>,
        currentLinks: List<ExtractorLink>,
        subtitles: List<SubtitleData>,
        startIndex: Int? = null,
        startTime: Long? = null,
    ): Boolean {
        // Advance synchronously, before any persistence or JSON work can yield to a later request.
        if (Looper.myLooper() !== Looper.getMainLooper()) return false
        val requestToken = latestCastStartRequestToken.incrementAndGet()
        val castSession = this ?: return false
        val accountId = DataStoreHelper.currentAccount
        val profileToken = DataStoreHelper.getCurrentAccountProfileToken()
        val profileRevision = DataStoreHelper.selectedAccountNumberFlow.value
        val remoteMediaClient = castSession.remoteMediaClient ?: return false
        val guard = CastStartRequestGuard(
            requestToken = requestToken,
            accountId = accountId,
            profileToken = profileToken,
            profileRevision = profileRevision,
            session = castSession,
            remoteMediaClient = remoteMediaClient,
            mediaIdentity = remoteMediaClient.currentCastStartMediaIdentity(),
        )
        if (episodes.isEmpty() || currentEpisodeIndex !in episodes.indices) return false
        val index = startIndex?.takeIf { it >= 0 } ?: 0
        if (index !in currentLinks.indices) return false

        val sequenceId = UUID.randomUUID().toString()
        context.ioSafe {
            try {
                if (!storeCastEpisodeSequence(
                        context,
                        sequenceId,
                        accountId,
                        profileToken,
                        apiName,
                        episodes,
                    ) { isCurrentCastStartRequestTokenNow(guard) }
                ) {
                    logError(IllegalArgumentException("Cast episode sequence exceeds the local safety bound."))
                    context.main {
                        if (isCurrentCastStartRequestNow(guard, activeSessionProvider)) {
                            Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@ioSafe
                }
                scheduleCastLoad(
                    context = context,
                    guard = guard,
                    activeSessionProvider = activeSessionProvider,
                    expectedMediaIdentity = guard.mediaIdentity,
                    sequenceId = sequenceId,
                    apiName = apiName,
                    isMovie = isMovie,
                    title = title,
                    poster = poster,
                    currentEpisodeIndex = currentEpisodeIndex,
                    episodes = episodes,
                    currentLinks = currentLinks,
                    subtitles = subtitles,
                    startIndex = index,
                    startTime = startTime,
                )
            } catch (error: Exception) {
                logError(error)
                context.main {
                    if (isCurrentCastStartRequestNow(guard, activeSessionProvider)) {
                        Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        return true
    }

    private fun scheduleCastLoad(
        context: Context,
        guard: CastStartRequestGuard,
        activeSessionProvider: () -> CastSession?,
        expectedMediaIdentity: CastStartMediaIdentity?,
        sequenceId: String,
        apiName: String,
        isMovie: Boolean,
        title: String?,
        poster: String?,
        currentEpisodeIndex: Int,
        episodes: List<ResultEpisode>,
        currentLinks: List<ExtractorLink>,
        subtitles: List<SubtitleData>,
        startIndex: Int,
        startTime: Long?,
    ) {
        context.ioSafe {
            val loadRequest = try {
                val currentEpisode = episodes.getOrNull(currentEpisodeIndex)
                    ?: return@ioSafe
                val holder = MetadataHolder(
                    apiName = apiName,
                    isMovie = isMovie,
                    title = title,
                    poster = poster,
                    currentEpisodeIndex = currentEpisodeIndex,
                    episodes = episodes,
                    currentLinks = currentLinks,
                    currentSubtitles = subtitles,
                    episodeSequenceId = sequenceId,
                )
                val metadataWindow = holder.toCastMetadataWindow()
                    ?: throw IllegalArgumentException("Cast metadata window exceeds its safe payload bound.")
                val customData = JSONObject(metadataWindow.toJson())
                val mediaInfo = getMediaInfo(
                    currentEpisode,
                    holder,
                    startIndex,
                    customData,
                    subtitles,
                )
                PreparedCastStartLoad(
                    request = MediaLoadRequestData.Builder()
                        .setMediaInfo(mediaInfo)
                        .setCurrentTime(startTime ?: 0L)
                        .build(),
                    mediaIdentity = CastStartAttemptMediaIdentity(
                        contentId = currentLinks[startIndex].url,
                        customData = customData.toString(),
                    ),
                )
            } catch (error: Exception) {
                logError(error)
                context.main {
                    if (isCurrentCastStartRequestNow(
                            guard,
                            activeSessionProvider,
                            expectedMediaIdentity,
                        )
                    ) {
                        Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show()
                    }
                }
                return@ioSafe
            }

            context.main {
                // All window serialization is done on IO. Keep the Cast load guarded on Main.
                if (!isCurrentCastStartRequestNow(guard, activeSessionProvider, expectedMediaIdentity)) {
                    return@main
                }
                val remoteMediaClient = guard.remoteMediaClient as? RemoteMediaClient ?: return@main
                val pending = remoteMediaClient.load(loadRequest.request)
                awaitLinks(pending) { failed ->
                    val currentAfterFailure = remoteMediaClient.currentCastStartMediaIdentity()
                    if (failed && currentLinks.size > startIndex + 1 &&
                        isCurrentCastStartRequestNow(
                            guard,
                            activeSessionProvider,
                            expectedMediaIdentity = currentAfterFailure,
                        ) && isExpectedCastStartFailureTransition(
                            expectedBeforeLoad = expectedMediaIdentity,
                            currentAfterFailure = currentAfterFailure,
                            attemptedMediaIdentity = loadRequest.mediaIdentity,
                        )
                    ) {
                        scheduleCastLoad(
                            context = context,
                            guard = guard,
                            activeSessionProvider = activeSessionProvider,
                            expectedMediaIdentity = currentAfterFailure,
                            sequenceId = sequenceId,
                            apiName = apiName,
                            isMovie = isMovie,
                            title = title,
                            poster = poster,
                            currentEpisodeIndex = currentEpisodeIndex,
                            episodes = episodes,
                            currentLinks = currentLinks,
                            subtitles = subtitles,
                            startIndex = startIndex + 1,
                            startTime = startTime,
                        )
                    }
                }
            }
        }
    }
}
