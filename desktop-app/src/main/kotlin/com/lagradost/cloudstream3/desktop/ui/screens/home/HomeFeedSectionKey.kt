package com.lagradost.cloudstream3.desktop.ui.screens.home

enum class HomeFeedSectionKey(val displayName: String, val description: String) {
    CONTINUE_WATCHING("Continue Watching", "In-progress movies and series"),
    UPCOMING_EPISODES("Coming Up", "Upcoming episodes from followed series"),
    LIBRARY("My Library", "Your saved movies and series"),
    CUSTOM_LISTS("My Lists", "Pinned custom lists from your Library"),
    RECENTLY_UPDATED("Recently Added / Updated", "New and recently updated catalog titles"),
    RECOMMENDED("Recommended for You", "Suggestions based on your watch history and library"),
    TRENDING("Trending / Popular", "Popular titles from your enabled catalogs"),
    ;

    companion object {
        val defaultOrder = listOf(
            CONTINUE_WATCHING,
            UPCOMING_EPISODES,
            LIBRARY,
            CUSTOM_LISTS,
            RECENTLY_UPDATED,
            RECOMMENDED,
            TRENDING,
        )

        fun parseOrder(raw: String?): List<HomeFeedSectionKey> {
            if (raw.isNullOrBlank()) return defaultOrder
            val parsed = raw.split(",").mapNotNull { value ->
                entries.find { it.name.equals(value.trim(), ignoreCase = true) }
            }.distinct()
            return parsed + defaultOrder.filterNot(parsed::contains)
        }

        fun parseDisabled(raw: String?): Set<HomeFeedSectionKey> {
            if (raw.isNullOrBlank() || raw.equals("NONE", ignoreCase = true)) return emptySet()
            return raw.split(",").mapNotNull { value ->
                entries.find { it.name.equals(value.trim(), ignoreCase = true) }
            }.toSet()
        }

        fun serialize(values: Collection<HomeFeedSectionKey>): String =
            if (values.isEmpty()) "NONE" else values.joinToString(",") { it.name }
    }
}

enum class HomeContinueWatchingPosition(val displayName: String) {
    ABOVE_HERO("Above hero"),
    BELOW_HERO("Below hero"),
    ;

    companion object {
        fun fromString(value: String?): HomeContinueWatchingPosition =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: BELOW_HERO
    }
}
