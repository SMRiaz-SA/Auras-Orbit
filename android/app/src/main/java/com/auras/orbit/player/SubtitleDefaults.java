package com.auras.orbit.player;

import java.util.List;
import java.util.Locale;

/** Language selection policy for profile-scoped subtitle defaults. */
public final class SubtitleDefaults {
    public static final String AUTO = "auto";
    public static final String NONE = "none";

    private SubtitleDefaults() { }

    /** Keeps explicit None distinct from an unset/automatic preference in persisted settings. */
    public static String normalizeLanguageChoice(String value) {
        if (value == null || value.trim().isEmpty()) return AUTO;
        String candidate = value.trim();
        if (NONE.equalsIgnoreCase(candidate)) return NONE;
        if (AUTO.equalsIgnoreCase(candidate)) return AUTO;
        Locale locale = Locale.forLanguageTag(candidate.replace('_', '-'));
        String tag = locale.toLanguageTag();
        return tag == null || tag.isEmpty() || "und".equalsIgnoreCase(tag) ? AUTO : tag;
    }

    /** Returns an exact tag match first, then the first matching base language, or -1. */
    public static int findMatchingTrack(String languageChoice, List<String> trackLanguages) {
        String choice = normalizeLanguageChoice(languageChoice);
        if (AUTO.equals(choice) || NONE.equals(choice) || trackLanguages == null) return -1;
        String baseLanguage = Locale.forLanguageTag(choice).getLanguage();
        int baseMatch = -1;
        for (int index = 0; index < trackLanguages.size(); index++) {
            String track = normalizeTrackLanguage(trackLanguages.get(index));
            if (track.isEmpty()) continue;
            if (choice.equalsIgnoreCase(track)) return index;
            if (baseMatch < 0 && !baseLanguage.isEmpty()
                    && baseLanguage.equalsIgnoreCase(Locale.forLanguageTag(track).getLanguage())) {
                baseMatch = index;
            }
        }
        return baseMatch;
    }

    /** Restricts saved player text size to 80–160%, rounded to a 20% step. */
    public static int normalizeTextScalePercent(int value) {
        int bounded = Math.max(80, Math.min(160, value));
        return ((bounded + 10) / 20) * 20;
    }

    private static String normalizeTrackLanguage(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String tag = Locale.forLanguageTag(value.trim().replace('_', '-')).toLanguageTag();
        return "und".equalsIgnoreCase(tag) ? "" : tag;
    }
}
