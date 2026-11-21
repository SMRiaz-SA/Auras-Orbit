package com.lagradost.cloudstream3.extractors

// Behavior/API reference: CloudStream library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/StreamSB.kt at 5024ace2922a148d38df2a816b483b529789cf58 (GPL-3.0).

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getPinnedExtractorResponse
import com.lagradost.cloudstream3.utils.validatePublicExtractorMediaUrl
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.random.Random

/** Orbit-owned StreamSB family implementation matching the pinned request and callback flow. */
open class StreamSB : ExtractorApi() {
    private var currentName = "StreamSB"
    private var currentMainUrl = "https://watchsb.com"
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    override fun getName(): String = currentName
    open fun setName(value: String) { currentName = requireNotNull(value) }
    override fun getMainUrl(): String = currentMainUrl
    open fun setMainUrl(value: String) { currentMainUrl = requireNotNull(value) }
    override fun getRequiresReferer(): Boolean = false

    @Suppress("UNCHECKED_CAST")
    override fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        continuation: Continuation<*>,
    ): Any? {
        val extraction: suspend () -> Unit = { extract(url, subtitleCallback, callback) }
        return extraction.startCoroutineUninterceptedOrReturn(continuation as Continuation<Unit>)
    }

    private suspend fun extract(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val input = url.toHttpUrlOrNull()?.takeIf {
            it.scheme in setOf("http", "https") &&
                it.encodedUsername.isEmpty() && it.encodedPassword.isEmpty()
        } ?: return
        withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
            val id = STREAM_ID_REGEX.findAll(url).map { match ->
                match.value.replace(Regex("(embed-|/e/)"), "")
            }.firstOrNull()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID_CHARS }
                ?: return@withTimeoutOrNull
            val headers = mapOf("watchsb" to "sbstream", "Referer" to input.toString())
            val master = "${getMainUrl()}/375664356a494546326c4b797c7c6e756577776778623171737/${encodeId(id)}"
            val response = getPinnedExtractorResponse(master.lowercase(), headers)
            val mapped = runCatching {
                json.decodeFromString<Main>(response.body)
            }.getOrNull() ?: return@withTimeoutOrNull

            val playlist = validatePublicExtractorMediaUrl(mapped.streamData.file)
                ?: return@withTimeoutOrNull
            M3u8Helper.generateM3u8(getName(), playlist, input.toString(), headers = mapOf("watchsb" to "sbstream")).forEach(callback)
            mapped.streamData.subs.orEmpty().forEach { sub ->
                val subtitleUrl = sub.file?.let { validatePublicExtractorMediaUrl(it) } ?: return@forEach
                subtitleCallback(SubtitleFile(sub.label ?: "null", subtitleUrl))
            }
        }
    }

    private fun encodeId(id: String): String {
        val code = "${createHashTable()}||$id||${createHashTable()}||streamsb"
        return code.toCharArray().joinToString("") { char -> char.code.toString(16) }
    }

    private fun createHashTable(): String = buildString {
        repeat(12) { append(alphabet[Random.nextInt(alphabet.length)]) }
    }

    @Serializable
    data class Subs(
        @JsonProperty("file") @SerialName("file") val file: String? = null,
        @JsonProperty("label") @SerialName("label") val label: String? = null,
    )

    @Serializable
    data class StreamData(
        @JsonProperty("file") @SerialName("file") val file: String,
        @JsonProperty("cdn_img") @SerialName("cdn_img") val cdnImg: String,
        @JsonProperty("hash") @SerialName("hash") val hash: String,
        @JsonProperty("subs") @SerialName("subs") val subs: ArrayList<Subs>? = arrayListOf(),
        @JsonProperty("length") @SerialName("length") val length: String,
        @JsonProperty("id") @SerialName("id") val id: String,
        @JsonProperty("title") @SerialName("title") val title: String,
        @JsonProperty("backup") @SerialName("backup") val backup: String,
    )

    @Serializable
    data class Main(
        @JsonProperty("stream_data") @SerialName("stream_data") val streamData: StreamData,
        @JsonProperty("status_code") @SerialName("status_code") val statusCode: Int,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val STREAM_ID_REGEX = Regex("(embed-[a-zA-Z\\d]{0,8}[a-zA-Z\\d_-]+|/e/[a-zA-Z\\d]{0,8}[a-zA-Z\\d_-]+)")
        const val MAX_ID_CHARS = 256
        const val EXTRACTION_TIMEOUT_MS = 30_000L
    }
}

class Sblona : StreamSB() { init { setName("Sblona"); setMainUrl("https://sblona.com") } }
class Lvturbo : StreamSB() { init { setName("Lvturbo"); setMainUrl("https://lvturbo.com") } }
class Sbrapid : StreamSB() { init { setName("Sbrapid"); setMainUrl("https://sbrapid.com") } }
class Sbface : StreamSB() { init { setName("Sbface"); setMainUrl("https://sbface.com") } }
class Sbsonic : StreamSB() { init { setName("Sbsonic"); setMainUrl("https://sbsonic.com") } }
class Vidgomunimesb : StreamSB() { init { setMainUrl("https://vidgomunimesb.xyz") } }
class Sbasian : StreamSB() { init { setName("Sbasian"); setMainUrl("https://sbasian.pro") } }
class Sbnet : StreamSB() { init { setName("Sbnet"); setMainUrl("https://sbnet.one") } }
class Keephealth : StreamSB() { init { setName("Keephealth"); setMainUrl("https://keephealth.info") } }
class Sbspeed : StreamSB() { init { setName("Sbspeed"); setMainUrl("https://sbspeed.com") } }
class Streamsss : StreamSB() { init { setMainUrl("https://streamsss.net") } }
class Sbflix : StreamSB() { init { setName("Sbflix"); setMainUrl("https://sbflix.xyz") } }
class Vidgomunime : StreamSB() { init { setMainUrl("https://vidgomunime.xyz") } }
class Sbthe : StreamSB() { init { setMainUrl("https://sbthe.com") } }
class Ssbstream : StreamSB() { init { setMainUrl("https://ssbstream.net") } }
class SBfull : StreamSB() { init { setMainUrl("https://sbfull.com") } }
class StreamSB1 : StreamSB() { init { setMainUrl("https://sbplay1.com") } }
class StreamSB2 : StreamSB() { init { setMainUrl("https://sbplay2.com") } }
class StreamSB3 : StreamSB() { init { setMainUrl("https://sbplay3.com") } }
class StreamSB4 : StreamSB() { init { setMainUrl("https://cloudemb.com") } }
class StreamSB5 : StreamSB() { init { setMainUrl("https://sbplay.org") } }
class StreamSB6 : StreamSB() { init { setMainUrl("https://embedsb.com") } }
class StreamSB7 : StreamSB() { init { setMainUrl("https://pelistop.co") } }
class StreamSB9 : StreamSB() { init { setMainUrl("https://sbplay.one") } }
class StreamSB10 : StreamSB() { init { setMainUrl("https://sbplay2.xyz") } }
class StreamSB11 : StreamSB() { init { setMainUrl("https://sbbrisk.com") } }
class Sblongvu : StreamSB() { init { setMainUrl("https://sblongvu.com") } }

