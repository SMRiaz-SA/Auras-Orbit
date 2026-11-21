package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fleeksoft.ksoup.Ksoup
import com.lagradost.cloudstream3.MainAPIKt
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorApiKt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.fetchVidHideProPage
import com.lagradost.cloudstream3.utils.isStrictHttpsUrl
import com.lagradost.cloudstream3.utils.normalizeVidHideProOrigin
import com.lagradost.cloudstream3.utils.normalizeVidHideProPageUrl
import com.lagradost.cloudstream3.utils.validatePublicExtractorMediaUrl
import com.lagradost.cloudstream3.utils.withVoePlaylistCookiesDisabled
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Orbit-owned, bounded implementation of the byte-identical pinned VidHidePro sources. */
open class VidHidePro : ExtractorApi() {
    @Volatile private var currentName = DEFAULT_NAME
    @Volatile private var currentMainUrl = DEFAULT_MAIN_URL
    @Volatile private var nameMutable = false
    @Volatile private var mainUrlMutable = false

    override fun getName(): String = currentName

    /** Retains the pinned public metadata setter for plugin subclasses and Java callers. */
    open fun setName(value: String) {
        if (nameMutable && value.length in 1..MAX_LABEL_CHARS &&
            value.none { it.isISOControl() }
        ) {
            currentName = value
        }
    }

    /** Initializes an alias's mutable metadata through its validated shared state. */
    protected fun initializeMetadata(name: String? = null, mainUrl: String? = null) {
        if (name != null && name.length in 1..MAX_LABEL_CHARS && name.none { it.isISOControl() }) {
            currentName = name
            nameMutable = true
        }
        if (mainUrl != null) {
            normalizeVidHideProOrigin(mainUrl)?.let {
                currentMainUrl = it
                mainUrlMutable = true
            }
        }
    }

    override fun getMainUrl(): String = currentMainUrl

    /** Accepts any valid HTTPS origin; repository subclasses are not curated by host. */
    open fun setMainUrl(value: String) {
        if (!mainUrlMutable) return
        normalizeVidHideProOrigin(value)?.let { currentMainUrl = it }
    }

    override fun getRequiresReferer(): Boolean = true

    /** Preserves the pinned suspend callback descriptor while keeping IO inside Orbit bounds. */
    @Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER")
    override fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        continuation: Continuation<*>,
    ): Any? {
        val extraction: suspend () -> Unit = {
            val result = extract(url, referer)
            currentCoroutineContext().ensureActive()
            for (link in result.links) {
                currentCoroutineContext().ensureActive()
                callback(link)
            }
            for (subtitle in result.subtitles) {
                currentCoroutineContext().ensureActive()
                subtitleCallback(subtitle)
            }
        }
        return extraction.startCoroutineUninterceptedOrReturn(
            continuation as Continuation<Unit>
        )
    }

    private suspend fun extract(inputUrl: String, referer: String?): ExtractionResult {
        return try {
            withTimeoutOrNull(MAX_EXTRACTION_MILLIS) {
                currentCoroutineContext().ensureActive()
                val extractorName = getName().takeIf(::isSafeLabel) ?: return@withTimeoutOrNull EMPTY_RESULT
                val configuredMainUrl = getMainUrl()
                val origin = normalizeVidHideProOrigin(configuredMainUrl)
                    ?: return@withTimeoutOrNull EMPTY_RESULT
                val pageUrl = normalizeVidHideProPageUrl(inputUrl, configuredMainUrl)
                    ?: return@withTimeoutOrNull EMPTY_RESULT
                val html = fetchVidHideProPage(pageUrl, referer, configuredMainUrl)
                    ?: return@withTimeoutOrNull EMPTY_RESULT
                if (html.length > MAX_PAGE_CHARS) return@withTimeoutOrNull EMPTY_RESULT
                currentCoroutineContext().ensureActive()

                val script = findPlayerScript(html) ?: return@withTimeoutOrNull EMPTY_RESULT
                val headers = mapOf(
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "Origin" to origin,
                    "User-Agent" to MainAPIKt.USER_AGENT,
                )
                val links = ArrayList<ExtractorLink>()
                val seenLinks = HashSet<String>()
                var hlsSources = 0
                var processedSourceCandidates = 0

                val sources = parseEntries(script, "sources")
                val sourceFiles = sources.mapNotNull { it.file }
                    .take(MAX_SOURCE_CANDIDATES)
                suspend fun processCandidates(
                    candidates: List<String>,
                    hlsReferer: (String) -> String,
                    hlsLimit: Int,
                ) {
                    for (rawSource in candidates.take(MAX_SOURCE_CANDIDATES)) {
                        if (links.size >= MAX_RETURNED_LINKS ||
                            processedSourceCandidates >= MAX_TOTAL_SOURCE_CANDIDATES
                        ) break
                        processedSourceCandidates++
                        currentCoroutineContext().ensureActive()
                        val absolute = resolveOutputUrl(origin, rawSource) ?: continue
                        val mediaUrl = validatePublicExtractorMediaUrl(absolute, requireHttps = true)
                            ?: continue
                        val isHls = mediaUrl.contains(".m3u8", ignoreCase = true) ||
                            mediaUrl.contains(".txt", ignoreCase = true)
                        if (!isHls) {
                            if (seenLinks.add(mediaUrl)) {
                                links += ExtractorLink(
                                    extractorName,
                                    extractorName,
                                    mediaUrl,
                                    mediaUrl,
                                    UNKNOWN_QUALITY,
                                    headers,
                                    null,
                                    null,
                                    emptyList(),
                                )
                            }
                            continue
                        }

                        // The legacy JW helper performs unbounded network access. Use Orbit's
                        // bounded HTTPS playlist expander, with cookies disabled for this flow.
                        if (hlsSources >= hlsLimit) continue
                        hlsSources++
                        val expanded = withVoePlaylistCookiesDisabled {
                            M3u8Helper.generateM3u8Https(
                                extractorName,
                                mediaUrl,
                                hlsReferer(mediaUrl),
                                UNKNOWN_QUALITY,
                                headers,
                                extractorName,
                            )
                        }
                        for (link in expanded) {
                            if (links.size >= MAX_RETURNED_LINKS) break
                            currentCoroutineContext().ensureActive()
                            if (link.url.length !in 1..MAX_OUTPUT_URL_CHARS ||
                                !isStrictHttpsUrl(link.url) || !seenLinks.add(link.url)
                            ) continue
                            // M3u8Helper has already performed bounded public-address validation
                            // on the master playlist and every returned variant.
                            links += link
                        }
                    }
                }

                // Keep one bounded playlist expansion available for the pinned post-source
                // fallback when source entries are invalid or expand to no playable links.
                processCandidates(sourceFiles, { origin }, MAX_HLS_SOURCES - 1)
                if (links.isEmpty()) {
                    processCandidates(
                        findHlsFallbacks(script),
                        { mediaUrl -> mediaUrl },
                        MAX_HLS_SOURCES,
                    )
                }

                val subtitles = ArrayList<SubtitleFile>()
                val seenSubtitles = HashSet<String>()
                for (entry in parseEntries(script, "tracks")) {
                    if (subtitles.size >= MAX_RETURNED_SUBTITLES) break
                    val kind = entry.kind.orEmpty().lowercase(Locale.ROOT)
                    val label = entry.label?.trim().orEmpty()
                    if (!(kind.contains("caption") || kind.contains("subtitle")) ||
                        !isSafeLabel(label)
                    ) continue
                    currentCoroutineContext().ensureActive()
                    val absolute = resolveOutputUrl(origin, entry.file ?: continue) ?: continue
                    val subtitleUrl = validatePublicExtractorMediaUrl(absolute, requireHttps = true)
                        ?: continue
                    if (seenSubtitles.add(subtitleUrl)) {
                        subtitles += SubtitleFile(label, subtitleUrl)
                    }
                }

                ExtractionResult(links.take(MAX_RETURNED_LINKS), subtitles.take(MAX_RETURNED_SUBTITLES))
            } ?: EMPTY_RESULT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EMPTY_RESULT
        }
    }

    private fun findPlayerScript(html: String): String? {
        if (html.length > MAX_PAGE_CHARS) return null
        val packed = ExtractorApiKt.getPacked(html)
        if (!packed.isNullOrEmpty()) {
            val unpacked = JsUnpacker(packed).unpack()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_SCRIPT_CHARS }
            if (unpacked != null) return unpacked
        }

        val document = Ksoup.parse(html)
        for (element in document.select("script").take(MAX_SCRIPT_ELEMENTS)) {
            val script = element.data().takeIf { it.isNotEmpty() } ?: element.html()
            if (script.length <= MAX_SCRIPT_CHARS && SOURCES_MARKER.containsMatchIn(script)) {
                return script
            }
        }
        return null
    }

    private fun parseEntries(script: String, property: String): List<PlayerEntry> {
        if (script.length > MAX_SCRIPT_CHARS) return emptyList()
        val entries = ArrayList<PlayerEntry>()
        for (array in findJsonArrays(script, property)) {
            if (entries.size >= MAX_PLAYER_ENTRIES) break
            val json = quoteBareProperties(array) ?: continue
            try {
                JsonFactory().createParser(json).use { parser ->
                    if (parser.nextToken() != JsonToken.START_ARRAY) return@use
                    var itemCount = 0
                    while (true) {
                        val token = parser.nextToken() ?: break
                        if (token == JsonToken.END_ARRAY || itemCount >= MAX_PLAYER_ENTRIES) break
                        itemCount++
                        if (token != JsonToken.START_OBJECT) {
                            parser.skipChildren()
                            continue
                        }

                        var file: String? = null
                        var label: String? = null
                        var kind: String? = null
                        var fieldCount = 0
                        while (true) {
                            val fieldToken = parser.nextToken() ?: break
                            if (fieldToken == JsonToken.END_OBJECT) break
                            if (fieldToken != JsonToken.FIELD_NAME ||
                                ++fieldCount > MAX_FIELDS_PER_ENTRY
                            ) break
                            val field = parser.currentName
                            val valueToken = parser.nextToken() ?: break
                            if (valueToken == JsonToken.VALUE_STRING &&
                                parser.textLength <= MAX_OUTPUT_URL_CHARS
                            ) {
                                val value = parser.text
                                when (field) {
                                    "file" -> file = value
                                    "label" -> label = value.take(MAX_LABEL_CHARS)
                                    "kind" -> kind = value.take(MAX_LABEL_CHARS)
                                }
                            } else {
                                parser.skipChildren()
                            }
                        }
                        if (!file.isNullOrBlank()) entries += PlayerEntry(file, label, kind)
                    }
                }
            } catch (_: Exception) {
                // One malformed player array does not prevent bounded later arrays from parsing.
            }
        }
        return entries.take(MAX_PLAYER_ENTRIES)
    }

    private fun findJsonArrays(script: String, property: String): List<String> {
        val marker = Regex("""[\"']?${Regex.escape(property)}[\"']?\s*:\s*\[""")
        val arrays = ArrayList<String>()
        for (match in marker.findAll(script).take(MAX_PLAYER_ARRAYS)) {
            val start = match.range.last
            val end = findArrayEnd(script, start) ?: continue
            if (end - start + 1 <= MAX_JSON_ARRAY_CHARS) {
                arrays += script.substring(start, end + 1)
            }
        }
        return arrays
    }

    private fun findArrayEnd(text: String, start: Int): Int? {
        val limit = minOf(text.length, start + MAX_JSON_ARRAY_CHARS)
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in start until limit) {
            val character = text[index]
            if (quote != null) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == quote) quote = null
                continue
            }
            when (character) {
                '"', '\'' -> quote = character
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    /** Quotes bare JavaScript object keys without touching quoted string values. */
    private fun quoteBareProperties(value: String): String? {
        if (value.length > MAX_JSON_ARRAY_CHARS) return null
        val output = StringBuilder(value.length + 32)
        var index = 0
        var quote: Char? = null
        var escaped = false
        while (index < value.length) {
            val character = value[index]
            if (quote != null) {
                output.append(character)
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == quote) quote = null
                index++
                continue
            }
            if (character == '"' || character == '\'') {
                quote = character
                output.append(character)
                index++
                continue
            }
            if (character.isLetter() || character == '_' || character == '$') {
                val start = index
                index++
                while (index < value.length &&
                    (value[index].isLetterOrDigit() || value[index] == '_' || value[index] == '$')
                ) index++
                val token = value.substring(start, index)
                var after = index
                while (after < value.length && value[after].isWhitespace()) after++
                if (after < value.length && value[after] == ':') output.append('"').append(token).append('"')
                else output.append(token)
                continue
            }
            output.append(character)
            index++
        }
        return output.toString()
    }

    private fun findHlsFallbacks(script: String): List<String> {
        if (script.length > MAX_SCRIPT_CHARS) return emptyList()
        return HLS_FALLBACK.findAll(script)
            .take(MAX_SOURCE_CANDIDATES)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .distinct()
            .toList()
    }

    private fun resolveOutputUrl(origin: String, rawValue: String): String? {
        val candidate = rawValue.replace("\\/", "/").trim()
        if (candidate.isEmpty() || candidate.length > MAX_OUTPUT_URL_CHARS ||
            candidate.any { it.isISOControl() }
        ) return null
        return try {
            URI("$origin/").resolve(URI(candidate)).normalize().toASCIIString()
                .takeIf { it.length in 1..MAX_OUTPUT_URL_CHARS }
        } catch (_: Exception) {
            null
        }
    }

    private fun isSafeLabel(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_LABEL_CHARS && value.none { it.isISOControl() }

    private data class PlayerEntry(val file: String?, val label: String?, val kind: String?)
    private data class ExtractionResult(
        val links: List<ExtractorLink>,
        val subtitles: List<SubtitleFile> = emptyList(),
    )

    private companion object {
        const val DEFAULT_NAME = "VidHidePro"
        const val DEFAULT_MAIN_URL = "https://vidhidepro.com"
        const val MAX_EXTRACTION_MILLIS = 25_000L
        const val MAX_PAGE_CHARS = 512 * 1024
        const val MAX_SCRIPT_CHARS = 512 * 1024
        const val MAX_JSON_ARRAY_CHARS = 128 * 1024
        const val MAX_PLAYER_ARRAYS = 16
        const val MAX_PLAYER_ENTRIES = 64
        const val MAX_FIELDS_PER_ENTRY = 32
        const val MAX_SCRIPT_ELEMENTS = 64
        const val MAX_SOURCE_CANDIDATES = 32
        const val MAX_TOTAL_SOURCE_CANDIDATES = 64
        const val MAX_HLS_SOURCES = 4
        const val MAX_RETURNED_LINKS = 32
        const val MAX_RETURNED_SUBTITLES = 16
        const val MAX_OUTPUT_URL_CHARS = 4_096
        const val MAX_LABEL_CHARS = 128
        const val UNKNOWN_QUALITY = 400
        val SOURCES_MARKER = Regex("""\bsources\s*:""", RegexOption.IGNORE_CASE)
        val HLS_FALLBACK = Regex(
            """[:=]\s*[\"']([^\"'\s]+(?:\.m3u8|master\.txt)[^\"'\s]*)[\"']""",
            RegexOption.IGNORE_CASE,
        )
        val EMPTY_RESULT = ExtractionResult(emptyList())
    }
}

/** Pinned first-party aliases in the order used by the default registry. */
class VidHidePro1 : VidHidePro() {
    init { initializeMetadata(mainUrl = "https://filelions.live") }
}
class VidHidePro2 : VidHidePro() {
    init { initializeMetadata(mainUrl = "https://filelions.online") }
}
class VidHidePro3 : VidHidePro() {
    init { initializeMetadata(mainUrl = "https://filelions.to") }
}
class VidHidePro4 : VidHidePro() { override fun getMainUrl() = "https://kinoger.be" }
class VidHidePro5 : VidHidePro() { override fun getMainUrl() = "https://vidhidevip.com" }
class VidHidePro6 : VidHidePro() { override fun getMainUrl() = "https://vidhidepre.com" }
class VidHideHub : VidHidePro() {
    init { initializeMetadata(mainUrl = "https://vidhidehub.com") }
}
class Ryderjet : VidHidePro() {
    init { initializeMetadata(mainUrl = "https://ryderjet.com") }
}

class Dhtpre : VidHidePro() {
    init { initializeMetadata(name = "EarnVids", mainUrl = "https://dhtpre.com") }
}

class HgplayCDN : VidHidePro() {
    override fun getName() = "CineMM"
    override fun getMainUrl() = "https://hgplaycdn.com"
}
class Habetar : VidHidePro() {
    override fun getName() = "CineMM"
    override fun getMainUrl() = "https://habetar.com"
}
class Yuguaab : VidHidePro() {
    override fun getName() = "CineMM"
    override fun getMainUrl() = "https://yuguaab.com"
}
class Guxhag : VidHidePro() {
    override fun getName() = "CineMM"
    override fun getMainUrl() = "https://guxhag.com"
}
class Auvexiug : VidHidePro() {
    override fun getName() = "CineMM"
    override fun getMainUrl() = "https://auvexiug.com"
}
class Xenolyzb : VidHidePro() {
    override fun getName() = "CineMM"
    override fun getMainUrl() = "https://xenolyzb.com"
}
class Haxloppd : VidHidePro() {
    override fun getName() = "CineMM"
    override fun getMainUrl() = "https://haxloppd.com"
}
class Cavanhabg : VidHidePro() {
    override fun getName() = "CineMM"
    override fun getMainUrl() = "https://cavanhabg.com"
}
class Dumbalag : VidHidePro() {
    override fun getName() = "CineMM"
    override fun getMainUrl() = "https://dumbalag.com"
}
class Uasopt : VidHidePro() {
    override fun getName() = "CineMM"
    override fun getMainUrl() = "https://uasopt.com"
}

class Smoothpre : VidHidePro() {
    init { initializeMetadata(name = "EarnVids", mainUrl = "https://smoothpre.com") }
}
class Peytonepre : VidHidePro() {
    init { initializeMetadata(name = "EarnVids", mainUrl = "https://peytonepre.com") }
}
