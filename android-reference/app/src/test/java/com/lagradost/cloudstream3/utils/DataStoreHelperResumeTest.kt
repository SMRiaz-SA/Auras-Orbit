package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DataStoreHelperResumeTest {
    @Test
    fun unknownNextEpisodeKeepsCurrentEpisodeResumableAtThreshold() {
        val currentEpisode = Any()

        val resumeMetadata = resolveResumeMetadata(
            currentEpisode = currentEpisode,
            nextEpisode = null,
            shouldAdvance = true,
            isNextEpisodeKnown = false,
        )

        assertSame(currentEpisode, resumeMetadata)
    }

    @Test
    fun confirmedFinalEpisodeCanClearResumeAtThreshold() {
        val resumeMetadata = resolveResumeMetadata(
            currentEpisode = Any(),
            nextEpisode = null,
            shouldAdvance = true,
            isNextEpisodeKnown = true,
        )

        assertNull(resumeMetadata)
    }

    @Test
    fun knownNextEpisodeBecomesTheResumeTarget() {
        val nextEpisode = Any()

        val resumeMetadata = resolveResumeMetadata(
            currentEpisode = Any(),
            nextEpisode = nextEpisode,
            shouldAdvance = true,
            isNextEpisodeKnown = true,
        )

        assertSame(nextEpisode, resumeMetadata)
    }
}
