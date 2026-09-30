package com.lagradost.cloudstream3.desktop.stremio

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.desktop.metadata.stremio.StremioAddonClient
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StremioTransportTest {
    @Test
    fun catalogExtrasUseOneProtocolSegmentAndPreserveManifestConfiguration() {
        val url = StremioTransport.buildCatalogUrl(
            manifestOrBaseUrl = "https://catalog.example/manifest.json?token=abc%20123&profile=tv",
            type = "movie",
            catalogId = "top",
            genre = "Science Fiction",
            skip = 20,
        )

        assertEquals(
            "https://catalog.example/catalog/movie/top/genre=Science%20Fiction&skip=20.json?token=abc%20123&profile=tv",
            url,
        )
    }

    @Test
    fun baseUrlAcceptsCaseInsensitiveManifestSuffix() {
        assertEquals(
            "https://catalog.example",
            StremioTransport.getBaseUrl("https://catalog.example/MANIFEST.JSON?token=abc"),
        )
    }

    @Test
    fun manifestNormalizationKeepsConfigurationAndRemovesBrowserFragment() {
        assertEquals(
            "https://catalog.example/manifest.json?token=abc%20123",
            StremioTransport.normalizeManifestUrl("catalog.example?token=abc%20123#settings"),
        )
    }

    @Test
    fun catalogSearchExtraIsEncodedAndKeepsConfiguration() {
        assertEquals(
            "https://catalog.example/catalog/movie/search-catalog/search=some%20query%20%26%20more.json?token=abc%20123",
            StremioTransport.buildCatalogUrl(
                manifestOrBaseUrl = "https://catalog.example/manifest.json?token=abc%20123",
                type = "movie",
                catalogId = "search-catalog",
                search = "some query & more",
            ),
        )
    }

    @Test
    fun catalogSerializesManifestDeclaredExtrasTogetherWithSearchAndPagination() {
        assertEquals(
            "https://catalog.example/catalog/movie/top/search=hello%20world&language=en%20%26%20fr&skip=40.json?token=secret",
            StremioTransport.buildCatalogUrl(
                manifestOrBaseUrl = "https://catalog.example/manifest.json?token=secret",
                type = "movie",
                catalogId = "top",
                search = "hello world",
                skip = 40,
                extraArgs = mapOf("language" to "en & fr"),
            ),
        )
    }

    @Test
    fun streamUrlPreservesExactVideoIdAsOneEncodedPathSegment() {
        assertEquals(
            "https://streams.example/stream/series/episode%3Aseason%2F1.json?cfg=1",
            StremioTransport.buildStreamUrl(
                manifestUrl = "https://streams.example/manifest.json?cfg=1",
                type = "series",
                id = "episode:season/1",
            ),
        )
    }

    @Test
    fun metadataVideoIdIsRetainedForExactSeriesStreamRequests() {
        val response = jacksonObjectMapper().readValue(
            """{"meta":{"id":"catalog:show","type":"series","videos":[{"id":"episode:season/1","season":1,"episode":1,"title":"Pilot"}]}}""",
            StremioAddonClient.StremioMetaResponse::class.java,
        )

        assertEquals("episode:season/1", response.meta?.videos?.single()?.id)
    }

    @Test
    fun manifestResourceDeclarationsAreMatchedByAnyCompatibleTypeAndPrefix() {
        val addon = ManagedStremioAddon(
            manifestUrl = "https://addon.example/manifest.json",
            resources = listOf(
                StremioResource(name = "stream", types = listOf("movie"), idPrefixes = listOf("tt")),
                StremioResource(name = "stream", types = listOf("series"), idPrefixes = listOf("catalog:")),
            ),
        )

        assertTrue(StremioAddonManager.supportsRequest(addon, "stream", "series", "catalog:show:season-1"))
        assertTrue(StremioAddonManager.supportsRequest(addon, "stream", "movie", "tt1234567"))
        assertTrue(!StremioAddonManager.supportsRequest(addon, "stream", "series", "tt1234567"))
        assertTrue(!StremioAddonManager.supportsRequest(addon, "stream", "movie", "catalog:show"))
    }

    @Test
    fun subtitleExtrasUseOneProtocolSegment() {
        assertEquals(
            "https://subs.example/subtitles/movie/tt123/videoHash=abc%20123&videoSize=456&filename=Movie%20Name.mkv.json?key=value",
            StremioTransport.buildSubtitleUrl(
                manifestUrl = "https://subs.example/manifest.json?key=value",
                type = "movie",
                id = "tt123",
                videoHash = "abc 123",
                videoSize = 456,
                filename = "Movie Name.mkv",
            ),
        )
    }

    @Test
    fun manifestParserRetainsCatalogSearchAndResourceConstraints() {
        val manifest = StremioManifestParser.parse(
            "https://addon.example/manifest.json?cfg=1",
            """{
                "id":"example.addon",
                "name":"Example",
                "resources":[
                    {"name":"stream","types":["movie"],"idPrefixes":["tt"]},
                    "meta"
                ],
                "types":["movie","series"],
                "idPrefixes":["tt"],
                "catalogs":[{"type":"movie","id":"find","name":"Find","extra":[{"name":"search","isRequired":true}]}],
                "behaviorHints":{"configurable":true,"configurationRequired":true}
            }""",
        )

        assertEquals(listOf("movie"), manifest.resources.first().types)
        assertEquals(listOf("tt"), manifest.resources.first().idPrefixes)
        assertEquals(listOf("search"), manifest.catalogs.single().extra.map { it.name })
        assertTrue(manifest.catalogs.single().extra.single().isRequired)
        assertTrue(manifest.behaviorHints.configurationRequired)
    }

    @Test
    fun previouslyStoredAddonRecordsRemainReadable() {
        val restored = jacksonObjectMapper().readValue(
            """{"manifestUrl":"https://addon.example/manifest.json","name":"Old addon","providesStreams":true}""",
            ManagedStremioAddon::class.java,
        )

        assertEquals("Old addon", restored.name)
        assertTrue(restored.providesStreams)
        assertTrue(restored.resources.isEmpty())
        assertTrue(restored.catalogs.isEmpty())
        assertEquals(false, restored.configurationRequired)
    }

    @Test
    fun mapsProxyRequestHeadersForDirectStreams() {
        val mapped = StremioStreamMapper.map(
            StremioStreamItem(
                url = "https://cdn.example/video.m3u8",
                behaviorHints = StremioStreamBehaviorHints(
                    proxyHeaders = StremioProxyHeaders(
                        request = mapOf("Referer" to "https://site.example", "User-Agent" to "Stremio"),
                        response = mapOf("Set-Cookie" to "ignored-for-requests"),
                    ),
                ),
            ),
        )

        val direct = assertIs<StremioPlayableStream.Direct>(mapped)
        assertEquals("https://cdn.example/video.m3u8", direct.url)
        assertEquals(mapOf("Referer" to "https://site.example", "User-Agent" to "Stremio"), direct.requestHeaders)
    }

    @Test
    fun mapsTorrentHashTrackersAndSelectedFileIndex() {
        val mapped = StremioStreamMapper.map(
            StremioStreamItem(
                infoHash = "0123456789abcdef0123456789abcdef01234567",
                fileIdx = 3,
                sources = listOf("tracker:udp://custom.example:80/announce"),
            ),
        )

        val torrent = assertIs<StremioPlayableStream.Torrent>(mapped)
        assertEquals(3, torrent.fileIdx)
        assertTrue(torrent.magnetUrl.startsWith("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
        assertTrue(torrent.magnetUrl.contains("tr=udp%3A%2F%2Fcustom.example%3A80%2Fannounce"))
        assertEquals(1, Regex("&tr=").findAll(torrent.magnetUrl).count())
    }

    @Test
    fun mapsYoutubeAndExternalStreamsAndSkipsInvalidEntries() {
        val youtube = StremioStreamMapper.map(StremioStreamItem(ytId = "abcDEF_1234"))
        assertEquals(
            "https://www.youtube.com/watch?v=abcDEF_1234",
            assertIs<StremioPlayableStream.YouTube>(youtube).url,
        )
        assertEquals(
            "https://example.com/watch",
            assertIs<StremioPlayableStream.External>(
                StremioStreamMapper.map(StremioStreamItem(externalUrl = "https://example.com/watch")),
            ).url,
        )
        assertNull(StremioStreamMapper.map(StremioStreamItem(url = "javascript:alert(1)")))
    }
}
