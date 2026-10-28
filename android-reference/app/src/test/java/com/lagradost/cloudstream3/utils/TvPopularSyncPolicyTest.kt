package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPopularSyncPolicyTest {
    @Test
    fun latestProviderSelectionWinsWhenSelectionReturnsToCommittedSource() {
        val policy = TvPopularSyncPolicy()
        val firstA = policy.request("A")
        assertTrue(firstA.shouldReplaceContent)
        assertTrue(policy.commitContent(firstA))

        val pendingB = policy.request("B")
        val selectedAAgain = policy.request("A")

        assertFalse(policy.isCurrent(pendingB))
        assertTrue(policy.isCurrent(selectedAAgain))
        assertTrue(selectedAAgain.shouldReplaceContent)
        assertTrue(policy.commitContent(selectedAAgain))
        assertEquals("A", policy.committedFingerprint())
    }

    @Test
    fun failedArtworkRetriesWithBackoffAndStopsAfterSuccess() {
        var now = 0L
        val policy = TvPopularSyncPolicy(nowMs = { now }, baseBackoffMs = 10L, maxBackoffMs = 40L)

        policy.finishArtworkAttempt("bad-image", succeeded = false)
        assertTrue(policy.claimDueArtworkKeys().isEmpty())
        assertEquals(10L, policy.nextArtworkRetryDelayMs())

        now = 10L
        assertEquals(setOf("bad-image"), policy.claimDueArtworkKeys())
        policy.finishArtworkAttempt("bad-image", succeeded = false)
        assertEquals(20L, policy.nextArtworkRetryDelayMs())

        now = 30L
        assertEquals(setOf("bad-image"), policy.claimDueArtworkKeys())
        policy.finishArtworkAttempt("bad-image", succeeded = true)
        assertFalse(policy.hasDueOrScheduledArtworkRetries())
    }

    @Test
    fun nullEpisodeIdMigratesOnlyAurasOwnedRawUrlRows() {
        assertTrue(
            isLegacyAurasWatchNextRow(
                internalProviderId = "https://provider.test/title/1",
                intentUri = "https://provider.test/title/1",
                rowPackageName = "com.auras.orbit",
                expectedUrl = "https://provider.test/title/1",
                appPackageName = "com.auras.orbit",
                resumeIntentPrefix = "orbit-resume://",
                hasEpisodeId = false,
            ),
        )
        assertFalse(
            isLegacyAurasWatchNextRow(
                internalProviderId = "https://provider.test/title/1",
                intentUri = "https://provider.test/title/1",
                rowPackageName = "com.other.app",
                expectedUrl = "https://provider.test/title/1",
                appPackageName = "com.auras.orbit",
                resumeIntentPrefix = "orbit-resume://",
                hasEpisodeId = false,
            ),
        )
    }
}
