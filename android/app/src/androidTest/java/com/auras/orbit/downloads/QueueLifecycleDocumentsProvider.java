package com.auras.orbit.downloads;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/** AndroidTest-only two-tree SAF fixture with controllable write and delete failures. */
public final class QueueLifecycleDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.auras.orbit.test.downloadqueue";
    static final String CONTROL_AUTHORITY = "com.auras.orbit.test.downloadqueue.control";
    static final Uri CONTROL_URI = Uri.parse("content://" + CONTROL_AUTHORITY);
    public static final String TREE_A = "orbit-queue-lifecycle-tree-a";
    public static final String TREE_B = "orbit-queue-lifecycle-tree-b";

    private static final String PREFS_NAME = "orbit_queue_lifecycle_provider_v1";
    private static final String NAME_PREFIX = "name:";
    private static final String MIME_PREFIX = "mime:";
    private static final String ROOT_PREFIX = "root:";
    private static final String DELETE_FAILURE_KEY = "delete_failure_document_id";
    private static final String WRITE_FAILURE_NEXT_TREE_KEY = "write_failure_next_tree_id";
    private static final String WRITE_FAILURE_DOCUMENT_KEY = "write_failure_document_id";
    private static final String WRITE_FAILURE_AFTER_BYTES_KEY = "write_failure_after_bytes";
    private static final String WRITE_FAILURE_RESULT_TREE_KEY = "write_failure_result_tree_id";
    private static final String WRITE_FAILURE_RESULT_BYTES_KEY = "write_failure_result_bytes";
    static final String SET_DELETE_FAILURE_METHOD = "set_delete_failure";
    static final String SET_OUTPUT_WRITE_FAILURE_METHOD = "set_output_write_failure";
    static final String GET_OUTPUT_WRITE_FAILURE_METHOD = "get_output_write_failure";
    static final String CLEAR_OUTPUT_WRITE_FAILURE_METHOD = "clear_output_write_failure";
    static final String FORCE_DELETE_METHOD = "force_delete_fixture_document";
    static final String AUTHORIZED_TREE_URI_EXTRA = "authorized_tree_uri";
    private static final Object LOCK = new Object();

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[]{
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_MIME_TYPES
        } : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        addRoot(cursor, columns, TREE_A, "Queue Lifecycle Tree A");
        addRoot(cursor, columns, TREE_B, "Queue Lifecycle Tree B");
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        String[] columns = projection == null ? documentColumns() : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        if (isRoot(documentId)) {
            addRootDocument(cursor, columns, documentId);
            return cursor;
        }
        requireDocument(documentId);
        addChild(cursor, columns, documentId);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
            String sortOrder) throws FileNotFoundException {
        if (!isRoot(parentDocumentId)) throw new FileNotFoundException("Unknown queue fixture root");
        String[] columns = projection == null ? documentColumns() : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        synchronized (LOCK) {
            for (Map.Entry<String, ?> entry : preferences().getAll().entrySet()) {
                if (!entry.getKey().startsWith(NAME_PREFIX)) continue;
                String id = entry.getKey().substring(NAME_PREFIX.length());
                if (!parentDocumentId.equals(preferences().getString(ROOT_PREFIX + id, null))) continue;
                addChild(cursor, columns, id);
            }
        }
        return cursor;
    }

    @Override public String getDocumentType(String documentId) throws FileNotFoundException {
        if (isRoot(documentId)) return DocumentsContract.Document.MIME_TYPE_DIR;
        requireDocument(documentId);
        return preferences().getString(MIME_PREFIX + documentId,
                "application/octet-stream");
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        return isRoot(parentDocumentId) && documentId != null
                && parentDocumentId.equals(preferences().getString(ROOT_PREFIX + documentId, null));
    }

    @Override public DocumentsContract.Path findDocumentPath(String parentDocumentId,
            String childDocumentId) throws FileNotFoundException {
        if (childDocumentId == null || childDocumentId.isEmpty()) {
            throw new FileNotFoundException("Unknown queue fixture document");
        }

        if (parentDocumentId == null) {
            String rootId;
            if (isRoot(childDocumentId)) {
                rootId = childDocumentId;
            } else {
                requireDocument(childDocumentId);
                rootId = preferences().getString(ROOT_PREFIX + childDocumentId, null);
                if (!isRoot(rootId)) {
                    throw new FileNotFoundException("Queue fixture document has no known root");
                }
            }
            return new DocumentsContract.Path(rootId, rootId.equals(childDocumentId)
                    ? Collections.singletonList(rootId)
                    : Arrays.asList(rootId, childDocumentId));
        }

        if (!isRoot(parentDocumentId)) {
            throw new FileNotFoundException("Unknown queue fixture parent root");
        }
        if (parentDocumentId.equals(childDocumentId)) {
            return new DocumentsContract.Path(null, Collections.singletonList(parentDocumentId));
        }

        requireDocument(childDocumentId);
        if (!parentDocumentId.equals(
                preferences().getString(ROOT_PREFIX + childDocumentId, null))) {
            throw new FileNotFoundException("Queue fixture document is outside the requested root");
        }
        return new DocumentsContract.Path(null, Arrays.asList(parentDocumentId, childDocumentId));
    }

    @Override public String createDocument(String parentDocumentId, String mimeType,
            String displayName) throws FileNotFoundException {
        if (!isRoot(parentDocumentId)) throw new FileNotFoundException("Unknown queue fixture root");
        String id = parentDocumentId + "-" + UUID.randomUUID();
        File file = fileFor(id);
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            throw new FileNotFoundException("Could not create the queue fixture directory");
        }
        try {
            if (!file.createNewFile()) throw new IOException("Queue fixture file already exists");
        } catch (IOException error) {
            FileNotFoundException wrapped = new FileNotFoundException("Could not create queue fixture document");
            wrapped.initCause(error);
            throw wrapped;
        }
        String name = displayName == null ? "" : displayName;
        boolean saved = preferences().edit()
                .putString(NAME_PREFIX + id, name)
                .putString(MIME_PREFIX + id, mimeType == null ? "application/octet-stream" : mimeType)
                .putString(ROOT_PREFIX + id, parentDocumentId)
                .commit();
        if (!saved) {
            file.delete();
            throw new FileNotFoundException("Could not persist queue fixture document metadata");
        }
        armConfiguredOutputFailure(parentDocumentId, id, name);
        return id;
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        requireDocument(documentId);
        if (documentId.equals(preferences().getString(DELETE_FAILURE_KEY, ""))) {
            throw new FileNotFoundException("Injected queue fixture delete failure");
        }
        File backingFile = fileFor(documentId);
        if (backingFile.exists() && !backingFile.delete()) {
            throw new FileNotFoundException("Could not delete queue fixture document");
        }
        if (backingFile.exists()) {
            throw new FileNotFoundException("Queue fixture backing file remains after delete");
        }
        if (!preferences().edit().remove(NAME_PREFIX + documentId)
                .remove(MIME_PREFIX + documentId).remove(ROOT_PREFIX + documentId).commit()) {
            throw new FileNotFoundException("Could not clear queue fixture document metadata");
        }
    }

    @Override public String renameDocument(String documentId, String displayName)
            throws FileNotFoundException {
        requireDocument(documentId);
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) throw new FileNotFoundException("Queue fixture rename needs a file name");
        if (!preferences().edit().putString(NAME_PREFIX + documentId, name).commit()) {
            throw new FileNotFoundException("Could not persist queue fixture rename");
        }
        // The stable document ID also names the backing file, so metadata rename preserves bytes.
        return documentId;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode,
            android.os.CancellationSignal signal) throws FileNotFoundException {
        requireDocument(documentId);
        boolean writing = mode != null && mode.contains("w");
        if (writing && documentId.equals(preferences().getString(WRITE_FAILURE_DOCUMENT_KEY, ""))) {
            File backingFile = fileFor(documentId);
            try {
                if (mode.contains("t")) {
                    try (RandomAccessFile output = new RandomAccessFile(backingFile, "rw")) {
                        output.setLength(0L);
                    }
                }
                StorageManager storage = (StorageManager) getContext()
                        .getSystemService(Context.STORAGE_SERVICE);
                if (storage == null) throw new IOException("StorageManager is unavailable");
                long failureAfterBytes = preferences().getLong(WRITE_FAILURE_AFTER_BYTES_KEY, 0L);
                return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_WRITE_ONLY,
                        new PartialWriteFailureCallback(getContext(), documentId, backingFile,
                                failureAfterBytes), new Handler(Looper.getMainLooper()));
            } catch (IOException error) {
                FileNotFoundException wrapped = new FileNotFoundException(
                        "Could not open the injected queue fixture output stream");
                wrapped.initCause(error);
                throw wrapped;
            }
        }
        int flags = writing
                ? ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(fileFor(documentId), flags);
    }

    public static void setDeleteFailure(Context targetContext, Uri authorizedTree, String documentId) {
        Bundle extras = authorizedTreeArguments(authorizedTree);
        Bundle result = targetContext.getContentResolver().call(CONTROL_URI,
                SET_DELETE_FAILURE_METHOD, documentId == null ? "" : documentId, extras);
        if (result == null || !result.getBoolean("updated")) {
            throw new IllegalStateException("Could not configure queue fixture delete behavior");
        }
    }

    /** Injects one write error into the next queue partial created in the selected fixture tree. */
    static void failNextOutputWriteAfter(Context targetContext, Uri authorizedTree, long bytes) {
        Bundle result = targetContext.getContentResolver().call(CONTROL_URI,
                SET_OUTPUT_WRITE_FAILURE_METHOD, Long.toString(bytes),
                authorizedTreeArguments(authorizedTree));
        if (result == null || !result.getBoolean("updated")) {
            throw new IllegalStateException("Could not configure queue fixture output failure");
        }
    }

    /** Returns the prefix persisted by the most recent injected output failure in this tree. */
    static long failedOutputBytes(Context targetContext, Uri authorizedTree) {
        Bundle result = targetContext.getContentResolver().call(CONTROL_URI,
                GET_OUTPUT_WRITE_FAILURE_METHOD, null, authorizedTreeArguments(authorizedTree));
        if (result == null || !result.getBoolean("available")) {
            throw new IllegalStateException("Could not read queue fixture output failure state");
        }
        return result.getLong("bytes_written", 0L);
    }

    static void clearOutputWriteFailure(Context targetContext, Uri authorizedTree) {
        Bundle result = targetContext.getContentResolver().call(CONTROL_URI,
                CLEAR_OUTPUT_WRITE_FAILURE_METHOD, null, authorizedTreeArguments(authorizedTree));
        if (result == null || !result.getBoolean("updated")) {
            throw new IllegalStateException("Could not clear queue fixture output failure");
        }
    }

    /** Called only by the call-only control provider after its UID and tree-grant checks. */
    static boolean setOutputWriteFailureHere(Context context, String authorizedTreeId, long bytes) {
        if (!isRoot(authorizedTreeId) || bytes <= 0L) return false;
        SharedPreferences preferences = preferences(context);
        String pendingTree = preferences.getString(WRITE_FAILURE_NEXT_TREE_KEY, "");
        String resultTree = preferences.getString(WRITE_FAILURE_RESULT_TREE_KEY, "");
        if ((!pendingTree.isEmpty() && !authorizedTreeId.equals(pendingTree))
                || (!resultTree.isEmpty() && !authorizedTreeId.equals(resultTree))
                || writeFailureDocumentBelongsToAnotherTree(preferences, authorizedTreeId)) {
            return false;
        }
        return preferences.edit()
                .putString(WRITE_FAILURE_NEXT_TREE_KEY, authorizedTreeId)
                .putLong(WRITE_FAILURE_AFTER_BYTES_KEY, bytes)
                .remove(WRITE_FAILURE_DOCUMENT_KEY)
                .remove(WRITE_FAILURE_RESULT_TREE_KEY)
                .remove(WRITE_FAILURE_RESULT_BYTES_KEY)
                .commit();
    }

    /** Returns null until a failure has been recorded for the exact authorized fixture root. */
    static Long failedOutputBytesHere(Context context, String authorizedTreeId) {
        if (!isRoot(authorizedTreeId)) return null;
        SharedPreferences preferences = preferences(context);
        if (!authorizedTreeId.equals(
                preferences.getString(WRITE_FAILURE_RESULT_TREE_KEY, ""))
                || !preferences.contains(WRITE_FAILURE_RESULT_BYTES_KEY)) return null;
        return preferences.getLong(WRITE_FAILURE_RESULT_BYTES_KEY, 0L);
    }

    static boolean clearOutputWriteFailureHere(Context context, String authorizedTreeId) {
        if (!isRoot(authorizedTreeId)) return false;
        SharedPreferences preferences = preferences(context);
        String pendingTree = preferences.getString(WRITE_FAILURE_NEXT_TREE_KEY, "");
        String resultTree = preferences.getString(WRITE_FAILURE_RESULT_TREE_KEY, "");
        if ((!pendingTree.isEmpty() && !authorizedTreeId.equals(pendingTree))
                || (!resultTree.isEmpty() && !authorizedTreeId.equals(resultTree))
                || writeFailureDocumentBelongsToAnotherTree(preferences, authorizedTreeId)) {
            return false;
        }
        return preferences.edit()
                .remove(WRITE_FAILURE_NEXT_TREE_KEY)
                .remove(WRITE_FAILURE_DOCUMENT_KEY)
                .remove(WRITE_FAILURE_AFTER_BYTES_KEY)
                .remove(WRITE_FAILURE_RESULT_TREE_KEY)
                .remove(WRITE_FAILURE_RESULT_BYTES_KEY)
                .commit();
    }

    private static boolean writeFailureDocumentBelongsToAnotherTree(
            SharedPreferences preferences, String authorizedTreeId) {
        String documentId = preferences.getString(WRITE_FAILURE_DOCUMENT_KEY, "");
        if (documentId.isEmpty()) return false;
        String documentRoot = preferences.getString(ROOT_PREFIX + documentId, "");
        return isRoot(documentRoot) && !authorizedTreeId.equals(documentRoot);
    }

    private void armConfiguredOutputFailure(String rootId, String documentId, String displayName) {
        if (displayName == null || !displayName.startsWith(".orbit-part-")) return;
        synchronized (LOCK) {
            SharedPreferences preferences = preferences();
            if (!rootId.equals(preferences.getString(WRITE_FAILURE_NEXT_TREE_KEY, ""))) return;
            preferences.edit()
                    .putString(WRITE_FAILURE_DOCUMENT_KEY, documentId)
                    .putString(WRITE_FAILURE_RESULT_TREE_KEY, rootId)
                    .remove(WRITE_FAILURE_NEXT_TREE_KEY)
                    .remove(WRITE_FAILURE_RESULT_BYTES_KEY)
                    .commit();
        }
    }

    private static final class PartialWriteFailureCallback extends ProxyFileDescriptorCallback {
        private final Context context;
        private final String documentId;
        private final File backingFile;
        private final long failureAfterBytes;

        PartialWriteFailureCallback(Context context, String documentId, File backingFile,
                long failureAfterBytes) {
            this.context = context;
            this.documentId = documentId;
            this.backingFile = backingFile;
            this.failureAfterBytes = failureAfterBytes;
        }

        @Override public long onGetSize() { return backingFile.length(); }

        @Override public int onRead(long offset, int size, byte[] data) throws ErrnoException {
            try (RandomAccessFile input = new RandomAccessFile(backingFile, "r")) {
                if (offset >= input.length()) return 0;
                input.seek(offset);
                int count = (int) Math.min((long) size, input.length() - offset);
                input.readFully(data, 0, count);
                return count;
            } catch (IOException error) {
                throw new ErrnoException("read", OsConstants.EIO, error);
            }
        }

        @Override public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
            if (offset >= failureAfterBytes) {
                recordFailure(offset);
                throw new ErrnoException("injected queue fixture write failure", OsConstants.EIO);
            }
            int count = (int) Math.min((long) size, failureAfterBytes - offset);
            try (RandomAccessFile output = new RandomAccessFile(backingFile, "rw")) {
                output.seek(offset);
                output.write(data, 0, count);
            } catch (IOException error) {
                throw new ErrnoException("write", OsConstants.EIO, error);
            }
            if (count < size) {
                recordFailure(offset + count);
                throw new ErrnoException("injected queue fixture write failure", OsConstants.EIO);
            }
            return count;
        }

        @Override public void onRelease() { }

        private void recordFailure(long persistedBytes) {
            preferences(context).edit()
                    .putString(WRITE_FAILURE_DOCUMENT_KEY, documentId)
                    .putLong(WRITE_FAILURE_RESULT_BYTES_KEY, persistedBytes)
                    .commit();
        }
    }

    /** Exact-ID cleanup escape hatch for AndroidTest teardown; it never scans outside this fixture. */
    public static void forceDeleteFixtureDocument(Context targetContext, Uri authorizedTree,
            String documentId) {
        if (documentId == null || documentId.isEmpty()) return;
        Bundle result = targetContext.getContentResolver().call(CONTROL_URI,
                FORCE_DELETE_METHOD, documentId, authorizedTreeArguments(authorizedTree));
        if (result == null || !result.getBoolean("deleted")) {
            throw new IllegalStateException("Queue fixture teardown could not verify backing-file deletion");
        }
    }

    static boolean setDeleteFailureHere(Context context, String authorizedTreeId,
            String documentId) {
        if (!isRoot(authorizedTreeId)) return false;
        String documentRoot = documentId == null || documentId.isEmpty() ? authorizedTreeId
                : preferences(context).getString(ROOT_PREFIX + documentId, "");
        if (!authorizedTreeId.equals(documentRoot)) return false;
        return preferences(context).edit().putString(DELETE_FAILURE_KEY,
                documentId == null ? "" : documentId).commit();
    }

    static boolean forceDeleteFixtureDocumentHere(Context context, String authorizedTreeId,
            String documentId) {
        if (documentId == null || documentId.isEmpty()
                || !documentId.startsWith(authorizedTreeId + "-")) return false;
        String storedRoot = preferences(context).getString(ROOT_PREFIX + documentId, null);
        if (storedRoot != null && !authorizedTreeId.equals(storedRoot)) return false;

        File backingFile = fileFor(context, documentId);
        if (backingFile.exists() && !backingFile.delete()) return false;
        if (backingFile.exists()) return false;
        boolean metadataRemoved = preferences(context).edit().remove(NAME_PREFIX + documentId)
                .remove(MIME_PREFIX + documentId).remove(ROOT_PREFIX + documentId).commit();
        return metadataRemoved && !backingFile.exists()
                && !preferences(context).contains(NAME_PREFIX + documentId);
    }

    private static Bundle authorizedTreeArguments(Uri tree) {
        String rootId = null;
        if (tree != null && DocumentsContract.isTreeUri(tree)
                && AUTHORITY.equals(tree.getAuthority())) {
            try { rootId = DocumentsContract.getTreeDocumentId(tree); }
            catch (IllegalArgumentException ignored) { }
        }
        if (!isCanonicalTreeRoot(tree, rootId)) {
            throw new IllegalArgumentException("A granted queue lifecycle tree URI is required");
        }
        Bundle extras = new Bundle();
        extras.putString(AUTHORIZED_TREE_URI_EXTRA, tree.toString());
        return extras;
    }

    static boolean isCanonicalTreeRoot(Uri tree, String expectedRoot) {
        if (tree == null || !isRoot(expectedRoot)) return false;
        try {
            return DocumentsContract.isTreeUri(tree)
                    && AUTHORITY.equals(tree.getAuthority())
                    && expectedRoot.equals(DocumentsContract.getTreeDocumentId(tree))
                    && DocumentsContract.buildTreeDocumentUri(AUTHORITY, expectedRoot).equals(tree);
        } catch (IllegalArgumentException malformedTree) {
            return false;
        }
    }

    private void addRoot(MatrixCursor cursor, String[] columns, String id, String title) {
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            switch (columns[index]) {
                case DocumentsContract.Root.COLUMN_ROOT_ID: values[index] = id; break;
                case DocumentsContract.Root.COLUMN_FLAGS:
                    values[index] = DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                            | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                            | DocumentsContract.Root.FLAG_LOCAL_ONLY;
                    break;
                case DocumentsContract.Root.COLUMN_TITLE: values[index] = title; break;
                case DocumentsContract.Root.COLUMN_DOCUMENT_ID: values[index] = id; break;
                case DocumentsContract.Root.COLUMN_MIME_TYPES:
                    values[index] = "video/mp4\napplication/octet-stream";
                    break;
                case DocumentsContract.Root.COLUMN_AVAILABLE_BYTES: values[index] = 64L * 1024L * 1024L; break;
                default: values[index] = null;
            }
        }
        cursor.addRow(values);
    }

    private void addRootDocument(MatrixCursor cursor, String[] columns, String id) {
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            switch (columns[index]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: values[index] = id; break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: values[index] = id; break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    values[index] = DocumentsContract.Document.MIME_TYPE_DIR;
                    break;
                case DocumentsContract.Document.COLUMN_FLAGS:
                    values[index] = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
                    break;
                default: values[index] = null;
            }
        }
        cursor.addRow(values);
    }

    private void addChild(MatrixCursor cursor, String[] columns, String id) {
        String name = preferences().getString(NAME_PREFIX + id, "");
        String mime = preferences().getString(MIME_PREFIX + id, "application/octet-stream");
        long size = fileFor(id).length();
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            switch (columns[index]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: values[index] = id; break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                    values[index] = name;
                    break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE: values[index] = mime; break;
                case DocumentsContract.Document.COLUMN_FLAGS:
                    values[index] = DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                            | DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                            | DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
                    break;
                case DocumentsContract.Document.COLUMN_SIZE:
                    values[index] = size;
                    break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED:
                    values[index] = fileFor(id).lastModified();
                    break;
                default: values[index] = null;
            }
        }
        cursor.addRow(values);
    }

    private void requireDocument(String id) throws FileNotFoundException {
        if (id == null || !preferences().contains(NAME_PREFIX + id)) {
            throw new FileNotFoundException("Unknown queue fixture document");
        }
    }

    static boolean isRoot(String id) { return TREE_A.equals(id) || TREE_B.equals(id); }

    private static String[] documentColumns() {
        return new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    }

    private SharedPreferences preferences() { return preferences(getContext()); }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private File fileFor(String id) { return fileFor(getContext(), id); }

    private static File fileFor(Context context, String id) {
        String safe = id == null ? "" : id.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(new File(context.getFilesDir(), "queue-lifecycle-documents"), safe);
    }
}
