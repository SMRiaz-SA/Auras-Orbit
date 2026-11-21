package com.auras.orbit.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One provider Home page request. The source list ordering belongs to the caller. */
public final class HomeRequest {
    public final int page;
    public final String cursor;
    public final List<MediaKind> mediaKinds;

    public HomeRequest(int page, String cursor, List<MediaKind> mediaKinds) {
        if (page < 1) throw new IllegalArgumentException("page must be at least 1");
        this.page = page;
        this.cursor = cursor == null ? "" : cursor;
        this.mediaKinds = mediaKinds == null ? Collections.<MediaKind>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(mediaKinds));
    }
}