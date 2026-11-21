package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.fetchVidStackEncryptedResponse
import com.lagradost.cloudstream3.utils.validatePublicExtractorMediaUrl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Orbit-owned VidStack implementation based on the pinned pre-release and stable sources. */
open class VidStack : ExtractorApi() {
    @Volatile private var currentName = VIDSTACK_NAME
    @Volatile private var currentMainUrl = VIDSTACK_ORIGIN

    override fun getName(): String = currentName

    open fun setName(value: String) {
        if (value.length in 1..MAX_LABEL_CHARS && value.none { it.isISOControl() }) {
            currentName = value
        }
    }

    override fun getMainUrl(): String = currentMainUrl

    open fun setMainUrl(value: String) {
        normalizeConfiguredOrigin(value, VIDSTACK_ALLOWED_HOSTS)?.let { currentMainUrl = it }
    }

    override fun getRequiresReferer(): Boolean = true

    /** Keep the callback-form JVM descriptor used by StreamPlay v686. */
    @Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER")
    override fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        continuation: Continuation<*>,
    ): Any? {
        val extraction: suspend () -> Unit = { extract(url, subtitleCallback, callback) }
        return extraction.startCoroutineUninterceptedOrReturn(
            continuation as Continuation<Unit>
        )
    }

    private suspend fun extract(
        inputUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            withTimeoutOrNull(MAX_EXTRACTION_MILLIS) {
                currentCoroutineContext().ensureActive()
                val request = makeApiRequest(inputUrl) ?: return@withTimeoutOrNull
                val encrypted = fetchVidStackEncryptedResponse(request.apiUrl)
                    ?: return@withTimeoutOrNull
                val payload = decryptPayload(encrypted) ?: return@withTimeoutOrNull
                val rawSource = payload.optString("source", "").trim()
                if (rawSource.isEmpty() || rawSource.length > MAX_OUTPUT_URL_CHARS) {
                    return@withTimeoutOrNull
                }
                val absoluteSource = resolveOutputUrl(request.origin, rawSource)
                    ?: return@withTimeoutOrNull
                val mediaUrl = validatePublicExtractorMediaUrl(absoluteSource, requireHttps = true)
                    ?: return@withTimeoutOrNull

                emitSubtitles(payload, request.origin, subtitleCallback)
                currentCoroutineContext().ensureActive()
                callback(
                    ExtractorLink(
                        getName(),
                        getName(),
                        mediaUrl,
                        inputUrl,
                        UNKNOWN_QUALITY,
                        emptyMap(),
                        null,
                        ExtractorLinkType.M3U8,
                        emptyList(),
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Malformed or unavailable responses fail closed without logging payload data.
        }
    }

    private suspend fun emitSubtitles(
        payload: JSONObject,
        origin: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val subtitles = payload.optJSONObject("subtitle") ?: return
        val languages = subtitles.keys()
        var emitted = 0
        while (languages.hasNext() && emitted < MAX_SUBTITLES) {
            currentCoroutineContext().ensureActive()
            val language = languages.next()
            if (language.isBlank() || language.length > MAX_LABEL_CHARS ||
                language.any { it.isISOControl() }
            ) continue
            val value = subtitles.opt(language) as? String ?: continue
            val subtitlePath = value.substringBefore('#').trim()
            if (subtitlePath.isEmpty() || subtitlePath.length > MAX_OUTPUT_URL_CHARS) continue
            val absoluteUrl = resolveOutputUrl(origin, subtitlePath) ?: continue
            val safeUrl = validatePublicExtractorMediaUrl(absoluteUrl, requireHttps = true) ?: continue
            subtitleCallback(SubtitleFile(language, safeUrl))
            emitted++
        }
    }

    private fun makeApiRequest(input: String): ApiRequest? {
        if (input.length !in 1..MAX_INPUT_URL_CHARS || input != input.trim() ||
            input.any { it.isISOControl() }
        ) return null

        return try {
            val uri = URI(input)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "https" || uri.rawUserInfo != null || host.endsWith('.') ||
                uri.port != -1 && uri.port != 443 || host !in VIDSTACK_ALLOWED_HOSTS
            ) return null

            // Match the pinned extractor's fragment/path id extraction, then encode it as a
            // single query value so it cannot alter the API request structure.
            val id = input.substringAfterLast('#').substringAfter('/')
            if (id.isEmpty() || id.length > MAX_QUERY_ID_CHARS || id.any { it.isISOControl() }) {
                return null
            }
            val encodedId = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
            val origin = "https://$host"
            val apiUrl = "$origin/api/v1/video?id=$encodedId"
            if (apiUrl.length !in 1..MAX_API_URL_CHARS) return null
            ApiRequest(origin, apiUrl)
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptPayload(encoded: String): JSONObject? {
        if (encoded.length !in MIN_CIPHERTEXT_HEX_CHARS..MAX_RESPONSE_HEX_CHARS ||
            encoded.length % 2 != 0
        ) return null
        val ciphertext = decodeHex(encoded) ?: return null
        if (ciphertext.isEmpty() || ciphertext.size % AES_BLOCK_BYTES != 0) return null

        for (iv in IVS) {
            try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(AES_KEY.toByteArray(StandardCharsets.UTF_8), "AES"),
                    IvParameterSpec(iv.toByteArray(StandardCharsets.UTF_8)),
                )
                val plaintext = cipher.doFinal(ciphertext)
                if (plaintext.size > MAX_DECRYPTED_BYTES) continue
                val decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext))
                    .toString()
                return JSONObject(decoded)
            } catch (_: Exception) {
                // Try the other pinned IV; no cipher or response details are logged.
            }
        }
        return null
    }

    private fun decodeHex(value: String): ByteArray? {
        if (value.length % 2 != 0 || value.length > MAX_RESPONSE_HEX_CHARS) return null
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val high = hexNibble(value[index * 2])
            val low = hexNibble(value[index * 2 + 1])
            if (high < 0 || low < 0) return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private fun hexNibble(value: Char): Int = when (value) {
        in '0'..'9' -> value.code - '0'.code
        in 'a'..'f' -> value.code - 'a'.code + 10
        in 'A'..'F' -> value.code - 'A'.code + 10
        else -> -1
    }

    private fun resolveOutputUrl(origin: String, value: String): String? {
        if (value.isEmpty() || value.length > MAX_OUTPUT_URL_CHARS ||
            value.any { it.isISOControl() }
        ) return null
        return try {
            URI(origin + "/").resolve(URI(value)).normalize().toASCIIString()
                .takeIf { it.length in 1..MAX_OUTPUT_URL_CHARS }
        } catch (_: Exception) {
            null
        }
    }

    private data class ApiRequest(val origin: String, val apiUrl: String)

    private companion object {
        const val VIDSTACK_NAME = "Vidstack"
        const val VIDSTACK_ORIGIN = "https://vidstack.io"
        const val AES_KEY = "kiemtienmua911ca"
        const val UNKNOWN_QUALITY = 400
        const val MAX_EXTRACTION_MILLIS = 20_000L
        const val MAX_INPUT_URL_CHARS = 4_096
        const val MAX_API_URL_CHARS = 2_048
        const val MAX_QUERY_ID_CHARS = 512
        const val MAX_OUTPUT_URL_CHARS = 4_096
        const val MAX_LABEL_CHARS = 128
        const val MAX_SUBTITLES = 32
        const val MIN_CIPHERTEXT_HEX_CHARS = 32
        const val MAX_RESPONSE_HEX_CHARS = 256 * 1024
        const val MAX_DECRYPTED_BYTES = 128 * 1024
        const val AES_BLOCK_BYTES = 16
        val IVS = listOf("1234567890oiuytr", "0123456789abcdef")
        val VIDSTACK_ALLOWED_HOSTS = setOf("vidstack.io", "server1.uns.bio")
    }
}

/** Alias preserved from both pinned upstream baselines. */
class Server1uns : VidStack() {
    @Volatile private var aliasName = "Vidstack"
    @Volatile private var aliasMainUrl = SERVER1UNS_DEFAULT_ORIGIN
    @Volatile private var refererRequired = true

    override fun getName(): String = aliasName

    override fun setName(value: String) {
        if (value.length in 1..128 && value.none { it.isISOControl() }) aliasName = value
    }

    override fun getMainUrl(): String = aliasMainUrl

    override fun setMainUrl(value: String) {
        normalizeConfiguredOrigin(value, setOf("server1.uns.bio"))?.let { aliasMainUrl = it }
    }

    override fun getRequiresReferer(): Boolean = refererRequired

    fun setRequiresReferer(value: Boolean) {
        refererRequired = value
    }
}

private const val SERVER1UNS_DEFAULT_ORIGIN = "https://server1.uns.bio"

private fun normalizeConfiguredOrigin(value: String, hosts: Set<String>): String? {
    if (value.length !in 1..2_048 || value != value.trim()) return null
    return try {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https" || uri.rawUserInfo != null || uri.rawQuery != null ||
            uri.rawFragment != null || (uri.rawPath != null && uri.rawPath != "/") ||
            (uri.port != -1 && uri.port != 443) || host !in hosts || host.endsWith('.')
        ) return null
        "https://$host"
    } catch (_: Exception) {
        null
    }
}
