package com.auras.orbit.providers;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Activity-scoped, volatile cache of exact provider detail results. Never persisted.
 *
 * <p>Provider payloads, including episode data and optional artwork headers, can be sensitive
 * and are retained ONLY in process memory. Caller must independently validate the active
 * profile, provider registration and action context before deciding to skip a provider load.
 * The cache does not infer a canonical work, prove availability, or authorize playback.</p>
 *
 * <p>Uses a monotonic caller clock. Bounded by entries, estimated characters and age;
 * intentionally declines huge titles rather than holding all 500 maximum episodes in RAM.</p>
 */
public final class ProviderDetailsMemoryCache {
    public static final int MAX_ENTRIES = 12;
    public static final long MAX_ENTRY_CHARS = 240_000L;
    public static final long MAX_TOTAL_CHARS = 960_000L;
    public static final long MAX_AGE_MS = 10L * 60_000L;

    private final LinkedHashMap<Key, Entry> lru = new LinkedHashMap<>(16, .75f, true);
    private long totalChars;

    public synchronized MediaItem get(MediaProvider provider, String profileId,
                                      MediaItem seed, long nowMs) {
        Key key = Key.of(provider, profileId, seed);
        if (key == null) return null;
        Entry entry = lru.get(key);
        if (entry == null) return null;
        // A backwards clock must not make an entry immortal.
        if (nowMs < entry.writtenAtMs || nowMs - entry.writtenAtMs >= MAX_AGE_MS) {
            remove(key);
            return null;
        }
        return entry.details;
    }

    public synchronized boolean put(MediaProvider provider, String profileId,
                                    MediaItem seed, MediaItem details, long nowMs) {
        Key key = Key.of(provider, profileId, seed);
        if (key == null) return false;
        // A new response must invalidate old cached content even when it is malformed.
        remove(key);
        if (details == null || details.kind != seed.kind
                || !details.stableId.equals(seed.stableId)) return false;
        long chars = estimateChars(details);
        if (chars > MAX_ENTRY_CHARS || chars <= 0) return false;
        lru.put(key, new Entry(details, nowMs, chars));
        totalChars += chars;
        trim();
        return true;
    }

    /** Small semantic state only: no Android View, Activity or bitmap references. */
    public static final class ViewState {
        public final String selectedEpisodeId;
        public final Integer selectedSeason;
        public final int scrollY;
        public final String focusTag;
        public ViewState(String selectedEpisodeId, Integer selectedSeason, int scrollY,
                         String focusTag) {
            this.selectedEpisodeId = selectedEpisodeId == null ? ""
                    : selectedEpisodeId.length() <= 256 ? selectedEpisodeId : "";
            this.selectedSeason = selectedSeason;
            this.scrollY = Math.max(0, Math.min(scrollY, 1_000_000));
            this.focusTag = focusTag == null ? ""
                    : focusTag.length() <= 256 ? focusTag : "";
        }
    }

    public synchronized void rememberViewState(MediaProvider provider, String profileId,
                                               MediaItem seed, ViewState state) {
        Key key = Key.of(provider, profileId, seed);
        if (key == null || state == null) return;
        Entry entry = lru.get(key);
        if (entry != null) entry.viewState = state;
    }

    public synchronized ViewState viewState(MediaProvider provider, String profileId,
                                             MediaItem seed, long nowMs) {
        Key key = Key.of(provider, profileId, seed);
        if (get(provider, profileId, seed, nowMs) == null || key == null) return null;
        Entry entry = lru.get(key);
        return entry == null ? null : entry.viewState;
    }

    public synchronized void clear() {
        lru.clear();
        totalChars = 0L;
    }

    public synchronized int size() { return lru.size(); }
    public synchronized long estimatedChars() { return totalChars; }

    private void trim() {
        Iterator<Map.Entry<Key, Entry>> it = lru.entrySet().iterator();
        while ((lru.size() > MAX_ENTRIES || totalChars > MAX_TOTAL_CHARS) && it.hasNext()) {
            totalChars -= it.next().getValue().chars;
            it.remove();
        }
    }

    private void remove(Key key) {
        Entry removed = lru.remove(key);
        if (removed != null) totalChars -= removed.chars;
    }

    private static long length(String s) { return s == null ? 0L : s.length(); }

    /**
     * A MediaItem is immutable, but a related title can itself contain hundreds of
     * episode payloads, headers, and cast records. Count the complete retained
     * one-level object graph, not just the visible fields of related cards. A
     * provider-owned list has already been snapshotted by the MediaItem constructor.
     */
    private static long estimateChars(MediaItem item) {
        long total = estimateFlatItemChars(item);
        if (total > MAX_ENTRY_CHARS) return total;
        for (MediaItem related : item.relatedTitles) {
            // Related cards are normally shallow. Do not admit an unexpected
            // nested graph even if a future model version permits one.
            if (!related.relatedTitles.isEmpty()) return MAX_ENTRY_CHARS + 1L;
            total += estimateFlatItemChars(related);
            if (total > MAX_ENTRY_CHARS) return total;
        }
        return total;
    }

    private static long estimateFlatItemChars(MediaItem item) {
        long total = length(item.stableId) + length(item.title) + length(item.detailUrl)
                + length(item.posterUrl) + length(item.overview) + length(item.year)
                + length(item.providerData) + length(item.backgroundPosterUrl)
                + length(item.logoUrl) + length(item.contentRating) + length(item.score);
        if (total > MAX_ENTRY_CHARS) return total;
        for (String tag : item.tags) {
            total += length(tag) + 32L;
            if (total > MAX_ENTRY_CHARS) return total;
        }
        for (Map.Entry<String, String> header : item.posterHeaders.entrySet()) {
            total += length(header.getKey()) + length(header.getValue()) + 96L;
            if (total > MAX_ENTRY_CHARS) return total;
        }
        for (MediaItem.EpisodeItem episode : item.episodes) {
            total += length(episode.stableId) + length(episode.title)
                    + length(episode.providerData) + length(episode.posterUrl)
                    + length(episode.overview) + length(episode.variant)
                    + length(episode.score) + 256L;
            if (total > MAX_ENTRY_CHARS) return total;
        }
        for (MediaItem.CastMember member : item.cast) {
            total += length(member.name) + length(member.role)
                    + length(member.imageUrl) + 128L;
            if (total > MAX_ENTRY_CHARS) return total;
        }
        for (MediaItem.SeasonInfo season : item.seasonNames) {
            total += length(season.name) + 96L;
            if (total > MAX_ENTRY_CHARS) return total;
        }
        // The remaining small immutable fields, list/map shells and references.
        return total + 512L + item.relatedTitles.size() * 64L;
    }

    private static final class Entry {
        final MediaItem details;
        final long writtenAtMs;
        final long chars;
        ViewState viewState;
        Entry(MediaItem details, long writtenAtMs, long chars) {
            this.details = details;
            this.writtenAtMs = writtenAtMs;
            this.chars = chars;
        }
    }

    private static final class Key {
        final MediaProvider provider;
        final String profileId;
        final String stableId;
        final String detailUrl;
        private Key(MediaProvider provider, String profileId, String stableId, String detailUrl) {
            this.provider = provider;
            this.profileId = profileId;
            this.stableId = stableId;
            this.detailUrl = detailUrl;
        }
        static Key of(MediaProvider provider, String profileId, MediaItem seed) {
            if (provider == null || seed == null || profileId == null || profileId.isEmpty()
                    || seed.detailUrl.isEmpty() || seed.detailUrl.length() > 8192) return null;
            return new Key(provider, profileId, seed.stableId, seed.detailUrl);
        }
        @Override public int hashCode() {
            int hash = System.identityHashCode(provider);
            hash = 31 * hash + profileId.hashCode();
            hash = 31 * hash + stableId.hashCode();
            return 31 * hash + detailUrl.hashCode();
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Key)) return false;
            Key other = (Key) value;
            return provider == other.provider && profileId.equals(other.profileId)
                    && stableId.equals(other.stableId) && detailUrl.equals(other.detailUrl);
        }
    }
}
