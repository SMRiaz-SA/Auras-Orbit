package com.auras.orbit.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Provider-authored Home shelf data rendered in its own section rail with source attribution. */
public final class HomeSection {
    /** Emergency admission ceiling, NOT the display-window size (UI pages 40 cards).
     * Exceeding it is an explicit failure, never silent loss of valid results.
     */
    public static final int MAX_DISPLAY_ITEMS = 25_000;
    public final String id;
    public final String title;
    public final List<MediaItem> items;
    public final String nextCursor;

    public HomeSection(String id, String title, List<MediaItem> items, String nextCursor) {
        this.id = requireText(id, "section id");
        this.title = requireText(title, "section title");
        if (items == null) {
            this.items = Collections.emptyList();
        } else {
            // Provider lists may override subList()/toArray() and may change their
            // reported size. Capture size once and access at most our fixed budget.
            int reportedSize = items.size();
            if (reportedSize < 0) throw new IllegalArgumentException("Invalid Home shelf item count");
            if (reportedSize > MAX_DISPLAY_ITEMS) {
                throw new IllegalArgumentException("Home shelf exceeds emergency item ceiling; paging required");
            }
            int count = reportedSize;
            ArrayList<MediaItem> copy = new ArrayList<>(count);
            for (int index = 0; index < count; index++) copy.add(items.get(index));
            this.items = Collections.unmodifiableList(copy);
        }
        this.nextCursor = nextCursor == null ? "" : nextCursor;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
