package com.lagradost.cloudstream3.utils

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.annotation.WorkerThread
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_SHARE
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

const val PROGRAM_ID_LIST_KEY = "persistent_program_ids"
private const val PENDING_PROGRAM_CLEANUP_KEY = "persistent_program_ids_pending_cleanup"

internal data class PopularPublishRequest(
    val generation: Long,
    val fingerprint: String,
    val shouldReplaceContent: Boolean,
)

/** Pure state for latest-selection ordering and bounded per-art retry backoff. */
internal class TvPopularSyncPolicy(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val baseBackoffMs: Long = 30_000L,
    private val maxBackoffMs: Long = 30L * 60L * 1000L,
    private val maxAttempts: Int = 5,
) {
    private data class RetryState(val attempts: Int, val nextAttemptAt: Long, val inFlight: Boolean)

    private var generation = 0L
    private var latestRequestedFingerprint: String? = null
    private var committedFingerprint: String? = null
    private val retryStates = mutableMapOf<String, RetryState>()

    @Synchronized
    fun request(fingerprint: String): PopularPublishRequest {
        val previous = latestRequestedFingerprint
        latestRequestedFingerprint = fingerprint
        val nextGeneration = ++generation
        return PopularPublishRequest(
            generation = nextGeneration,
            fingerprint = fingerprint,
            shouldReplaceContent = fingerprint != committedFingerprint || previous != fingerprint,
        )
    }

    @Synchronized
    fun isCurrent(request: PopularPublishRequest): Boolean =
        generation == request.generation && latestRequestedFingerprint == request.fingerprint

    @Synchronized
    fun commitContent(request: PopularPublishRequest): Boolean {
        if (!isCurrent(request)) return false
        committedFingerprint = request.fingerprint
        return true
    }

    @Synchronized
    fun committedFingerprint(): String? = committedFingerprint

    @Synchronized
    fun invalidateCommittedContent() {
        committedFingerprint = null
    }

    @Synchronized
    fun resetArtworkRetries() = retryStates.clear()

    @Synchronized
    fun claimDueArtworkKeys(): Set<String> {
        val now = nowMs()
        val due = retryStates.filter { (_, state) ->
            !state.inFlight && state.attempts < maxAttempts && state.nextAttemptAt <= now
        }.keys
        due.forEach { key ->
            val state = retryStates.getValue(key)
            retryStates[key] = state.copy(inFlight = true)
        }
        return due.toSet()
    }

    @Synchronized
    fun finishArtworkAttempt(key: String, succeeded: Boolean) {
        val previous = retryStates[key] ?: RetryState(0, 0L, true)
        if (succeeded) {
            retryStates.remove(key)
            return
        }
        val attempts = (previous.attempts + 1).coerceAtMost(maxAttempts)
        val multiplier = 1L shl (attempts - 1).coerceAtMost(20)
        val backoff = (baseBackoffMs * multiplier).coerceAtMost(maxBackoffMs)
        retryStates[key] = RetryState(attempts, nowMs() + backoff, inFlight = false)
    }

    @Synchronized
    fun hasDueOrScheduledArtworkRetries(): Boolean =
        retryStates.values.any { !it.inFlight && it.attempts < maxAttempts }

    @Synchronized
    fun nextArtworkRetryDelayMs(): Long? = retryStates.values
        .filter { !it.inFlight && it.attempts < maxAttempts }
        .minOfOrNull { (it.nextAttemptAt - nowMs()).coerceAtLeast(0L) }
}

internal fun isLegacyAurasWatchNextRow(
    internalProviderId: String?,
    intentUri: String?,
    rowPackageName: String?,
    expectedUrl: String,
    appPackageName: String,
    resumeIntentPrefix: String,
    hasEpisodeId: Boolean,
): Boolean = rowPackageName == appPackageName && internalProviderId == expectedUrl &&
    if (hasEpisodeId) intentUri.orEmpty().startsWith(resumeIntentPrefix)
    else intentUri == expectedUrl

object TvChannelUtils {
    private val popularContentLock = Mutex()
    private val popularPublisherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val popularSyncPolicy = TvPopularSyncPolicy()
    private val artworkRetrySchedulerRunning = AtomicBoolean(false)
    @Volatile private var publishedArtworkTargets: List<ArtworkTarget> = emptyList()

    fun isPopularShelf(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        return listOf("popular", "trending", "most viewed", "most watched")
            .any { phrase -> normalized.contains(phrase) } ||
            Regex("\\btop(?:\\s*10|\\s*ten)\\b|\\btop10\\b").containsMatchIn(normalized)
    }

    fun Context.saveProgramId(programId: Long) {
        val existing: List<Long> = getKey<List<Long>>(PROGRAM_ID_LIST_KEY) ?: emptyList()
        val updated = (existing + programId).distinct()
        setKey(PROGRAM_ID_LIST_KEY, updated)
    }
    fun Context.getStoredProgramIds(): List<Long> {
        return getKey<List<Long>>(PROGRAM_ID_LIST_KEY) ?: emptyList()
    }
    fun Context.removeProgramId(programId: Long) {
        val existing: List<Long> = getKey<List<Long>>(PROGRAM_ID_LIST_KEY) ?: emptyList()
        val updated = existing.filter { it != programId }
        setKey(PROGRAM_ID_LIST_KEY, updated)
    }

    private fun Context.getPendingCleanupProgramIds(): List<Long> =
        getKey<List<Long>>(PENDING_PROGRAM_CLEANUP_KEY) ?: emptyList()

    private fun Context.setPendingCleanupProgramIds(ids: List<Long>) {
        setKey(PENDING_PROGRAM_CLEANUP_KEY, ids.distinct())
    }


    fun getChannelId(context: Context, channelName: String): Long? {
        return try {
            val componentName = ComponentName(context, MainActivity::class.java)
            val inputId = TvContractCompat.buildInputId(componentName)
            val channelId = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(
                    TvContractCompat.Channels._ID,
                    TvContractCompat.Channels.COLUMN_DISPLAY_NAME,
                    TvContractCompat.Channels.COLUMN_INPUT_ID,
                ),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val channelInputId = cursor.getString(
                        cursor.getColumnIndexOrThrow(TvContractCompat.Channels.COLUMN_INPUT_ID)
                    )
                    if (channelInputId == inputId) return@use Pair(
                        cursor.getLong(
                            cursor.getColumnIndexOrThrow(TvContractCompat.Channels._ID)
                        ),
                        cursor.getString(
                            cursor.getColumnIndexOrThrow(TvContractCompat.Channels.COLUMN_DISPLAY_NAME)
                        ),
                    )
                }
                null
            }
            channelId?.let { (id, currentName) ->
                if (currentName != channelName) {
                    context.contentResolver.update(
                        ContentUris.withAppendedId(TvContractCompat.Channels.CONTENT_URI, id),
                        ContentValues().apply {
                            put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, channelName)
                        },
                        null,
                        null,
                    )
                }
                id
            }
        } catch (e: Exception) {
            Log.e("TvChannelUtils", "Query failed: ${e.message}", e)
            null
        }
    }

    private data class ArtworkTarget(
        val id: Long,
        val item: SearchResponse,
        val retryKey: String,
    )

    private data class InsertResult(
        val ids: Set<Long>,
        val artwork: List<ArtworkTarget>,
        val complete: Boolean,
    )

    private data class SyncOutcome(
        val skipped: Boolean,
        val artwork: List<ArtworkTarget> = emptyList(),
    )

    /** Queue the latest selected provider/shelf. Every request advances generation before dedupe. */
    fun publishPopularContent(
        context: Context,
        providerName: String,
        shelfName: String,
        items: List<SearchResponse>,
    ) {
        val appContext = context.applicationContext
        val cards = items.asSequence()
            .filter { item -> item.posterUrl?.let(::isHttpUrl) == true }
            .take(MAX_PROGRAMS_PER_CHANNEL)
            .toList()
        val fingerprint = fingerprint(providerName, shelfName, cards)
        val request = popularSyncPolicy.request(fingerprint)

        popularPublisherScope.launch {
            try {
                val outcome = popularContentLock.withLock {
                    if (!popularSyncPolicy.isCurrent(request)) return@withLock null
                    val hasPendingCleanup = appContext.getPendingCleanupProgramIds().isNotEmpty()
                    if (hasPendingCleanup) popularSyncPolicy.invalidateCommittedContent()
                    if (!cleanupPendingPrograms(appContext)) return@withLock null
                    val mustReplace = request.shouldReplaceContent || hasPendingCleanup ||
                        popularSyncPolicy.committedFingerprint() != fingerprint
                    if (!mustReplace) return@withLock SyncOutcome(skipped = true)

                    val channelId = ensurePopularChannel(appContext)
                        ?: return@withLock null
                    if (!popularSyncPolicy.isCurrent(request)) return@withLock null

                    val result = insertPrograms(appContext, channelId, cards, request)
                    if (!result.complete || !popularSyncPolicy.isCurrent(request)) {
                        popularSyncPolicy.invalidateCommittedContent()
                        rollbackStagedPrograms(appContext, result.ids)
                        Log.e("TvChannelUtils", "Popular replacement was partial or superseded; previous rows retained")
                        return@withLock null
                    }

                    val oldIds = appContext.getStoredProgramIds().filterNot(result.ids::contains)
                    val failedDeletes = deletePrograms(appContext, oldIds)
                    if (failedDeletes.isNotEmpty()) {
                        popularSyncPolicy.invalidateCommittedContent()
                        recordPendingCleanup(appContext, failedDeletes)
                        rollbackStagedPrograms(appContext, result.ids)
                        Log.e("TvChannelUtils", "Stale programs could not be fully removed; staged rows were rolled back")
                        return@withLock null
                    }

                    if (!popularSyncPolicy.commitContent(request)) {
                        // The newer selection will reconcile this complete replacement next.
                        return@withLock null
                    }
                    publishedArtworkTargets = result.artwork
                    popularSyncPolicy.resetArtworkRetries()
                    SyncOutcome(skipped = false, artwork = result.artwork)
                } ?: return@launch

                if (outcome.skipped) {
                    scheduleArtworkRetries(appContext)
                    return@launch
                }

                // Row content is already committed. Image failures only schedule per-item retries;
                // they never trigger a replacement of the whole launcher row.
                updateProgramArtwork(appContext, outcome.artwork)
                scheduleArtworkRetries(appContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("TvChannelUtils", "Error syncing popular content", error)
            }
        }
    }

    private suspend fun ensurePopularChannel(context: Context): Long? {
        val channelName = context.getString(R.string.auras_tv_popular_channel_name)
        getChannelId(context, channelName)?.let { return it }
        createTvChannel(context)
        repeat(CHANNEL_CREATE_RETRIES) { attempt ->
            getChannelId(context, channelName)?.let { return it }
            if (attempt < CHANNEL_CREATE_RETRIES - 1) delay(CHANNEL_CREATE_RETRY_DELAY_MS)
        }
        Log.e("TvChannelUtils", "Could not create or query the Popular Content channel")
        return null
    }

    @SuppressLint("RestrictedApi")
    private suspend fun insertPrograms(
        context: Context,
        channelId: Long,
        items: List<SearchResponse>,
        request: PopularPublishRequest,
    ): InsertResult {
        val insertedIds = mutableSetOf<Long>()
        val artwork = mutableListOf<ArtworkTarget>()
        var complete = true
        for (item in items) {
            currentCoroutineContext().ensureActive()
            if (!popularSyncPolicy.isCurrent(request)) return InsertResult(insertedIds, artwork, false)
            try {
                val nameBase64 = base64Encode(item.apiName.toByteArray(Charsets.UTF_8))
                val urlBase64 = base64Encode(item.url.toByteArray(Charsets.UTF_8))
                val csshareUri = "$APP_STRING_SHARE:$nameBase64?$urlBase64"
                val poster = item.posterUrl?.takeIf(::isHttpUrl)
                if (poster == null) {
                    complete = false
                    continue
                }
                val builder = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setTitle(item.name)
                    .apply {
                        val scoreText = item.score?.toStringNull(0.1, 10, 1)?.let {
                            " - " + txt(R.string.rating_format, it).asString(context)
                        } ?: ""
                        setDescription("${item.apiName}$scoreText")
                    }
                    .setContentId(item.url)
                    .setType(
                        if (item.type?.isMovieType() == false) {
                            TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
                        } else TvContractCompat.PreviewPrograms.TYPE_MOVIE
                    )
                    .setIntentUri(csshareUri.toUri())
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                    .setPosterArtUri(poster.toUri())

                val uri = context.contentResolver.insert(
                    TvContractCompat.PreviewPrograms.CONTENT_URI,
                    builder.build().toContentValues(),
                )
                if (uri == null) {
                    complete = false
                    Log.e("TvChannelUtils", "Insert failed for ${item.name}")
                } else {
                    val id = ContentUris.parseId(uri)
                    insertedIds += id
                    context.saveProgramId(id)
                    artwork += ArtworkTarget(id, item, artworkRetryKey(item))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                complete = false
                Log.e("TvChannelUtils", "Error inserting ${item.name}", error)
            }
        }
        return InsertResult(insertedIds, artwork, complete)
    }

    /** Failed deletes stay in the persistent ID list until a later cleanup succeeds. */
    private fun deletePrograms(context: Context, ids: List<Long>): Set<Long> {
        val failed = mutableSetOf<Long>()
        for (id in ids) {
            val uri = ContentUris.withAppendedId(TvContractCompat.PreviewPrograms.CONTENT_URI, id)
            try {
                context.contentResolver.delete(uri, null, null)
                context.removeProgramId(id)
            } catch (error: Exception) {
                failed += id
                Log.e("TvChannelUtils", "Failed to delete program ID: $id", error)
            }
        }
        return failed
    }

    private fun recordPendingCleanup(context: Context, ids: Set<Long>) {
        if (ids.isEmpty()) return
        context.setPendingCleanupProgramIds(context.getPendingCleanupProgramIds() + ids)
    }

    private fun cleanupPendingPrograms(context: Context): Boolean {
        val pending = context.getPendingCleanupProgramIds()
        if (pending.isEmpty()) return true
        val failed = deletePrograms(context, pending)
        context.setPendingCleanupProgramIds(failed.toList())
        if (failed.isNotEmpty()) {
            Log.w("TvChannelUtils", "Pending stale program cleanup still has failures: $failed")
        }
        return failed.isEmpty()
    }

    private fun rollbackStagedPrograms(context: Context, ids: Set<Long>) {
        if (ids.isEmpty()) return
        val failed = deletePrograms(context, ids.toList())
        recordPendingCleanup(context, failed)
    }

    private fun artworkRetryKey(item: SearchResponse): String {
        val data = buildString {
            append(item.apiName).append('|').append(item.url).append('|').append(item.posterUrl.orEmpty())
            item.posterHeaders.orEmpty().toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (key, value) ->
                append('|').append(key.lowercase(Locale.ROOT)).append(':').append(value)
            }
        }
        return sha256(data)
    }

    private fun fingerprint(providerName: String, shelfName: String, cards: List<SearchResponse>): String {
        val data = buildString {
            append("artwork-format:").append(TvPreviewArtwork.ARTWORK_FORMAT_REVISION).append('\n')
            append(providerName).append('\n').append(shelfName)
            cards.forEach { card ->
                append('\n').append(card.apiName).append('|').append(card.url).append('|')
                    .append(card.name).append('|').append(card.posterUrl.orEmpty()).append('|')
                    .append(card.type?.name.orEmpty()).append('|').append(card.score?.toString().orEmpty())
                card.posterHeaders.orEmpty().toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (key, value) ->
                    append('|').append(key.lowercase(Locale.ROOT)).append(':').append(value)
                }
            }
        }
        return sha256(data)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun isCurrentArtworkTarget(target: ArtworkTarget): Boolean =
        publishedArtworkTargets.any { it.id == target.id && it.retryKey == target.retryKey }

    @SuppressLint("RestrictedApi")
    private suspend fun updateProgramArtwork(context: Context, targets: List<ArtworkTarget>) {
        if (targets.isEmpty()) return
        val results = coroutineScope {
            targets.map { target ->
                async {
                    val updated = try {
                        val artwork = TvPreviewArtwork.previewArtwork(
                            context,
                            target.item.posterUrl,
                            target.item.posterHeaders,
                        )
                        if (artwork == null || !isCurrentArtworkTarget(target)) {
                            false
                        } else {
                            val values = ContentValues().apply {
                                put(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI, artwork.uri.toString())
                                put(
                                    TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                                    artwork.aspectRatio,
                                )
                                put(TvContractCompat.PreviewPrograms.COLUMN_THUMBNAIL_URI, artwork.uri.toString())
                                put(
                                    TvContractCompat.PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
                                    artwork.aspectRatio,
                                )
                            }
                            context.contentResolver.update(
                                ContentUris.withAppendedId(TvContractCompat.PreviewPrograms.CONTENT_URI, target.id),
                                values,
                                null,
                                null,
                            ) > 0
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w("TvChannelUtils", "Could not update artwork for ${target.item.name}", error)
                        false
                    }
                    target to updated
                }
            }.awaitAll()
        }
        results.forEach { (target, succeeded) ->
            if (isCurrentArtworkTarget(target)) {
                popularSyncPolicy.finishArtworkAttempt(target.retryKey, succeeded)
            }
        }
    }

    private fun scheduleArtworkRetries(context: Context) {
        if (!popularSyncPolicy.hasDueOrScheduledArtworkRetries() ||
            !artworkRetrySchedulerRunning.compareAndSet(false, true)
        ) return

        popularPublisherScope.launch {
            try {
                while (popularSyncPolicy.hasDueOrScheduledArtworkRetries()) {
                    val waitMs = popularSyncPolicy.nextArtworkRetryDelayMs() ?: break
                    if (waitMs > 0L) delay(waitMs)
                    val dueKeys = popularSyncPolicy.claimDueArtworkKeys()
                    if (dueKeys.isEmpty()) continue
                    val targets = publishedArtworkTargets.filter { it.retryKey in dueKeys }
                    val foundKeys = targets.mapTo(mutableSetOf()) { it.retryKey }
                    (dueKeys - foundKeys).forEach { popularSyncPolicy.finishArtworkAttempt(it, true) }
                    updateProgramArtwork(context, targets)
                }
            } finally {
                artworkRetrySchedulerRunning.set(false)
                if (popularSyncPolicy.hasDueOrScheduledArtworkRetries()) scheduleArtworkRetries(context)
            }
        }
    }
    @Synchronized
    fun createTvChannel(context: Context) {
        val channelName = context.getString(R.string.auras_tv_popular_channel_name)
        if (getChannelId(context, channelName) != null) return
        val componentName = ComponentName(context, MainActivity::class.java)
        val iconUri = "android.resource://${context.packageName}/mipmap/ic_launcher".toUri()
        val inputId = TvContractCompat.buildInputId(componentName)
        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setAppLinkIconUri(iconUri)
            .setDisplayName(channelName)
            .setAppLinkIntent(Intent(Intent.ACTION_VIEW).apply {
                data = "cloudstreamapp://open".toUri()
            })
            .setInputId(inputId)
            .build()

        val channelUri = runCatching {
            context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channel.toContentValues()
            )
        }.getOrNull()

        channelUri?.let {
            val channelId = ContentUris.parseId(it)
            TvContractCompat.requestChannelBrowsable(context, channelId)
            Log.d("TvChannelUtils", "Channel Created: $channelId")
        }
    }

    private fun isHttpUrl(value: String): Boolean =
        runCatching {
            val scheme = android.net.Uri.parse(value).scheme
            scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
        }.getOrDefault(false)

    private const val MAX_PROGRAMS_PER_CHANNEL = 24
    private const val CHANNEL_CREATE_RETRIES = 3
    private const val CHANNEL_CREATE_RETRY_DELAY_MS = 150L

}
