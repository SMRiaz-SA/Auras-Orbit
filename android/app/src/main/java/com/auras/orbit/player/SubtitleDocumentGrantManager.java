package com.auras.orbit.player;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Persists accepted document read grants for the active playback session and releases stale ones. */
public final class SubtitleDocumentGrantManager {
    private static final String PREFS = "orbit_subtitle_document_grants_v1";
    private static final String URIS_KEY = "uris";

    private SubtitleDocumentGrantManager() { }

    public static boolean persistAcceptedReadGrant(Context context, Uri uri, int resultFlags) {
        if (context == null || uri == null
                || (resultFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0
                || (resultFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) return false;
        boolean grantTaken = false;
        try {
            context.getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            grantTaken = true;
            Set<String> current = storedUris(context);
            current.add(uri.toString());
            boolean stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putStringSet(URIS_KEY, current).commit();
            if (!stored) {
                context.getContentResolver().releasePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            return stored;
        } catch (RuntimeException ignored) {
            if (grantTaken) {
                try {
                    context.getContentResolver().releasePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (RuntimeException ignoredRelease) { }
            }
            return false;
        }
    }

    /** Releases all stored grants not referenced by the current playback sources. */
    public static void releaseUnreferenced(Context context, Collection<String> referencedUris) {
        if (context == null) return;
        Set<String> referenced = referencedUris == null
                ? new HashSet<>() : new HashSet<>(referencedUris);
        Set<String> current = storedUris(context);
        boolean changed = false;
        for (String grant : new HashSet<>(current)) {
            if (!SubtitleDocumentGrantPolicy.isUnreferenced(grant, referenced)) continue;
            try {
                context.getContentResolver().releasePersistableUriPermission(
                        Uri.parse(grant), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                current.remove(grant);
                changed = true;
            } catch (RuntimeException ignored) {
                // Keep a failed release recorded so the next player session can retry it.
            }
        }
        if (changed) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putStringSet(URIS_KEY, current).commit();
        }
    }

    private static Set<String> storedUris(Context context) {
        Set<String> stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(URIS_KEY, null);
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }
}
