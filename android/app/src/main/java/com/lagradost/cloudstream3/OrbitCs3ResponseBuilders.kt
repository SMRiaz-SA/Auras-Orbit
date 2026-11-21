@file:JvmName("OrbitCs3ResponseBuilders")

package com.lagradost.cloudstream3

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializerOrNull

private val cs3JacksonFallbackMapper = MainAPIKt.getMapper().copy().apply {
    setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
}

/** Mirrors CS3's `toJson()` serializer selection and Jackson fallback for provider payloads. */
@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
fun serializeCs3ProviderData(value: Any): String {
    val serializer = value::class.serializerOrNull()
        ?: cs3Json.serializersModule.getContextual(value::class)
    if (serializer == null) return cs3JacksonFallbackMapper.writeValueAsString(value)

    return try {
        @Suppress("UNCHECKED_CAST")
        cs3Json.encodeToString(serializer as KSerializer<Any>, value)
    } catch (serializationFailure: SerializationException) {
        android.util.Log.w(
            "CS3",
            "Episode payload serialization failed; using the upstream Jackson fallback.",
            serializationFailure,
        )
        cs3JacksonFallbackMapper.writeValueAsString(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
private val cs3Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

/** Shared CS3 JSON configuration used by serializer selection and the Java API facade. */
fun getCs3Json(): Json = cs3Json

/**
 * Source-backed response builder used by the Java ABI facade.
 */
suspend fun newMovieLoadResponse(
    provider: MainAPI,
    name: String,
    url: String,
    type: TvType,
    data: Any?,
    initializer: (suspend MovieLoadResponse.() -> Unit)?,
): MovieLoadResponse {
    val dataUrl = when (data) {
        null -> ""
        is String -> data
        else -> serializeCs3ProviderData(data)
    }
    val response = MovieLoadResponse(
        name, url, provider.name, type, dataUrl, null, null, null, null, null, null,
        arrayListOf<TrailerData>(), null, null, dataUrl.isBlank(), linkedMapOf(),
        null, null, null, null, url,
    )
    initializer?.invoke(response)
    return response
}

/** Source-backed CS3 live-stream response builder with the pinned blank-data coming-soon rule. */
suspend fun newLiveStreamLoadResponse(
    provider: MainAPI,
    name: String,
    url: String,
    dataUrl: String,
    initializer: suspend LiveStreamLoadResponse.() -> Unit,
): LiveStreamLoadResponse {
    val response = LiveStreamLoadResponse(
        name, url, provider.name, dataUrl,
        null, null, null, TvType.Live, null, null, null,
        arrayListOf<TrailerData>(), null, null, dataUrl.isBlank(), linkedMapOf(),
        null, null, null, null, url,
    )
    initializer(response)
    return response
}

/** Source-backed CS3 TV-series response builder with bounded episode storage. */
suspend fun newTvSeriesLoadResponse(
    provider: MainAPI,
    name: String,
    url: String,
    type: TvType,
    episodes: List<Episode>,
    initializer: (suspend TvSeriesLoadResponse.() -> Unit)?,
): TvSeriesLoadResponse {
    val response = TvSeriesLoadResponse(name, url, provider.name, type, episodes)
    initializer?.invoke(response)
    return response
}

/** Source-backed CS3 anime response builder, including the original empty-series rule. */
suspend fun newAnimeLoadResponse(
    provider: MainAPI,
    name: String,
    url: String,
    type: TvType,
    comingSoonIfNone: Boolean,
    initializer: (suspend AnimeLoadResponse.() -> Unit)?,
): AnimeLoadResponse {
    val response = AnimeLoadResponse(name, url, provider.name, type)
    initializer?.invoke(response)
    if (comingSoonIfNone) {
        response.comingSoon = response.episodes.values.none { it.isNotEmpty() }
    }
    return response
}

/** Source-backed SubtitleFile initializer bridge; extraction remains the provider's responsibility. */
suspend fun newSubtitleFile(
    lang: String,
    url: String,
    initializer: (suspend SubtitleFile.() -> Unit)?,
): SubtitleFile {
    val subtitle = SubtitleFile(lang, url)
    initializer?.invoke(subtitle)
    return subtitle
}
