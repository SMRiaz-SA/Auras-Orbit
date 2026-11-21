package com.lagradost.cloudstream3;

import java.util.List;

/** Orbit-owned static descriptor facade; required service behavior is unimplemented. */
public final class HomePageList {
    private final String name;
    private List<? extends SearchResponse> list;
    private final boolean horizontalImages;

    public HomePageList(String name, List<? extends SearchResponse> list, boolean horizontalImages) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(name, "name");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(list, "list");
        this.name = name;
        this.list = list;
        this.horizontalImages = horizontalImages;
    }

    public HomePageList(String name, List<? extends SearchResponse> list, boolean horizontalImages,
                        int mask, kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(name, list, (mask & 4) != 0 ? false : horizontalImages);
    }

    public String getName() { return name; }
    public List<? extends SearchResponse> getList() { return list; }
    public void setList(List<? extends SearchResponse> value) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(value, "list");
        list = value;
    }
    public boolean isHorizontalImages() { return horizontalImages; }

    public String component1() { return name; }
    public List<? extends SearchResponse> component2() { return list; }
    public boolean component3() { return horizontalImages; }

    public HomePageList copy(String name, List<? extends SearchResponse> list, boolean horizontalImages) {
        return new HomePageList(name, list, horizontalImages);
    }

    public static HomePageList copy$default(HomePageList self, String name,
            List<? extends SearchResponse> list, boolean horizontalImages, int mask, Object marker) {
        if ((mask & 1) != 0) name = self.name;
        if ((mask & 2) != 0) list = self.list;
        if ((mask & 4) != 0) horizontalImages = self.horizontalImages;
        return self.copy(name, list, horizontalImages);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HomePageList)) return false;
        HomePageList that = (HomePageList) other;
        return horizontalImages == that.horizontalImages
                && kotlin.jvm.internal.Intrinsics.areEqual(name, that.name)
                && kotlin.jvm.internal.Intrinsics.areEqual(list, that.list);
    }

    @Override public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + list.hashCode();
        result = 31 * result + (horizontalImages ? 1231 : 1237);
        return result;
    }

    @Override public String toString() {
        return "HomePageList(name=" + name + ", list=" + list
                + ", isHorizontalImages=" + horizontalImages + ")";
    }
}
