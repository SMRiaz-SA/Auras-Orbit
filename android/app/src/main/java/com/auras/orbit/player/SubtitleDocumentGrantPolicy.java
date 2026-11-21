package com.auras.orbit.player;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/** Pure validation and lifetime rules for subtitle documents selected through ACTION_OPEN_DOCUMENT. */
public final class SubtitleDocumentGrantPolicy {
    private SubtitleDocumentGrantPolicy() { }

    public static boolean isAcceptedDocument(boolean sourceAvailable, String uriValue, String mimeType) {
        if (!sourceAvailable || SubtitleFormat.allowedMime(mimeType) == null
                || uriValue == null || uriValue.isEmpty()
                || uriValue.contains("\\") || uriValue.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            URI uri = new URI(uriValue);
            return "content".equalsIgnoreCase(uri.getScheme())
                    && uri.getAuthority() != null && !uri.getAuthority().isEmpty()
                    && uri.getUserInfo() == null;
        } catch (URISyntaxException | RuntimeException malformed) {
            return false;
        }
    }

    /** Local document subtitles are usable only after their accepted read grant was persisted. */
    public static boolean canAddToPlayback(boolean sourceAvailable, String uriValue,
            String mimeType, boolean readGrantPersisted) {
        return readGrantPersisted && isAcceptedDocument(sourceAvailable, uriValue, mimeType);
    }

    public static boolean isUnreferenced(String grantUri, Set<String> referencedUris) {
        return grantUri != null && (referencedUris == null || !referencedUris.contains(grantUri));
    }
}
