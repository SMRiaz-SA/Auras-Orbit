package com.auras.orbit.backup;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Base64;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Test-only provider for the export replacement failure acceptance. A document created through
 * ACTION_CREATE_DOCUMENT receives sentinel bytes before DocumentsUI returns its URI. The first
 * replacement write accepts a short prefix and then reports EIO; the next open accepts restoration.
 */
public final class OrbitRestoreDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.auras.orbit.test.orbitlibrestore";
    public static final String ARCHIVE_MIME_TYPE = "application/vnd.auras.orbit-library";

    private static final String ROOT_ID = "orbitlib-restore-root";
    private static final String PREFS_NAME = "orbitlib_restore_provider_fixture_v1";
    private static final int ACCEPTED_PREFIX_BYTES = 7;
    private static final byte[] SENTINEL = ("ORBIT-RESTORE-SENTINEL-v1\n"
            + "Preserve this exact preexisting document while the replacement write fails.\n")
            .getBytes(StandardCharsets.UTF_8);
    private static final Object STATE_LOCK = new Object();

    private static final String KEY_DOCUMENT_ID = "document_id";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_CONTENTS = "contents_base64";
    private static final String KEY_TRACE = "trace";
    private static final String KEY_WRITE_OPENS = "write_opens";
    private static final String KEY_FIRST_WRITE_CALLS = "first_write_calls";
    private static final String KEY_SNAPSHOT_READ = "snapshot_read";
    private static final String KEY_SNAPSHOT_BYTES_READ = "snapshot_bytes_read";
    private static final String KEY_PREFIX_ACCEPTED = "prefix_accepted";
    private static final String KEY_REPLACEMENT_FAILED = "replacement_failed";
    private static final String KEY_RESTORE_COMPLETE = "restore_complete";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[]{
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_ICON,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_MIME_TYPES,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        } : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            switch (column) {
                case DocumentsContract.Root.COLUMN_ROOT_ID:
                    row.add(ROOT_ID);
                    break;
                case DocumentsContract.Root.COLUMN_FLAGS:
                    row.add(DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                            | DocumentsContract.Root.FLAG_LOCAL_ONLY);
                    break;
                case DocumentsContract.Root.COLUMN_ICON:
                    row.add(getContext().getResources().getIdentifier(
                            "orbit_restore_provider_icon", "drawable", getContext().getPackageName()));
                    break;
                case DocumentsContract.Root.COLUMN_TITLE:
                    row.add("Orbit Restore Failure Fixture");
                    break;
                case DocumentsContract.Root.COLUMN_DOCUMENT_ID:
                    row.add(ROOT_ID);
                    break;
                case DocumentsContract.Root.COLUMN_MIME_TYPES:
                    row.add(ARCHIVE_MIME_TYPE + "\napplication/octet-stream");
                    break;
                case DocumentsContract.Root.COLUMN_AVAILABLE_BYTES:
                    row.add(64L * 1024L * 1024L);
                    break;
                default:
                    row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        if (ROOT_ID.equals(documentId)) return rootDocument(projection);
        requireDocument(documentId);
        String[] columns = projection == null ? documentColumns() : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) {
                row.add(documentId);
            } else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)
                    || OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(preferences().getString(KEY_DISPLAY_NAME, "Orbit-Library.orbitlib"));
            } else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)) {
                row.add(ARCHIVE_MIME_TYPE);
            } else if (DocumentsContract.Document.COLUMN_FLAGS.equals(column)) {
                row.add(DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                        | DocumentsContract.Document.FLAG_SUPPORTS_DELETE);
            } else if (DocumentsContract.Document.COLUMN_SIZE.equals(column)
                    || OpenableColumns.SIZE.equals(column)) {
                row.add(contents().length);
            } else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) {
                row.add(System.currentTimeMillis());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
            String sortOrder) throws FileNotFoundException {
        if (!ROOT_ID.equals(parentDocumentId)) throw new FileNotFoundException("Unknown fixture root");
        String documentId = preferences().getString(KEY_DOCUMENT_ID, null);
        if (documentId == null) return new MatrixCursor(projection == null ? documentColumns() : projection);
        return queryDocument(documentId, projection);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        if (ROOT_ID.equals(documentId)) return DocumentsContract.Document.MIME_TYPE_DIR;
        requireDocument(documentId);
        return ARCHIVE_MIME_TYPE;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return ROOT_ID.equals(parentDocumentId)
                && documentId != null
                && documentId.equals(preferences().getString(KEY_DOCUMENT_ID, null));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        if (!ROOT_ID.equals(parentDocumentId)) throw new FileNotFoundException("Unknown fixture root");
        if (!ARCHIVE_MIME_TYPE.equals(mimeType) && !"application/octet-stream".equals(mimeType)) {
            throw new FileNotFoundException("Unexpected archive MIME type: " + mimeType);
        }
        String documentId = "orbitlib-restore-" + UUID.randomUUID();
        String safeName = displayName == null || displayName.trim().isEmpty()
                ? "Orbit-Library.orbitlib" : displayName.trim();
        synchronized (STATE_LOCK) {
            boolean saved = preferences().edit()
                    .putString(KEY_DOCUMENT_ID, documentId)
                    .putString(KEY_DISPLAY_NAME, safeName)
                    .putString(KEY_CONTENTS, Base64.encodeToString(SENTINEL, Base64.NO_WRAP))
                    .putString(KEY_TRACE, "created-with-sentinel:" + documentId + ":" + SENTINEL.length)
                    .putInt(KEY_WRITE_OPENS, 0)
                    .putInt(KEY_FIRST_WRITE_CALLS, 0)
                    .putBoolean(KEY_SNAPSHOT_READ, false)
                    .putInt(KEY_SNAPSHOT_BYTES_READ, 0)
                    .putBoolean(KEY_PREFIX_ACCEPTED, false)
                    .putBoolean(KEY_REPLACEMENT_FAILED, false)
                    .putBoolean(KEY_RESTORE_COMPLETE, false)
                    .commit();
            if (!saved) throw new FileNotFoundException("Could not persist synthetic restore fixture");
        }
        return documentId;
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        requireDocument(documentId);
        synchronized (STATE_LOCK) {
            preferences().edit().remove(KEY_DOCUMENT_ID).remove(KEY_DISPLAY_NAME)
                    .remove(KEY_CONTENTS).commit();
            appendTrace("document-deleted");
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode,
            CancellationSignal signal) throws FileNotFoundException {
        requireDocument(documentId);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw new FileNotFoundException("The proxy descriptor fixture requires Android 8.0 or newer");
        }
        boolean writing = mode != null && mode.contains("w");
        int openNumber = -1;
        if (writing) {
            synchronized (STATE_LOCK) {
                openNumber = preferences().getInt(KEY_WRITE_OPENS, 0);
                preferences().edit().putInt(KEY_WRITE_OPENS, openNumber + 1)
                        .putString(KEY_CONTENTS, Base64.encodeToString(new byte[0], Base64.NO_WRAP))
                        .commit();
                appendTrace(openNumber == 0 ? "replacement-open-truncate" : "restore-open-truncate");
            }
        } else {
            synchronized (STATE_LOCK) {
                preferences().edit().putBoolean(KEY_SNAPSHOT_READ, true).commit();
                appendTrace("snapshot-read-open");
            }
        }

        StorageManager storage = (StorageManager) getContext().getSystemService(Context.STORAGE_SERVICE);
        if (storage == null) throw new FileNotFoundException("StorageManager unavailable");
        int pfdMode = writing
                ? ParcelFileDescriptor.MODE_WRITE_ONLY : ParcelFileDescriptor.MODE_READ_ONLY;
        try {
            return storage.openProxyFileDescriptor(pfdMode,
                    new FixtureDescriptorCallback(documentId, writing, openNumber),
                    new Handler(Looper.getMainLooper()));
        } catch (IOException | RuntimeException error) {
            FileNotFoundException wrapped = new FileNotFoundException("Could not open restore fixture");
            wrapped.initCause(error);
            throw wrapped;
        }
    }

    private Cursor rootDocument(String[] projection) {
        String[] columns = projection == null ? new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE
        } : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            switch (column) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                    row.add(ROOT_ID);
                    break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                    row.add("Orbit Restore Failure Fixture");
                    break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    row.add(DocumentsContract.Document.MIME_TYPE_DIR);
                    break;
                case DocumentsContract.Document.COLUMN_FLAGS:
                    row.add(DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                            | DocumentsContract.Document.FLAG_DIR_PREFERS_LAST_MODIFIED);
                    break;
                case DocumentsContract.Document.COLUMN_SIZE:
                    row.add(null);
                    break;
                default:
                    row.add(null);
            }
        }
        return cursor;
    }

    private static String[] documentColumns() {
        return new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
    }

    private void requireDocument(String documentId) throws FileNotFoundException {
        if (documentId == null || !documentId.equals(preferences().getString(KEY_DOCUMENT_ID, null))) {
            throw new FileNotFoundException("Unknown restore fixture document");
        }
    }

    private SharedPreferences preferences() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private byte[] contents() {
        String encoded = preferences().getString(KEY_CONTENTS, "");
        if (encoded == null || encoded.isEmpty()) return new byte[0];
        return Base64.decode(encoded, Base64.NO_WRAP);
    }

    private void appendTrace(String event) {
        SharedPreferences prefs = preferences();
        String old = prefs.getString(KEY_TRACE, "");
        String next = old == null || old.isEmpty() ? event : old + "\n" + event;
        prefs.edit().putString(KEY_TRACE, next).commit();
    }

    private void writeAt(long offset, byte[] source, int count) {
        synchronized (STATE_LOCK) {
            byte[] current = contents();
            int start = Math.toIntExact(offset);
            int end = start + count;
            byte[] updated = new byte[Math.max(current.length, end)];
            System.arraycopy(current, 0, updated, 0, current.length);
            System.arraycopy(source, 0, updated, start, count);
            preferences().edit().putString(KEY_CONTENTS,
                    Base64.encodeToString(updated, Base64.NO_WRAP)).commit();
        }
    }

    private final class FixtureDescriptorCallback extends ProxyFileDescriptorCallback {
        private final String documentId;
        private final boolean writing;
        private final int writeAttempt;

        FixtureDescriptorCallback(String documentId, boolean writing, int writeAttempt) {
            this.documentId = documentId;
            this.writing = writing;
            this.writeAttempt = writeAttempt;
        }

        @Override
        public long onGetSize() throws ErrnoException {
            try {
                requireDocument(documentId);
                return contents().length;
            } catch (FileNotFoundException error) {
                throw new ErrnoException("orbitlib-restore-size", OsConstants.ENOENT, error);
            }
        }

        @Override
        public int onRead(long offset, int size, byte[] data) throws ErrnoException {
            if (writing) throw new ErrnoException("orbitlib-restore-read", OsConstants.EBADF);
            byte[] current = contents();
            if (offset >= current.length) return 0;
            int count = (int) Math.min((long) size, current.length - offset);
            System.arraycopy(current, Math.toIntExact(offset), data, 0, count);
            synchronized (STATE_LOCK) {
                int readThrough = Math.toIntExact(offset) + count;
                int previous = preferences().getInt(KEY_SNAPSHOT_BYTES_READ, 0);
                preferences().edit().putInt(KEY_SNAPSHOT_BYTES_READ,
                        Math.max(previous, readThrough)).commit();
                appendTrace("snapshot-read-bytes:" + count + ":through=" + readThrough);
            }
            return count;
        }

        @Override
        public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
            if (!writing) throw new ErrnoException("orbitlib-restore-write", OsConstants.EBADF);
            if (writeAttempt == 0) {
                synchronized (STATE_LOCK) {
                    int calls = preferences().getInt(KEY_FIRST_WRITE_CALLS, 0);
                    if (calls == 0) {
                        if (size <= ACCEPTED_PREFIX_BYTES) {
                            appendTrace("replacement-write-too-small-to-partialize:" + size);
                            throw new ErrnoException("orbitlib-restore-replacement", OsConstants.EIO);
                        }
                        writeAt(offset, data, ACCEPTED_PREFIX_BYTES);
                        preferences().edit().putInt(KEY_FIRST_WRITE_CALLS, 1)
                                .putBoolean(KEY_PREFIX_ACCEPTED, true).commit();
                        appendTrace("replacement-prefix-accepted:" + ACCEPTED_PREFIX_BYTES);
                        return ACCEPTED_PREFIX_BYTES;
                    }
                    preferences().edit().putBoolean(KEY_REPLACEMENT_FAILED, true).commit();
                    appendTrace("replacement-write-failed:EIO-after-prefix");
                    throw new ErrnoException("orbitlib-restore-injected-replacement-failure",
                            OsConstants.EIO);
                }
            }
            writeAt(offset, data, size);
            appendTrace("restore-write-accepted:" + size);
            return size;
        }

        @Override
        public void onFsync() throws ErrnoException {
            appendTrace(writeAttempt == 0 ? "replacement-fsync" : "restore-fsync");
        }

        @Override
        public void onRelease() {
            if (!writing) return;
            if (writeAttempt == 1) {
                boolean matches = java.util.Arrays.equals(SENTINEL, contents());
                preferences().edit().putBoolean(KEY_RESTORE_COMPLETE, matches).commit();
                appendTrace(matches ? "restore-complete:sentinel-matches"
                        : "restore-incomplete:sentinel-mismatch");
            } else {
                appendTrace("replacement-release");
            }
        }
    }
}
