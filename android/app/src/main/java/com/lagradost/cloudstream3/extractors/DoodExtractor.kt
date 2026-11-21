package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorApiKt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.fetchDoodStreamPage
import com.lagradost.cloudstream3.utils.validatePublicExtractorMediaUrl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.random.Random

/**
 * Orbit-owned bounded DoodStream implementation matching the behavior in CloudStream
 * pre-release 5024ace2922a148d38df2a816b483b529789cf58 and stable v4.8.0
 * (3796406e636356f556c16e04e1da4e37d6a19038; upstream GPL-3.0).
 */
open class DoodLaExtractor : ExtractorApi() {
    private var currentName = "DoodStream"
    private var currentMainUrl = "https://dood.la"

    override fun getName(): String = currentName

    open fun setName(value: String) {
        currentName = value
    }

    override fun getMainUrl(): String = currentMainUrl

    open fun setMainUrl(value: String) {
        currentMainUrl = value
    }

    override fun getRequiresReferer(): Boolean = false

    /** Keep the pinned callback-form JVM descriptor used by StreamPlay. */
    @Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER")
    override fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        continuation: Continuation<*>,
    ): Any? {
        val extraction: suspend () -> Unit = {
            withTimeoutOrNull(MAX_EXTRACTION_MILLIS) {
                currentCoroutineContext().ensureActive()
                val extractorName = getName()
                val registeredMainUrl = getMainUrl()
                val pageUrl = normalizeEmbedUrl(url) ?: return@withTimeoutOrNull
                val response = fetchDoodStreamPage(pageUrl, registeredMainUrl)
                    ?: return@withTimeoutOrNull
                val mediaUrl = response.mediaBase + randomSuffix() + "?token=" + response.token
                val safeMediaUrl = validatePublicExtractorMediaUrl(mediaUrl)
                    ?: return@withTimeoutOrNull

                val title = response.html.substringAfter("<title>", "")
                    .substringBefore("</title>")
                val qualityName = QUALITY_PATTERN.find(title)?.value
                callback(
                    ExtractorLink(
                        extractorName,
                        extractorName,
                        safeMediaUrl,
                        response.referer,
                        ExtractorApiKt.getQualityFromName(qualityName),
                        emptyMap(),
                        null,
                        null,
                        emptyList(),
                    )
                )
            }
        }

        return extraction.startCoroutineUninterceptedOrReturn(
            continuation as Continuation<Unit>
        )
    }

    private fun normalizeEmbedUrl(value: String): String? {
        if (value.length !in 1..MAX_URL_CHARS) return null
        val candidate = value.trim()
        if (candidate.isEmpty() || candidate.length > MAX_URL_CHARS) return null
        // Preserve the pinned /d/ -> /e/ transformation while keeping all URL policy in the
        // bounded resolver, which also checks the registered mainUrl host and public DNS.
        return candidate.replace("/d/", "/e/")
    }

    private fun randomSuffix(): String = buildString(RANDOM_SUFFIX_LENGTH) {
        repeat(RANDOM_SUFFIX_LENGTH) {
            append(RANDOM_ALPHABET[Random.nextInt(RANDOM_ALPHABET.length)])
        }
    }

    private companion object {
        const val MAX_URL_CHARS = 4_096
        const val MAX_EXTRACTION_MILLIS = 30_000L
        const val RANDOM_SUFFIX_LENGTH = 10
        const val RANDOM_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val QUALITY_PATTERN = Regex("\\d{3,4}[pP]")
    }
}

/** The stable Dood YT alias referenced by StreamPlay v694. */
class DoodYtExtractor : DoodLaExtractor() {
    private var aliasMainUrl = "https://dood.yt"

    override fun getMainUrl(): String = aliasMainUrl

    override fun setMainUrl(value: String) {
        aliasMainUrl = value
    }
}
