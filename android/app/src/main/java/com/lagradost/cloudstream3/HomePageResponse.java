package com.lagradost.cloudstream3;

import java.util.List;

/** Orbit-owned response container matching the pinned CS3 HomePageResponse data class. */
public final class HomePageResponse {
    private final List<HomePageList> items;
    private final boolean hasNext;

    public HomePageResponse(List<HomePageList> items, boolean hasNext) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(items, "items");
        this.items = items;
        this.hasNext = hasNext;
    }

    public HomePageResponse(List<HomePageList> items, boolean hasNext, int mask,
                            kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(items, (mask & 2) != 0 ? false : hasNext);
    }

    public List<HomePageList> getItems() { return items; }
    public boolean getHasNext() { return hasNext; }
}
