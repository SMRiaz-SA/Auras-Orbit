package com.lagradost.cloudstream3.desktop.pluginworker

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PlayListItem
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginProviderRpcCodecTest {
    private val provider = object : MainAPI() {
        override var name = "RPC Fixture"
        override var mainUrl = "https://fixture.example"
    }

    @Test
    fun searchRowsAndHomePagesPreserveAllowlistedResponseTypes() {
        val movie = provider.newMovieSearchResponse("Movie", "https://fixture.example/movie")
        val anime = provider.newAnimeSearchResponse("Anime", "https://fixture.example/anime")

        @Suppress("DEPRECATION_ERROR")
        val searchPage = SearchResponseList(listOf(movie, anime), hasNext = true)
        val decodedSearch = ProviderRpcJson.decodeSearchResponseList(ProviderRpcJson.encodeSearchResponseList(searchPage))
        assertEquals(listOf("Movie", "Anime"), decodedSearch.items.map(SearchResponse::name))
        assertEquals(true, decodedSearch.hasNext)

        @Suppress("DEPRECATION_ERROR")
        val homePage = HomePageResponse(listOf(HomePageList("Featured", listOf(movie, anime), true)), hasNext = false)
        val decodedHome = ProviderRpcJson.decodeHomePage(ProviderRpcJson.encodeHomePage(homePage))
        assertEquals("Featured", decodedHome.items.single().name)
        assertEquals(true, decodedHome.items.single().isHorizontalImages)
        assertEquals(listOf("Movie", "Anime"), decodedHome.items.single().list.map(SearchResponse::name))
    }

    @Test
    fun loadResponsesPreserveNestedRecommendations() = runBlocking {
        val movie = provider.newMovieSearchResponse("Recommendation", "https://fixture.example/recommendation")
        val anime = provider.newAnimeSearchResponse("Anime", "https://fixture.example/anime")
        val load = provider.newMovieLoadResponse(
            name = "Loaded movie",
            url = "https://fixture.example/movie",
            type = TvType.Movie,
            dataUrl = "movie-data",
        ) {
            recommendations = listOf(movie, anime)
        }

        val decoded = ProviderRpcJson.decodeLoadResponse(ProviderRpcJson.encodeLoadResponse(load))
        assertEquals("Loaded movie", decoded.name)
        assertEquals(listOf("Recommendation", "Anime"), decoded.recommendations?.map(SearchResponse::name))
    }

    @Test
    fun rejectsUnknownPolymorphicResponseTypes() {
        val unknown = object : SearchResponse {
            override val name = "unknown"
            override val url = "https://fixture.example/unknown"
            override val apiName = "RPC Fixture"
            override var type: TvType? = null
            override var posterUrl: String? = null
            override var posterHeaders: Map<String, String>? = null
            override var id: Int? = null
            override var quality: com.lagradost.cloudstream3.SearchQuality? = null
            override var score: com.lagradost.cloudstream3.Score? = null
        }

        assertFailsWith<PluginProviderRpcException> { ProviderRpcJson.encodeSearchResponse(unknown) }
    }

    @Test
    fun extractorLinkRpcPreservesStandardDrmAndPlaylistTypes() = runBlocking {
        val drm = newDrmExtractorLink(
            source = "Fixture DRM",
            name = "Protected stream",
            url = "https://fixture.example/protected.mpd",
            type = ExtractorLinkType.DASH,
            uuid = java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
        ) {
            referer = "https://fixture.example/"
            quality = 1080
            headers = mapOf("X-Fixture" to "drm")
            extractorData = "drm-data"
            kid = "fixture-kid"
            key = "fixture-key"
            kty = "oct"
            keyRequestParameters = hashMapOf("tenant" to "fixture")
            licenseUrl = "https://fixture.example/license"
        }
        val playlist = ExtractorLinkPlayList(
            source = "Fixture playlist",
            name = "Concatenated video",
            playlist = listOf(PlayListItem("https://fixture.example/segment.ts", 15_000_000)),
            referer = "https://fixture.example/playlist",
            quality = 720,
            headers = mapOf("X-Fixture" to "playlist"),
            extractorData = "playlist-data",
            type = ExtractorLinkType.M3U8,
        )
        val standard = newExtractorLink(
            source = "Fixture standard",
            name = "Regular stream",
            url = "https://fixture.example/regular.mp4",
            type = ExtractorLinkType.VIDEO,
        ) {
            referer = "https://fixture.example/"
            quality = 480
            headers = mapOf("X-Fixture" to "standard")
            extractorData = "standard-data"
        }

        val decoded = ProviderRpcJson.decodeExtractorLinks(
            ProviderRpcJson.encodeExtractorLinks(listOf(drm, playlist, standard)),
        )

        val decodedDrm = assertIs<DrmExtractorLink>(decoded[0])
        assertEquals(drm.uuid, decodedDrm.uuid)
        assertEquals(drm.kid, decodedDrm.kid)
        assertEquals(drm.key, decodedDrm.key)
        assertEquals(drm.keyRequestParameters, decodedDrm.keyRequestParameters)
        assertEquals(drm.licenseUrl, decodedDrm.licenseUrl)
        assertEquals(drm.headers, decodedDrm.headers)

        val decodedPlaylist = assertIs<ExtractorLinkPlayList>(decoded[1])
        assertEquals(playlist.playlist, decodedPlaylist.playlist)
        assertEquals(playlist.type, decodedPlaylist.type)
        assertEquals(playlist.headers, decodedPlaylist.headers)

        assertEquals(standard.url, decoded[2].url)
        assertEquals(standard.type, decoded[2].type)
        assertEquals(standard.headers, decoded[2].headers)
    }

    @Test
    fun extractorLinkRpcPreservesTorrentAndMagnetTypes() = runBlocking {
        val magnet = newExtractorLink(
            source = "Fixture torrent provider",
            name = "Magnet result",
            url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            type = ExtractorLinkType.MAGNET,
        )
        val torrentFile = newExtractorLink(
            source = "Fixture torrent provider",
            name = "Torrent file result",
            url = "https://fixture.example/video.torrent",
            type = ExtractorLinkType.TORRENT,
        )

        val decoded = ProviderRpcJson.decodeExtractorLinks(
            ProviderRpcJson.encodeExtractorLinks(listOf(magnet, torrentFile)),
        )

        assertEquals(listOf(ExtractorLinkType.MAGNET, ExtractorLinkType.TORRENT), decoded.map { it.type })
        assertEquals(listOf(magnet.url, torrentFile.url), decoded.map { it.url })
    }

    @Test
    fun torrentPluginMagnetSurvivesRpcAndIsRecognizedAtPlaybackBoundary() = runBlocking {
        val providerMagnet = newExtractorLink(
            source = "Fixture torrent provider",
            name = "Magnet result",
            url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            type = ExtractorLinkType.MAGNET,
        )

        val decoded = ProviderRpcJson.decodeExtractorLink(ProviderRpcJson.encodeExtractorLink(providerMagnet))

        assertEquals(ExtractorLinkType.MAGNET, decoded.type)
        assertTrue(DesktopTorrentEngine.isTorrentLink(decoded))
    }

    @Test
    fun extractorLinkRpcCanReadLegacySubtypeFrames() = runBlocking {
        val drm = newDrmExtractorLink(
            source = "Legacy DRM",
            name = "Protected stream",
            url = "https://fixture.example/protected.mpd",
            uuid = java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
        ) {
            kid = "legacy-kid"
            key = "legacy-key"
            licenseUrl = "https://fixture.example/license"
        }

        val legacyFrame = ProviderRpcJson.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(drm)
        val decoded = ProviderRpcJson.decodeExtractorLink(legacyFrame)

        val decodedDrm = assertIs<DrmExtractorLink>(decoded)
        assertEquals(drm.kid, decodedDrm.kid)
        assertEquals(drm.key, decodedDrm.key)
        assertEquals(drm.licenseUrl, decodedDrm.licenseUrl)
    }
}
