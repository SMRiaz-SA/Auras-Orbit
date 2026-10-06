package com.lagradost.cloudstream3.desktop.ui.screens.details

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailsEpisodeReleaseStatusTest {
    @Test
    fun `cached date-only release changes status on its release day`() {
        val zone = ZoneId.of("Africa/Johannesburg")
        val beforeReleaseDay = Instant.parse("2026-10-04T21:59:59Z")
        val onReleaseDay = Instant.parse("2026-10-04T22:00:00Z")

        assertTrue(computeEpisodeReleaseStatus("2026-10-05", beforeReleaseDay, zone).isUnreleased)

        val released = computeEpisodeReleaseStatus("2026-10-05", onReleaseDay, zone)
        assertFalse(released.isUnreleased)
        assertNull(released.statusBadgeText)
    }

    @Test
    fun `cached timestamp release changes status after its instant`() {
        val zone = ZoneId.of("Africa/Johannesburg")
        val releaseInstant = Instant.parse("2026-10-05T12:00:00Z")

        assertTrue(
            computeEpisodeReleaseStatus(
                "2026-10-05T12:00:00Z",
                releaseInstant.minusSeconds(1),
                zone,
            ).isUnreleased,
        )
        assertFalse(
            computeEpisodeReleaseStatus(
                "2026-10-05T12:00:00Z",
                releaseInstant,
                zone,
            ).isUnreleased,
        )
    }
}
