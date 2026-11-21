package com.auras.orbit.downloads;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;

/** Validates a SAF tree grant before persisting the download destination or releasing queued work. */
public final class DownloadFolderSelection {
    private static final String PREFERENCES = "orbit_downloads_shell_v1";
    private static final String FOLDER_KEY = "folder_uri";
    private static final int PERMISSION_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private DownloadFolderSelection() { }

    /** Production entry point: permissions and the folder preference are read from Android. */
    public static boolean acceptSelection(Context context, Uri tree, int grantedFlags,
            PendingRequests pendingRequests) {
        if (context == null) {
            reject(pendingRequests);
            return false;
        }
        return acceptSelection(tree, grantedFlags, new AndroidGrantAccess(context),
                new AndroidFolderPreference(context), pendingRequests);
    }

    /** Package-private dependency seam for AndroidTest; no production UI exposes these ports. */
    static boolean acceptSelection(Uri tree, int grantedFlags, GrantAccess grantAccess,
            FolderPreference folderPreference, PendingRequests pendingRequests) {
        boolean accepted = false;
        if (pendingRequests == null) return false;
        try {
            if (tree != null && DocumentsContract.isTreeUri(tree)
                    && grantAccess != null && folderPreference != null) {
                boolean alreadyWritable = grantAccess.hasPersistedWrite(tree);
                int flagsBeforeTake = alreadyWritable ? 0 : grantAccess.persistedFlags(tree);
                int persistableFlags = grantedFlags & PERMISSION_FLAGS;
                if (!alreadyWritable && persistableFlags != 0) {
                    // Pass only URI permissions Android actually returned for this result.
                    try { grantAccess.takePersistable(tree, persistableFlags); }
                    catch (RuntimeException ignored) {
                        // The call can partially persist permissions; inspect the exact URI below.
                    }
                }
                // The exact URI's durable post-operation write grant is authoritative.
                boolean persistedWrite = false;
                try { persistedWrite = grantAccess.hasPersistedWrite(tree); }
                catch (RuntimeException ignored) { }
                if (persistedWrite) {
                    String previousFolder = folderPreference.read();
                    boolean committed = false;
                    try {
                        committed = folderPreference.commit(tree.toString());
                    } catch (RuntimeException ignored) {
                        // Restore below; never dispatch from a failed preference write.
                    }
                    if (committed) {
                        accepted = true;
                    } else {
                        restoreFolder(folderPreference, previousFolder);
                    }
                } else if (!persistedWrite && !alreadyWritable) {
                    // A failed/read-only selection may have added a read grant before failing.
                    // Release only new exact-tree flags, never a permission that predated this result.
                    releaseNewPermissions(grantAccess, tree, flagsBeforeTake);
                }
            }
        } catch (RuntimeException ignored) {
            // Invalid tree, denied persistable permission, or unavailable persisted grant.
        }

        if (accepted) {
            pendingRequests.accepted(tree);
            return true;
        }
        reject(pendingRequests);
        return false;
    }

    private static void restoreFolder(FolderPreference folderPreference, String previousFolder) {
        try { folderPreference.restore(previousFolder); }
        catch (RuntimeException ignored) { }
    }

    private static void releaseNewPermissions(GrantAccess grantAccess, Uri tree, int flagsBeforeTake) {
        try {
            int flagsAfterTake = grantAccess.persistedFlags(tree) & PERMISSION_FLAGS;
            int newlyPersistedFlags = flagsAfterTake & ~flagsBeforeTake;
            if (newlyPersistedFlags != 0) {
                try { grantAccess.releasePersistable(tree, newlyPersistedFlags); }
                catch (RuntimeException ignored) { }
            }
        } catch (RuntimeException ignored) {
            // Selection still rejects; inability to inspect safely must not change the folder.
        }
    }

    private static void reject(PendingRequests pendingRequests) {
        if (pendingRequests == null) return;
        try { pendingRequests.clear(); }
        finally { pendingRequests.rejected(); }
    }

    interface GrantAccess {
        void takePersistable(Uri tree, int flags);
        boolean hasPersistedWrite(Uri tree);
        int persistedFlags(Uri tree);
        void releasePersistable(Uri tree, int flags);
    }

    interface FolderPreference {
        String read();
        boolean commit(String treeUri);
        boolean restore(String previousFolder);
    }

    public interface PendingRequests {
        void clear();
        void accepted(Uri tree);
        void rejected();
    }

    private static final class AndroidGrantAccess implements GrantAccess {
        private final ContentResolver resolver;
        AndroidGrantAccess(Context context) { resolver = context.getContentResolver(); }

        @Override public void takePersistable(Uri tree, int flags) {
            resolver.takePersistableUriPermission(tree, flags);
        }

        @Override public boolean hasPersistedWrite(Uri tree) {
            for (UriPermission permission : resolver.getPersistedUriPermissions()) {
                if (tree.equals(permission.getUri()) && permission.isWritePermission()) return true;
            }
            return false;
        }

        @Override public int persistedFlags(Uri tree) {
            for (UriPermission permission : resolver.getPersistedUriPermissions()) {
                if (!tree.equals(permission.getUri())) continue;
                int flags = 0;
                if (permission.isReadPermission()) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if (permission.isWritePermission()) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                return flags;
            }
            return 0;
        }

        @Override public void releasePersistable(Uri tree, int flags) {
            resolver.releasePersistableUriPermission(tree, flags);
        }
    }

    private static final class AndroidFolderPreference implements FolderPreference {
        private final SharedPreferences preferences;
        AndroidFolderPreference(Context context) {
            preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        }

        @Override public String read() { return preferences.getString(FOLDER_KEY, null); }

        @Override public boolean commit(String treeUri) {
            return preferences.edit().putString(FOLDER_KEY, treeUri).commit();
        }

        @Override public boolean restore(String previousFolder) {
            SharedPreferences.Editor editor = preferences.edit();
            if (previousFolder == null) editor.remove(FOLDER_KEY);
            else editor.putString(FOLDER_KEY, previousFolder);
            return editor.commit();
        }
    }
}
