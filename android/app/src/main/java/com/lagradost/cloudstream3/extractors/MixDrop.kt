package com.lagradost.cloudstream3.extractors

// Behavior/API reference: CloudStream library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/MixDrop.kt at 5024ace2922a148d38df2a816b483b529789cf58 (GPL-3.0).

import com.lagradost.cloudstream3.utils.getPinnedExtractorResponse
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorApiKt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.validatePublicExtractorMediaUrl
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Orbit-owned MixDrop family implementation matching the pinned extractor behavior. */
open class MixDrop : ExtractorApi() {
    private var currentName = "MixDrop"
    private var currentMainUrl = "https://mixdrop.co"

    override fun getName(): String = currentName
    open fun setName(value: String) { currentName = requireNotNull(value) }
    override fun getMainUrl(): String = currentMainUrl
    open fun setMainUrl(value: String) { currentMainUrl = requireNotNull(value) }
    override fun getRequiresReferer(): Boolean = false

    override fun getExtractorUrl(id: String): String = "${getMainUrl()}/e/$id"

    @Suppress("UNCHECKED_CAST")
    override fun getUrl(url: String, referer: String?, continuation: Continuation<*>): Any? {
        val extraction: suspend () -> List<ExtractorLink>? = { extract(url) }
        return extraction.startCoroutineUninterceptedOrReturn(
            continuation as Continuation<List<ExtractorLink>?>
        )
    }

    private suspend fun extract(url: String): List<ExtractorLink>? {
        return withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
            val target = url.replaceFirst("/f/", "/e/")
            val response = getPinnedExtractorResponse(target)
            val unpacked = ExtractorApiKt.getAndUnpack(response.body)
            val link = SOURCE_REGEX.find(unpacked)?.groupValues?.getOrNull(1) ?: return@withTimeoutOrNull null
            val mediaUrl = ExtractorApiKt.httpsify(link)
                ?.let { validatePublicExtractorMediaUrl(it) }
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
        val SOURCE_REGEX = Regex("""wurl.*?=.*?"(.*?)";""")
        const val EXTRACTION_TIMEOUT_MS = 30_000L
    }
}

class MixDropPs : MixDrop() {
    override fun getMainUrl(): String = mainUrl
    private var mainUrl = "https://mixdrop.ps"
    override fun setMainUrl(value: String) { mainUrl = requireNotNull(value) }
}

class Mdy : MixDrop() {
    override fun getMainUrl(): String = mainUrl
    private var mainUrl = "https://mdy48tn97.com"
    override fun setMainUrl(value: String) { mainUrl = requireNotNull(value) }
}

class MxDropTo : MixDrop() {
    override fun getMainUrl(): String = mainUrl
    private var mainUrl = "https://mxdrop.to"
    override fun setMainUrl(value: String) { mainUrl = requireNotNull(value) }
}

class MixDropSi : MixDrop() {
    override fun getMainUrl(): String = mainUrl
    private var mainUrl = "https://mixdrop.si"
    override fun setMainUrl(value: String) { mainUrl = requireNotNull(value) }
}

class MixDropBz : MixDrop() {
    override fun getMainUrl(): String = mainUrl
    private var mainUrl = "https://mixdrop.bz"
    override fun setMainUrl(value: String) { mainUrl = requireNotNull(value) }
}

class MixDropAg : MixDrop() {
    override fun getMainUrl(): String = mainUrl
    private var mainUrl = "https://mixdrop.ag"
    override fun setMainUrl(value: String) { mainUrl = requireNotNull(value) }
}

class MixDropCh : MixDrop() {
    override fun getMainUrl(): String = mainUrl
    private var mainUrl = "https://mixdrop.ch"
    override fun setMainUrl(value: String) { mainUrl = requireNotNull(value) }
}

class MixDropTo : MixDrop() {
    override fun getMainUrl(): String = mainUrl
    private var mainUrl = "https://mixdrop.to"
    override fun setMainUrl(value: String) { mainUrl = requireNotNull(value) }
}

