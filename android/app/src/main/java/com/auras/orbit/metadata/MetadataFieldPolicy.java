package com.auras.orbit.metadata;

import java.util.Objects;

/** Deterministic display-only provenance policy. This never creates provider actions. */
public final class MetadataFieldPolicy {
    private MetadataFieldPolicy() { }
    public enum Mode { INTELLIGENT_HYBRID, PROVIDER_FIRST, CATALOG_FIRST }
    public enum Field { TITLE, SYNOPSIS, RELEASE_DATE, GENRE, CAST, BACKDROP, RATING,
        SOURCE_EPISODE, SOURCE_AVAILABILITY, SOURCE_ACTION, SOURCE_HEADERS }
    public enum Origin { PROVIDER, CATALOG, USER, JELLYFIN_LOCAL }

    public static final class Fact {
        public final String value;
        public final Origin origin;
        public final boolean verified;
        public Fact(String value, Origin origin, boolean verified) {
            this.value = value == null ? "" : value;
            this.origin = Objects.requireNonNull(origin, "origin");
            this.verified = verified;
        }
        public boolean present() { return !value.trim().isEmpty(); }
    }

    public static Fact choose(Field field, Mode mode, boolean canonicalMatchVerified,
            boolean isJellyfinItem, Fact provider, Fact catalog, Fact userOverride,
            Fact jellyfinLocal) {
        Objects.requireNonNull(field, "field"); Objects.requireNonNull(mode, "mode");
        // A user's override is display-only, never permitted to replace Play or
        // provider availability/episode ownership.
        if (sourceOwned(field)) return safe(provider, Origin.PROVIDER);
        if (userOverride != null && userOverride.present()
                && userOverride.origin == Origin.USER) return userOverride;
        if (isJellyfinItem && jellyfinLocal != null && jellyfinLocal.present()
                && jellyfinLocal.origin == Origin.JELLYFIN_LOCAL) return jellyfinLocal;
        Fact local = safe(provider, Origin.PROVIDER);
        Fact external = catalog != null && catalog.origin == Origin.CATALOG
                && catalog.verified && canonicalMatchVerified ? catalog : null;
        if (mode == Mode.PROVIDER_FIRST) return local != null && local.present() ? local : external;
        if (mode == Mode.CATALOG_FIRST) return external != null && external.present() ? external : local;
        // Hybrid improves missing art/descriptions only after a verified canonical match.
        boolean enrichable = field == Field.SYNOPSIS || field == Field.BACKDROP
                || field == Field.CAST || field == Field.GENRE;
        return enrichable && external != null && external.present() ? external
                : local != null && local.present() ? local : external;
    }
    public static boolean sourceOwned(Field field) {
        return field == Field.SOURCE_EPISODE || field == Field.SOURCE_AVAILABILITY
                || field == Field.SOURCE_ACTION || field == Field.SOURCE_HEADERS;
    }
    private static Fact safe(Fact fact, Origin expected) {
        return fact != null && fact.origin == expected && fact.present() ? fact : null;
    }
}
