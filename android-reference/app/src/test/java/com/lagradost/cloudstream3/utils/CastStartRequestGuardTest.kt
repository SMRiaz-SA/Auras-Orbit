package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.utils.CastHelper.CastStartMediaIdentity
import com.lagradost.cloudstream3.utils.CastHelper.CastStartAttemptMediaIdentity
import com.lagradost.cloudstream3.utils.CastHelper.CastStartRequestGuard
import com.lagradost.cloudstream3.utils.CastHelper.isCurrentCastStartRequest
import com.lagradost.cloudstream3.utils.CastHelper.isCurrentCastStartRequestToken
import com.lagradost.cloudstream3.utils.CastHelper.isExpectedCastStartFailureTransition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastStartRequestGuardTest {
    @Test
    fun workerPreparationGuardUsesOnlyRequestAndProfileState() {
        val guard = guard(Any(), Any())

        assertTrue(
            isCurrentCastStartRequestToken(
                guard = guard,
                latestRequestToken = guard.requestToken,
                currentAccountId = guard.accountId,
                currentProfileToken = guard.profileToken,
                currentProfileRevision = guard.profileRevision,
            ),
        )
        assertFalse(
            isCurrentCastStartRequestToken(
                guard = guard,
                latestRequestToken = guard.requestToken,
                currentAccountId = guard.accountId,
                currentProfileToken = "different-profile",
                currentProfileRevision = guard.profileRevision,
            ),
        )
    }

    @Test
    fun newerRequestInvalidatesAnEarlierPreparedStart() {
        val session = Any()
        val client = Any()
        val guard = guard(session, client)

        assertFalse(
            isCurrentCastStartRequest(
                guard = guard,
                latestRequestToken = guard.requestToken + 1,
                currentAccountId = guard.accountId,
                currentProfileToken = guard.profileToken,
                currentProfileRevision = guard.profileRevision,
                activeSession = session,
                activeRemoteMediaClient = client,
                currentMediaIdentity = guard.mediaIdentity,
            ),
        )
    }

    @Test
    fun sessionProfileClientAndMediaSwitchesInvalidateMirrorFallback() {
        val session = Any()
        val client = Any()
        val guard = guard(session, client)
        val changedIdentity = CastStartMediaIdentity(8, "other-content", "other-data")

        assertFalse(isCurrent(guard, activeSession = Any(), activeClient = client))
        assertFalse(isCurrent(guard, activeSession = session, activeClient = Any()))
        assertFalse(isCurrent(guard, activeSession = session, activeClient = client, accountId = "other"))
        assertFalse(isCurrent(guard, activeSession = session, activeClient = client, profileToken = "other-profile"))
        assertFalse(
            isCurrent(
                guard,
                activeSession = session,
                activeClient = client,
                profileRevision = guard.profileRevision + 1,
            ),
        )
        assertFalse(isCurrent(guard, activeSession = session, activeClient = client, mediaIdentity = changedIdentity))
        assertTrue(isCurrent(guard, activeSession = session, activeClient = client))
    }

    @Test
    fun failedReceiverLoadCanTransitionToAttemptedItemOrClearButNotUnrelatedMedia() {
        val beforeLoad = CastStartMediaIdentity(7, "old-content", "old-data")
        val attempted = CastStartAttemptMediaIdentity("attempted-content", "attempted-data")

        assertTrue(isExpectedCastStartFailureTransition(beforeLoad, beforeLoad, attempted))
        assertTrue(isExpectedCastStartFailureTransition(beforeLoad, null, attempted))
        assertTrue(
            isExpectedCastStartFailureTransition(
                beforeLoad,
                CastStartMediaIdentity(11, "attempted-content", "attempted-data"),
                attempted,
            ),
        )
        assertFalse(
            isExpectedCastStartFailureTransition(
                beforeLoad,
                CastStartMediaIdentity(12, "user-selected-content", "user-selected-data"),
                attempted,
            ),
        )
    }

    private fun guard(session: Any, client: Any) = CastStartRequestGuard(
        requestToken = 17L,
        accountId = "profile-1",
        profileToken = "profile-token-1",
        profileRevision = 3,
        session = session,
        remoteMediaClient = client,
        mediaIdentity = CastStartMediaIdentity(7, "current-content", "current-data"),
    )

    private fun isCurrent(
        guard: CastStartRequestGuard,
        activeSession: Any,
        activeClient: Any,
        accountId: String = guard.accountId,
        profileToken: String = guard.profileToken,
        profileRevision: Int = guard.profileRevision,
        mediaIdentity: CastStartMediaIdentity? = guard.mediaIdentity,
    ): Boolean = isCurrentCastStartRequest(
        guard = guard,
        latestRequestToken = guard.requestToken,
        currentAccountId = accountId,
        currentProfileToken = profileToken,
        currentProfileRevision = profileRevision,
        activeSession = activeSession,
        activeRemoteMediaClient = activeClient,
        currentMediaIdentity = mediaIdentity,
    )
}
