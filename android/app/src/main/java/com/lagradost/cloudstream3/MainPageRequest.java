package com.lagradost.cloudstream3;

/** Orbit-owned model matching the pinned CS3 MainPageRequest data class. */
public final class MainPageRequest {
    private final String name;
    private final String data;
    private final boolean horizontalImages;

    public MainPageRequest(String name, String data, boolean horizontalImages) {
        this.name = name;
        this.data = data;
        this.horizontalImages = horizontalImages;
    }

    public String getName() { return name; }
    public String getData() { return data; }
    public boolean getHorizontalImages() { return horizontalImages; }
}
