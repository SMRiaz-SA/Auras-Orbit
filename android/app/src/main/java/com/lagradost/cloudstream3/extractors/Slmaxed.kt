package com.lagradost.cloudstream3.extractors

// Behavior/API reference: CloudStream library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/Streamlare.kt at 5024ace2922a148d38df2a816b483b529789cf58 (GPL-3.0).

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.postPinnedExtractorJson
import com.lagradost.cloudstream3.utils.validatePublicExtractorMediaUrl
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Orbit-owned Slmaxed implementation of the pinned JSON POST flow. */
open class Slmaxed : ExtractorApi() {
    override fun getName(): String = "Streamlare"
    override fun getMainUrl(): String = "https://slmaxed.com/"
    override fun getRequiresReferer(): Boolean = true
    val embedRegex = Regex("""/e/([^/]*)""")

    @Suppress("UNCHECKED_CAST")
    override fun getUrl(url: String, referer: String?, continuation: Continuation<*>): Any? {
        val extraction: suspend () -> List<ExtractorLink>? = { extract(url) }
        return extraction.startCoroutineUninterceptedOrReturn(
            continuation as Continuation<List<ExtractorLink>?>
        )
    }

    private suspend fun extract(url: String): List<ExtractorLink>? {
        if (url.length !in 1..MAX_URL_CHARS) return null
        val input = url.toHttpUrlOrNull()?.takeIf {
            it.scheme in setOf("http", "https") && it.encodedUsername.isEmpty() && it.encodedPassword.isEmpty()
        } ?: return null
        val id = embedRegex.find(url)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_ID_CHARS } ?: return null
        return withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
            val body = JSONObject().put("id", id).toString()
                .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            val response = postPinnedExtractorJson(
                "${getMainUrl()}api/video/stream/get",
                body = body,
            )
            val resultMap = JSONObject(response.body).optJSONObject("result")
                ?: return@withTimeoutOrNull null
            val links = ArrayList<ExtractorLink>()
            val keys = resultMap.keys()
            while (keys.hasNext()) {
                val result = resultMap.optJSONObject(keys.next()) ?: continue
                val file = result.optString("file").let { validatePublicExtractorMediaUrl(it) }
                    ?: continue
                val linkType = if (result.optString("type").contains("hls", ignoreCase = true)) {
                    ExtractorLinkType.M3U8
                } else null
                val quality = result.optString("label").takeIf { it.isNotEmpty() }
                    ?.replace("p", "", ignoreCase = true)
                    ?.trim()?.toIntOrNull() ?: Qualities.Unknown.value
                links += ExtractorLink(
                    getName(), getName(), file, url, quality,
                    emptyMap(), null, linkType, emptyList(),
                )
            }
            links
        }
    }

    @Serializable
    data class JsonResponse(
        @JsonProperty("status") @SerialName("status") val status: String? = null,
        @JsonProperty("message") @SerialName("message") val message: String? = null,
        @JsonProperty("type") @SerialName("type") val type: String? = null,
        @JsonProperty("token") @SerialName("token") val token: String? = null,
        @JsonProperty("result") @SerialName("result") val result: Map<String, Result>? = null,
    )

    @Serializable
    data class Result(
        @JsonProperty("label") @SerialName("label") val label: String? = null,
        @JsonProperty("file") @SerialName("file") val file: String? = null,
        @JsonProperty("type") @SerialName("type") val type: String? = null,
    )

    private companion object {
        const val MAX_URL_CHARS = 4_096
        const val MAX_ID_CHARS = 256
        const val EXTRACTION_TIMEOUT_MS = 30_000L
    }
}
