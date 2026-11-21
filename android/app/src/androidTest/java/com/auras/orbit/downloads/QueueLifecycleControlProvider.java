package com.auras.orbit.downloads;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Test-only control endpoint; the protected DocumentsProvider remains SAF-only. */
public final class QueueLifecycleControlProvider extends ContentProvider {
    private static final String TARGET_PACKAGE = "com.auras.orbit";
    private static final int READ_WRITE_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        Context context = getContext();
        if (!isOrbitTargetUid(context, callingUid)) {
            throw new SecurityException("Only the Orbit package may control this fixture");
        }

        Bundle result = new Bundle();
        Uri tree = authorizedTree(extras);
        if (tree == null || context.checkUriPermission(tree, callingPid, callingUid,
                READ_WRITE_FLAGS) != PackageManager.PERMISSION_GRANTED) {
            result.putBoolean("updated", false);
            result.putBoolean("deleted", false);
            result.putBoolean("available", false);
            return result;
        }
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        if (QueueLifecycleDocumentsProvider.SET_DELETE_FAILURE_METHOD.equals(method)) {
            String documentId = arg == null || arg.isEmpty() ? null : arg;
            result.putBoolean("updated", QueueLifecycleDocumentsProvider.setDeleteFailureHere(
                    context, rootId, documentId));
        } else if (QueueLifecycleDocumentsProvider.SET_OUTPUT_WRITE_FAILURE_METHOD.equals(method)) {
            long bytes = -1L;
            try { bytes = Long.parseLong(arg); }
            catch (RuntimeException ignored) { }
            result.putBoolean("updated", QueueLifecycleDocumentsProvider
                    .setOutputWriteFailureHere(context, rootId, bytes));
        } else if (QueueLifecycleDocumentsProvider.GET_OUTPUT_WRITE_FAILURE_METHOD.equals(method)) {
            Long bytesWritten = QueueLifecycleDocumentsProvider
                    .failedOutputBytesHere(context, rootId);
            result.putBoolean("available", bytesWritten != null);
            if (bytesWritten != null) result.putLong("bytes_written", bytesWritten);
        } else if (QueueLifecycleDocumentsProvider.CLEAR_OUTPUT_WRITE_FAILURE_METHOD.equals(method)) {
            result.putBoolean("updated", QueueLifecycleDocumentsProvider
                    .clearOutputWriteFailureHere(context, rootId));
        } else if (QueueLifecycleDocumentsProvider.FORCE_DELETE_METHOD.equals(method)) {
            result.putBoolean("deleted", QueueLifecycleDocumentsProvider
                    .forceDeleteFixtureDocumentHere(context, rootId, arg));
        }
        return result;
    }

    private static boolean isOrbitTargetUid(Context context, int callingUid) {
        if (context == null) return false;
        try {
            PackageManager packageManager = context.getPackageManager();
            int targetUid = packageManager.getApplicationInfo(TARGET_PACKAGE, 0).uid;
            String[] callingPackages = packageManager.getPackagesForUid(callingUid);
            if (callingUid != targetUid || callingPackages == null) return false;
            for (String packageName : callingPackages) {
                if (TARGET_PACKAGE.equals(packageName)) return true;
            }
            return false;
        } catch (PackageManager.NameNotFoundException missingTarget) {
            return false;
        }
    }

    private static Uri authorizedTree(Bundle extras) {
        if (extras == null) return null;
        String rawTree = extras.getString(QueueLifecycleDocumentsProvider.AUTHORIZED_TREE_URI_EXTRA);
        if (rawTree == null) return null;
        try {
            Uri tree = Uri.parse(rawTree);
            if (!DocumentsContract.isTreeUri(tree)
                    || !QueueLifecycleDocumentsProvider.AUTHORITY.equals(tree.getAuthority())
                    || !QueueLifecycleDocumentsProvider.isRoot(
                            DocumentsContract.getTreeDocumentId(tree))) return null;
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            return QueueLifecycleDocumentsProvider.isCanonicalTreeRoot(tree, rootId) ? tree : null;
        } catch (IllegalArgumentException malformedTree) {
            return null;
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("Queue lifecycle control is call-only");
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) { return 0; }
}
