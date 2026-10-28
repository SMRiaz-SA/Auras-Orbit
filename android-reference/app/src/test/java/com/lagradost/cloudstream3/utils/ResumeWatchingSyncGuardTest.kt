package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeWatchingSyncGuardTest {
    @Test
    fun newerRemovalRequestRejectsAnOlderSnapshotThatCompletesLater() {
        val gate = ResumeWatchingSyncGate()
        val oldSnapshot = gate.begin("profile-1")
        val removalSnapshot = gate.begin("profile-1")

        assertTrue(gate.isCurrent(removalSnapshot, "profile-1"))
        assertFalse(gate.isCurrent(oldSnapshot, "profile-1"))
    }

    @Test
    fun profileIdentityRejectsSnapshotEvenBeforeItsReplacementLoads() {
        val gate = ResumeWatchingSyncGate()
        val oldProfileSnapshot = gate.begin("profile-1")

        assertFalse(gate.isCurrent(oldProfileSnapshot, "profile-2"))
    }

    @Test
    fun snapshotIndexFindsStableAndLegacyRowsOnlyForThisApp() {
        val index = indexWatchNextSnapshot(
            rows = listOf(
                WatchNextSnapshotRow(1, "auras:stable", "auras-share://item", "com.auras"),
                WatchNextSnapshotRow(2, "https://provider.test/episode", "orbit-resume://42", "com.auras"),
                WatchNextSnapshotRow(3, "https://provider.test/movie", "https://provider.test/movie", "com.auras"),
                WatchNextSnapshotRow(4, "auras:other", "auras-share://item", "other.app"),
            ),
            appPackageName = "com.auras",
            resumeIntentPrefix = "orbit-resume://",
        )

        assertEquals(1L, index.stableRows["auras:stable"]?.id ?: -1L)
        assertEquals(2L, index.legacyRows[WatchNextLegacyKey("https://provider.test/episode", true)]?.id ?: -1L)
        assertEquals(3L, index.legacyRows[WatchNextLegacyKey("https://provider.test/movie", false)]?.id ?: -1L)
        assertEquals(setOf(1L, 2L, 3L), index.aurasRowIds)
    }
}
