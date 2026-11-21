package com.lagradost.cloudstream3.extractors

// Behavior/API reference: CloudStream library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/Filesim.kt at 5024ace2922a148d38df2a816b483b529789cf58 (GPL-3.0).

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorApiKt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getPinnedExtractorResponse
import com.lagradost.cloudstream3.utils.validatePublicExtractorMediaUrl
import com.fleeksoft.ksoup.Ksoup
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Orbit-owned implementation of the pinned Filesim extractor flow. */
open class Filesim : ExtractorApi() {
    private var currentName = "Filesim"
    private var currentMainUrl = "https://files.im"

    override fun getName(): String = currentName
    protected open fun setName(value: String) { currentName = requireNotNull(value) }
    override fun getMainUrl(): String = currentMainUrl
    protected open fun setMainUrl(value: String) { currentMainUrl = requireNotNull(value) }
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
            val embedUrl = url.replace("/download/", "/e/")
            var page = getPinnedExtractorResponse(
                embedUrl,
                headers = referer?.takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) }.orEmpty(),
            )
            var document = Ksoup.parse(page.body)
            val iframe = document.selectFirst("iframe")
            if (iframe != null) {
                val iframeSrc = iframe.attr("src").trim().takeIf { it.isNotEmpty() }
                    ?: return@withTimeoutOrNull
                val iframeUrl = page.url.toHttpUrlOrNull()?.resolve(iframeSrc)?.toString()
                    ?: return@withTimeoutOrNull
                page = getPinnedExtractorResponse(
                    iframeUrl,
                    headers = mapOf(
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Sec-Fetch-Dest" to "iframe",
                        "Referer" to page.url,
                    ),
                )
                document = Ksoup.parse(page.body)
            }

            val packed = ExtractorApiKt.getPacked(page.body)
            val script = if (!packed.isNullOrEmpty()) ExtractorApiKt.getAndUnpack(page.body)
                else document.selectFirst("script:containsData(sources:)")?.data().orEmpty()
            val links = ArrayList<ExtractorLink>()
            val subtitles = ArrayList<SubtitleFile>()
            val found = JwPlayerHelper.extractStreamLinks(
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
            if (!found) {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("(m3u8|master\\.txt)"),
                    additionalUrls = listOf(Regex("(m3u8|master\\.txt)")),
                    useOkhttp = false,
                    timeout = WEBVIEW_TIMEOUT_MS,
                )
                val intercepted = getPinnedExtractorResponse(
                    page.url,
                    headers = referer?.let { mapOf("Referer" to it) }.orEmpty(),
                    interceptor = resolver,
                ).url
                val safeIntercepted = validatePublicExtractorMediaUrl(intercepted)
                    ?: return@withTimeoutOrNull
                M3u8Helper.generateM3u8(getName(), safeIntercepted, getMainUrl()).forEach(callback)
            }
        }
    }

    private companion object {
        const val EXTRACTION_TIMEOUT_MS = 30_000L
        const val WEBVIEW_TIMEOUT_MS = 15_000L
    }
}

/** Pinned Filesim-compatible host aliases from the official CloudStream extractor family. */
class Multimoviesshg : Filesim() {
    override fun getMainUrl(): String = mainUrl
    private var mainUrl = "https://multimoviesshg.com"
    override fun setMainUrl(value: String) { mainUrl = requireNotNull(value) }
}

class Guccihide : Filesim() {
    override fun getName(): String = "Guccihide"
    override fun getMainUrl(): String = mainUrl
    private var mainUrl = "https://guccihide.com"
    override fun setMainUrl(value: String) { mainUrl = requireNotNull(value) }
}

class Ahvsh : Filesim() {
    override fun getName(): String = "Ahvsh"
    override fun getMainUrl(): String = mainUrl
    private var mainUrl = "https://ahvsh.com"
    override fun setMainUrl(value: String) { mainUrl = requireNotNull(value) }
}

class Moviesm4u : Filesim() {
    override fun getName(): String = "Moviesm4u"
    override fun getMainUrl(): String = "https://moviesm4u.com"
}

class StreamhideTo : Filesim() {
    override fun getName(): String = "Streamhide"
    override fun getMainUrl(): String = "https://streamhide.to"
}

class StreamhideCom : Filesim() {
    private var currentAliasName = "Streamhide"
    private var currentAliasUrl = "https://streamhide.com"
    override fun getName(): String = currentAliasName
    override fun setName(value: String) { currentAliasName = requireNotNull(value) }
    override fun getMainUrl(): String = currentAliasUrl
    override fun setMainUrl(value: String) { currentAliasUrl = requireNotNull(value) }
}

class Movhide : Filesim() {
    private var currentAliasName = "Movhide"
    private var currentAliasUrl = "https://movhide.pro"
    override fun getName(): String = currentAliasName
    override fun setName(value: String) { currentAliasName = requireNotNull(value) }
    override fun getMainUrl(): String = currentAliasUrl
    override fun setMainUrl(value: String) { currentAliasUrl = requireNotNull(value) }
}

class Ztreamhub : Filesim() {
    override fun getName(): String = "Zstreamhub"
    override fun getMainUrl(): String = "https://ztreamhub.com"
}
