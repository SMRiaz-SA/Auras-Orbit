package com.auras.orbit.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SubtitleProfileBindingTest {
    @Test public void acceptsOnlyTheCapturedNonBlankProfile() {
        assertTrue(SubtitleProfileBinding.isCurrent("profile-a", "profile-a"));
        assertFalse(SubtitleProfileBinding.isCurrent("profile-a", "profile-b"));
        assertFalse(SubtitleProfileBinding.isCurrent("profile-a", null));
        assertFalse(SubtitleProfileBinding.isCurrent(" ", " "));
        assertFalse(SubtitleProfileBinding.isCurrent(null, null));
    }
}
