package com.auras.orbit.jellyfin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps the server pagination offset separate from the distinct media tiles displayed by Orbit. */
public final class JellyfinPageAccumulator {
    public static final int MAX_ITEMS_PER_RESPONSE = 100;
    public static final int MAX_LIBRARY_ITEMS = 20_000;

    private JellyfinPageAccumulator() { }

    public static Page empty() {
        return new Page(Collections.emptyList(), 0, 0, false, false);
    }

    public static Page first(int startIndex, List<JellyfinModels.MediaItem> received,
                             int declaredTotal) {
        return append(new Page(Collections.emptyList(), Math.max(0, startIndex),
                Math.max(0, declaredTotal), false, false), received, declaredTotal);
    }

    /** Duplicate IDs never inflate the tile count, but all consumed server rows advance the offset. */
    public static Page append(Page prior, List<JellyfinModels.MediaItem> received,
                              int declaredTotal) {
        if (prior == null || received == null || received.size() > MAX_ITEMS_PER_RESPONSE) {
            throw new IllegalArgumentException("Invalid or oversized Jellyfin page");
        }
        ArrayList<JellyfinModels.MediaItem> unique = new ArrayList<>(
                Math.min(MAX_LIBRARY_ITEMS, prior.items.size() + received.size()));
        Set<String> seenIds = new HashSet<>();
        for (JellyfinModels.MediaItem item : prior.items) {
            if (item != null && item.id != null && !item.id.trim().isEmpty()
                    && seenIds.add(item.id)) unique.add(item);
        }
        int added = 0;
        for (JellyfinModels.MediaItem item : received) {
            if (item != null && item.id != null && !item.id.trim().isEmpty()
                    && seenIds.add(item.id) && unique.size() < MAX_LIBRARY_ITEMS) {
                unique.add(item);
                added++;
            }
        }
        int nextIndex = JellyfinPageBounds.fallbackTotal(prior.nextServerIndex, received.size());
        int total = Math.max(nextIndex, Math.max(0, declaredTotal));
        boolean limitReached = unique.size() >= MAX_LIBRARY_ITEMS && nextIndex < total;
        boolean stalled = added == 0 && !limitReached;
        if (stalled) total = nextIndex;
        return new Page(Collections.unmodifiableList(unique), nextIndex, total,
                stalled, limitReached);
    }

    public static final class Page {
        public final List<JellyfinModels.MediaItem> items;
        public final int nextServerIndex;
        public final int totalRecords;
        public final boolean stalled;
        public final boolean limitReached;

        private Page(List<JellyfinModels.MediaItem> items, int nextServerIndex,
                     int totalRecords, boolean stalled, boolean limitReached) {
            this.items = items;
            this.nextServerIndex = nextServerIndex;
            this.totalRecords = totalRecords;
            this.stalled = stalled;
            this.limitReached = limitReached;
        }
        public boolean hasMore() {
            return !stalled && !limitReached && nextServerIndex < totalRecords;
        }
    }
}
