package com.auras.orbit.metadata;

/**
 * Source-scoped display projection. Never returns or mutates a MediaItem, so a persisted
 * display fact cannot supply a stream payload, episode action, or availability claim.
 */
public final class MetadataDisplayProjection {
    private MetadataDisplayProjection() { }
    public enum Provenance { USER, CURRENT_PROVIDER, CACHED_PROVIDER, LISTING, CATALOG, MISSING }
    public static final class Value {
        public final String text;
        public final Provenance provenance;
        public final boolean cacheFresh;
        private Value(String text, Provenance provenance, boolean cacheFresh) {
            this.text = text;
            this.provenance = provenance;
            this.cacheFresh = cacheFresh;
        }
    }
    /** Explicit user edits outrank the source; source Details outranks historical cache. */
    public static Value choose(String override, String currentDetails, String cachedDetails,
                               boolean cacheFresh, String listing) {
        if (present(override)) return new Value(override.trim(), Provenance.USER, true);
        if (present(currentDetails)) return new Value(currentDetails.trim(), Provenance.CURRENT_PROVIDER, true);
        if (present(cachedDetails)) return new Value(cachedDetails.trim(), Provenance.CACHED_PROVIDER, cacheFresh);
        if (present(listing)) return new Value(listing.trim(), Provenance.LISTING, true);
        return new Value("", Provenance.MISSING, false);
    }
    /** Choose optional verified catalogue enrichment without changing source-owned actions. */
    public static Value choose(String override, String currentDetails, String cachedDetails,
                               boolean cacheFresh, String listing, String catalog,
                               MetadataFieldPolicy.Field field,
                               MetadataFieldPolicy.Mode mode, boolean verifiedTypedLink) {
        Value local = choose(override, currentDetails, cachedDetails, cacheFresh, listing);
        if (local.provenance == Provenance.USER || !verifiedTypedLink || !present(catalog))
            return local;
        MetadataFieldPolicy.Fact source = local.provenance == Provenance.MISSING ? null
                : new MetadataFieldPolicy.Fact(local.text, MetadataFieldPolicy.Origin.PROVIDER, true);
        MetadataFieldPolicy.Fact external = new MetadataFieldPolicy.Fact(catalog,
                MetadataFieldPolicy.Origin.CATALOG, true);
        MetadataFieldPolicy.Fact selected = MetadataFieldPolicy.choose(field, mode, true,
                false, source, external, null, null);
        return selected != null && selected.origin == MetadataFieldPolicy.Origin.CATALOG
                ? new Value(selected.value, Provenance.CATALOG, true) : local;
    }
    private static boolean present(String s) { return s != null && !s.trim().isEmpty(); }
}
