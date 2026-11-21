package com.lagradost.cloudstream3;

import java.util.List;

/** Orbit-owned response container matching the pinned CS3 SearchResponseList data class. */
public final class SearchResponseList {
    private final List<? extends SearchResponse> items;
    private final boolean hasNext;

    public SearchResponseList(List<? extends SearchResponse> items, boolean hasNext) {
        if (items == null) throw new IllegalArgumentException("items is required");
        this.items = items;
        this.hasNext = hasNext;
    }

    public SearchResponseList(List<? extends SearchResponse> items, boolean hasNext, int mask,
                              kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(items, (mask & 2) != 0 ? false : hasNext);
    }

    public List<? extends SearchResponse> getItems() { return items; }
    public boolean getHasNext() { return hasNext; }
}
