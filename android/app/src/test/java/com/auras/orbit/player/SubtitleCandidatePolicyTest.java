package com.auras.orbit.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SubtitleCandidatePolicyTest {
    @Test public void unsafeAndUnsupportedCandidatesAreCountedAsSkipped() {
        assertTrue(SubtitleCandidatePolicy.shouldCountAsSkipped(false, false, true, false));
        assertTrue(SubtitleCandidatePolicy.shouldCountAsSkipped(true, true, true, false));
        assertTrue(SubtitleCandidatePolicy.shouldCountAsSkipped(true, false, true, true));
        assertFalse(SubtitleCandidatePolicy.shouldCountAsSkipped(true, true, true, true));
    }
}
