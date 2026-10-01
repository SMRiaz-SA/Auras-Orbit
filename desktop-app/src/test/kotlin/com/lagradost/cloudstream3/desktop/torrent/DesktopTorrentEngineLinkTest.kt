package com.lagradost.cloudstream3.desktop.torrent

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopTorrentEngineLinkTest {
    @Test
    fun recognizesProviderMagnetTorrentFileAndInfoHashLinks() = runBlocking {
        val links = listOf(
            newExtractorLink(
                source = "Fixture",
                name = "Magnet",
                url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                type = ExtractorLinkType.MAGNET,
            ),
            newExtractorLink(
                source = "Fixture",
                name = "Torrent file",
                url = "https://fixture.example/video.torrent",
                type = ExtractorLinkType.TORRENT,
            ),
            newExtractorLink(
                source = "Fixture",
                name = "Bare info hash",
                url = "0123456789abcdef0123456789abcdef01234567",
                type = ExtractorLinkType.VIDEO,
            ),
        )

        assertTrue(links.all(DesktopTorrentEngine::isTorrentLink))
    }

    @Test
    fun doesNotClassifyOrdinaryProviderVideoLinksAsTorrentLinks() = runBlocking {
        val directVideo = newExtractorLink(
            source = "Fixture",
            name = "Direct MP4",
            url = "https://fixture.example/video.mp4",
            type = ExtractorLinkType.VIDEO,
        )

        assertFalse(DesktopTorrentEngine.isTorrentLink(directVideo))
    }
}
