package com.lagradost.cloudstream3.desktop.torrent

import java.net.URLEncoder

object TorrentMagnetUri {
    private val defaultTrackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "http://tracker.openbittorrent.com:80/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.bittor.pw:1337/announce",
    )

    fun fromInfoHash(infoHash: String, sources: List<String> = emptyList()): String {
        val suppliedTrackers = sources.mapNotNull(::trackerUrl)
        // Keep the addon-provided peer sources intact. In particular, don't add public trackers
        // to a torrent that may be using a private tracker. Retain Orbit's defaults for bare hashes.
        val trackers = if (sources.any { it.isNotBlank() }) suppliedTrackers.distinct() else defaultTrackers
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(infoHash.trim())
            trackers.forEach { tracker ->
                append("&tr=")
                append(URLEncoder.encode(tracker, "UTF-8"))
            }
        }
    }

    private fun trackerUrl(source: String): String? {
        val tracker = source.trim().replace(Regex("^tracker:", RegexOption.IGNORE_CASE), "")
            .takeIf { it.isNotBlank() } ?: return null
        val scheme = runCatching { java.net.URI(tracker).scheme?.lowercase() }.getOrNull()
        return tracker.takeIf { scheme in setOf("udp", "http", "https") }
    }
}
