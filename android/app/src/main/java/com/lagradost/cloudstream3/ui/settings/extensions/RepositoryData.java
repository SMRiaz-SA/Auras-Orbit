package com.lagradost.cloudstream3.ui.settings.extensions;

/** Orbit-owned repository metadata value used by the sampled CS3 ABI. */
public final class RepositoryData {
    private final String iconUrl;
    private final String name;
    private final String url;
    private final String description;

    public RepositoryData(String name, String url) {
        this(null, name, url, null);
    }

    public RepositoryData(String iconUrl, String name, String url) {
        this(iconUrl, name, url, null);
    }

    public RepositoryData(String iconUrl, String name, String url, String description) {
        this.iconUrl = iconUrl;
        this.name = name;
        this.url = url;
        this.description = description;
    }

    public String getIconUrl() { return iconUrl; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getDescription() { return description; }
}
