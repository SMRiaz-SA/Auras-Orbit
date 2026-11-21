package com.auras.orbit.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import androidx.media3.ui.CaptionStyleCompat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Profile-scoped subtitle defaults and appearance preferences. */
public final class SubtitlePreferences {
    private static final String FILE_PREFIX = "orbit_subtitle_preferences_v1_";
    private static final String LANGUAGE_KEY = "auto_select_language";
    private static final String SCALE_KEY = "text_scale_percent";
    private static final String FOREGROUND_KEY = "foreground_color";
    private static final String BACKGROUND_KEY = "background_color";
    private static final String EDGE_TYPE_KEY = "edge_type";
    private static final String EDGE_COLOR_KEY = "edge_color";

    private SubtitlePreferences() { }

    public static String languageChoice(Context context, String profileId) {
        SharedPreferences preferences = preferences(context, profileId);
        return preferences == null ? SubtitleDefaults.AUTO : SubtitleDefaults.normalizeLanguageChoice(
                preferences.getString(LANGUAGE_KEY, SubtitleDefaults.AUTO));
    }

    /** Persists "none" as an explicit choice, separate from the automatic default. */
    public static boolean setLanguageChoice(Context context, String profileId, String languageChoice) {
        SharedPreferences preferences = preferences(context, profileId);
        return preferences != null && preferences.edit()
                .putString(LANGUAGE_KEY, SubtitleDefaults.normalizeLanguageChoice(languageChoice))
                .commit();
    }

    public static Style style(Context context, String profileId) {
        SharedPreferences preferences = preferences(context, profileId);
        if (preferences == null) return Style.DEFAULT;
        return new Style(
                preferences.getInt(SCALE_KEY, Style.DEFAULT.textScalePercent),
                preferences.getInt(FOREGROUND_KEY, Style.DEFAULT.foregroundColor),
                preferences.getInt(BACKGROUND_KEY, Style.DEFAULT.backgroundColor),
                preferences.getInt(EDGE_TYPE_KEY, Style.DEFAULT.edgeType),
                preferences.getInt(EDGE_COLOR_KEY, Style.DEFAULT.edgeColor));
    }

    public static boolean setStyle(Context context, String profileId, Style style) {
        SharedPreferences preferences = preferences(context, profileId);
        if (preferences == null || style == null) return false;
        return preferences.edit()
                .putInt(SCALE_KEY, SubtitleDefaults.normalizeTextScalePercent(style.textScalePercent))
                .putInt(FOREGROUND_KEY, style.foregroundColor)
                .putInt(BACKGROUND_KEY, style.backgroundColor)
                .putInt(EDGE_TYPE_KEY, normalizeEdgeType(style.edgeType))
                .putInt(EDGE_COLOR_KEY, style.edgeColor)
                .commit();
    }

    private static SharedPreferences preferences(Context context, String profileId) {
        if (context == null || profileId == null || profileId.trim().isEmpty()) return null;
        return context.getApplicationContext().getSharedPreferences(
                FILE_PREFIX + profileSuffix(profileId), Context.MODE_PRIVATE);
    }

    private static String profileSuffix(String profileId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(profileId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required for profile preference isolation.");
        }
    }

    private static int normalizeEdgeType(int edgeType) {
        switch (edgeType) {
            case CaptionStyleCompat.EDGE_TYPE_NONE:
            case CaptionStyleCompat.EDGE_TYPE_OUTLINE:
            case CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW:
            case CaptionStyleCompat.EDGE_TYPE_RAISED:
            case CaptionStyleCompat.EDGE_TYPE_DEPRESSED:
                return edgeType;
            default:
                return Style.DEFAULT.edgeType;
        }
    }

    public static final class Style {
        public static final Style DEFAULT = new Style(
                100,
                Color.WHITE,
                0x99000000,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK);

        public final int textScalePercent;
        public final int foregroundColor;
        public final int backgroundColor;
        public final int edgeType;
        public final int edgeColor;

        public Style(int textScalePercent, int foregroundColor, int backgroundColor,
                int edgeType, int edgeColor) {
            this.textScalePercent = SubtitleDefaults.normalizeTextScalePercent(textScalePercent);
            this.foregroundColor = foregroundColor;
            this.backgroundColor = backgroundColor;
            this.edgeType = normalizeEdgeType(edgeType);
            this.edgeColor = edgeColor;
        }
    }
}
