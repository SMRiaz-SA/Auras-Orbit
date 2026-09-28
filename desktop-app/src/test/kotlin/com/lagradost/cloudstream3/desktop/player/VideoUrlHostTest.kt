package com.lagradost.cloudstream3.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoUrlHostTest {
    @Test
    fun localWindowsPathHasNoHttpHost() {
        assertNull(extractVideoHost("D:\\Videos\\playback-test.avi"))
    }

    @Test
    fun fileUriHasNoHttpHost() {
        assertNull(extractVideoHost("file:///D:/Videos/playback-test.avi"))
    }

    @Test
    fun httpStreamRetainsItsHostForSubtitleExpansion() {
        assertEquals("media.example.test", extractVideoHost("https://media.example.test/watch/video.mp4"))
    }
}
