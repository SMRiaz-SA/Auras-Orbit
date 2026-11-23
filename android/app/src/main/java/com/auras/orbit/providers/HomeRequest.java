package com.auras.orbit.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One provider Home page request. The source list ordering belongs to the caller. */
public final class HomeRequest {
    public final int page;
    public final String cursor;
    /** Empty for the initial all-sections request, stable section identity for a later page. */
    public final String sectionId;
    /** Provider-level continuation token; separate from each section's item cursor. */
    public final String providerCursor;
    public final List<MediaKind> mediaKinds;

    public HomeRequest(int page, String cursor, List<MediaKind> mediaKinds) {
        this(page, cursor, "", "", mediaKinds);
    }

    public HomeRequest(int page, String cursor, String sectionId, List<MediaKind> mediaKinds) {
        this(page, cursor, sectionId, "", mediaKinds);
    }

    private HomeRequest(int page, String cursor, String sectionId, String providerCursor,
                        List<MediaKind> mediaKinds) {
        if (page < 1) throw new IllegalArgumentException("page must be at least 1");
        this.page = page;
        this.cursor = cursor == null ? "" : cursor;
        this.sectionId = sectionId == null ? "" : sectionId.trim();
        this.providerCursor = providerCursor == null ? "" : providerCursor;
        this.mediaKinds = mediaKinds == null ? Collections.<MediaKind>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(mediaKinds));
    }

    public static HomeRequest forSection(String sectionId, int page, String cursor,
                                         List<MediaKind> mediaKinds) {
        if (sectionId == null || sectionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sectionId is required for a section request");
        }
        return new HomeRequest(page, cursor, sectionId, mediaKinds);
    }

    public static HomeRequest forProviderContinuation(String providerCursor,
            List<MediaKind> mediaKinds) {
        if (providerCursor == null || providerCursor.trim().isEmpty()) {
            throw new IllegalArgumentException("providerCursor is required for a continuation request");
        }
        return new HomeRequest(1, "", "", providerCursor, mediaKinds);
    }

    public boolean isSectionRequest() { return !sectionId.isEmpty(); }
    public boolean isProviderContinuationRequest() { return !providerCursor.isEmpty(); }
}
