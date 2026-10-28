package com.lagradost.cloudstream3.ui.result

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineSourceProgressGateTest {
    @Test
    fun publishesFirstNonEmptySnapshotImmediately() {
        val gate = InlineSourceProgressGate()

        assertFalse(gate.shouldPublish(linkCount = 0, elapsedRealtimeMs = 100))
        assertTrue(gate.shouldPublish(linkCount = 1, elapsedRealtimeMs = 100))
    }

    @Test
    fun coalescesIntermediateSnapshotsUntilIntervalHasElapsed() {
        val gate = InlineSourceProgressGate()
        val firstTime = 100L

        assertTrue(gate.shouldPublish(linkCount = 1, elapsedRealtimeMs = firstTime))
        assertFalse(
            gate.shouldPublish(
                linkCount = 2,
                elapsedRealtimeMs = firstTime + INLINE_SOURCE_PROGRESS_MIN_INTERVAL_MS - 1,
            )
        )
        assertTrue(
            gate.shouldPublish(
                linkCount = 2,
                elapsedRealtimeMs = firstTime + INLINE_SOURCE_PROGRESS_MIN_INTERVAL_MS,
            )
        )
        assertFalse(
            gate.shouldPublish(
                linkCount = 2,
                elapsedRealtimeMs = firstTime + INLINE_SOURCE_PROGRESS_MIN_INTERVAL_MS * 2,
            )
        )
    }

    @Test
    fun eachDiscoveryGetsItsOwnProgressGate() {
        val firstDiscovery = InlineSourceProgressGate()
        val nextDiscovery = InlineSourceProgressGate()

        assertTrue(firstDiscovery.shouldPublish(linkCount = 1, elapsedRealtimeMs = 100))
        assertTrue(nextDiscovery.shouldPublish(linkCount = 1, elapsedRealtimeMs = 100))
    }
}
