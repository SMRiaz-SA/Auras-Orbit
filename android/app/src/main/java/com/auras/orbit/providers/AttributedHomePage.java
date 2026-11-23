package com.auras.orbit.providers;

import java.util.List;

/** Provider attribution and source ordering for one Home batch plus its provider cursor. */
public final class AttributedHomePage {
    public final ProviderDescriptor provider;
    public final int sourceOrder;
    public final List<AttributedHomeSection> sections;
    public final String nextProviderCursor;
    public final String continuationError;
    public final boolean hasMoreProviderSections;
    public final boolean retryAvailable;

    public AttributedHomePage(ProviderDescriptor provider, int sourceOrder,
            List<AttributedHomeSection> sections, String nextProviderCursor) {
        this(provider, sourceOrder, sections, nextProviderCursor, "");
    }

    public AttributedHomePage(ProviderDescriptor provider, int sourceOrder,
            List<AttributedHomeSection> sections, String nextProviderCursor,
            String continuationError) {
        this(provider, sourceOrder, sections, nextProviderCursor, continuationError,
                nextProviderCursor != null && !nextProviderCursor.isEmpty(),
                continuationError != null && !continuationError.isEmpty());
    }

    public AttributedHomePage(ProviderDescriptor provider, int sourceOrder,
            List<AttributedHomeSection> sections, String nextProviderCursor,
            String continuationError, boolean hasMoreProviderSections,
            boolean retryAvailable) {
        if (provider == null || sections == null) {
            throw new IllegalArgumentException("provider and Home sections are required");
        }
        if (sourceOrder < 0) throw new IllegalArgumentException("sourceOrder cannot be negative");
        this.provider = provider;
        this.sourceOrder = sourceOrder;
        this.sections = sections;
        this.nextProviderCursor = nextProviderCursor == null ? "" : nextProviderCursor;
        this.continuationError = continuationError == null ? "" : continuationError;
        this.hasMoreProviderSections = hasMoreProviderSections;
        this.retryAvailable = retryAvailable;
    }
}
