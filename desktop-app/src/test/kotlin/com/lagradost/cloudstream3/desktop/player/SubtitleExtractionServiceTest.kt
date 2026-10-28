package com.lagradost.cloudstream3.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleExtractionServiceTest {
    @Test
    fun mappedSegmentsPreservePlaylistWhenMpegTsOriginIsUnknown() {
        val flattened = SubtitleExtractionService.flattenWebVttSegments(
            listOf(
                """WEBVTT
X-TIMESTAMP-MAP=LOCAL:00:00:00.500,MPEGTS:900000

00:00:00.500 --> 00:00:01.500
First cue""",
                """WEBVTT
X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:2700000

00:00:00.250 --> 00:00:00.750
Second cue""",
            ),
        )

        assertEquals(null, flattened)
    }

    @Test
    fun preservedPlaylistResolvesRelativeUrisAgainstRedirectedFinalUrl() {
        val playlist = """#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",URI="variants/en.m3u8"
#EXTINF:6.0,
segments/first.vtt"""

        val requestedUrl = "https://origin.example.test/original/master.m3u8"
        val redirectedFinalUrl = "https://cdn.example.test/subtitles/redirected/master.m3u8"
        val playable = SubtitleExtractionService.rewriteM3u8UrisForLocalPlayback(
            playlist,
            redirectedFinalUrl,
        )

        assertEquals(
            """#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",URI="https://cdn.example.test/subtitles/redirected/variants/en.m3u8"
#EXTINF:6.0,
https://cdn.example.test/subtitles/redirected/segments/first.vtt""",
            playable,
        )
        assertTrue(playable?.contains(requestedUrl) == false)

        assertEquals(
            null,
            SubtitleExtractionService.rewriteM3u8UrisForLocalPlayback("#EXTM3U\nsegments/first.vtt", null),
        )
    }

    @Test
    fun plainWebVttKeepsItsOriginalCueTimeline() {
        val flattened = SubtitleExtractionService.flattenWebVttSegments(
            listOf(
                """WEBVTT

00:01.000 --> 00:02.000
Plain cue""",
            ),
        )

        assertEquals(
            """WEBVTT

00:01.000 --> 00:02.000
Plain cue

""",
            flattened,
        )
    }
}
