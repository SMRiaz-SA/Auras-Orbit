package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.ui.player.reloadTargetAfterOnlineLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailCancellationAndReloadTargetTest {
    @Test
    fun cancellationDuringStageThreePresentationWaitMakesLoadRetryable() {
        assertTrue(
            shouldResetDetailLoadAfterCancellation(
                inProgress = false,
                complete = true,
                stageThreeReady = false,
            )
        )
        assertFalse(
            shouldResetDetailLoadAfterCancellation(
                inProgress = false,
                complete = true,
                stageThreeReady = true,
            )
        )
        assertTrue(
            shouldResetDetailLoadAfterCancellation(
                inProgress = true,
                complete = false,
                stageThreeReady = false,
            )
        )
    }

    @Test
    fun rejectedTorrentKeepsThePreviousReloadTarget() {
        val previousLink = "previously accepted stream"

        assertEquals(
            previousLink,
            reloadTargetAfterOnlineLoad(
                current = previousLink,
                candidate = "rejected torrent",
                sourceAccepted = false,
                loadSucceeded = false,
            ),
        )
    }

    @Test
    fun acceptedAndLoadedTorrentBecomesTheReloadTarget() {
        assertEquals(
            "accepted torrent",
            reloadTargetAfterOnlineLoad(
                current = "previous stream",
                candidate = "accepted torrent",
                sourceAccepted = true,
                loadSucceeded = true,
            ),
        )
    }
}
