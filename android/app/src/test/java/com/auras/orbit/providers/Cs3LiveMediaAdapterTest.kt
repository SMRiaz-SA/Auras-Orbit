package com.auras.orbit.providers

import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class Cs3LiveMediaAdapterTest {
    @Test
    fun liveSearchResultIsTypedAndProviderAdvertisesDetails() {
        val provider = adapter(LiveFixtureApi())

        val item = provider.search(SearchRequest("news", SearchType.ALL, 1)).single()

        assertEquals("LIVE", item.kind.name)
        assertTrue(provider.descriptor().details)
    }

    @Test
    fun liveDetailsRetainOpaqueDataUrlAndPassItToLoadLinks() {
        val api = LiveFixtureApi(liveDataUrl = LIVE_DATA_URL)
        val provider = adapter(api)
        val details = provider.loadDetails("https://provider.fixture.invalid/live/news")

        assertEquals("LIVE", details.kind.name)
        assertEquals(LIVE_DATA_URL, details.providerData)
        assertTrue(details.episodes.isEmpty())

        provider.resolveLinks(details.providerData, false)

        assertEquals(LIVE_DATA_URL, api.receivedLoadLinksData)
        assertEquals(false, api.receivedCasting)
    }

    @Test
    fun liveResponseWithMismatchedTypeFailsClosed() {
        val failure = try {
            adapter(LiveFixtureApi(detailsType = TvType.Movie))
                .loadDetails("https://provider.fixture.invalid/live/news")
            null
        } catch (error: IllegalStateException) {
            error
        } catch (error: UnsupportedOperationException) {
            error
        }
        assertTrue("mismatched response type must be rejected explicitly",
            failure is UnsupportedOperationException)
    }

    @Test
    fun registrationCurrentTracksTheExactRuntimeLease() {
        val leaseCurrent = AtomicBoolean(true)
        val provider = adapter(LiveFixtureApi()) { leaseCurrent.get() }

        assertTrue("a committed registration reports its live lease", provider.isRegistrationCurrent())
        leaseCurrent.set(false)
        assertFalse("a stale plugin generation no longer owns the registration",
            provider.isRegistrationCurrent())
    }

    private fun adapter(api: LiveFixtureApi, stillCurrent: () -> Boolean = { true }): Cs3MediaProviderAdapter {
        val key = Cs3ProviderMetadata.Key(
            profileId = "live-fixture-profile",
            pluginId = "live-fixture",
            profileGeneration = 1L,
            pluginGeneration = 1L,
            registrationIndex = 0,
            api = api,
        )
        return Cs3MediaProviderAdapter(
            pluginId = "live-fixture",
            profileId = "live-fixture-profile",
            registrationIndex = 0,
            providerId = "cs3:live-fixture:provider-0",
            fallbackName = "Live fixture",
            api = api,
            metadataKey = key,
            suppliedMetadata = Cs3ProviderMetadata.Snapshot(
                key = key,
                name = "Live fixture",
                hasMainPage = false,
                hasQuickSearch = false,
                supportedTypes = setOf(TvType.Live),
            ),
            pluginArchiveSha256 = "0".repeat(64),
            stillCurrent = stillCurrent,
        )
    }

    private class LiveFixtureApi(
        private val detailsType: TvType = TvType.Live,
        private val liveDataUrl: String = LIVE_DATA_URL,
    ) : MainAPI() {
        var receivedLoadLinksData: String? = null
        var receivedCasting: Boolean? = null

        override fun getName(): String = "Live fixture"
        override fun getMainUrl(): String = "https://provider.fixture.invalid"
        override fun getSupportedTypes(): Set<TvType> = setOf(TvType.Live)

        override suspend fun search(query: String, page: Int): SearchResponseList =
            SearchResponseList(
                listOf(
                    LiveSearchResponse(
                        "Live news", "https://provider.fixture.invalid/live/news", name,
                        TvType.Live, null, null, null, null, "en", null,
                    ),
                ),
                false,
            )

        override suspend fun load(url: String): LoadResponse = LiveStreamLoadResponse(
            "Live news", url, name, liveDataUrl, null, null, "Fixture stream",
            detailsType, null, emptyList<String>(), null, emptyList<TrailerData>(),
            emptyList<SearchResponse>(), emptyList<ActorData>(), false,
            emptyMap<String, String>(), null, null, null, null, url,
        )

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            receivedLoadLinksData = data
            receivedCasting = isCasting
            return true
        }
    }

    private companion object {
        const val LIVE_DATA_URL = "  opaque-live-id \n"
    }
}
