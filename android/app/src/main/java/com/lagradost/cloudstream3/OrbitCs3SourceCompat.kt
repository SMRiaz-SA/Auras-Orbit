@file:JvmName("OrbitCs3SourceCompat")

package com.lagradost.cloudstream3

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitAll
import com.lagradost.cloudstream3.newMovieLoadResponse as buildMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse as buildTvSeriesLoadResponse

/** Source-level compatibility helpers mirrored from the pinned CloudStream MainAPI. */
fun newHomePageResponse(
    name: String,
    list: List<SearchResponse>,
    hasNext: Boolean? = null,
): HomePageResponse = MainAPIKt.newHomePageResponse(name, list, hasNext)

fun newHomePageResponse(
    request: MainPageRequest,
    list: List<SearchResponse>,
    hasNext: Boolean? = null,
): HomePageResponse = MainAPIKt.newHomePageResponse(request, list, hasNext)

fun newHomePageResponse(
    list: HomePageList,
    hasNext: Boolean? = null,
): HomePageResponse = MainAPIKt.newHomePageResponse(list, hasNext)

fun newHomePageResponse(
    list: List<HomePageList>,
    hasNext: Boolean? = null,
): HomePageResponse = MainAPIKt.newHomePageResponse(list, hasNext)

fun newSearchResponseList(
    list: List<SearchResponse>,
    hasNext: Boolean? = null,
): SearchResponseList = MainAPIKt.newSearchResponseList(list, hasNext)

fun List<SearchResponse>.toNewSearchResponseList(hasNext: Boolean? = null): SearchResponseList =
    newSearchResponseList(this, hasNext)

fun MainAPI.newMovieSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    fix: Boolean = true,
    initializer: MovieSearchResponse.() -> Unit = {},
): MovieSearchResponse = MainAPIKt.newMovieSearchResponse(
    this, name, url, type, fix,
    { value: Any? -> (value as MovieSearchResponse).apply(initializer); kotlin.Unit },
)

fun MainAPI.newTvSeriesSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    fix: Boolean = true,
    initializer: TvSeriesSearchResponse.() -> Unit = {},
): TvSeriesSearchResponse = MainAPIKt.newTvSeriesSearchResponse(
    this, name, url, type, fix,
    { value: Any? -> (value as TvSeriesSearchResponse).apply(initializer); kotlin.Unit },
)

fun MainAPI.newEpisode(
    data: Any,
    initializer: Episode.() -> Unit = {},
): Episode = MainAPIKt.newEpisode(
    this, data,
    { value: Any? -> (value as Episode).apply(initializer); kotlin.Unit },
)

suspend fun MainAPI.newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType,
    data: Any?,
    initializer: (suspend MovieLoadResponse.() -> Unit)? = null,
): MovieLoadResponse = buildMovieLoadResponse(
    this, name, url, type, data, initializer
)

suspend fun MainAPI.newTvSeriesLoadResponse(
    name: String,
    url: String,
    type: TvType,
    episodes: List<Episode>,
    initializer: (suspend TvSeriesLoadResponse.() -> Unit)? = null,
): TvSeriesLoadResponse = buildTvSeriesLoadResponse(
    this, name, url, type, episodes, initializer
)

/** Pinned helper executes all transforms concurrently and treats individual failures as null. */
suspend fun <R> runAllAsync(vararg transforms: suspend () -> R): List<R?> = coroutineScope {
    coroutineContext.ensureActive()
    transforms.map { transform ->
        async {
            try {
                transform()
            } catch (failure: Exception) {
                android.util.Log.w("CS3", "Concurrent provider request failed", failure)
                null
            }
        }
    }.awaitAll()
}

/** Maps the pinned actor/name cast pairs into the LoadResponse ABI representation. */
@JvmName("addActorsWithRoleString")
fun LoadResponse.addActors(actors: List<Pair<Actor, String?>>?) {
    setActors(actors?.map { (actor, role) -> ActorData(actor, null, role, null) })
}

@JvmName("addActorsOnly")
fun LoadResponse.addActors(actors: List<Actor>?) {
    setActors(actors?.map { actor -> ActorData(actor, null, null, null) })
}

fun LoadResponse.addImdbId(id: String?) {
    if (id == null) return
    val current = getSyncData() ?: linkedMapOf()
    val encoded = current[""]
    val ids = try {
        if (encoded.isNullOrBlank()) linkedMapOf() else MainAPIKt.getMapper().readValue(
            encoded,
            object : com.fasterxml.jackson.core.type.TypeReference<MutableMap<String, String>>() {},
        )
    } catch (_: Exception) {
        linkedMapOf()
    }
    ids["Imdb"] = id
    current[""] = MainAPIKt.getMapper().writeValueAsString(ids)
    setSyncData(current)
}

suspend fun LoadResponse.addTrailer(
    trailerUrls: List<String>?,
    referer: String? = null,
    addRaw: Boolean = false,
) {
    if (trailerUrls == null) return
    @Suppress("UNCHECKED_CAST")
    val current = (getTrailers() as? MutableList<TrailerData>) ?: arrayListOf()
    trailerUrls.filter { it.isNotBlank() }.forEach { url ->
        current.add(TrailerData(url, referer, addRaw))
    }
    setTrailers(current)
}
