package com.auras.orbit.metadata;

/** Verified, typed *display-only* TMDB title data, with no provider availability or actions. */
public final class TitleCatalogRecord {
    public final int catalogId;
    public final String kind;
    public final String title;
    public final String synopsis;
    public final String year;
    public final String posterUrl;
    public final String backdropUrl;

    public TitleCatalogRecord(int catalogId, String kind, String title, String synopsis,
                              String year, String posterUrl, String backdropUrl) {
        if (catalogId <= 0 || !("MOVIE".equals(kind) || "SERIES".equals(kind)))
            throw new IllegalArgumentException("A typed TMDB ID is required");
        this.catalogId = catalogId;
        this.kind = kind;
        this.title = bounded(title, 1024);
        this.synopsis = bounded(synopsis, 24000);
        this.year = bounded(year, 16);
        this.posterUrl = trustedTmdbArtwork(posterUrl);
        this.backdropUrl = trustedTmdbArtwork(backdropUrl);
    }

    private static String bounded(String input, int max) {
        if (input == null) return "";
        String s = input.trim();
        return s.length() <= max ? s : "";
    }
    private static String trustedTmdbArtwork(String input) {
        String s = bounded(input, 512);
        return s.startsWith("https://image.tmdb.org/t/p/")
                && s.indexOf('?') < 0 && s.indexOf('#') < 0
                && s.matches("https://image\\.tmdb\\.org/t/p/[A-Za-z0-9._/-]{1,400}") ? s : "";
    }
}
