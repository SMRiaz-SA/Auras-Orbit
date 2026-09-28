package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.SyncAPI
import java.time.Instant

/** One regular episode in Simkl's canonical catalog order. Specials are deliberately excluded. */
internal data class SimklCatalogEpisode(
    val season: Int? = null,
    val episode: Int? = null,
    val type: String? = null,
    val aired: Boolean? = null,
)

internal data class SimklEpisodeRef(val season: Int?, val number: Int)

internal data class SimklEpisodeProgressPlan(
    val add: List<SimklEpisodeRef>,
    val remove: List<SimklEpisodeRef>,
)

/** Adapts the shared tracker count model to Simkl's season/episode history model. */
internal object SimklEpisodeProgress {
    /** Build an additive Simkl history event; unlike updateStatus this never removes remote episodes. */
    fun historyEventBody(
        media: SyncAPI.SyncMediaIdentity,
        events: List<SyncAPI.WatchedEpisodeEvent>,
    ): Map<String, Any>? {
        if (events.isEmpty() || media.mediaType == SyncAPI.SyncMediaType.MOVIE) return null

        val anime = media.mediaType == SyncAPI.SyncMediaType.ANIME
        val refs = events.map { event ->
            val episode = event.episode
            if (anime && episode.season != null) return null
            if (!anime && episode.season == null) return null
            SimklEpisodeRef(episode.season, episode.number) to event.watchedAt
        }

        val ids = media.externalIds
            .filterKeys { it in supportedIdKeys }
            .filterValues(String::isNotBlank)
        if (ids.isEmpty() && media.title.isBlank()) return null

        val item = buildMap<String, Any> {
            if (ids.isNotEmpty()) put("ids", ids)
            if (media.title.isNotBlank()) put("title", media.title)
            media.year?.takeIf { it > 0 }?.let { put("year", it) }
            if (anime) {
                put(
                    "episodes",
                    refs.map { (ref, watchedAt) ->
                        buildMap<String, Any> {
                            put("number", ref.number)
                            watchedAt?.takeIf { it > 0 }?.let { put("watched_at", Instant.ofEpochMilli(it).toString()) }
                        }
                    },
                )
            } else {
                put(
                    "seasons",
                    refs.groupBy { (ref, _) -> requireNotNull(ref.season) }
                        .toSortedMap()
                        .map { (season, seasonEvents) ->
                            mapOf(
                                "number" to season,
                                "episodes" to seasonEvents.map { (ref, watchedAt) ->
                                    buildMap<String, Any> {
                                        put("number", ref.number)
                                        watchedAt?.takeIf { it > 0 }?.let { put("watched_at", Instant.ofEpochMilli(it).toString()) }
                                    }
                                },
                            )
                        },
                )
            }
        }
        return mapOf((if (anime) "anime" else "shows") to listOf(item))
    }

    fun canonicalSelection(
        catalog: List<SimklCatalogEpisode>,
        anime: Boolean,
    ): Set<SyncAPI.SyncEpisode> = catalog.asSequence()
        .filter { it.type == "episode" || (!anime && it.type == "special") }
        .filter { it.aired != false }
        .mapNotNull { entry ->
            val number = entry.episode?.takeIf { it > 0 } ?: return@mapNotNull null
            val season = if (anime) null else entry.season?.takeIf { it >= 0 } ?: return@mapNotNull null
            SyncAPI.SyncEpisode(season, number)
        }
        .toSet()

    fun exactPlan(
        current: Set<SyncAPI.SyncEpisode>,
        target: Set<SyncAPI.SyncEpisode>,
        anime: Boolean,
    ): SimklEpisodeProgressPlan? {
        fun toRef(episode: SyncAPI.SyncEpisode): SimklEpisodeRef? {
            if (anime && episode.season != null) return null
            if (!anime && episode.season == null) return null
            return SimklEpisodeRef(episode.season, episode.number)
        }

        val currentRefs = current.map { toRef(it) ?: return null }.toSet()
        val targetRefs = target.map { toRef(it) ?: return null }.toSet()
        return SimklEpisodeProgressPlan(
            add = (targetRefs - currentRefs).sortedWith(episodeOrder),
            remove = (currentRefs - targetRefs).sortedWith(episodeOrder),
        )
    }

    fun selectionIsInCatalog(
        selection: Set<SyncAPI.SyncEpisode>,
        catalog: List<SimklCatalogEpisode>,
        anime: Boolean,
    ): Boolean {
        if (selection.any { episode ->
                if (anime) episode.season != null else episode.season == null
            }
        ) {
            return false
        }
        return canonicalSelection(catalog, anime).containsAll(selection)
    }

    fun historyBody(
        simklId: Int,
        episodes: List<SimklEpisodeRef>,
        anime: Boolean,
    ): Map<String, Any> {
        val progress: Map<String, Any> = if (anime) {
            mapOf("episodes" to episodes.map { mapOf("number" to it.number) })
        } else {
            mapOf(
                "seasons" to episodes.groupBy { requireNotNull(it.season) }
                    .toSortedMap()
                    .map { (season, entries) ->
                        mapOf("number" to season, "episodes" to entries.map { mapOf("number" to it.number) })
                    },
            )
        }
        val bucket = if (anime) "anime" else "shows"
        return mapOf(bucket to listOf(mapOf("ids" to mapOf("simkl" to simklId)) + progress))
    }

    fun plan(
        catalog: List<SimklCatalogEpisode>,
        previousCount: Int,
        targetCount: Int,
        anime: Boolean,
    ): SimklEpisodeProgressPlan? {
        if (previousCount < 0 || targetCount < 0) return null

        val episodes = catalog.asSequence()
            .filter { it.type == "episode" && it.aired != false }
            .mapNotNull { entry ->
                val number = entry.episode?.takeIf { it > 0 } ?: return@mapNotNull null
                val season = if (anime) null else entry.season?.takeIf { it >= 0 } ?: return@mapNotNull null
                SimklEpisodeRef(season, number)
            }
            .distinct()
            .let { sequence ->
                if (anime) {
                    sequence.sortedBy(SimklEpisodeRef::number)
                } else {
                    sequence.sortedWith(compareBy<SimklEpisodeRef>({ it.season }, { it.number }))
                }
            }
            .toList()

        if (previousCount > episodes.size || targetCount > episodes.size) return null
        val start = minOf(previousCount, targetCount)
        val end = maxOf(previousCount, targetCount)
        val changed = episodes.subList(start, end)
        return if (targetCount > previousCount) {
            SimklEpisodeProgressPlan(add = changed, remove = emptyList())
        } else {
            SimklEpisodeProgressPlan(add = emptyList(), remove = changed)
        }
    }

    private val episodeOrder = compareBy<SimklEpisodeRef>({ it.season ?: -1 }, { it.number })

    private val supportedIdKeys = setOf("simkl", "mal", "anilist", "imdb", "tmdb", "tvdb", "anidb", "kitsu")
}
