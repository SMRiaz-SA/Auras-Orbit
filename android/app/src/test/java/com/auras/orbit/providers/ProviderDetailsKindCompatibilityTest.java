package com.auras.orbit.providers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProviderDetailsKindCompatibilityTest {
    @Test public void acceptsMatchingSupportedKinds() {
        assertTrue(ProviderDetailsKindCompatibility.isCompatible(MediaKind.MOVIE, MediaKind.MOVIE));
        assertTrue(ProviderDetailsKindCompatibility.isCompatible(MediaKind.SERIES, MediaKind.SERIES));
        assertTrue(ProviderDetailsKindCompatibility.isCompatible(MediaKind.LIVE, MediaKind.LIVE));
    }

    @Test public void acceptsMovieAndSeriesSearchDetailsMismatchInEitherDirection() {
        assertTrue(ProviderDetailsKindCompatibility.isCompatible(MediaKind.MOVIE, MediaKind.SERIES));
        assertTrue(ProviderDetailsKindCompatibility.isCompatible(MediaKind.SERIES, MediaKind.MOVIE));
    }

    @Test public void rejectsLiveAndUnknownCrossKindMismatches() {
        assertFalse(ProviderDetailsKindCompatibility.isCompatible(MediaKind.MOVIE, MediaKind.LIVE));
        assertFalse(ProviderDetailsKindCompatibility.isCompatible(MediaKind.SERIES, MediaKind.LIVE));
        assertFalse(ProviderDetailsKindCompatibility.isCompatible(MediaKind.LIVE, MediaKind.MOVIE));
        assertFalse(ProviderDetailsKindCompatibility.isCompatible(MediaKind.LIVE, MediaKind.SERIES));
        assertFalse(ProviderDetailsKindCompatibility.isCompatible(MediaKind.LIVE, MediaKind.UNKNOWN));
        assertFalse(ProviderDetailsKindCompatibility.isCompatible(MediaKind.UNKNOWN, MediaKind.LIVE));
        assertFalse(ProviderDetailsKindCompatibility.isCompatible(MediaKind.UNKNOWN, MediaKind.MOVIE));
        assertFalse(ProviderDetailsKindCompatibility.isCompatible(MediaKind.UNKNOWN, MediaKind.SERIES));
        assertFalse(ProviderDetailsKindCompatibility.isCompatible(MediaKind.MOVIE, MediaKind.UNKNOWN));
        assertFalse(ProviderDetailsKindCompatibility.isCompatible(MediaKind.SERIES, MediaKind.UNKNOWN));
        assertFalse(ProviderDetailsKindCompatibility.isCompatible(MediaKind.UNKNOWN, MediaKind.UNKNOWN));
    }
}
