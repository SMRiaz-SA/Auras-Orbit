package com.lagradost.cloudstream3.ui

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CastHelper
import com.lagradost.cloudstream3.utils.CastHelper.CastEpisodeProfileBinding
import com.lagradost.cloudstream3.utils.CastHelper.createCastEpisodeWindow
import com.lagradost.cloudstream3.utils.CastHelper.isCastEpisodeSequenceProfileAllowed
import com.lagradost.cloudstream3.utils.CastHelper.toCastMetadataWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastEpisodeSequenceTest {
    @Test
    fun sequenceOwnershipRejectsOtherProfileAndAllowsReturningOwner() {
        val owner = CastEpisodeProfileBinding(accountId = "2", profileToken = "profile-a")

        assertTrue(isCastEpisodeSequenceProfileAllowed(owner, "2", "profile-a"))
        assertTrue(!isCastEpisodeSequenceProfileAllowed(owner, "2", "profile-b"))
        assertTrue(!isCastEpisodeSequenceProfileAllowed(owner, "3", "profile-a"))
        // The owner check is reevaluated from its binding, so A becomes valid again after B.
        assertTrue(isCastEpisodeSequenceProfileAllowed(owner, "2", "profile-a"))
    }

    @Test
    fun windowsContinuePastFortyEpisodeMetadataBoundary() {
        val fullSequence = (0 until 45).toList()
        val firstWindow = createCastEpisodeWindow(fullSequence, startIndex = 0)!!

        assertEquals(0, firstWindow.offset)
        assertEquals(40, firstWindow.episodes.size)
        assertNull(getNextCastMetadataEpisode(firstWindow.episodes, firstWindow.episodes.lastIndex))

        val boundaryIndex = firstWindow.offset + firstWindow.episodes.size
        assertEquals(40, getNextCastMetadataEpisode(fullSequence, boundaryIndex - 1))

        val nextWindow = createCastEpisodeWindow(fullSequence, startIndex = boundaryIndex)!!
        assertEquals(40, nextWindow.offset)
        assertEquals((40 until 45).toList(), nextWindow.episodes)
        assertTrue(firstWindow.episodes.last() < nextWindow.episodes.first())
    }

    @Test
    fun finalWindowContainsAllRemainingEpisodesWithoutTruncation() {
        val fullSequence = (0 until 45).toList()
        val lastWindow = createCastEpisodeWindow(fullSequence, startIndex = 40)!!

        assertEquals(5, lastWindow.episodes.size)
        assertEquals(44, lastWindow.episodes.last())
        assertNull(createCastEpisodeWindow(fullSequence, startIndex = fullSequence.size))
    }

    @Test
    fun castMetadataWindowOmitsPosterHeadersAndStaysWithinPayloadBound() {
        val posterHeaders = mapOf("X-Poster-Authorization" to "provider-secret-value")
        val episodes = (0 until 45).map { index ->
            ResultEpisode(
                headerName = "Series",
                name = "Episode ${index + 1}",
                poster = "https://provider.example/poster/$index.jpg",
                episode = index + 1,
                seasonIndex = 1,
                season = 1,
                data = "series-data",
                apiName = "Provider",
                id = index + 100,
                index = index,
                position = 0L,
                duration = 0L,
                score = null,
                description = null,
                isFiller = null,
                tvType = TvType.TvSeries,
                parentId = 42,
                videoWatchState = VideoWatchState.None,
                posterHeaders = posterHeaders,
            )
        }
        val metadata = MetadataHolder(
            apiName = "Provider",
            isMovie = false,
            title = "Series",
            poster = null,
            currentEpisodeIndex = 39,
            episodes = episodes,
            currentLinks = emptyList(),
            currentSubtitles = emptyList(),
        )

        val window = metadata.toCastMetadataWindow()!!
        val serialized = window.toJson()

        assertEquals(39, window.episodeOffset)
        assertEquals(0, window.currentEpisodeIndex)
        assertTrue(window.episodes.all { it.posterHeaders == null })
        assertTrue("Poster headers leaked into Cast customData", !serialized.contains("X-Poster-Authorization"))
        assertTrue("Poster header value leaked into Cast customData", !serialized.contains("provider-secret-value"))
        assertTrue(
            "Cast customData exceeded its payload bound",
            serialized.toByteArray(Charsets.UTF_8).size <= CastHelper.MAX_CAST_CUSTOM_DATA_BYTES,
        )
    }
}
