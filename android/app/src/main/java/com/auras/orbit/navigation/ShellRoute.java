package com.auras.orbit.navigation;

/** The only top-level destinations in the accepted Orbit shell. */
public enum ShellRoute {
    HOME("Home", "Choose a provider feed and return to your lists"),
    SEARCH("Search", "Search across all installed providers"),
    LIBRARY("Library", "Your personal lists and connected media"),
    DOWNLOADS("Downloads", "Offline storage and saved items"),
    SETTINGS("Settings", "Extensions, connections, and app preferences");

    private final String title;
    private final String subtitle;

    ShellRoute(String title, String subtitle) {
        this.title = title;
        this.subtitle = subtitle;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }
}
