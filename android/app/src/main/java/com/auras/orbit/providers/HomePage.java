package com.auras.orbit.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One bounded provider Home batch, with continuation for the provider's section catalog. */
public final class HomePage {
    public final List<HomeSection> sections;
    /** Provider-level cursor; independent of each HomeSection.nextCursor item-page cursor. */
    public final String nextProviderCursor;
    /** Non-empty when one or more catalog requests failed in this batch. */
    public final String continuationError;
    /** True while more never-attempted provider Home sections remain. */
    public final boolean hasMoreProviderSections;
    /** True when the cursor represents a manual retry-only continuation. */
    public final boolean retryAvailable;

    public HomePage(List<HomeSection> sections, String nextProviderCursor) {
        this(sections, nextProviderCursor, "", nextProviderCursor != null
                && !nextProviderCursor.isEmpty(), false);
    }

    public HomePage(List<HomeSection> sections, String nextProviderCursor,
                    String continuationError) {
        this(sections, nextProviderCursor, continuationError,
                nextProviderCursor != null && !nextProviderCursor.isEmpty(),
                continuationError != null && !continuationError.isEmpty());
    }

    public HomePage(List<HomeSection> sections, String nextProviderCursor,
            String continuationError, boolean hasMoreProviderSections, boolean retryAvailable) {
        if (sections == null) {
            this.sections = Collections.emptyList();
        } else {
            int count = sections.size();
            if (count < 0 || count > ProviderResultBudget.MAX_HOME_SECTIONS) {
                throw new IllegalArgumentException("Home page exceeds emergency section ceiling");
            }
            ArrayList<HomeSection> copy = new ArrayList<>(count);
            for (int index = 0; index < count; index++) copy.add(sections.get(index));
            this.sections = Collections.unmodifiableList(copy);
        }
        this.nextProviderCursor = nextProviderCursor == null ? "" : nextProviderCursor;
        this.continuationError = continuationError == null ? "" : continuationError;
        this.hasMoreProviderSections = hasMoreProviderSections;
        this.retryAvailable = retryAvailable;
    }
}
