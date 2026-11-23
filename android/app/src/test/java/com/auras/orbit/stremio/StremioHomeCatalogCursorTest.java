package com.auras.orbit.stremio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class StremioHomeCatalogCursorTest {
    @Test public void cursorPreservesRetryQueueOrderAndRemovesDuplicates() throws Exception {
        StremioHomeCatalogCursor cursor = StremioHomeCatalogCursor.create(
                8, Arrays.asList(4, 2, 4), 11);

        assertEquals("stremio-home-v2:8:4,2", cursor.encode(11));
        assertEquals(8, cursor.nextFreshIndex);
        assertEquals(Arrays.asList(4, 2), cursor.failedCatalogIndices);
        assertTrue(cursor.hasMoreFresh(11));
        assertFalse(cursor.isRetryOnly(11));
    }

    @Test public void exhaustedFreshCatalogsBecomeBoundedRetryOnlyState() throws Exception {
        StremioHomeCatalogCursor retry = StremioHomeCatalogCursor.parse(
                "stremio-home-v2:11:4,2", 11);

        assertFalse(retry.hasMoreFresh(11));
        assertTrue(retry.isRetryOnly(11));
        assertEquals("stremio-home-v2:11:4,2", retry.encode(11));
    }

    @Test public void retryKeepsOnlyUnattemptedAndStillFailingIndices() {
        assertEquals(Arrays.asList(6, 4), StremioHomeCatalogCursor.remainingFailures(
                Arrays.asList(2, 4, 6), Arrays.asList(2, 4), Collections.singletonList(4)));
    }

    @Test public void retryQueueRotatesUnattemptedCatalogsAheadOfPersistentFailures() {
        java.util.ArrayList<Integer> pending = new java.util.ArrayList<>();
        for (int index = 0; index < 17; index++) pending.add(index);
        java.util.Set<Integer> seen = new java.util.HashSet<>();

        for (int batch = 0; batch < 3; batch++) {
            java.util.List<Integer> attempted = new java.util.ArrayList<>(
                    pending.subList(0, Math.min(8, pending.size())));
            seen.addAll(attempted);
            pending = new java.util.ArrayList<>(StremioHomeCatalogCursor.remainingFailures(
                    pending, attempted, attempted));
        }

        assertEquals(17, seen.size());
        assertTrue(seen.contains(16));
        assertEquals(Arrays.asList(7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 0, 1, 2, 3, 4, 5, 6), pending);
    }

    @Test public void failedFreshCatalogIsRetainedWhileLaterCatalogsAdvance() throws Exception {
        StremioHomeCatalogCursor initial = StremioHomeCatalogCursor.parse("", 3);

        StremioHomeCatalogCursor afterFailure = initial.afterFreshAttempt(0, true, 3);
        StremioHomeCatalogCursor afterLaterSuccess = afterFailure.afterFreshAttempt(1, false, 3);

        assertEquals(1, afterFailure.nextFreshIndex);
        assertEquals(Collections.singletonList(0), afterFailure.failedCatalogIndices);
        assertEquals(2, afterLaterSuccess.nextFreshIndex);
        assertEquals(Collections.singletonList(0), afterLaterSuccess.failedCatalogIndices);
        assertTrue(afterLaterSuccess.hasMoreFresh(3));
    }

    @Test public void cursorRejectsDuplicateOrOutOfRangeFailureIndices() throws Exception {
        assertInvalid("stremio-home-v2:4:2,2", 11);
        assertInvalid("stremio-home-v2:4:4", 11);
        assertInvalid("stremio-home-v2:12:2", 11);
    }

    private static void assertInvalid(String cursor, int catalogCount) throws Exception {
        try {
            StremioHomeCatalogCursor.parse(cursor, catalogCount);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Invalid Home catalog cursor should be rejected: " + cursor);
    }
}
