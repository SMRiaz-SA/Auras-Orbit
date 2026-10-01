package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WatchHistoryCoordinatorTest {
    @Test
    fun `screenshot failure still saves playback progress and keeps the prior image`() = runBlocking {
        val history = sampleHistory(screenshotUrl = "file:///previous.jpg")
        var saved: WatchHistory? = null
        var screenshotFailure: Exception? = null

        WatchHistoryCoordinator.saveWithOptionalScreenshot(
            history = history,
            captureScreenshot = { throw IOException("native handle already closed") },
            saveHistory = { saved = it },
            onScreenshotFailure = { screenshotFailure = it },
        )

        assertEquals(history.position, saved?.position)
        assertEquals(history.duration, saved?.duration)
        assertEquals(history.episodeId, saved?.episodeId)
        assertEquals("file:///previous.jpg", saved?.screenshotUrl)
        assertEquals("native handle already closed", screenshotFailure?.message)
    }

    @Test
    fun `successful screenshot replaces the prior image when saving progress`() = runBlocking {
        val history = sampleHistory(screenshotUrl = "file:///previous.jpg")
        var saved: WatchHistory? = null

        WatchHistoryCoordinator.saveWithOptionalScreenshot(
            history = history,
            captureScreenshot = { "file:///current.jpg" },
            saveHistory = { saved = it },
        )

        assertEquals(history.position, saved?.position)
        assertEquals(history.duration, saved?.duration)
        assertEquals("file:///current.jpg", saved?.screenshotUrl)
    }

    @Test
    fun `cancellation during screenshot capture preserves the committed progress`() = runBlocking {
        var saved = false

        assertFailsWith<CancellationException> {
            WatchHistoryCoordinator.saveWithOptionalScreenshot(
                history = sampleHistory(screenshotUrl = null),
                captureScreenshot = { throw CancellationException("dispose cancelled") },
                saveHistory = { saved = true },
            )
        }

        assertTrue(saved)
    }

    private fun sampleHistory(screenshotUrl: String?) = WatchHistory(
        parentId = "series-1",
        showName = "Series",
        showUrl = "https://example.invalid/series",
        apiName = "FixtureProvider",
        posterUrl = null,
        episodeThumbnailUrl = null,
        screenshotUrl = screenshotUrl,
        episode = 3,
        season = 1,
        episodeId = "episode-3",
        position = 420,
        duration = 1200,
    )
}
