package com.lagradost.cloudstream3.extractors

// Behavior/API reference: CloudStream library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/Jeniusplay.kt at 5024ace2922a148d38df2a816b483b529789cf58 (GPL-3.0).

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorApiKt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getPinnedExtractorResponse
import com.lagradost.cloudstream3.utils.postPinnedExtractorForm
import com.lagradost.cloudstream3.utils.validatePublicExtractorMediaUrl
import com.fleeksoft.ksoup.Ksoup
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Orbit-owned implementation of the pinned Jeniusplay extractor flow. */
open class Jeniusplay : ExtractorApi() {
    override fun getName(): String = "Jeniusplay"
    override fun getMainUrl(): String = "https://jeniusplay.com"
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
            val page = getPinnedExtractorResponse(
                url,
                headers = mapOf("Referer" to "${getMainUrl()}/"),
            )
            val document = Ksoup.parse(page.body)
            val hash = url.substringAfterLast('/').substringAfter("data=")
            if (hash.isEmpty() || hash.length > MAX_HASH_CHARS) return@withTimeoutOrNull
            val endpoint = "${getMainUrl()}/player/index.php".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("data", hash)
                ?.addQueryParameter("do", "getVideo")
                ?.build()
                ?.toString()
                ?: return@withTimeoutOrNull
            val response = postPinnedExtractorForm(
                endpoint,
                fields = mapOf("hash" to hash, "r" to "$referer"),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to url,
                ),
            )

            val streamUrl = validatePublicExtractorMediaUrl(
                JSONObject(response.body).optString("videoSource")
            )
            if (streamUrl != null) {
                M3u8Helper.generateM3u8(getName(), streamUrl, url).forEach(callback)
            }
            document.select("script").forEach { script ->
                if (!ExtractorApiKt.getPacked(script.data()).isNullOrEmpty()) {
                    val unpacked = ExtractorApiKt.getAndUnpack(script.data())
                    val links = ArrayList<ExtractorLink>()
                    val subtitles = ArrayList<SubtitleFile>()
                    JwPlayerHelper.extractStreamLinks(
                        unpacked, getName(), getMainUrl(), { links.add(it) }, { subtitles.add(it) }
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
        }
    }

    @Serializable
    data class ResponseSource(
        @JsonProperty("hls") @SerialName("hls") val hls: Boolean,
        @JsonProperty("videoSource") @SerialName("videoSource") val videoSource: String,
        @JsonProperty("securedLink") @SerialName("securedLink") val securedLink: String?,
    )

    private companion object {
        const val MAX_HASH_CHARS = 512
        const val EXTRACTION_TIMEOUT_MS = 30_000L
    }
}
