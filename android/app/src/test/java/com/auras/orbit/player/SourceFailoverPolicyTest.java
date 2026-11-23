package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class SourceFailoverPolicyTest {
    private static final List<String> QUALITY_ORDER = Arrays.asList(
            "4k", "1080p", "720p", "360p", "240p");

    @Test public void advancesAfterSelectedLowQualitySourceInsteadOfRestartingAtFirst() {
        Set<String> attempted = new HashSet<>(Arrays.asList("4k", "360p"));
        Set<String> failed = Collections.singleton("360p");

        assertEquals(4, SourceFailoverPolicy.nextUnattemptedIndexAfterCurrent(
                QUALITY_ORDER, "360p", attempted, failed));
    }

    @Test public void skipsAttemptedAndFailedEntriesAfterCurrent() {
        Set<String> attempted = new HashSet<>(Arrays.asList("4k", "1080p", "720p"));
        Set<String> failed = new HashSet<>(Arrays.asList("1080p", "720p"));

        assertEquals(3, SourceFailoverPolicy.nextUnattemptedIndexAfterCurrent(
                QUALITY_ORDER, "4k", attempted, failed));
    }

    @Test public void doesNotWrapToEarlierSourcesWhenThereIsNoLaterCandidate() {
        Set<String> attempted = Collections.singleton("240p");

        assertEquals(-1, SourceFailoverPolicy.nextUnattemptedIndexAfterCurrent(
                QUALITY_ORDER, "240p", attempted, Collections.emptySet()));
    }

    @Test public void missingCurrentSourceDoesNotRestartFromTheBeginning() {
        assertEquals(-1, SourceFailoverPolicy.nextUnattemptedIndexAfterCurrent(
                QUALITY_ORDER, "missing", Collections.emptySet(), Collections.emptySet()));
    }
}
