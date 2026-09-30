package com.lagradost.cloudstream3.desktop.stremio

import java.net.URLEncoder

object StremioTransport {

    fun normalizeManifestUrl(inputUrl: String): String {
        var url = inputUrl.trim()
        if (url.startsWith("stremio://", ignoreCase = true)) {
            url = "https://" + url.substring(10)
        }
        url = url.substringBefore("#")
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "https://$url"
        }
        val path = url.substringBefore("?")
        if (!path.endsWith("/manifest.json", ignoreCase = true)) {
            val queryIndex = url.indexOf('?')
            url = if (queryIndex >= 0) {
                val base = url.substring(0, queryIndex).trimEnd('/')
                val query = url.substring(queryIndex)
                "$base/manifest.json$query"
            } else {
                url.trimEnd('/') + "/manifest.json"
            }
        }
        return url
    }

    fun getBaseUrl(manifestUrl: String): String {
        val clean = manifestUrl.substringBefore("?")
        return clean.replace(Regex("/manifest\\.json$", RegexOption.IGNORE_CASE), "").removeSuffix("/")
    }

    fun getQueryParams(manifestUrl: String): String {
        val query = manifestUrl.substringAfter("?", "")
        return if (query.isNotBlank()) "?$query" else ""
    }

    fun buildMetadataUrl(manifestUrl: String, type: String, id: String): String {
        val baseUrl = getBaseUrl(manifestUrl)
        val query = getQueryParams(manifestUrl)
        val encodedId = encodePathSegment(id)
        return "$baseUrl/meta/${encodePathSegment(type)}/$encodedId.json$query"
    }

    fun buildStreamUrl(manifestUrl: String, type: String, id: String): String {
        val baseUrl = getBaseUrl(manifestUrl)
        val query = getQueryParams(manifestUrl)
        val encodedId = encodePathSegment(id)
        return "$baseUrl/stream/${encodePathSegment(type)}/$encodedId.json$query"
    }

    /** Build a Stremio route with the protocol's single stringified extraArgs segment. */
    fun buildCatalogUrl(
        manifestOrBaseUrl: String,
        type: String,
        catalogId: String,
        search: String? = null,
        genre: String? = null,
        skip: Int = 0,
        extraArgs: Map<String, String> = emptyMap(),
    ): String {
        val baseUrl = getBaseUrl(manifestOrBaseUrl)
        val query = getQueryParams(manifestOrBaseUrl)
        val encodedType = encodePathSegment(type)
        val encodedCatalogId = encodePathSegment(catalogId)
        val extras = linkedMapOf<String, String>()
        if (!search.isNullOrBlank()) extras["search"] = search.trim()
        if (!genre.isNullOrBlank() && !genre.equals("All", ignoreCase = true)) extras["genre"] = genre.trim()
        extraArgs.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) extras[key] = value
        }
        if (skip > 0) extras["skip"] = skip.toString()
        val extraPath = extras.entries.joinToString(separator = "&", prefix = "/") { (key, value) ->
            "${encodePathSegment(key)}=${encodePathSegment(value)}"
        }.takeIf { extras.isNotEmpty() }.orEmpty()
        return "$baseUrl/catalog/$encodedType/$encodedCatalogId$extraPath.json$query"
    }

    fun buildSubtitleUrl(
        manifestUrl: String,
        type: String,
        id: String,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
    ): String {
        val baseUrl = getBaseUrl(manifestUrl)
        val query = getQueryParams(manifestUrl)
        val encodedId = encodePathSegment(id)
        val extras = linkedMapOf<String, String>()
        if (!videoHash.isNullOrBlank()) extras["videoHash"] = videoHash
        if (videoSize != null && videoSize >= 0) extras["videoSize"] = videoSize.toString()
        if (!filename.isNullOrBlank()) extras["filename"] = filename
        val extraPath = extras.entries.joinToString(separator = "&", prefix = "/") { (key, value) ->
            "${encodePathSegment(key)}=${encodePathSegment(value)}"
        }.takeIf { extras.isNotEmpty() }.orEmpty()
        return "$baseUrl/subtitles/${encodePathSegment(type)}/$encodedId$extraPath.json$query"
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
