package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.MainActivityKt
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorApiKt
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * Resolves the single-file playback route used by CloudStream's official
 * InternetArchiveProvider. Requests use the normal CloudStream `app` client.
 */
class InternetArchiveExtractor : ExtractorApi() {
    override fun getName(): String = EXTRACTOR_NAME
    override fun getMainUrl(): String = MAIN_URL
    override fun getRequiresReferer(): Boolean = false

    @Suppress("UNCHECKED_CAST")
    override fun getUrl(url: String, referer: String?, continuation: Continuation<*>): Any? {
        val extraction: suspend () -> List<ExtractorLink>? = { extract(url) }
        return extraction.startCoroutineUninterceptedOrReturn(
            continuation as Continuation<List<ExtractorLink>?>,
        )
    }

    private suspend fun extract(inputUrl: String): List<ExtractorLink>? {
        return try {
            withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
                currentCoroutineContext().ensureActive()
                val identifier = identifierFromDetailsUrl(inputUrl)
                    ?: return@withTimeoutOrNull null

                // The identifier grammar is deliberately narrow, so it can only occupy one path
                // segment on the fixed Internet Archive metadata endpoint.
                val metadataUrl = "$MAIN_URL/metadata/$identifier"
                val response = MainActivityKt.getApp().get(
                    url = metadataUrl,
                    headers = mapOf("Accept" to "application/json"),
                )
                val body = response.text
                if (body.isEmpty() || body.length > MAX_METADATA_CHARS) {
                    return@withTimeoutOrNull null
                }
                currentCoroutineContext().ensureActive()

                val metadata = JSONObject(body)
                val metadataIdentifier = metadata.optJSONObject("metadata")
                    ?.optString("identifier")
                    ?: return@withTimeoutOrNull null
                if (metadataIdentifier != identifier) return@withTimeoutOrNull null
                val storageServer = safeStorageServer(metadata.optString("server"))
                    ?: return@withTimeoutOrNull null
                val storageShard = safeStorageShard(metadata.optString("dir"), identifier)
                    ?: return@withTimeoutOrNull null
                val files = metadata.optJSONArray("files")
                    ?: return@withTimeoutOrNull null
                val links = ArrayList<ExtractorLink>(minOf(files.length(), MAX_LINKS))
                for (index in 0 until files.length()) {
                    currentCoroutineContext().ensureActive()
                    val file = files.optJSONObject(index) ?: continue
                    val fileName = safeFileName(file.optString("name")) ?: continue
                    if (file.optString("private").equals("true", ignoreCase = true) ||
                        file.optString("private") == "1"
                    ) continue

                    val format = file.optString("format")
                    if (!isVideoFile(fileName, format)) continue

                    val quality = quality(file.optInt("height", 0), fileName)
                    val downloadUrl = directStorageUrl(storageServer, storageShard, identifier, fileName)
                        ?: continue
                    val suffix = quality.takeIf { it != UNKNOWN_QUALITY }
                        ?.let { "${it}p" }
                        ?: "Video"
                    links += ExtractorLink(
                        EXTRACTOR_NAME,
                        "Internet Archive · $suffix · $fileName",
                        downloadUrl,
                        inputUrl,
                        quality,
                        false,
                        emptyMap(),
                        null,
                    )
                    if (links.size >= MAX_LINKS) break
                }

                links.takeIf { it.isNotEmpty() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun identifierFromDetailsUrl(value: String): String? {
        if (value.length !in 1..MAX_URL_CHARS || value != value.trim() ||
            value.any { it.isISOControl() } || '\\' in value
        ) return null

        return try {
            val uri = URI(value)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val authority = uri.rawAuthority ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                (host != "archive.org" && host != "www.archive.org") ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                !(authority.equals(host, ignoreCase = true) ||
                    authority.equals("$host:443", ignoreCase = true))
            ) return null

            val match = DETAILS_PATH.matchEntire(uri.rawPath.orEmpty()) ?: return null
            match.groupValues[1].takeUnless { it == "." || it == ".." }
        } catch (_: Exception) {
            null
        }
    }

    private fun safeFileName(value: String): String? {
        if (value.isEmpty() || value.length > MAX_FILE_NAME_CHARS ||
            value == "." || value == ".." || '/' in value || '\\' in value ||
            value.any { it.isISOControl() }
        ) return null
        return value
    }

    private fun isVideoFile(fileName: String, format: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        val extensionMatches = extension in VIDEO_EXTENSIONS
        val formatMatches = format.contains("MPEG", ignoreCase = true) ||
            format.startsWith("H.264", ignoreCase = true) ||
            format.startsWith("Matroska", ignoreCase = true) ||
            format.startsWith("DivX", ignoreCase = true) ||
            format.startsWith("Ogg Video", ignoreCase = true)
        return extensionMatches || formatMatches
    }

    private fun quality(height: Int, fileName: String): Int {
        val resolution = if (height > 0) height else RESOLUTION_PATTERN
            .find(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return if (resolution > 0) {
            ExtractorApiKt.getQualityFromName("${resolution}p")
        } else {
            ExtractorApiKt.getQualityFromName(null)
        }
    }

    private fun safeStorageServer(value: String): String? {
        if (value.length !in 1..MAX_STORAGE_HOST_CHARS || value != value.trim()) return null
        return value.lowercase(Locale.ROOT).takeIf(STORAGE_HOST::matches)
    }

    private fun safeStorageShard(directory: String, identifier: String): String? {
        if (directory.length !in 1..MAX_STORAGE_DIRECTORY_CHARS ||
            directory.any { it.isISOControl() } || '\\' in directory
        ) return null
        val match = STORAGE_DIRECTORY.matchEntire(directory) ?: return null
        if (match.groupValues[2] != identifier) return null
        return match.groupValues[1]
    }

    private fun directStorageUrl(
        server: String,
        shard: String,
        identifier: String,
        fileName: String,
    ): String? {
        return try {
            val base = "https://$server".toHttpUrl()
            base.newBuilder()
                .addPathSegment(shard)
                .addPathSegment("items")
                .addPathSegment(identifier)
                .addPathSegment(fileName)
                .build()
                .toString()
                .takeIf {
                    it.length <= MAX_URL_CHARS &&
                        ExtractorApiKt.isBoundedExtractorHttpUrl(it)
                }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val EXTRACTOR_NAME = "Internet Archive"
        const val MAIN_URL = "https://archive.org"
        const val MAX_URL_CHARS = 4_096
        const val MAX_FILE_NAME_CHARS = 512
        const val MAX_METADATA_CHARS = 1_048_576
        const val MAX_STORAGE_HOST_CHARS = 64
        const val MAX_STORAGE_DIRECTORY_CHARS = 256
        const val MAX_LINKS = 12
        const val EXTRACTION_TIMEOUT_MS = 25_000L
        const val UNKNOWN_QUALITY = 400
        val DETAILS_PATH = Regex("^/details/([A-Za-z0-9_.-]{1,128})/?$")
        val STORAGE_HOST = Regex("^(?:ia|dn)[0-9]{6}\\.us\\.archive\\.org$", RegexOption.IGNORE_CASE)
        val STORAGE_DIRECTORY = Regex("^/([0-9]{1,4})/items/([A-Za-z0-9_.-]{1,128})$")
        val RESOLUTION_PATTERN = Regex("""(?:^|[._-])(\d{3,4})p(?:[._-]|$)""", RegexOption.IGNORE_CASE)
        val VIDEO_EXTENSIONS = setOf(
            "avi", "flv", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "ogv", "webm", "wmv",
        )
    }
}
