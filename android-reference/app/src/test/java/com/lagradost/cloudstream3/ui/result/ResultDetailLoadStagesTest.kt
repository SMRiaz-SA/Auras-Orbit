package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultDetailLoadStagesTest {
    @Test
    fun providerPageAndEpisodesAppearFirstWhilePhoneAndTvWaitForStageThree() = runBlocking {
        val events = mutableListOf<String>()
        val providerEpisode = episode(id = 1)
        var visibleEpisodes = emptyList<ResultEpisode>()
        var stageThreeReady = false
        var discoveryStarts = 0
        val published = runProviderFirstDetailStages(
            provider = "provider",
            isCurrent = { true },
            publishProvider = {
                events += "provider-page"
                visibleEpisodes = listOf(providerEpisode)
                events += "provider-episodes"
            },
            awaitFirstPresentation = {
                events += "first-frame"
                assertEquals(listOf(providerEpisode), visibleEpisodes)
                assertFalse(canStartInlineSourceDiscovery(stageThreeReady, visibleEpisodes.firstOrNull()))
                assertEquals(0, discoveryStarts)
            },
            enrich = {
                assertTrue(events.contains("provider-episodes"))
                assertEquals(listOf(providerEpisode), visibleEpisodes)
                assertFalse(canStartInlineSourceDiscovery(stageThreeReady, visibleEpisodes.firstOrNull()))
                assertEquals(0, discoveryStarts)
                events += "enrichment-start"
                "enriched"
            },
            fallback = { it },
            publishEpisodes = {
                events += "stage-three-ready"
                stageThreeReady = true
                if (canStartInlineSourceDiscovery(stageThreeReady, visibleEpisodes.firstOrNull())) {
                    discoveryStarts++
                }
            },
            enrichmentTimeoutMs = 100,
        )

        assertTrue(published)
        assertEquals(
            listOf("provider-page", "provider-episodes", "first-frame", "enrichment-start", "stage-three-ready"),
            events,
        )
        assertEquals(1, discoveryStarts)
    }

    @Test
    fun timedOutEnrichmentPublishesProviderResponseForDiscovery() = runBlocking {
        var finalValue: String? = null
        val published = runProviderFirstDetailStages(
            provider = "provider",
            isCurrent = { true },
            publishProvider = {},
            awaitFirstPresentation = {},
            enrich = {
                delay(10_000)
                "late enrichment"
            },
            fallback = { it },
            publishEpisodes = { finalValue = it },
            enrichmentTimeoutMs = 1,
        )

        assertTrue(published)
        assertEquals("provider", finalValue)
    }

    @Test
    fun failedEnrichmentPublishesProviderResponseForDiscovery() = runBlocking {
        var finalValue: String? = null
        val published = runProviderFirstDetailStages(
            provider = "provider",
            isCurrent = { true },
            publishProvider = {},
            awaitFirstPresentation = {},
            enrich = { error("tracker unavailable") },
            fallback = { it },
            publishEpisodes = { finalValue = it },
            enrichmentTimeoutMs = 100,
        )

        assertTrue(published)
        assertEquals("provider", finalValue)
    }

    @Test
    fun repeatedMetadataVersionChangesStillReachStageThreeWithinTheDeadline() = runBlocking {
        var version = 0
        var stageThreeReady = false
        var discoveryStarted = false
        val providerEpisode = episode(id = 2)
        var finalValue: String? = null
        val published = runProviderFirstDetailStages(
            provider = "provider",
            isCurrent = { true },
            publishProvider = {},
            awaitFirstPresentation = {},
            enrich = {
                val observedVersion = version
                while (true) {
                    version++
                    delay(1)
                    if (observedVersion == version) return@runProviderFirstDetailStages "enriched"
                }
                @Suppress("UNREACHABLE_CODE")
                "unreachable"
            },
            fallback = { it },
            publishEpisodes = {
                finalValue = it
                stageThreeReady = true
                discoveryStarted = canStartInlineSourceDiscovery(stageThreeReady, providerEpisode)
            },
            enrichmentTimeoutMs = 20,
        )

        assertTrue(published)
        assertEquals("provider", finalValue)
        assertTrue(stageThreeReady)
        assertTrue(discoveryStarted)
    }

    @Test
    fun staleEnrichmentCannotPublishEpisodesOrStartDiscovery() = runBlocking {
        var current = true
        var discoveryStarted = false
        val published = runProviderFirstDetailStages(
            provider = "old provider response",
            isCurrent = { current },
            publishProvider = {},
            awaitFirstPresentation = {},
            enrich = {
                current = false
                "stale enriched response"
            },
            fallback = { it },
            publishEpisodes = { discoveryStarted = true },
            enrichmentTimeoutMs = 100,
        )

        assertFalse(published)
        assertFalse(discoveryStarted)
    }

    @Test
    fun cancelledLoadDoesNotPublishEpisodes() = runBlocking {
        val enteredPresentationWait = CompletableDeferred<Unit>()
        val presentationReady = CompletableDeferred<Unit>()
        var discoveryStarted = false
        val job: Job = launch {
            runProviderFirstDetailStages(
                provider = "provider",
                isCurrent = { true },
                publishProvider = {},
                awaitFirstPresentation = {
                    enteredPresentationWait.complete(Unit)
                    presentationReady.await()
                },
                enrich = { it },
                fallback = { it },
                publishEpisodes = { discoveryStarted = true },
                enrichmentTimeoutMs = 100,
            )
        }

        enteredPresentationWait.await()
        job.cancelAndJoin()
        assertFalse(discoveryStarted)
    }

    @Test
    fun generationGateRejectsEarlierNavigationAndEpisodeRequests() {
        val gate = ResultLoadGeneration()
        val firstRequest = gate.begin()
        val nextEpisodeOrPage = gate.begin()

        assertFalse(gate.isCurrent(firstRequest))
        assertTrue(gate.isCurrent(nextEpisodeOrPage))
        gate.invalidate()
        assertFalse(gate.isCurrent(nextEpisodeOrPage))
    }

    @Test
    fun phoneAndTvUseOneFirstUnwatchedEpisodeRule() {
        val first = episode(id = 1, progress = 0.95f)
        val next = episode(id = 2, progress = 0.2f)
        val watchedByStatus = episode(id = 3, progress = 0f, watched = true)
        val unwatched = episode(id = 4, progress = 0f)

        assertEquals(unwatched, firstUnwatchedEpisode(listOf(first, next, watchedByStatus, unwatched)))
        assertNull(firstUnwatchedEpisode(emptyList()))
    }

    @Test
    fun phoneAndTvWaitForTheSharedStageThreeSourceGate() {
        val episode = episode(id = 1)

        assertFalse(canStartInlineSourceDiscovery(detailStageThreeReady = false, episode = episode))
        assertTrue(canStartInlineSourceDiscovery(detailStageThreeReady = true, episode = episode))
        assertFalse(canStartInlineSourceDiscovery(detailStageThreeReady = true, episode = null))
    }

    @Test
    fun inlineSourceEpisodeIdentityRejectsOldPageOrEpisodeResults() {
        val current = episode(id = 4)
        assertTrue(isSameInlineSourceEpisode(current, current.copy()))
        assertFalse(isSameInlineSourceEpisode(current, current.copy(id = 5)))
        assertFalse(isSameInlineSourceEpisode(current, current.copy(parentId = 99)))
        assertFalse(isSameInlineSourceEpisode(current, current.copy(apiName = "another provider")))
        assertFalse(isSameInlineSourceEpisode(null, current))
    }

    private fun episode(
        id: Int,
        progress: Float = 0f,
        watched: Boolean = false,
    ) = ResultEpisode(
        headerName = "Test title",
        name = "Episode $id",
        poster = null,
        episode = id,
        seasonIndex = 1,
        season = 1,
        data = "episode-$id",
        apiName = "fixture provider",
        id = id,
        index = id - 1,
        position = (progress * 100_000).toLong(),
        duration = 100_000,
        score = null,
        description = null,
        isFiller = false,
        tvType = TvType.Anime,
        parentId = 10,
        videoWatchState = if (watched) VideoWatchState.Watched else VideoWatchState.None,
    )
}
