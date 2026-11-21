package com.auras.orbit.jellyfin;

/** Safe arithmetic for server pagination metadata; independent of Android and HTTP. */
public final class JellyfinPageBounds {
    private JellyfinPageBounds() { }

    /** A missing total must not wrap negative when a legitimate page is near Integer.MAX_VALUE. */
    /** Malformed server totals must not hide already received page items. */
    public static int effectiveTotal(int requestedStart, int itemCount, int declaredTotal) {
        return Math.max(fallbackTotal(requestedStart, itemCount), Math.max(0, declaredTotal));
    }

    public static int fallbackTotal(int requestedStart, int itemCount) {
        if (itemCount < 0) throw new IllegalArgumentException("Invalid Jellyfin item count");
        long start = Math.max(0L, (long) requestedStart);
        return (int) Math.min(Integer.MAX_VALUE, start + (long) itemCount);
    }
}
