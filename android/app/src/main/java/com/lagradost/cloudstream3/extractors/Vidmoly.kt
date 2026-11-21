package com.lagradost.cloudstream3.extractors

// Behavior/API reference: CloudStream library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/Vidmoly.kt at 5024ace2922a148d38df2a816b483b529789cf58 (GPL-3.0).

import com.lagradost.cloudstream3.MainAPIKt
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getPinnedExtractorResponse
import com.lagradost.cloudstream3.utils.validatePublicExtractorMediaUrl
import com.fleeksoft.ksoup.Ksoup
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Orbit-owned Vidmoly family implementation matching the pinned iframe/JW flow. */
open class Vidmoly : ExtractorApi() {
    override fun getName(): String = "Vidmoly"
    override fun getMainUrl(): String = "https://vidmoly.net"
    override fun getRequiresReferer(): Boolean = true

    @Suppress("UNCHECKED_CAST")
    override fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        continuation: Continuation<*>,
    ): Any? {
        val extraction: suspend () -> Unit = { extract(url, referer, subtitleCallback, callback) }
        return extraction.startCoroutineUninterceptedOrReturn(continuation as Continuation<Unit>)
    }

    private suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
            val headers = mapOf(
                "user-agent" to MainAPIKt.USER_AGENT,
                "Sec-Fetch-Dest" to "iframe",
            )
            val target = if (url.contains("/w/")) {
                url.replaceFirst("/w/", "/embed-") + ".html"
            } else url
            val response = getPinnedExtractorResponse(
                target,
                headers = headers + referer?.takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) }.orEmpty(),
            )
            val script = Ksoup.parse(response.body).select("script")
                .firstOrNull { it.data().contains("sources:") }
                ?.data().orEmpty()
            val links = ArrayList<ExtractorLink>()
            val subtitles = ArrayList<SubtitleFile>()
            JwPlayerHelper.extractStreamLinks(
                script, getName(), getMainUrl(), { links.add(it) }, { subtitles.add(it) }
            )
            links.forEach { link ->
                val safeUrl = validatePublicExtractorMediaUrl(link.url) ?: return@forEach
                callback(
                    ExtractorLink(
                        link.source, link.name, safeUrl, link.referer, link.quality,
                        link.headers, link.extractorData, link.type, link.audioTracks,
                    )
                )
            }
            subtitles.forEach { subtitle ->
                val safeUrl = validatePublicExtractorMediaUrl(subtitle.url) ?: return@forEach
                val output = SubtitleFile(subtitle.lang, safeUrl)
                output.headers = subtitle.headers
                subtitleCallback(output)
            }
        }
    }

    private companion object {
        const val EXTRACTION_TIMEOUT_MS = 30_000L
    }
}

class Vidmolyto : Vidmoly() {
    override fun getMainUrl(): String = "https://vidmoly.to"
}

class Vidmolybiz : Vidmoly() {
    override fun getMainUrl(): String = "https://vidmoly.biz"
}
