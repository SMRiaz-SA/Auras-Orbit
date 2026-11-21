package com.lagradost.cloudstream3.syncproviders;

/**
 * Pinned CloudStream sync-provider identifiers, in their source enum order.
 * Keep the declaration order aligned with library SyncAPI.kt because callers may
 * observe the enum name, ordinal, or values array even when archive samples only
 * reference the constants.
 */
public enum SyncIdName {
    Anilist,
    MyAnimeList,
    Kitsu,
    Trakt,
    Imdb,
    Simkl,
    LocalList
}
