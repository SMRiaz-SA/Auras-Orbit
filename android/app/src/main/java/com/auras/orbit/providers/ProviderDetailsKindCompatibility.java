package com.auras.orbit.providers;

/** Checks whether a provider details result is compatible with its search card kind. */
public final class ProviderDetailsKindCompatibility {
    private ProviderDetailsKindCompatibility() { }

    public static boolean isCompatible(MediaKind listedKind, MediaKind detailsKind) {
        if (listedKind == null || detailsKind == null) return false;
        if (listedKind == detailsKind) {
            return detailsKind == MediaKind.MOVIE
                    || detailsKind == MediaKind.SERIES
                    || detailsKind == MediaKind.LIVE;
        }
        return (listedKind == MediaKind.MOVIE && detailsKind == MediaKind.SERIES)
                || (listedKind == MediaKind.SERIES && detailsKind == MediaKind.MOVIE);
    }
}
