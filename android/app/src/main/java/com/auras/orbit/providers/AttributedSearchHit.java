package com.auras.orbit.providers;

/** A title result paired with the provider that returned it. */
public final class AttributedSearchHit {
    public final ProviderDescriptor provider;
    public final MediaItem item;

    public AttributedSearchHit(ProviderDescriptor provider, MediaItem item) {
        if (provider == null || item == null) throw new IllegalArgumentException("provider and item are required");
        this.provider = provider;
        this.item = item;
    }
}