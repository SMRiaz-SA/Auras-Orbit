package com.auras.orbit.providers;

/** Provider attribution and stable source ordering for one Home shelf. */
public final class AttributedHomeSection {
    public final ProviderDescriptor provider;
    public final int sourceOrder;
    public final HomeSection section;

    public AttributedHomeSection(ProviderDescriptor provider, int sourceOrder, HomeSection section) {
        if (provider == null || section == null) throw new IllegalArgumentException("provider and section are required");
        if (sourceOrder < 0) throw new IllegalArgumentException("sourceOrder cannot be negative");
        this.provider = provider;
        this.sourceOrder = sourceOrder;
        this.section = section;
    }
}