package com.auras.orbit.jellyfin;

import java.io.IOException;

/** Reject oversized server arrays before allocating per-record application objects. */
public final class JellyfinResponseBudgets {
    public static final int MAX_LIBRARIES = 256;
    public static final int MAX_RECENT_ITEMS = 100;
    public static final int MAX_PLAYBACK_SOURCES = 64;
    public static final int MAX_MEDIA_STREAMS_PER_SOURCE = 256;

    private JellyfinResponseBudgets() { }

    public static void requireCount(int supplied, int max, String category) throws IOException {
        if (supplied < 0 || max < 0 || supplied > max) {
            throw new IOException("Jellyfin returned an oversized or invalid " + category + " array.");
        }
    }
}
