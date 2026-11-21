package com.auras.orbit.metadata

import com.lagradost.cloudstream3.metaproviders.createOrbitTmdbClient
import com.uwetrottmann.tmdb2.Tmdb
import com.uwetrottmann.tmdb2.entities.AppendToResponse
import com.uwetrottmann.tmdb2.enumerations.AppendToResponseItem
import retrofit2.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IllformedLocaleException
import java.util.Locale

/**
 * Fetches display-only fields for an explicitly linked, typed TMDB ID. It does not accept a
 * provider item or return provider playback/action data; the caller keeps those identities.
 */
class TmdbTitleMetadataRepository internal constructor(
    private val source: TmdbTitleMetadataSource,
) {
    constructor() : this(RetrofitTmdbTitleMetadataSource(createOrbitTmdbClient()))

    constructor(tmdb: Tmdb) : this(RetrofitTmdbTitleMetadataSource(tmdb))

    /**
     * Loads the exact catalog ID and kind. Missing display text in a non-English locale is filled
     * from English, while any populated localized value remains preferred.
     */
    @JvmOverloads
    fun load(id: Int, kind: String, languageTag: String = ENGLISH_LANGUAGE): TitleCatalogRecord? {
        require(id > 0 && (kind == MOVIE || kind == SERIES)) {
            "A positive, typed TMDB title ID is required"
        }
        val language = canonicalLanguageTag(languageTag)
        val localized = lookup(id, kind, language) ?: return null
        if (localized.id != id) return null

        val localizedTitle = localized.displayTitle()
        val localizedOverview = localized.overview?.takeIf(String::isNotBlank)
        val english = if (language != ENGLISH_LANGUAGE &&
            (localizedTitle == null || localizedOverview == null)
        ) {
            lookup(id, kind, ENGLISH_LANGUAGE)?.also {
                if (it.id != id) return null
            }
        } else {
            null
        }

        return TitleCatalogRecord(
            id,
            kind,
            localizedTitle ?: english?.displayTitle(),
            localizedOverview ?: english?.overview,
            year(localized.releaseDate),
            image(localized.posterPath),
            image(localized.backdropPath),
        )
    }

    private fun lookup(id: Int, kind: String, language: String): TmdbTitleMetadataBody? {
        val response = try {
            source.load(id, kind, language)
        } catch (failure: IOException) {
            throw TmdbTitleMetadataFailure.Transport(failure)
        }
        if (response.statusCode !in 200..299) {
            if (response.statusCode == 429) {
                throw TmdbTitleMetadataFailure.RateLimited(response.retryAfterHeader)
            }
            throw TmdbTitleMetadataFailure.HttpStatus(response.statusCode)
        }
        return response.body
    }

    private fun canonicalLanguageTag(raw: String): String {
        val normalized = raw.trim().replace('_', '-')
        require(normalized.length in 2..32) {
            "A valid BCP-47 language tag is required"
        }
        val locale = try {
            Locale.Builder().setLanguageTag(normalized).build()
        } catch (malformed: IllformedLocaleException) {
            throw IllegalArgumentException("A valid BCP-47 language tag is required", malformed)
        }
        val canonical = locale.toLanguageTag()
        require(locale.language.isNotEmpty() &&
            !locale.language.equals("und", ignoreCase = true) && canonical != "und"
        ) {
            "A valid BCP-47 language tag is required"
        }
        return canonical
    }

    private fun year(date: Date?): String = date?.let {
        SimpleDateFormat("yyyy", Locale.US).format(it)
    }.orEmpty()

    private fun image(path: String?): String = path
        ?.takeIf { it.matches(Regex("/[A-Za-z0-9._/-]{1,200}")) && !it.contains("..") }
        ?.let { "https://image.tmdb.org/t/p/w500$it" }.orEmpty()

    private fun TmdbTitleMetadataBody.displayTitle(): String? =
        title?.takeIf(String::isNotBlank) ?: originalTitle?.takeIf(String::isNotBlank)

    private companion object {
        const val MOVIE = "MOVIE"
        const val SERIES = "SERIES"
        const val ENGLISH_LANGUAGE = "en-US"
    }
}

/** Typed lookup failures let callers distinguish throttling from other HTTP and transport errors. */
sealed class TmdbTitleMetadataFailure(
    message: String,
    val statusCode: Int?,
    cause: Throwable? = null,
) : IOException(message, cause) {
    class RateLimited(val retryAfterHeader: String?) :
        TmdbTitleMetadataFailure("TMDB title lookup was rate limited (429)", 429) {
        val retryAfterSeconds: Long? = retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
    }

    class HttpStatus(statusCode: Int) :
        TmdbTitleMetadataFailure("TMDB title lookup failed ($statusCode)", statusCode)

    class Transport(cause: IOException) :
        TmdbTitleMetadataFailure("TMDB title lookup transport failed", null, cause)
}

/** Small transport seam: tests can supply deterministic response objects without networking. */
internal fun interface TmdbTitleMetadataSource {
    @Throws(IOException::class)
    fun load(id: Int, kind: String, languageTag: String): TmdbTitleMetadataResponse
}

internal data class TmdbTitleMetadataResponse(
    val statusCode: Int,
    val retryAfterHeader: String? = null,
    val body: TmdbTitleMetadataBody? = null,
)

internal data class TmdbTitleMetadataBody(
    val id: Int,
    val title: String? = null,
    val originalTitle: String? = null,
    val overview: String? = null,
    val releaseDate: Date? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
)

private class RetrofitTmdbTitleMetadataSource(
    private val tmdb: Tmdb,
) : TmdbTitleMetadataSource {
    override fun load(id: Int, kind: String, languageTag: String): TmdbTitleMetadataResponse {
        return if (kind == "MOVIE") {
            tmdb.moviesService().summary(
                id, languageTag, AppendToResponse(AppendToResponseItem.EXTERNAL_IDS),
            ).execute().mapBody { movie ->
                TmdbTitleMetadataBody(
                    id = movie.id ?: 0,
                    title = movie.title,
                    originalTitle = movie.original_title,
                    overview = movie.overview,
                    releaseDate = movie.release_date,
                    posterPath = movie.poster_path,
                    backdropPath = movie.backdrop_path,
                )
            }
        } else {
            tmdb.tvService().tv(
                id, languageTag, AppendToResponse(AppendToResponseItem.EXTERNAL_IDS),
            ).execute().mapBody { series ->
                TmdbTitleMetadataBody(
                    id = series.id ?: 0,
                    title = series.name,
                    originalTitle = series.original_name,
                    overview = series.overview,
                    releaseDate = series.first_air_date,
                    posterPath = series.poster_path,
                    backdropPath = series.backdrop_path,
                )
            }
        }
    }

    private fun <T : Any> Response<T>.mapBody(
        transform: (T) -> TmdbTitleMetadataBody,
    ): TmdbTitleMetadataResponse {
        val responseCode = code()
        val retryAfter = headers()["Retry-After"]
        if (!isSuccessful) errorBody()?.close()
        return TmdbTitleMetadataResponse(
            statusCode = responseCode,
            retryAfterHeader = retryAfter,
            body = if (isSuccessful) body()?.let(transform) else null,
        )
    }
}
