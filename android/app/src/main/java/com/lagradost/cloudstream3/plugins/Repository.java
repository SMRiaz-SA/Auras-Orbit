package com.lagradost.cloudstream3.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Orbit-owned repository descriptor value from the pinned CS3 contract. */
public final class Repository {
    private final String iconUrl;
    private final String name;
    private final String description;
    private final int manifestVersion;
    private final List<String> pluginLists;

    public Repository(String iconUrl, String name, String description, int manifestVersion,
                      List<String> pluginLists) {
        this.iconUrl = iconUrl;
        this.name = name;
        this.description = description;
        this.manifestVersion = manifestVersion;
        this.pluginLists = Collections.unmodifiableList(new ArrayList<>(
                pluginLists == null ? Collections.emptyList() : pluginLists));
    }

    public String getIconUrl() { return iconUrl; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getManifestVersion() { return manifestVersion; }
    public List<String> getPluginLists() { return pluginLists; }
}
