package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PlayerStreamScraperTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun cleanUp() {
        scope.cancel()
        LinkCache.clearAll()
    }

    @Test
    fun timedOutPreScrapeCanRetryAndCacheTheLaterResult() = runBlocking {
        LinkCache.clearAll()
        val firstCallStarted = CompletableDeferred<Unit>()
        var calls = 0
        val provider = fixtureProvider { _, _, callback ->
            calls++
            if (calls == 1) {
                firstCallStarted.complete(Unit)
                awaitCancellation()
            }
            callback(link("retry-success"))
            true
        }
        val scraper = PlayerStreamScraper(scope, preScrapeTimeoutMs = 80L)
        val episode = provider.newEpisode("episode-retry") { name = "Retry" }

        scraper.preScrapeNextEpisode(episode, provider)
        withTimeout(2_000L) { firstCallStarted.await() }
        waitUntilInactive(scraper)

        assertFalse(scraper.isPreScrapeActive)
        assertNull(LinkCache.get(episode.data))

        scraper.preScrapeNextEpisode(episode, provider)
        waitUntilInactive(scraper)

        assertEquals(2, calls)
        assertEquals("retry-success", LinkCache.get(episode.data)?.links?.single()?.name)
    }

    @Test
    fun cancelledAttemptRejectsLateCallbacksAndDoesNotCacheWrongEpisode() = runBlocking {
        LinkCache.clearAll()
        val oldAttemptStarted = CompletableDeferred<Unit>()
        val lateCallback = CompletableDeferred<(ExtractorLink) -> Unit>()
        val provider = fixtureProvider { data, _, callback ->
            if (data.endsWith("/episode-old")) {
                callback(link("old-before-cancel"))
                lateCallback.complete(callback)
                oldAttemptStarted.complete(Unit)
                awaitCancellation()
            } else {
                callback(link("new-episode"))
                true
            }
        }
        val scraper = PlayerStreamScraper(scope, preScrapeTimeoutMs = 5_000L)
        val oldEpisode = provider.newEpisode("episode-old")
        val newEpisode = provider.newEpisode("episode-new")

        scraper.preScrapeNextEpisode(oldEpisode, provider)
        withTimeout(2_000L) { oldAttemptStarted.await() }
        val oldLinkCallback = withTimeout(2_000L) { lateCallback.await() }
        scraper.cancelPreScrape()

        scraper.preScrapeNextEpisode(newEpisode, provider)
        waitUntilInactive(scraper)
        oldLinkCallback(link("late-old-episode"))
        delay(50L)

        assertNull(LinkCache.get(oldEpisode.data))
        assertEquals("new-episode", LinkCache.get(newEpisode.data)?.links?.single()?.name)
    }

    @Test
    fun concurrentProviderCallbacksAreCollectedSafely() = runBlocking {
        LinkCache.clearAll()
        val callbackThreadsDone = CompletableDeferred<Unit>()
        val provider = fixtureProvider { _, _, callback ->
            val threads = (0 until 24).map { index ->
                Thread { callback(link("parallel-$index")) }.apply { start() }
            }
            threads.forEach(Thread::join)
            callbackThreadsDone.complete(Unit)
            true
        }
        val scraper = PlayerStreamScraper(scope)
        val episode = provider.newEpisode("episode-parallel")

        scraper.preScrapeNextEpisode(episode, provider)
        withTimeout(2_000L) { callbackThreadsDone.await() }
        waitUntilInactive(scraper)

        assertEquals(24, LinkCache.get(episode.data)?.links?.size)
    }

    @Test
    fun episodeThatIsNoLongerNextIsNotCached() = runBlocking {
        LinkCache.clearAll()
        val provider = fixtureProvider { _, _, callback ->
            callback(link("stale-episode"))
            true
        }
        val scraper = PlayerStreamScraper(scope, isEpisodeStillNext = { false })
        val episode = provider.newEpisode("episode-stale")

        scraper.preScrapeNextEpisode(episode, provider)
        waitUntilInactive(scraper)

        assertNull(LinkCache.get(episode.data))
    }

    private suspend fun waitUntilInactive(scraper: PlayerStreamScraper) {
        withTimeout(2_000L) {
            while (scraper.isPreScrapeActive) delay(5L)
        }
    }

    private fun fixtureProvider(
        load: suspend (String, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Boolean,
    ): MainAPI = object : MainAPI() {
        override var name: String = "PreScrapeFixture"
        override var mainUrl: String = "https://fixture.example"

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean = load(data, subtitleCallback, callback)
    }

    @Suppress("DEPRECATION")
    private fun link(name: String) = ExtractorLink(
        source = "PreScrapeFixture",
        name = name,
        url = "https://fixture.example/$name.m3u8",
        referer = "",
        quality = 720,
        type = ExtractorLinkType.M3U8,
    )
}
