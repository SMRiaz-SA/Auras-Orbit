package com.lagradost.cloudstream3.desktop.player

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class QualityDataHelperTest {
    @Suppress("DEPRECATION")
    private fun link(name: String, quality: Int, type: ExtractorLinkType, url: String): ExtractorLink = ExtractorLink(
        source = "test",
        name = name,
        url = url,
        referer = "",
        quality = quality,
        type = type,
    )

    @Test
    fun `range probe failures and missing range headers remain unknown`() {
        assertEquals(QualityDataHelper.Seekability.UNKNOWN, QualityDataHelper.classifyRangeSupport(403, null, null))
        assertEquals(QualityDataHelper.Seekability.UNKNOWN, QualityDataHelper.classifyRangeSupport(200, null, null))
    }

    @Test
    fun `range probe only marks seekability from explicit response evidence`() {
        assertEquals(QualityDataHelper.Seekability.SEEKABLE, QualityDataHelper.classifyRangeSupport(206, "bytes 0-1/100", null))
        assertEquals(QualityDataHelper.Seekability.SEEKABLE, QualityDataHelper.classifyRangeSupport(200, null, "bytes"))
        assertEquals(QualityDataHelper.Seekability.NON_SEEKABLE, QualityDataHelper.classifyRangeSupport(200, null, "none"))
    }

    @Test
    fun `unverified high quality source is not ranked below verified low quality source`() {
        val unknown4k = link(
            name = "Direct 4K",
            quality = Qualities.P2160.value,
            type = ExtractorLinkType.VIDEO,
            url = "https://unknown-range.example/video.mp4",
        )
        val seekableSd = link(
            name = "HLS 532p",
            quality = 532,
            type = ExtractorLinkType.M3U8,
            url = "https://hls.example/video.m3u8",
        )

        assertEquals(QualityDataHelper.Seekability.UNKNOWN, QualityDataHelper.getSeekability(unknown4k))
        assertEquals(unknown4k.url, QualityDataHelper.sortLinks(listOf(seekableSd, unknown4k), "Auto").first().url)
    }
}
