package com.auras.orbit.player;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Safe subtitle MIME inference shared by provider, online, and local subtitle sources. */
public final class SubtitleFormat {
    /** Internal routing value used only when a safe provider candidate has no known format hint. */
    public static final String UNKNOWN_MIME_TYPE = "application/x-orbit-unknown-subtitle";

    private SubtitleFormat() { }

    /**
     * Maps a known suffix first. Extensionless resources may use a supported Content-Type; an
     * unfamiliar suffixed URI remains unknown here. Provider candidates use providerMimeType for
     * their supported Content-Type fallback and internal unknown-format marker.
     */
    public static String mimeType(String uriValue, String contentType) {
        if (uriValue == null || uriValue.trim().isEmpty()) return null;
        final String path;
        try {
            URI uri = new URI(uriValue);
            path = uri.getPath();
        } catch (URISyntaxException | RuntimeException malformed) {
            return null;
        }
        if (path == null || path.isEmpty()) return allowedMime(contentType);
        String leaf = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = leaf.lastIndexOf('.');
        if (dot >= 0) {
            switch (leaf.substring(dot + 1)) {
                case "vtt": return "text/vtt";
                case "srt": return "application/x-subrip";
                case "ttml":
                case "xml": return "application/ttml+xml";
                case "ssa":
                case "ass": return "text/x-ssa";
                default: return null;
            }
        }
        return allowedMime(contentType);
    }

    /**
     * Provider admission keeps safe candidates even when neither their suffix nor MIME is known.
     * The returned marker is internal and must never be treated as a provider-declared MIME.
     */
    public static String providerMimeType(String uriValue, String contentType) {
        String known = mimeType(uriValue, contentType);
        if (known != null) return known;
        String declared = allowedMime(contentType);
        return declared == null ? UNKNOWN_MIME_TYPE : declared;
    }

    /** Uses a provider's effective filename when the download URI is extensionless. */
    public static String mimeType(String uriValue, String contentType, String effectiveFileName) {
        String mappedFromUri = mimeType(uriValue, contentType);
        if (mappedFromUri != null || hasSuffix(uriValue)) return mappedFromUri;
        if (effectiveFileName == null || effectiveFileName.trim().isEmpty()) return null;
        int separator = Math.max(effectiveFileName.lastIndexOf('/'), effectiveFileName.lastIndexOf('\\'));
        String leaf = effectiveFileName.substring(separator + 1);
        int dot = leaf.lastIndexOf('.');
        if (dot < 0 || dot == leaf.length() - 1) return null;
        return mimeType("https://subtitle.invalid/subtitle." + leaf.substring(dot + 1), null);
    }

    private static boolean hasSuffix(String uriValue) {
        if (uriValue == null) return false;
        try {
            URI uri = new URI(uriValue);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return false;
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            return leaf.lastIndexOf('.') >= 0;
        } catch (URISyntaxException | RuntimeException malformed) {
            return false;
        }
    }

    public static String allowedMime(String contentType) {
        if (contentType == null) return null;
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        int parameters = normalized.indexOf(';');
        if (parameters >= 0) normalized = normalized.substring(0, parameters).trim();
        switch (normalized) {
            case "text/vtt":
            case "application/x-subrip":
            case "application/ttml+xml":
            case "text/x-ssa":
                return normalized;
            default:
                return null;
        }
    }
}
