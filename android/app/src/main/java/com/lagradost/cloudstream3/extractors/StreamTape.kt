package com.lagradost.cloudstream3.extractors

// Behavior/API reference: CloudStream library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/StreamTape.kt at 5024ace2922a148d38df2a816b483b529789cf58 (GPL-3.0).

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.evalJs
import com.lagradost.cloudstream3.utils.getPinnedExtractorResponse
import com.lagradost.cloudstream3.utils.validatePublicExtractorMediaUrl
import com.fleeksoft.ksoup.Ksoup
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Orbit-owned StreamTape implementation using the pinned bounded evalJs helper. */
open class StreamTape : ExtractorApi() {
    private var currentName = "StreamTape"
    private var currentMainUrl = "https://streamtape.com"

    override fun getName(): String = currentName
    open fun setName(value: String) { currentName = requireNotNull(value) }
    override fun getMainUrl(): String = currentMainUrl
    open fun setMainUrl(value: String) { currentMainUrl = requireNotNull(value) }
    override fun getRequiresReferer(): Boolean = false

    @Suppress("UNCHECKED_CAST")
    override fun getUrl(url: String, referer: String?, continuation: Continuation<*>): Any? {
        val extraction: suspend () -> List<ExtractorLink>? = { extract(url) }
        return extraction.startCoroutineUninterceptedOrReturn(
            continuation as Continuation<List<ExtractorLink>?>
        )
    }

    private suspend fun extract(url: String): List<ExtractorLink>? {
        return withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
            val response = getPinnedExtractorResponse(url)
            val document = Ksoup.parse(response.body)
            val line = document.select("script")
                .firstOrNull { it.html().contains("botlink').innerHTML") }
                ?.html()?.lines()?.firstOrNull { it.contains("botlink').innerHTML") }
            val scriptContent = line?.substringAfter(").innerHTML")?.replaceFirst("=", "var url =")
            val evaluated = scriptContent?.let { evalJs(it, "url") }
            val result = if (evaluated == null || evaluated === Unit) null else evaluated.toString()
            if (result.isNullOrEmpty() || result.length > MAX_URL_CHARS) return@withTimeoutOrNull null
            val mediaUrl = validatePublicExtractorMediaUrl("https:$result&stream=1")
                ?: return@withTimeoutOrNull null
            listOf(
                ExtractorLink(
                    getName(), getName(), mediaUrl, url, Qualities.Unknown.value,
                    emptyMap(), null, null, emptyList(),
                )
            )
        }
    }

    private companion object {
        const val MAX_URL_CHARS = 4_096
        const val EXTRACTION_TIMEOUT_MS = 30_000L
    }
}

class Watchadsontape : StreamTape() { init { setMainUrl("https://watchadsontape.com") } }
class StreamTapeNet : StreamTape() { init { setMainUrl("https://streamtape.net") } }
class StreamTapeXyz : StreamTape() { init { setMainUrl("https://streamtape.xyz") } }
class ShaveTape : StreamTape() { init { setMainUrl("https://shavetape.cash") } }
