package com.lagradost.cloudstream3;

/** Orbit-owned provider category metadata matching the pinned CS3 MainPageData data class. */
public final class MainPageData {
    private final String name;
    private final String data;
    private final boolean horizontalImages;

    public MainPageData(String name, String data, boolean horizontalImages) {
        this.name = name;
        this.data = data;
        this.horizontalImages = horizontalImages;
    }

    public MainPageData(String name, String data, boolean horizontalImages, int mask,
                        kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(name, data, (mask & 4) != 0 ? false : horizontalImages);
    }

    public String getName() { return name; }
    public String getData() { return data; }
    public boolean getHorizontalImages() { return horizontalImages; }
}
