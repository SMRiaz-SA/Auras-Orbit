package com.auras.orbit.player;

/** Selects only a reported IMDb ID or the user's title text; it never manufactures identifiers. */
public final class SubSourceSearchTerm {
    private SubSourceSearchTerm() { }

    public static String forQuery(String reportedImdbId, String title) {
        if (reportedImdbId != null && reportedImdbId.matches("(?i)tt[0-9]{5,12}")) {
            return reportedImdbId;
        }
        if (title == null) return null;
        String normalizedTitle = title.trim();
        return normalizedTitle.isEmpty() ? null : normalizedTitle;
    }
}
