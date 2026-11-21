package com.auras.orbit.metadata

import com.lagradost.cloudstream3.metaproviders.createOrbitTmdbClient
import com.uwetrottmann.tmdb2.Tmdb
import com.uwetrottmann.tmdb2.entities.AppendToResponse
import com.uwetrottmann.tmdb2.entities.BasePerson
import com.uwetrottmann.tmdb2.entities.Media
import com.uwetrottmann.tmdb2.entities.Person
import com.uwetrottmann.tmdb2.entities.PersonCastCredit
import com.uwetrottmann.tmdb2.entities.PersonCrewCredit
import com.uwetrottmann.tmdb2.enumerations.AppendToResponseItem
import com.uwetrottmann.tmdb2.enumerations.MediaType
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

/** TMDB person lookup and combined credits, independent of provider metadata or streams. */
class TmdbActorMetadataRepository @JvmOverloads constructor(
    private val tmdb: Tmdb = createOrbitTmdbClient(),
) : ActorMetadataRepository {
    override fun load(actorName: String, contextTitle: String): ActorMetadata? {
        val query = actorName.trim()
        if (query.isEmpty()) return null

        val searchResponse = tmdb.searchService()
            .person(query, 1, LANGUAGE, REGION, false)
            .execute()
        if (!searchResponse.isSuccessful) {
            searchResponse.errorBody()?.close()
            throw IOException("TMDB person search failed (${searchResponse.code()})")
        }
        val results = searchResponse.body()?.results.orEmpty()
        val match = selectPerson(results, query, contextTitle)
            ?: return null
        val personId = match.id ?: return null

        val summaryResponse = tmdb.personService().summary(
            personId,
            LANGUAGE,
            AppendToResponse(AppendToResponseItem.COMBINED_CREDITS),
        ).execute()
        if (!summaryResponse.isSuccessful) {
            summaryResponse.errorBody()?.close()
            throw IOException("TMDB person details failed (${summaryResponse.code()})")
        }
        val person = summaryResponse.body()
            ?: throw IOException("TMDB returned an empty person response")

        return ActorMetadata(
            person.name.orEmpty().ifBlank { match.name.orEmpty() },
            person.profile_path.orEmpty(),
            person.biography.orEmpty(),
            formatDate(person.birthday),
            formatDate(person.deathday),
            person.place_of_birth.orEmpty(),
            combinedCredits(person),
        )
    }

    private fun selectPerson(results: List<BasePerson>, actorName: String, contextTitle: String): BasePerson? {
        val expectedName = normalize(actorName)
        if (expectedName.isEmpty()) return null
        val expectedTitle = normalize(contextTitle)
        return results.withIndex()
            .asSequence()
            .filter { normalize(it.value.name.orEmpty()) == expectedName }
            .sortedWith(
                compareByDescending<IndexedValue<BasePerson>> {
                    knownForContextScore(it.value, expectedTitle)
                }.thenBy { it.index },
            )
            .firstOrNull()
            ?.value
    }

    private fun knownForContextScore(person: BasePerson, contextTitle: String): Int {
        if (contextTitle.isEmpty()) return 0
        return person.known_for.orEmpty().any { media ->
            val names = listOf(media?.movie?.title, media?.tvShow?.name)
            names.any { normalize(it.orEmpty()) == contextTitle }
        }.let { if (it) 1 else 0 }
    }

    private fun combinedCredits(person: Person): List<ActorMetadata.Credit> {
        val credits = person.combined_credits ?: return emptyList()
        val unique = LinkedHashMap<String, MutableCredit>()

        credits.cast.orEmpty().forEach { cast ->
            val work = work(cast?.media) ?: return@forEach
            val role = cast?.character?.trim().orEmpty().takeIf { it.isNotEmpty() }
                ?.let { "Cast · $it" }.orEmpty()
            addCredit(unique, cast?.id, work, role)
        }
        credits.crew.orEmpty().forEach { crew ->
            val work = work(crew?.media) ?: return@forEach
            val details = listOfNotNull(
                crew?.department?.trim()?.takeIf { it.isNotEmpty() },
                crew?.job?.trim()?.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            val role = if (details.isEmpty()) "Crew" else "Crew · $details"
            addCredit(unique, crew?.id, work, role)
        }

        return unique.values
            .sortedWith(compareByDescending<MutableCredit> { it.year.toIntOrNull() ?: 0 }
                .thenBy { it.title.lowercase(Locale.ROOT) })
            .map { credit ->
                ActorMetadata.Credit(
                    credit.title,
                    credit.year,
                    credit.mediaType,
                    credit.roles.joinToString("; "),
                    credit.posterPath,
                )
            }
    }

    private fun addCredit(
        destination: MutableMap<String, MutableCredit>,
        id: Int?,
        work: Work,
        role: String,
    ) {
        val key = if (id != null) "${work.mediaType}:$id"
            else "${work.mediaType}:${normalize(work.title)}:${work.year}"
        val existing = destination[key]
        if (existing != null) {
            if (role.isNotEmpty()) existing.roles.add(role)
            return
        }
        val roles = LinkedHashSet<String>()
        if (role.isNotEmpty()) roles.add(role)
        destination[key] = MutableCredit(
            work.title, work.year, work.mediaType, work.posterPath, roles,
        )
    }

    private fun work(media: Media?): Work? {
        if (media == null) return null
        val movie = media.movie
        val series = media.tvShow
        return when {
            media.media_type == MediaType.MOVIE || movie != null -> {
                val title = movie?.title?.trim().orEmpty()
                if (title.isEmpty()) null else Work(
                    title, year(movie?.release_date), "Movie", movie?.poster_path.orEmpty(),
                )
            }
            media.media_type == MediaType.TV || series != null -> {
                val title = series?.name?.trim().orEmpty()
                if (title.isEmpty()) null else Work(
                    title, year(series?.first_air_date), "TV", series?.poster_path.orEmpty(),
                )
            }
            else -> null
        }
    }

    private fun year(date: Date?): String = date?.let { SimpleDateFormat("yyyy", Locale.US).format(it) }.orEmpty()

    private fun formatDate(date: Date?): String =
        date?.let { SimpleDateFormat("d MMMM yyyy", Locale.US).format(it) }.orEmpty()

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private data class Work(
        val title: String,
        val year: String,
        val mediaType: String,
        val posterPath: String,
    )

    private data class MutableCredit(
        val title: String,
        val year: String,
        val mediaType: String,
        val posterPath: String,
        val roles: LinkedHashSet<String>,
    )

    private companion object {
        const val LANGUAGE = "en-US"
        const val REGION = "US"
    }
}
