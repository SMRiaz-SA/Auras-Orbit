package com.lagradost.cloudstream3.ui

import com.lagradost.cloudstream3.utils.CastHelper.CastEpisodeProfileBinding
import com.lagradost.cloudstream3.utils.CastHelper.isCastEpisodeSequenceProfileAllowed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Keeps a started Cast queue operation in flight until its result settles, even if its caller is cancelled. */
internal suspend fun <T> withCastQueueAppendSettled(operation: suspend () -> T): T =
    withContext(NonCancellable) { operation() }

internal data class CastQueueItemIdentity(
    val itemId: Int,
    val contentId: String?,
    val customData: String?,
)

/** A missing next item in the current Cast metadata only means "unknown," not "final." */
internal fun <T> getNextCastMetadataEpisode(episodes: List<T>, currentIndex: Int): T? =
    episodes.getOrNull(currentIndex + 1)

/** A pending queue lookup may only append to the same client and media item that started it. */
internal fun isSameCastQueueTarget(
    initiatingClient: Any,
    currentClient: Any?,
    initiatingItem: CastQueueItemIdentity,
    currentItem: CastQueueItemIdentity?,
): Boolean = currentClient === initiatingClient && currentItem == initiatingItem

/** A delayed link lookup may only append while its sequence still belongs to the active profile. */
internal fun isCastQueueSequenceOwnerCurrent(
    initiatingSequenceId: String?,
    currentSequenceId: String?,
    initiatingOwner: CastEpisodeProfileBinding?,
    currentOwner: CastEpisodeProfileBinding?,
    currentAccountId: String,
    currentProfileToken: String,
): Boolean = initiatingSequenceId != null &&
    currentSequenceId == initiatingSequenceId &&
    initiatingOwner != null &&
    currentOwner == initiatingOwner &&
    isCastEpisodeSequenceProfileAllowed(initiatingOwner, currentAccountId, currentProfileToken)

/** Checks cancellation and playback identity after dispatcher handoff, immediately before append. */
internal suspend fun <T> enqueueCastAppendOnDispatcherIfActive(
    initiatingJob: Job,
    dispatcher: CoroutineDispatcher,
    isTargetCurrent: () -> Boolean = { true },
    enqueue: () -> T,
): T? = withContext(dispatcher) {
    initiatingJob.ensureActive()
    if (isTargetCurrent()) enqueue() else null
}
