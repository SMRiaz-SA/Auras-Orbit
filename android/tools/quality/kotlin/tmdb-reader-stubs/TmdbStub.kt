package com.uwetrottmann.tmdb2

import com.uwetrottmann.tmdb2.entities.AppendToResponse
import java.util.Date

class Tmdb {
    var expectedMovieId = 0
    var expectedTvId = 0
    fun moviesService() = MoviesService(this)
    fun tvService() = TvService(this)
}
class ApiCall<T>(private val value: T?) {
    fun execute() = ApiResponse(value)
}
class ApiResponse<T>(private val data: T?) {
    val isSuccessful = true
    fun errorBody(): AutoCloseable? = null
    fun code(): Int = 200
    fun body(): T? = data
}
data class Movie(val id: Int, val title: String?, val original_title: String?,
                 val overview: String?, val release_date: Date?,
                 val poster_path: String?, val backdrop_path: String?)
data class Series(val id: Int, val name: String?, val original_name: String?,
                  val overview: String?, val first_air_date: Date?,
                  val poster_path: String?, val backdrop_path: String?)
class MoviesService(private val tmdb: Tmdb) {
    fun summary(id: Int, language: String, appended: AppendToResponse): ApiCall<Movie> =
        ApiCall(Movie(tmdb.expectedMovieId, "Movie $id", null, "Movie detail", null,
            "/public.jpg", "/backdrop.jpg"))
}
class TvService(private val tmdb: Tmdb) {
    fun tv(id: Int, language: String, appended: AppendToResponse): ApiCall<Series> =
        ApiCall(Series(tmdb.expectedTvId, "Show $id", null, "TV detail", null,
            "/tv.jpg", "/tv-backdrop.jpg"))
}
