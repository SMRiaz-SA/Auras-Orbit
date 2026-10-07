package com.lagradost.cloudstream3.ui.result

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.search.SearchSuggestionApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** TMDB people and their combined movie/TV credits used by the Android cast browser. */
object AndroidPersonRepository {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMAGE = "https://image.tmdb.org/t/p"

    @Serializable
    private data class PersonSearchResponse(
        @JsonProperty("results") @SerialName("results") val results: List<PersonSearchItem> = emptyList(),
    )

    @Serializable
    private data class PersonSearchItem(
        @JsonProperty("id") @SerialName("id") val id: Int? = null,
        @JsonProperty("name") @SerialName("name") val name: String? = null,
        @JsonProperty("profile_path") @SerialName("profile_path") val profilePath: String? = null,
        @JsonProperty("known_for_department") @SerialName("known_for_department") val department: String? = null,
        @JsonProperty("known_for") @SerialName("known_for") val knownFor: List<PersonKnownFor> = emptyList(),
    )

    @Serializable
    private data class PersonKnownFor(
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("name") @SerialName("name") val name: String? = null,
    )

    @Serializable
    private data class PersonDetailResponse(
        @JsonProperty("id") @SerialName("id") val id: Int? = null,
        @JsonProperty("name") @SerialName("name") val name: String? = null,
        @JsonProperty("profile_path") @SerialName("profile_path") val profilePath: String? = null,
        @JsonProperty("known_for_department") @SerialName("known_for_department") val department: String? = null,
        @JsonProperty("biography") @SerialName("biography") val biography: String? = null,
        @JsonProperty("birthday") @SerialName("birthday") val birthday: String? = null,
        @JsonProperty("deathday") @SerialName("deathday") val deathday: String? = null,
        @JsonProperty("place_of_birth") @SerialName("place_of_birth") val placeOfBirth: String? = null,
        @JsonProperty("combined_credits") @SerialName("combined_credits") val credits: CombinedCredits? = null,
    )

    @Serializable
    private data class CombinedCredits(
        @JsonProperty("cast") @SerialName("cast") val cast: List<PersonCreditResponse> = emptyList(),
        @JsonProperty("crew") @SerialName("crew") val crew: List<PersonCreditResponse> = emptyList(),
    )

    @Serializable
    private data class PersonCreditResponse(
        @JsonProperty("id") @SerialName("id") val id: Int? = null,
        @JsonProperty("media_type") @SerialName("media_type") val mediaType: String? = null,
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        @JsonProperty("name") @SerialName("name") val name: String? = null,
        @JsonProperty("poster_path") @SerialName("poster_path") val posterPath: String? = null,
        @JsonProperty("release_date") @SerialName("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") @SerialName("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("character") @SerialName("character") val character: String? = null,
        @JsonProperty("job") @SerialName("job") val job: String? = null,
        @JsonProperty("department") @SerialName("department") val department: String? = null,
        @JsonProperty("overview") @SerialName("overview") val overview: String? = null,
        @JsonProperty("popularity") @SerialName("popularity") val popularity: Double? = null,
    )

    data class PersonCandidate(
        val id: Int,
        val name: String,
        val profileUrl: String?,
        val department: String?,
        val knownFor: List<String>,
    )

    data class PersonDetail(
        val id: Int,
        val name: String,
        val profileUrl: String?,
        val department: String?,
        val biography: String?,
        val birthday: String?,
        val deathday: String?,
        val placeOfBirth: String?,
        val credits: List<PersonCredit>,
    )

    data class PersonCredit(
        val id: Int,
        val mediaType: String,
        val title: String,
        val posterUrl: String?,
        val year: String?,
        val credit: String?,
        val overview: String?,
        val popularity: Double,
    )

    suspend fun searchPeople(name: String): List<PersonCandidate> {
        val response = app.get(
            "$API/search/person",
            params = mapOf(
                "api_key" to SearchSuggestionApi.TMDB_API_KEY,
                "query" to name,
                "page" to "1",
                "language" to "en-US",
                "include_adult" to "false",
            ),
            cacheTime = 60 * 24,
        ).parsed<PersonSearchResponse>()

        return response.results.mapNotNull { person ->
            val id = person.id ?: return@mapNotNull null
            val cleanName = person.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            PersonCandidate(
                id = id,
                name = cleanName,
                profileUrl = imageUrl(person.profilePath, "w185"),
                department = person.department?.takeIf { it.isNotBlank() },
                knownFor = person.knownFor.mapNotNull { it.title ?: it.name }.distinct().take(3),
            )
        }.take(8)
    }

    suspend fun loadPerson(id: Int): PersonDetail? {
        val response = app.get(
            "$API/person/$id",
            params = mapOf(
                "api_key" to SearchSuggestionApi.TMDB_API_KEY,
                "append_to_response" to "combined_credits",
                "language" to "en-US",
            ),
            cacheTime = 60 * 24,
        ).parsed<PersonDetailResponse>()

        val personId = response.id ?: return null
        val personName = response.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val credits = (response.credits?.cast.orEmpty().map { it to false } +
            response.credits?.crew.orEmpty().map { it to true })
            .mapNotNull { (credit, isCrew) ->
                val creditId = credit.id ?: return@mapNotNull null
                val media = credit.mediaType?.takeIf { it == "movie" || it == "tv" }
                    ?: return@mapNotNull null
                val title = (credit.title ?: credit.name)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                PersonCredit(
                    id = creditId,
                    mediaType = media,
                    title = title,
                    posterUrl = imageUrl(credit.posterPath, "w342"),
                    year = (credit.releaseDate ?: credit.firstAirDate)
                        ?.take(4)?.takeIf { it.length == 4 && it != "null" },
                    credit = (if (isCrew) credit.job else credit.character)
                        ?.takeIf { it.isNotBlank() }
                        ?: if (isCrew) credit.department?.takeIf { it.isNotBlank() } else null,
                    overview = credit.overview?.takeIf { it.isNotBlank() && it != "null" },
                    popularity = credit.popularity ?: 0.0,
                )
            }
            .distinctBy { "${it.mediaType}:${it.id}" }
            .sortedWith(compareByDescending<PersonCredit> { it.popularity }.thenByDescending { it.year })

        return PersonDetail(
            id = personId,
            name = personName,
            profileUrl = imageUrl(response.profilePath, "w500"),
            department = response.department?.takeIf { it.isNotBlank() },
            biography = response.biography?.takeIf { it.isNotBlank() },
            birthday = response.birthday?.takeIf { it.isNotBlank() && it != "null" },
            deathday = response.deathday?.takeIf { it.isNotBlank() && it != "null" },
            placeOfBirth = response.placeOfBirth?.takeIf { it.isNotBlank() },
            credits = credits,
        )
    }

    private fun imageUrl(path: String?, size: String): String? = path
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?.let { "$IMAGE/$size/${it.removePrefix("/")}" }
}
