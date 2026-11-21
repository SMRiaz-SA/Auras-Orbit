package com.lagradost.cloudstream3.utils

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.ArrayDeque
import kotlin.coroutines.cancellation.CancellationException

/**
 * Orbit-owned implementation of the pinned M3u8Helper compatibility API.
 * Playlist parsing uses Media3; network reads go through Orbit's bounded,
 * DNS-pinned public playlist transport.
 */
class M3u8Helper {
    companion object {
        suspend fun generateM3u8(
            source: String,
            streamUrl: String,
            referer: String,
            quality: Int? = null,
            headers: Map<String, String> = emptyMap(),
            name: String = source,
        ): List<ExtractorLink> {
            return generateM3u8Internal(source, streamUrl, referer, quality, headers, name, false)
        }

        /** HTTPS-only playlist expansion for URLs captured by the Filemoon WebView flow. */
        suspend fun generateM3u8Https(
            source: String,
            streamUrl: String,
            referer: String,
            quality: Int? = null,
            headers: Map<String, String> = emptyMap(),
            name: String = source,
        ): List<ExtractorLink> =
            generateM3u8Internal(source, streamUrl, referer, quality, headers, name, true)

        private suspend fun generateM3u8Internal(
            source: String,
            streamUrl: String,
            referer: String,
            quality: Int?,
            headers: Map<String, String>,
            name: String,
            requireHttps: Boolean,
        ): List<ExtractorLink> {
            if (source.length > MAX_PLAYLIST_URL_CHARS || name.length > MAX_PLAYLIST_URL_CHARS ||
                referer.length > MAX_PLAYLIST_URL_CHARS || referer.any { it == '\r' || it == '\n' }
            ) return emptyList()

            return expandPlaylist(
                M3u8Stream(streamUrl, quality, headers),
                returnThis = true,
                requireHttps = requireHttps,
            ).map { stream ->
                val sameOrigin = sameHttpOrigin(streamUrl, stream.streamUrl)
                ExtractorLink(
                    source,
                    name,
                    stream.streamUrl,
                    if (sameOrigin) referer else "",
                    stream.quality ?: UNKNOWN_QUALITY,
                    if (sameOrigin) stream.headers else emptyMap(),
                    null,
                    ExtractorLinkType.M3U8,
                    emptyList(),
                )
            }
        }
    }

    data class M3u8Stream(
        val streamUrl: String,
        val quality: Int? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    suspend fun m3u8Generation(
        m3u8: M3u8Stream,
        returnThis: Boolean? = true,
    ): List<M3u8Stream> = expandPlaylist(m3u8, returnThis ?: true, requireHttps = false)
}

private const val MAX_PLAYLIST_URL_CHARS = 4_096
private const val MAX_PLAYLIST_LINES = 8_192
private const val MAX_PLAYLIST_VARIANTS = 64
private const val PLAYLIST_DEADLINE_MILLIS = 30_000L
private const val UNKNOWN_QUALITY = 400

private suspend fun expandPlaylist(
    input: M3u8Helper.M3u8Stream,
    returnThis: Boolean,
    requireHttps: Boolean,
): List<M3u8Helper.M3u8Stream> {
    if (input.streamUrl.length !in 1..MAX_PLAYLIST_URL_CHARS ||
        (requireHttps && !isStrictHttpsUrl(input.streamUrl))
    ) return emptyList()

    return try {
        withTimeoutOrNull(PLAYLIST_DEADLINE_MILLIS) {
            currentCoroutineContext().ensureActive()
            val playlist = fetchPublicM3u8Playlist(input.streamUrl, input.headers, requireHttps)
                ?: return@withTimeoutOrNull emptyList()
            if (playlist.url.length !in 1..MAX_PLAYLIST_URL_CHARS ||
                (requireHttps && !isStrictHttpsUrl(playlist.url))
            ) {
                return@withTimeoutOrNull emptyList()
            }
            val body = playlist.body
            if (!withinPlaylistStructureLimits(body)) return@withTimeoutOrNull emptyList()

            val parsed = try {
                HlsPlaylistParser().parse(
                    Uri.parse(playlist.url),
                    ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withTimeoutOrNull emptyList()

            val streams = ArrayList<M3u8Helper.M3u8Stream>()
            val sameOriginParent = sameHttpOrigin(input.streamUrl, playlist.url)
            val retainedParent = if (sameOriginParent) {
                input
            } else {
                input.copy(streamUrl = playlist.url, headers = emptyMap())
            }
            var foundPlayableVariant = false
            var allMasterTargetsArePublic = true
            if (parsed is HlsMultivariantPlaylist) {
                for (variant in parsed.variants.take(MAX_PLAYLIST_VARIANTS)) {
                    currentCoroutineContext().ensureActive()
                    val isTrickPlay = variant.format.roleFlags and C.ROLE_FLAG_TRICK_PLAY != 0
                    if (!isTrickPlay) foundPlayableVariant = true
                    val url = validatePublicExtractorMediaUrl(variant.url.toString(), requireHttps)
                    if (url == null) {
                        allMasterTargetsArePublic = false
                        continue
                    }
                    if (isTrickPlay) continue
                    streams += M3u8Helper.M3u8Stream(
                        streamUrl = url,
                        quality = variant.format.height.takeIf { it > 0 },
                        headers = if (sameHttpOrigin(input.streamUrl, url)) {
                            input.headers
                        } else {
                            emptyMap()
                        },
                    )
                }
                for (rendition in parsed.videos + parsed.audios + parsed.subtitles + parsed.closedCaptions) {
                    currentCoroutineContext().ensureActive()
                    val renditionUrl = rendition.url?.toString() ?: continue
                    if (validatePublicExtractorMediaUrl(renditionUrl, requireHttps) == null) {
                        allMasterTargetsArePublic = false
                    }
                }

                val steeringUrl = parsed.contentSteeringInfo?.serverUri?.toString()
                if (steeringUrl != null &&
                    validatePublicExtractorMediaUrl(steeringUrl, requireHttps) == null
                ) {
                    allMasterTargetsArePublic = false
                }

                // Media3 1.11.1 represents parsed session-key URI data inside
                // DrmInitData.SchemeData; validate any explicit license endpoint if
                // one is ever supplied by that model as well.
                for (sessionKey in parsed.sessionKeyDrmInitData) {
                    for (index in 0 until sessionKey.schemeDataCount) {
                        currentCoroutineContext().ensureActive()
                        val licenseUrl = sessionKey.get(index).licenseServerUrl ?: continue
                        if (validatePublicExtractorMediaUrl(licenseUrl, requireHttps) == null) {
                            allMasterTargetsArePublic = false
                        }
                    }
                }
            }

            // The pinned helper retains the original playlist by default while also
            // exposing each playable master variant. Media playlists remain unchanged.
            if (parsed is HlsMediaPlaylist) {
                if (requireHttps && !validateHttpsMediaPlaylistTargets(parsed)) {
                    return@withTimeoutOrNull emptyList()
                }
                streams += retainedParent
            } else if (parsed is HlsMultivariantPlaylist && allMasterTargetsArePublic &&
                (!foundPlayableVariant || returnThis)
            ) {
                streams += retainedParent
            }
            streams
        } ?: emptyList()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
}

/** Validates all URI-bearing media-playlist fields exposed by the pinned Media3 model. */
private suspend fun validateHttpsMediaPlaylistTargets(playlist: HlsMediaPlaylist): Boolean {
    val validatedTargets = HashMap<String, Boolean>()
    suspend fun validateTarget(url: String?): Boolean {
        if (url == null) return true
        validatedTargets[url]?.let { return it }
        val valid = validatePublicExtractorMediaUrl(url, requireHttps = true) != null
        validatedTargets[url] = valid
        return valid
    }

    suspend fun validateDrmTargets(drm: DrmInitData?): Boolean {
        if (drm == null) return true
        for (index in 0 until drm.schemeDataCount) {
            currentCoroutineContext().ensureActive()
            if (!validateTarget(drm.get(index).licenseServerUrl)) return false
        }
        return true
    }

    val pendingSegments = ArrayDeque<HlsMediaPlaylist.SegmentBase>()
    val visitedSegments = HashSet<HlsMediaPlaylist.SegmentBase>()
    fun enqueue(segment: HlsMediaPlaylist.SegmentBase?) {
        if (segment != null && visitedSegments.add(segment)) pendingSegments.addLast(segment)
    }

    for (segment in playlist.segments) {
        currentCoroutineContext().ensureActive()
        enqueue(segment)
    }
    for (part in playlist.trailingParts) {
        currentCoroutineContext().ensureActive()
        enqueue(part)
    }
    enqueue(playlist.lastSeenInitSegment)

    if (!validateDrmTargets(playlist.protectionSchemes)) return false
    for (report in playlist.renditionReports.values) {
        currentCoroutineContext().ensureActive()
        if (!validateTarget(report.playlistUri.toString())) return false
    }
    for (interstitial in playlist.interstitials) {
        currentCoroutineContext().ensureActive()
        if (!validateTarget(interstitial.assetUri?.toString()) ||
            !validateTarget(interstitial.assetListUri?.toString())
        ) return false
    }

    while (pendingSegments.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val segment = pendingSegments.removeFirst()
        if (segment.url.isNullOrBlank() || !validateTarget(segment.url) ||
            !validateTarget(segment.fullSegmentEncryptionKeyUri) ||
            !validateDrmTargets(segment.drmInitData)
        ) return false
        enqueue(segment.initializationSegment)
        if (segment is HlsMediaPlaylist.Segment) {
            for (part in segment.parts) enqueue(part)
        }
    }
    return true
}

private fun withinPlaylistStructureLimits(body: String): Boolean {
    var lines = 0
    var variants = 0
    for (line in body.lineSequence()) {
        if (++lines > MAX_PLAYLIST_LINES) return false
        if (line.startsWith("#EXT-X-STREAM-INF:") ||
            line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") ||
            line.startsWith("#EXT-X-MEDIA:") ||
            line.startsWith("#EXT-X-SESSION-KEY:") ||
            line.startsWith("#EXT-X-CONTENT-STEERING:")
        ) {
            if (++variants > MAX_PLAYLIST_VARIANTS) return false
        }
    }
    return true
}

