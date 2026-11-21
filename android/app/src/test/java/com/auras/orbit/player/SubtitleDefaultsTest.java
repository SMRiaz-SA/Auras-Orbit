package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class SubtitleDefaultsTest {
    @Test public void explicitNoneNeverSelectsAnAvailableTrack() {
        List<String> tracks = Arrays.asList("en", "fr", "de");
        assertEquals(-1, SubtitleDefaults.findMatchingTrack("none", tracks));
    }

    @Test public void savedLanguagePrefersExactTagThenBaseLanguage() {
        List<String> tracks = Arrays.asList("en", "fr", "fr-CA", "de");
        assertEquals(2, SubtitleDefaults.findMatchingTrack("fr-CA", tracks));
        assertEquals(1, SubtitleDefaults.findMatchingTrack("fr-BE", tracks));
    }

    @Test public void savedLanguageDoesNotSilentlyChooseAnotherLanguage() {
        assertEquals(-1, SubtitleDefaults.findMatchingTrack("ja", Arrays.asList("en", "fr")));
        assertEquals(-1, SubtitleDefaults.findMatchingTrack("en", Collections.emptyList()));
    }

    @Test public void subtitleTextScaleSnapsToSupportedRangeAndStep() {
        assertEquals(80, SubtitleDefaults.normalizeTextScalePercent(10));
        assertEquals(120, SubtitleDefaults.normalizeTextScalePercent(111));
        assertEquals(160, SubtitleDefaults.normalizeTextScalePercent(190));
    }
}
