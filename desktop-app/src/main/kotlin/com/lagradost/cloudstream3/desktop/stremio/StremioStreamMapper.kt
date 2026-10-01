package com.lagradost.cloudstream3.desktop.stremio

import com.lagradost.cloudstream3.desktop.torrent.TorrentMagnetUri
import java.net.URI

sealed interface StremioPlayableStream {
    data class Direct(
        val url: String,
        val requestHeaders: Map<String, String>,
    ) : StremioPlayableStream

    data class Torrent(
        val magnetUrl: String,
        val fileIdx: Int?,
    ) : StremioPlayableStream

    data class YouTube(val url: String) : StremioPlayableStream

    data class External(val url: String) : StremioPlayableStream
}

/** Maps standard Stremio stream response variants onto the playback mechanisms Orbit already has. */
object StremioStreamMapper {
    fun map(item: StremioStreamItem): StremioPlayableStream? {
        item.url?.trim()?.takeIf(::isSupportedDirectUrl)?.let { url ->
            val hints = item.behaviorHints
            val headers = hints?.proxyHeaders?.request?.takeIf { it.isNotEmpty() }
                ?: hints?.headers.orEmpty()
            return StremioPlayableStream.Direct(url, headers)
        }

        item.infoHash?.trim()?.takeIf(::isSupportedInfoHash)?.let { infoHash ->
            return StremioPlayableStream.Torrent(
                magnetUrl = TorrentMagnetUri.fromInfoHash(infoHash, item.sources.orEmpty()),
                fileIdx = item.fileIdx?.takeIf { it >= 0 },
            )
        }

        item.ytId?.trim()?.takeIf { it.matches(YOUTUBE_ID) }?.let { id ->
            return StremioPlayableStream.YouTube("https://www.youtube.com/watch?v=$id")
        }

        item.externalUrl?.trim()?.takeIf(::isHttpUrl)?.let { url ->
            return StremioPlayableStream.External(url)
        }

        return null
    }

    private fun isSupportedDirectUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https", "ftp", "ftps", "rtmp") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }

    private fun isHttpUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }

    private fun isSupportedInfoHash(value: String): Boolean =
        value.matches(HEX_INFO_HASH) || value.matches(BASE32_INFO_HASH)

    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
    private val HEX_INFO_HASH = Regex("[0-9a-fA-F]{40}")
    private val BASE32_INFO_HASH = Regex("[A-Z2-7a-z]{32}")
}
