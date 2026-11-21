package com.auras.orbit.player;

/** Binds subtitle work to the profile captured by the initiating player action. */
public final class SubtitleProfileBinding {
    private SubtitleProfileBinding() { }

    public static boolean isCurrent(String expectedProfileId, String activeProfileId) {
        return expectedProfileId != null && !expectedProfileId.trim().isEmpty()
                && expectedProfileId.equals(activeProfileId);
    }
}
