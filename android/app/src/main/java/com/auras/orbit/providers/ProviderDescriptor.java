package com.auras.orbit.providers;

/** Stable user-visible identity and declared capabilities for one registered content provider. */
public final class ProviderDescriptor {
    public final String id;
    public final String name;
    public final boolean homeFeed;
    public final boolean titleSearch;
    public final boolean quickSearch;
    public final boolean details;
    public final boolean playback;
    public final boolean links;
    public final boolean downloads;

    public ProviderDescriptor(String id, String name, boolean homeFeed, boolean titleSearch,
                              boolean details, boolean playback, boolean downloads) {
        this(id, name, homeFeed, titleSearch, titleSearch, details, playback, playback, downloads);
    }

    public ProviderDescriptor(String id, String name, boolean homeFeed, boolean titleSearch,
                              boolean quickSearch, boolean details, boolean playback,
                              boolean links, boolean downloads) {
        this.id = requireText(id, "provider id");
        this.name = requireText(name, "provider name");
        this.homeFeed = homeFeed;
        this.titleSearch = titleSearch;
        this.quickSearch = quickSearch;
        this.details = details;
        this.playback = playback;
        this.links = links;
        this.downloads = downloads;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    @Override public boolean equals(Object value) {
        return value instanceof ProviderDescriptor && id.equals(((ProviderDescriptor) value).id);
    }

    @Override public int hashCode() { return id.hashCode(); }
}
