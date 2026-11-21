package com.auras.orbit.metadata;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Typed identity claim and provenance. Claims are intentionally unverified until an explicit
 * issuer authority and proof contract is implemented; neither providers nor callers can set a
 * verified flag. The existing namespace vocabulary is retained for future policy decisions.
 */
public final class IdentityProvenance {
    public enum Kind { MOVIE, SERIES }
    public enum Issuer { TMDB, IMDB, TVDB, ANILIST, MAL, PROVIDER }
    public enum Namespace {
        TMDB_MOVIE(Issuer.TMDB, Kind.MOVIE),
        TMDB_TV(Issuer.TMDB, Kind.SERIES),
        IMDB_TITLE(Issuer.IMDB, null),
        TVDB_SERIES(Issuer.TVDB, Kind.SERIES),
        ANILIST(Issuer.ANILIST, null),
        MAL(Issuer.MAL, null);

        final Issuer issuer;
        final Kind fixedKind;
        Namespace(Issuer issuer, Kind fixedKind) {
            this.issuer = issuer;
            this.fixedKind = fixedKind;
        }
    }
    public enum EvidenceType { PROVIDER_CLAIM, ISSUER_CLAIM, VERIFIED_ISSUER_RESPONSE }

    public final Issuer issuer;
    public final Namespace namespace;
    public final Kind kind;
    public final String externalId;
    public final EvidenceType evidenceType;
    /** Bounded source label or claimed record reference; this is not a trust proof. */
    public final String sourceRef;
    public final long observedAtMs;

    private IdentityProvenance(Issuer issuer, Namespace namespace, Kind kind, String externalId,
            EvidenceType evidenceType, String sourceRef, long observedAtMs) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.externalId = validateExternalId(namespace, externalId);
        this.evidenceType = Objects.requireNonNull(evidenceType, "evidenceType");
        this.sourceRef = evidenceType == EvidenceType.ISSUER_CLAIM
                ? claimedIssuerHost(sourceRef) : bounded(sourceRef, 80, "sourceRef");
        if (observedAtMs < 0L) throw new IllegalArgumentException("observedAtMs");
        this.observedAtMs = observedAtMs;
        if (namespace.fixedKind != null && namespace.fixedKind != kind)
            throw new IllegalArgumentException("Identity namespace has a different media kind");
        if (evidenceType == EvidenceType.PROVIDER_CLAIM && issuer != Issuer.PROVIDER)
            throw new IllegalArgumentException("Provider claim issuer required");
        if (evidenceType != EvidenceType.PROVIDER_CLAIM && issuer != namespace.issuer)
            throw new IllegalArgumentException("Claim issuer does not match its namespace");
    }

    /** A provider-submitted identifier is always unverified. */
    public static IdentityProvenance providerClaim(Namespace namespace, Kind kind,
            String externalId, String providerLabel, long observedAtMs) {
        return new IdentityProvenance(Issuer.PROVIDER, namespace, kind, externalId,
                EvidenceType.PROVIDER_CLAIM, providerLabel, observedAtMs);
    }

    /** Records an identifier attributed to its apparent issuer without asserting verification. */
    public static IdentityProvenance issuerClaim(Namespace namespace, Kind kind,
            String externalId, String claimedRecordRef, long observedAtMs) {
        return new IdentityProvenance(namespace.issuer, namespace, kind, externalId,
                EvidenceType.ISSUER_CLAIM, claimedRecordRef, observedAtMs);
    }

    /** No current constructor can manufacture issuer verification; an explicit policy is required. */
    public boolean isIssuerBacked() {
        return evidenceType == EvidenceType.VERIFIED_ISSUER_RESPONSE;
    }

    private static String validateExternalId(Namespace namespace, String input) {
        Objects.requireNonNull(namespace, "namespace");
        String value = bounded(input, 128, "externalId");
        String pattern;
        switch (namespace) {
            case TMDB_MOVIE:
            case TMDB_TV:
            case TVDB_SERIES:
            case ANILIST:
            case MAL:
                pattern = "[1-9][0-9]{0,11}";
                break;
            case IMDB_TITLE:
                pattern = "tt[0-9]{7,12}";
                break;
            default:
                throw new IllegalArgumentException("Unsupported identity namespace");
        }
        if (!value.matches(pattern)) throw new IllegalArgumentException("Invalid external ID for namespace");
        return value;
    }

    private static String bounded(String input, int max, String label) {
        if (input == null) throw new IllegalArgumentException(label);
        String value = input.trim();
        if (value.isEmpty() || value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label);
        return value;
    }

    /** Discards URL paths, queries, fragments and credentials from an unverified claimed reference. */
    private static String claimedIssuerHost(String input) {
        String value = bounded(input, 2048, "claimedRecordRef");
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getHost() == null) return "untrusted-reference";
            return "https://" + uri.getHost().toLowerCase(Locale.ROOT);
        } catch (RuntimeException invalid) {
            return "untrusted-reference";
        }
    }
}
