package com.lagradost.player.impl.proxy

import org.junit.jupiter.api.Test
import java.net.URI
import java.util.Base64
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsRewriterTest {

    @Test
    fun rewritesMasterVariantsAndRenditionsAgainstTheManifestUrl() {
        val manifestUrl = "https://cdn.example.test/show/master.m3u8?token=secret"
        val source = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="subs/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1100000,RESOLUTION=1280x720,AUDIO="audio",SUBTITLES="subs"
            video/720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3100000,RESOLUTION=1920x1080,AUDIO="audio",SUBTITLES="subs"
            video/1080.m3u8?quality=high
        """.trimIndent()

        val rewritten = HlsRewriter.rewriteM3u8(
            content = source,
            baseUrl = manifestUrl,
            sessionId = "hls-fixture",
            tracksListener = null,
        )

        val proxiedUrls = proxiedOriginalUrls(rewritten)
        assertContains(proxiedUrls, "https://cdn.example.test/show/video/720.m3u8?token=secret")
        assertContains(proxiedUrls, "https://cdn.example.test/show/video/1080.m3u8?quality=high&token=secret")
        assertContains(proxiedUrls, "https://cdn.example.test/show/audio/en.m3u8?token=secret")
        assertTrue(rewritten.contains("#EXT-X-MEDIA:TYPE=AUDIO"))
        assertFalse(rewritten.contains("#EXT-X-MEDIA:TYPE=SUBTITLES"), "Subtitle renditions are exposed through the track callback")
    }

    @Test
    fun rewritesMediaKeyMapAndSegmentsWhilePreservingByteRangesAndTags() {
        val source = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:12
            #EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin"
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
            #EXT-X-BYTERANGE:1000@0
            segments/part.m4s?part=2
            #EXT-X-ENDLIST
        """.trimIndent()

        val rewritten = HlsRewriter.rewriteM3u8(
            content = source,
            baseUrl = "https://cdn.example.test/vod/index.m3u8?token=secret",
            sessionId = "hls-fixture",
            tracksListener = null,
        )

        val proxiedUrls = proxiedOriginalUrls(rewritten)
        assertContains(proxiedUrls, "https://cdn.example.test/vod/keys/key.bin?token=secret")
        assertContains(proxiedUrls, "https://cdn.example.test/vod/init.mp4?token=secret")
        assertContains(proxiedUrls, "https://cdn.example.test/vod/segments/part.m4s?part=2&token=secret")
        assertContains(rewritten, "#EXT-X-MAP:URI=")
        assertContains(rewritten, "BYTERANGE=\"720@0\"")
        assertContains(rewritten, "#EXT-X-BYTERANGE:1000@0")
        assertContains(rewritten, "#EXT-X-ENDLIST")
    }

    @Test
    fun preservesLivePlaylistSemanticsAndAddsEndListForVodsMissingIt() {
        val baseUrl = "https://cdn.example.test/live/index.m3u8"

        val event = HlsRewriter.rewriteM3u8(
            "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXTINF:4,\nsegment.ts",
            baseUrl,
            "hls-fixture",
            tracksListener = null,
        )
        val sliding = HlsRewriter.rewriteM3u8(
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:50\n#EXTINF:4,\nsegment.ts",
            baseUrl,
            "hls-fixture",
            tracksListener = null,
        )
        val vod = HlsRewriter.rewriteM3u8(
            "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4,\nsegment.ts",
            baseUrl,
            "hls-fixture",
            tracksListener = null,
        )

        assertFalse(event.contains("#EXT-X-ENDLIST"))
        assertFalse(sliding.contains("#EXT-X-ENDLIST"))
        assertTrue(vod.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun doesNotInheritManifestTokensForCrossHostSegments() {
        val source = "#EXTM3U\n#EXTINF:4,\nhttps://media.example.test/segment.ts"

        val rewritten = HlsRewriter.rewriteM3u8(
            content = source,
            baseUrl = "https://cdn.example.test/live/index.m3u8?token=secret",
            sessionId = "hls-fixture",
            tracksListener = null,
        )

        assertEquals("https://media.example.test/segment.ts", proxiedOriginalUrls(rewritten).single())
    }

    private fun proxiedOriginalUrls(content: String): List<String> = PROXY_URL_REGEX.findAll(content)
        .mapNotNull { proxiedOriginalUrl(it.value) }
        .toList()

    private fun proxiedOriginalUrl(proxyUrl: String): String? {
        val proxyUri = runCatching { URI(proxyUrl) }.getOrNull() ?: return null
        if (proxyUri.path != "/proxy") return null
        val encoded = proxyUri.rawQuery.orEmpty()
            .split('&')
            .firstOrNull { it.startsWith("u=") }
            ?.substringAfter('=')
            ?: return null
        return runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull()
    }

    private companion object {
        val PROXY_URL_REGEX = Regex("""http://127\.0\.0\.1:\d+/proxy\?[^\"\s]+""")
    }
}
