package com.lagradost.cloudstream3.ui

import com.lagradost.cloudstream3.utils.CastHelper.CastEpisodeProfileBinding
import com.lagradost.cloudstream3.utils.resolveResumeMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext

class CastQueueOperationTest {
    @Test
    fun delayedLookupAppendIsRejectedForOtherProfileAndAllowedWhenOwnerReturns() {
        val owner = CastEpisodeProfileBinding(accountId = "2", profileToken = "profile-a")

        assertTrue(
            isCastQueueSequenceOwnerCurrent(
                initiatingSequenceId = "sequence-1",
                currentSequenceId = "sequence-1",
                initiatingOwner = owner,
                currentOwner = owner,
                currentAccountId = "2",
                currentProfileToken = "profile-a",
            ),
        )
        assertFalse(
            isCastQueueSequenceOwnerCurrent(
                initiatingSequenceId = "sequence-1",
                currentSequenceId = "sequence-1",
                initiatingOwner = owner,
                currentOwner = owner,
                currentAccountId = "2",
                currentProfileToken = "profile-b",
            ),
        )
        assertTrue(
            isCastQueueSequenceOwnerCurrent(
                initiatingSequenceId = "sequence-1",
                currentSequenceId = "sequence-1",
                initiatingOwner = owner,
                currentOwner = owner,
                currentAccountId = "2",
                currentProfileToken = "profile-a",
            ),
        )
        assertFalse(
            isCastQueueSequenceOwnerCurrent(
                initiatingSequenceId = "sequence-1",
                currentSequenceId = "sequence-2",
                initiatingOwner = owner,
                currentOwner = owner,
                currentAccountId = "2",
                currentProfileToken = "profile-a",
            ),
        )
    }

    @Test
    fun missingNextEpisodeAtSeasonBoundaryKeepsCurrentEpisodeResumable() {
        val currentEpisode = Any()
        val currentSeasonSequence = listOf(currentEpisode)
        val nextEpisode = getNextCastMetadataEpisode(currentSeasonSequence, 0)

        assertNull(nextEpisode)
        assertSame(
            currentEpisode,
            resolveResumeMetadata(
                currentEpisode = currentEpisode,
                nextEpisode = nextEpisode,
                shouldAdvance = true,
                isNextEpisodeKnown = nextEpisode != null,
            ),
        )
    }

    @Test
    fun cancellationBeforeMainDispatchPreventsQueueAppend() = runBlocking {
        withTimeout(5_000) {
            val mainDispatcher = QueuedDispatcher()
            var appendStarted = false
            var gateReleased = false

            val appendJob = launch {
                val initiatingJob = currentCoroutineContext()[Job]!!
                try {
                    withCastQueueAppendSettled {
                        enqueueCastAppendOnDispatcherIfActive(initiatingJob, mainDispatcher) {
                            appendStarted = true
                        }
                    }
                } finally {
                    gateReleased = true
                }
            }

            mainDispatcher.dispatched.await()
            appendJob.cancel()
            yield()
            assertFalse(gateReleased)

            mainDispatcher.runNext()
            appendJob.join()

            assertFalse(appendStarted)
            assertTrue(gateReleased)
        }
    }

    @Test
    fun playbackSwitchBeforeMainDispatchPreventsQueueAppend() = runBlocking {
        withTimeout(5_000) {
            val mainDispatcher = QueuedDispatcher()
            val initiatingClient = Any()
            var currentClient: Any? = initiatingClient
            val initiatingItem = CastQueueItemIdentity(7, "old-content", "{\"episode\":4}")
            var currentItem: CastQueueItemIdentity? = initiatingItem
            var appendStarted = false
            var appendResult: Unit? = Unit

            val appendJob = launch {
                val initiatingJob = currentCoroutineContext()[Job]!!
                appendResult = enqueueCastAppendOnDispatcherIfActive(
                    initiatingJob = initiatingJob,
                    dispatcher = mainDispatcher,
                    isTargetCurrent = {
                        isSameCastQueueTarget(
                            initiatingClient,
                            currentClient,
                            initiatingItem,
                            currentItem,
                        )
                    },
                ) {
                    appendStarted = true
                }
            }

            mainDispatcher.dispatched.await()
            currentItem = CastQueueItemIdentity(1, "new-content", "{\"episode\":0}")
            mainDispatcher.runNext()
            appendJob.join()

            assertFalse(appendStarted)
            assertFalse(isSameCastQueueTarget(initiatingClient, currentClient, initiatingItem, currentItem))
            assertNull(appendResult)

            assertFalse(
                isSameCastQueueTarget(
                    initiatingClient,
                    Any(),
                    initiatingItem,
                    initiatingItem,
                )
            )
        }
    }

    @Test
    fun callerCancellationWaitsForPendingQueueAppendBeforeReleasingGate() = runBlocking {
        withTimeout(5_000) {
            val appendStarted = CompletableDeferred<Unit>()
            val settleAppend = CompletableDeferred<Unit>()
            var gateReleased = false

            val appendJob = launch {
                try {
                    withCastQueueAppendSettled {
                        appendStarted.complete(Unit)
                        settleAppend.await()
                    }
                } finally {
                    gateReleased = true
                }
            }

            appendStarted.await()
            appendJob.cancel()
            yield()
            assertFalse(gateReleased)

            settleAppend.complete(Unit)
            appendJob.join()
            assertTrue(gateReleased)
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        val dispatched = CompletableDeferred<Unit>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
            dispatched.complete(Unit)
        }

        fun runNext() {
            checkNotNull(tasks.poll()) { "Expected a queued Main dispatcher task" }.run()
        }
    }
}
