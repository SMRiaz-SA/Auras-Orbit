package com.lagradost.cloudstream3.desktop.player

import com.lagradost.cloudstream3.desktop.subtitles.SubtitlePipeline
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleEntity
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.net.readBoundedBytes
import com.lagradost.runtime.executor.SafePluginInvoker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object SubtitleExtractionService {
    private const val TAG = "SubtitleExtractionService"
    private const val MAX_SUBTITLE_BYTES = 32 * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 256
    private const val MAX_PLAYLIST_SEGMENTS = 512

    private suspend fun fetchSubtitleBytes(url: String): ByteArray =
        com.lagradost.cloudstream3.app.get(url, timeout = 15000L).okhttpResponse.use { response ->
            check(response.isSuccessful) { "Subtitle service returned HTTP ${response.code}" }
            response.body.byteStream().readBoundedBytes(MAX_SUBTITLE_BYTES)
        }

    suspend fun searchSubtitles(
        query: String,
        lang: String?,
        season: Int?,
        episode: Int?,
    ): List<Map<String, Any?>> = SubtitlePipeline.searchSubtitles(
        query = query,
        lang = lang,
        season = season,
        episode = episode,
    )

    suspend fun downloadAndExtractSubtitle(
        idPrefix: String,
        data: String,
        name: String,
        lang: String,
        source: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val provider = AccountManager.subtitleProviders.firstOrNull { it.idPrefix == idPrefix }
            val auth = AccountManager.cachedAccounts[idPrefix]?.firstOrNull()

            val sub = SubtitleEntity(
                idPrefix = idPrefix,
                name = name,
                data = data,
                lang = lang,
                source = source,
            )

            val fileUrl = if (provider != null) {
                SafePluginInvoker.invokeOrNull(
                    tag = "SubLoad:${provider.name}",
                    providerName = provider.name,
                    timeoutMs = SafePluginInvoker.TIMEOUT_LOAD_MS,
                ) {
                    provider.load(auth, sub)
                }
            } else {
                data.takeIf { it.startsWith("http", ignoreCase = true) || it.startsWith("file:", ignoreCase = true) }
            }

            if (fileUrl == null) {
                AppLogger.w(TAG, "Failed to resolve download URL for subtitle '$name' (idPrefix=$idPrefix)")
                return@withContext null
            }

            AppLogger.i(TAG, "Resolved subtitle download URL from provider '$idPrefix': $fileUrl")
            var finalFile: File? = null

            if (fileUrl.startsWith("http", ignoreCase = true)) {
                val rawBytes = fetchSubtitleBytes(fileUrl)
                AppLogger.i(TAG, "Downloaded ${rawBytes.size} subtitle bytes")

                if (rawBytes.isEmpty()) {
                    AppLogger.w(TAG, "Downloaded subtitle payload is empty")
                    return@withContext null
                }

                finalFile = processAndNormalizeSubtitleBytes(rawBytes, name, fileUrl)
            } else if (fileUrl.startsWith("file://", ignoreCase = true)) {
                val f = File(URI(fileUrl))
                if (f.exists()) {
                    finalFile = processAndNormalizeSubtitleBytes(f.inputStream().use { it.readBoundedBytes(MAX_SUBTITLE_BYTES) }, name, fileUrl)
                }
            } else {
                val f = File(fileUrl)
                if (f.exists()) {
                    finalFile = processAndNormalizeSubtitleBytes(f.inputStream().use { it.readBoundedBytes(MAX_SUBTITLE_BYTES) }, name, fileUrl)
                }
            }

            if (finalFile == null || !finalFile.exists() || finalFile.length() == 0L) {
                AppLogger.w(TAG, "Subtitle extraction produced no valid subtitle file")
                return@withContext null
            }

            val finalPath = finalFile.absolutePath.replace("\\", "/")
            AppLogger.i(TAG, "Validated and saved normalized subtitle to '$finalPath' (${finalFile.length()} bytes)")
            return@withContext finalPath
        } catch (e: Exception) {
            AppLogger.e(TAG, "downloadAndExtractSubtitle error: ${e.message}", e)
            return@withContext null
        }
    }

    private suspend fun processAndNormalizeSubtitleBytes(
        rawBytes: ByteArray,
        fallbackName: String,
        sourceUrl: String? = null,
    ): File? {
        val extractedBytes = extractFromArchiveIfPresent(rawBytes) ?: rawBytes
        if (extractedBytes.isEmpty()) return null

        val (decodedText, formatExt) = decodeAndDetectFormat(extractedBytes)
        if (decodedText.isBlank()) return null

        if (formatExt == ".m3u8" || decodedText.trimStart().startsWith("#EXTM3U", ignoreCase = true)) {
            AppLogger.i(TAG, "Detected M3U8 subtitle stream, flattening segments to WebVTT...")
            val flattenedVtt = flattenM3u8SubtitleToWebVtt(decodedText, sourceUrl)
            if (!flattenedVtt.isNullOrBlank()) {
                val tmpFile = File.createTempFile("sub_norm_", ".vtt")
                tmpFile.writeText(flattenedVtt, Charsets.UTF_8)
                return tmpFile
            } else {
                AppLogger.w(TAG, "Could not flatten M3U8 subtitle stream, falling back to raw M3U8 playlist file")
                val tmpFile = File.createTempFile("sub_norm_", ".m3u8")
                tmpFile.writeText(decodedText, Charsets.UTF_8)
                return tmpFile
            }
        }

        val tmpFile = File.createTempFile("sub_norm_", formatExt)
        tmpFile.writeText(decodedText, Charsets.UTF_8)
        return tmpFile
    }

    private suspend fun flattenM3u8SubtitleToWebVtt(m3u8Content: String, baseUrl: String?): String? {
        return try {
            val lines = m3u8Content.lines()
            var currentLines = lines
            var currentBase = baseUrl

            if (m3u8Content.contains("#EXT-X-STREAM-INF") || m3u8Content.contains("#EXT-X-MEDIA:TYPE=SUBTITLES")) {
                val subLine = lines.firstOrNull { it.trim().startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES") }
                val uriMatch = subLine?.let { Regex("""URI="([^"]+)"""").find(it)?.groupValues?.get(1) }
                    ?: lines.firstOrNull { !it.trim().startsWith("#") && it.trim().isNotEmpty() }
                if (uriMatch != null && currentBase != null) {
                    val resolvedChild = resolveUrl(currentBase, uriMatch)
                    val childResp = fetchSubtitleBytes(resolvedChild).toString(Charsets.UTF_8)
                    if (childResp.isNotBlank()) {
                        currentLines = childResp.lines()
                        currentBase = resolvedChild
                    }
                }
            }

            val segmentUrls = currentLines
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { if (currentBase != null) resolveUrl(currentBase, it) else it }

            if (segmentUrls.isEmpty()) return null
            require(segmentUrls.size <= MAX_PLAYLIST_SEGMENTS) { "Subtitle playlist contains too many segments" }

            val vttBuilder = StringBuilder("WEBVTT\n\n")
            var hasCues = false
            var totalBytes = 0L

            for (segUrl in segmentUrls) {
                val bytes = fetchSubtitleBytes(segUrl)
                totalBytes += bytes.size
                require(totalBytes <= MAX_SUBTITLE_BYTES) { "Subtitle playlist exceeds its byte budget" }
                try {
                    val segText = bytes.toString(Charsets.UTF_8)
                    if (segText.isNotBlank()) {
                        val cleanLines = segText.trimStart('\uFEFF').lines()
                        for (line in cleanLines) {
                            val trimmed = line.trim()
                            if (trimmed == "WEBVTT" || trimmed.startsWith("X-TIMESTAMP-MAP") || trimmed.startsWith("NOTE")) continue
                            vttBuilder.append(line).append("\n")
                            hasCues = true
                        }
                        vttBuilder.append("\n")
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to download VTT segment $segUrl: ${e.message}")
                }
            }

            if (hasCues) vttBuilder.toString() else null
        } catch (e: Exception) {
            AppLogger.e(TAG, "flattenM3u8SubtitleToWebVtt error: ${e.message}", e)
            null
        }
    }

    private fun resolveUrl(base: String, uri: String): String {
        return try {
            val baseUri = URI(base)
            baseUri.resolve(uri).toString()
        } catch (_: Exception) {
            if (base.contains("/")) {
                base.substringBeforeLast('/') + "/" + uri
            } else {
                uri
            }
        }
    }

    internal fun extractFromArchiveIfPresent(bytes: ByteArray): ByteArray? {
        // ZIP Archive: PK (0x50 0x4B)
        if (bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            AppLogger.i(TAG, "Detected ZIP archive payload, extracting subtitle entries...")
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zipIn ->
                    var entry: ZipEntry? = zipIn.nextEntry
                    var entries = 0
                    var expandedBytes = 0
                    while (entry != null) {
                        require(++entries <= MAX_ARCHIVE_ENTRIES) { "Subtitle archive contains too many entries" }
                        val entryBytes = zipIn.readBoundedBytes(MAX_SUBTITLE_BYTES - expandedBytes)
                        expandedBytes += entryBytes.size
                        val nameLower = entry.name.lowercase()
                        if (!entry.isDirectory && !nameLower.contains("__macosx") && !nameLower.startsWith(".")) {
                            if (nameLower.endsWith(".srt") || nameLower.endsWith(".vtt") || nameLower.endsWith(".ass") || nameLower.endsWith(".ssa") || nameLower.endsWith(".sub")) {
                                if (entryBytes.isNotEmpty()) {
                                    AppLogger.i(TAG, "Extracted valid subtitle '${entry.name}' (${entryBytes.size} bytes) from ZIP")
                                    return entryBytes
                                }
                            }
                        }
                        entry = zipIn.nextEntry
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to unpack ZIP archive: ${e.message}")
                throw java.io.IOException("Invalid or oversized subtitle archive", e)
            }
        }
        // GZIP Stream: 0x1F 0x8B
        else if (bytes.size > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            AppLogger.i(TAG, "Detected GZIP compressed stream, decompressing...")
            try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { gzIn ->
                    val decompressed = gzIn.readBoundedBytes(MAX_SUBTITLE_BYTES)
                    if (decompressed.isNotEmpty()) {
                        AppLogger.i(TAG, "Decompressed ${decompressed.size} bytes from GZIP stream")
                        return decompressed
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to decompress GZIP stream: ${e.message}")
                throw java.io.IOException("Invalid or oversized compressed subtitle", e)
            }
        }
        return null
    }

    private fun decodeAndDetectFormat(bytes: ByteArray): Pair<String, String> {
        val text = when {
            // UTF-8 with BOM: EF BB BF
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> {
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
            // UTF-16 LE with BOM: FF FE
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            // UTF-16 BE with BOM: FE FF
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
            else -> {
                try {
                    val decoder = Charsets.UTF_8.newDecoder()
                    val byteBuffer = java.nio.ByteBuffer.wrap(bytes)
                    decoder.decode(byteBuffer).toString()
                } catch (e: Exception) {
                    // Fallback to Windows-1252 / ISO-8859-1 for legacy single-byte encodings
                    try {
                        String(bytes, Charset.forName("windows-1252"))
                    } catch (_: Exception) {
                        String(bytes, Charsets.ISO_8859_1)
                    }
                }
            }
        }

        val trimmedSample = text.take(500).trimStart()
        val ext = when {
            trimmedSample.startsWith("WEBVTT", ignoreCase = true) -> ".vtt"
            trimmedSample.contains("[Script Info]", ignoreCase = true) || trimmedSample.contains("[V4+ Styles]", ignoreCase = true) -> ".ass"
            trimmedSample.contains("<SAMI>", ignoreCase = true) -> ".smi"
            trimmedSample.startsWith("#EXTM3U", ignoreCase = true) || trimmedSample.contains("#EXT-X-") -> ".m3u8"
            else -> ".srt"
        }

        return Pair(text, ext)
    }
}
