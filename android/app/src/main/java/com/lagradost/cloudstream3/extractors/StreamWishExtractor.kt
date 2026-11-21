package com.lagradost.cloudstream3.extractors

// Behavior/API reference: CloudStream library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/StreamWishExtractor.kt at 5024ace2922a148d38df2a816b483b529789cf58 (GPL-3.0).

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.MainAPIKt
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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Orbit-owned Streamwish implementation of the pinned JW and WebView fallback flow. */
open class StreamWishExtractor : ExtractorApi() {
    override fun getName(): String = "Streamwish"
    override fun getMainUrl(): String = "https://streamwish.to"
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
                "Accept" to "*/*",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Referer" to "${getMainUrl()}/",
                "Origin" to "${getMainUrl()}/",
                "User-Agent" to MainAPIKt.USER_AGENT,
            )
            val page = getPinnedExtractorResponse(
                resolveEmbedUrl(url),
                headers = referer?.takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) }.orEmpty(),
            )
            val document = Ksoup.parse(page.body)
            val unpacked = ExtractorApiKt.getPacked(page.body)
            val script = when {
                !unpacked.isNullOrEmpty() -> ExtractorApiKt.getAndUnpack(page.body)
                document.select("script").any {
                    it.html().contains("jwplayer(\"vplayer\").setup(")
                } -> document.select("script").firstOrNull {
                    it.html().contains("jwplayer(\"vplayer\").setup(")
                }?.html()
                else -> document.selectFirst("script:containsData(sources:)")?.data()
            }.orEmpty()

            val links = ArrayList<ExtractorLink>()
            val subtitles = ArrayList<SubtitleFile>()
            val found = JwPlayerHelper.extractStreamLinks(
                script, getName(), getMainUrl(), links::add, subtitles::add, headers
            )
            links.forEach { link ->
                val safeUrl = validatePublicExtractorMediaUrl(link.url) ?: return@forEach
                val sameOrigin = sameOrigin(getMainUrl(), safeUrl)
                callback(
                    ExtractorLink(
                        link.source,
                        link.name,
                        safeUrl,
                        if (sameOrigin) link.referer else "",
                        link.quality,
                        if (sameOrigin) link.headers else emptyMap(),
                        link.extractorData,
                        link.type,
                        link.audioTracks,
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
                    interceptUrl = Regex("(txt|m3u8)"),
                    additionalUrls = listOf(Regex("(txt|m3u8)")),
                    useOkhttp = false,
                    timeout = WEBVIEW_TIMEOUT_MS,
                )
                val intercepted = getPinnedExtractorResponse(
                    url,
                    headers = referer?.takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) }.orEmpty(),
                    interceptor = resolver,
                ).url
                val publicIntercepted = validatePublicExtractorMediaUrl(intercepted)
                    ?: return@withTimeoutOrNull
                if (publicIntercepted.isNotEmpty()) {
                    val sameOrigin = sameOrigin(getMainUrl(), publicIntercepted)
                    M3u8Helper.generateM3u8(
                        getName(),
                        publicIntercepted,
                        if (sameOrigin) "${getMainUrl()}/" else "",
                        headers = if (sameOrigin) headers else emptyMap(),
                    ).forEach(callback)
                }
            }
        }
    }

    private fun resolveEmbedUrl(inputUrl: String): String = when {
        inputUrl.contains("/f/") -> "${getMainUrl()}/${inputUrl.substringAfter("/f/")}"
        inputUrl.contains("/e/") -> "${getMainUrl()}/${inputUrl.substringAfter("/e/")}"
        else -> inputUrl
    }

    private fun sameOrigin(first: String, second: String): Boolean {
        val left: HttpUrl = first.toHttpUrlOrNull() ?: return false
        val right: HttpUrl = second.toHttpUrlOrNull() ?: return false
        return left.scheme == right.scheme && left.host == right.host && left.port == right.port
    }

    private companion object {
        const val EXTRACTION_TIMEOUT_MS = 30_000L
        const val WEBVIEW_TIMEOUT_MS = 15_000L
    }
}

class Mwish : StreamWishExtractor() {
    override fun getName(): String = "Mwish"
    override fun getMainUrl(): String = "https://mwish.pro"
}

class Dwish : StreamWishExtractor() {
    override fun getName(): String = "Dwish"
    override fun getMainUrl(): String = "https://dwish.pro"
}

class Ewish : StreamWishExtractor() {
    override fun getName(): String = "Embedwish"
    override fun getMainUrl(): String = "https://embedwish.com"
}

class Hgcloudto : StreamWishExtractor() {
    override fun getName(): String = "Hgcloud"
    override fun getMainUrl(): String = "https://Hgcloud.to"
}

class WishembedPro : StreamWishExtractor() {
    override fun getName(): String = "Wishembed"
    override fun getMainUrl(): String = "https://wishembed.pro"
}

class Kswplayer : StreamWishExtractor() {
    override fun getName(): String = "Kswplayer"
    override fun getMainUrl(): String = "https://kswplayer.info"
}

class Wishfast : StreamWishExtractor() {
    override fun getName(): String = "Wishfast"
    override fun getMainUrl(): String = "https://wishfast.top"
}

class Streamwish2 : StreamWishExtractor() {
    override fun getMainUrl(): String = "https://streamwish.site"
}

class SfastwishCom : StreamWishExtractor() {
    override fun getName(): String = "Sfastwish"
    override fun getMainUrl(): String = "https://sfastwish.com"
}

class Strwish : StreamWishExtractor() {
    override fun getName(): String = "Strwish"
    override fun getMainUrl(): String = "https://strwish.xyz"
}

class Strwish2 : StreamWishExtractor() {
    override fun getName(): String = "Strwish"
    override fun getMainUrl(): String = "https://strwish.com"
}

class FlaswishCom : StreamWishExtractor() {
    override fun getName(): String = "Flaswish"
    override fun getMainUrl(): String = "https://flaswish.com"
}

class Awish : StreamWishExtractor() {
    override fun getName(): String = "Awish"
    override fun getMainUrl(): String = "https://awish.pro"
}

class Obeywish : StreamWishExtractor() {
    override fun getName(): String = "Obeywish"
    override fun getMainUrl(): String = "https://obeywish.com"
}

class Jodwish : StreamWishExtractor() {
    override fun getName(): String = "Jodwish"
    override fun getMainUrl(): String = "https://jodwish.com"
}

class Swhoi : StreamWishExtractor() {
    override fun getName(): String = "Swhoi"
    override fun getMainUrl(): String = "https://swhoi.com"
}

class Multimovies : StreamWishExtractor() {
    override fun getName(): String = "Multimovies"
    override fun getMainUrl(): String = "https://multimovies.cloud"
}

class UqloadsXyz : StreamWishExtractor() {
    override fun getName(): String = "Uqloads"
    override fun getMainUrl(): String = "https://uqloads.xyz"
}

class Doodporn : StreamWishExtractor() {
    override fun getName(): String = "Doodporn"
    override fun getMainUrl(): String = "https://doodporn.xyz"
}

class CdnwishCom : StreamWishExtractor() {
    override fun getName(): String = "Cdnwish"
    override fun getMainUrl(): String = "https://cdnwish.com"
}

class Asnwish : StreamWishExtractor() {
    override fun getName(): String = "Asnwish"
    override fun getMainUrl(): String = "https://asnwish.com"
}

class Nekowish : StreamWishExtractor() {
    override fun getName(): String = "Nekowish"
    override fun getMainUrl(): String = "https://nekowish.my.id"
}

class Nekostream : StreamWishExtractor() {
    override fun getName(): String = "Nekostream"
    override fun getMainUrl(): String = "https://neko-stream.click"
}

class Swdyu : StreamWishExtractor() {
    override fun getName(): String = "Swdyu"
    override fun getMainUrl(): String = "https://swdyu.com"
}

class Wishonly : StreamWishExtractor() {
    override fun getName(): String = "Wishonly"
    override fun getMainUrl(): String = "https://wishonly.site"
}

class Playerwish : StreamWishExtractor() {
    override fun getName(): String = "Playerwish"
    override fun getMainUrl(): String = "https://playerwish.com"
}

class StreamHLS : StreamWishExtractor() {
    override fun getName(): String = "StreamHLS"
    override fun getMainUrl(): String = "https://streamhls.to"
}

class HlsWish : StreamWishExtractor() {
    override fun getName(): String = "HlsWish"
    override fun getMainUrl(): String = "https://hlswish.com"
}
