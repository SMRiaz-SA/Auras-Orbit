package com.auras.orbit.providers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises subtitle metadata capture through the real adapter without opening any URL. */
@RunWith(AndroidJUnit4::class)
class Cs3ProviderSubtitleCallbackInstrumentationTest {
    @Test
    fun mainApiLoadLinksCapturesSubtitleMetadataAndIgnoresBlankSubtitleUrl() {
        val providerId = "cs3:subtitle-fixture:provider-0"
        val videoUrl = "https://media.fixture.invalid/video.mp4"
        val subtitleUrl = "https://cdn.fixture.invalid/subtitles/title.en.vtt"
        val originalSubtitleHeaders = mapOf("X-Subtitle-Fixture" to "initial-header")
        var receivedItemId = ""
        var receivedCasting = true

        val api = object : MainAPI() {
            override fun getName(): String = "Orbit subtitle fixture"
            override fun getMainUrl(): String = "https://provider.fixture.invalid"
            override fun getSupportedTypes(): Set<TvType> = setOf(TvType.Movie)

            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit,
            ): Boolean {
                receivedItemId = data
                receivedCasting = isCasting
                subtitleCallback(SubtitleFile("en", " "))

                val subtitle = SubtitleFile("en", subtitleUrl)
                subtitle.headers = originalSubtitleHeaders
                subtitleCallback(subtitle)
                subtitle.headers = mapOf("X-Subtitle-Fixture" to "mutated-after-callback")

                callback(
                    ExtractorLink(
                        "fixture",
                        "Fixture video",
                        videoUrl,
                        "",
                        720,
                        mapOf("X-Video-Fixture" to "video-header"),
                        null,
                        ExtractorLinkType.VIDEO,
                        emptyList(),
                    ),
                )
                return true
            }
        }
        val provider = Cs3MediaProviderAdapter(
            pluginId = "subtitle-fixture",
            profileId = "subtitle-fixture-profile",
            registrationIndex = 0,
            providerId = providerId,
            fallbackName = "Subtitle fixture",
            api = api,
            metadataKey = Cs3ProviderMetadata.Key(
                profileId = "subtitle-fixture-profile", pluginId = "subtitle-fixture",
                profileGeneration = 0L, pluginGeneration = 0L,
                registrationIndex = 0, api = api,
            ),
            suppliedMetadata = null,
            pluginArchiveSha256 = "0".repeat(64),
            stillCurrent = { true },
        )

        val result = provider.resolveLinks("fixture-item", false)

        assertEquals("MainAPI receives the requested provider item", "fixture-item", receivedItemId)
        assertFalse("MainAPI receives the requested casting mode", receivedCasting)
        assertTrue("the extractor callback still resolves a video link", result.resolved)
        assertEquals("a successful callback has no diagnostic", "", result.diagnostic)
        assertEquals("one extractor link is retained", 1, result.links.size)
        assertEquals("the extractor link URL is preserved without opening it", videoUrl, result.links[0].uri)
        assertEquals("the blank subtitle URL is ignored", 1, result.subtitles.size)

        val subtitle = result.subtitles.single()
        assertEquals("subtitle identity follows its callback order",
            "$providerId:subtitle:0", subtitle.id)
        assertEquals("subtitle language is preserved", "en", subtitle.language)
        assertEquals("subtitle URL is preserved", subtitleUrl, subtitle.uri)
        assertEquals("subtitle headers are snapshotted during the callback",
            mapOf("X-Subtitle-Fixture" to "initial-header"), subtitle.headers)
    }
}
