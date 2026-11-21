package com.auras.orbit.player;

import java.util.Locale;

/** Normalizes language tags for SubDL's case-sensitive regional alias table. */
public final class SubdlLanguageTag {
    private SubdlLanguageTag() { }

    public static String normalize(String languageTag) {
        if (languageTag == null || languageTag.trim().isEmpty()) return "";
        return languageTag.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }
}
