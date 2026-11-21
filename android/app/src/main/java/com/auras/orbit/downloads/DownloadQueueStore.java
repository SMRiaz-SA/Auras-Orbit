package com.auras.orbit.downloads;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Private, non-backed-up persistence for Orbit's download queue and offline file references. */
final class DownloadQueueStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "orbit_download_queue.db";
    private static final int DATABASE_VERSION = 6;
    private static final String TABLE = "downloads";

    DownloadQueueStore(Context context) {
        this(context, DATABASE_NAME);
    }

    /** Package-private isolated database seam used by AndroidTest migration fixtures. */
    DownloadQueueStore(Context context, String databaseName) {
        super(context.getApplicationContext(), databaseName, null, DATABASE_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "profile_id TEXT NOT NULL,"
                + "stable_item_id TEXT NOT NULL,"
                + "source_id TEXT NOT NULL,"
                + "title TEXT NOT NULL,"
                + "file_name TEXT NOT NULL,"
                + "mime_type TEXT NOT NULL,"
                + "season_label TEXT NOT NULL DEFAULT '',"
                + "episode_label TEXT NOT NULL DEFAULT '',"
                + "known_size_bytes INTEGER NOT NULL DEFAULT -1,"
                + "artwork_ref TEXT NOT NULL DEFAULT '',"
                + "source_nonce BLOB NOT NULL,"
                + "source_ciphertext BLOB NOT NULL,"
                + "headers_nonce BLOB NOT NULL DEFAULT X'',"
                + "headers_ciphertext BLOB NOT NULL DEFAULT X'',"
                + "verifier_state TEXT NOT NULL DEFAULT 'NONE',"
                + "verifier_nonce BLOB NOT NULL DEFAULT X'',"
                + "verifier_ciphertext BLOB NOT NULL DEFAULT X'',"
                + "status TEXT NOT NULL,"
                + "bytes_downloaded INTEGER NOT NULL DEFAULT 0,"
                + "total_bytes INTEGER NOT NULL DEFAULT 0,"
                + "queue_order INTEGER NOT NULL DEFAULT 0,"
                + "run_token TEXT NOT NULL DEFAULT '',"
                + "created_at INTEGER NOT NULL,"
                + "file_uri TEXT NOT NULL DEFAULT '',"
                + "rename_old_uri TEXT NOT NULL DEFAULT '',"
                + "rename_old_name TEXT NOT NULL DEFAULT '',"
                + "rename_target_name TEXT NOT NULL DEFAULT '',"
                + "partial_uri TEXT NOT NULL DEFAULT '',"
                + "partial_name TEXT NOT NULL DEFAULT '',"
                + "folder_uri TEXT NOT NULL,"
                + "error_message TEXT NOT NULL DEFAULT ''"
                + ")");
        db.execSQL("CREATE INDEX downloads_status_order ON " + TABLE
                + " (status, queue_order ASC, created_at ASC, id ASC)");
        db.execSQL("CREATE INDEX downloads_profile_order ON " + TABLE + " (profile_id, created_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN partial_name TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 3) {
            // Older rows had no request headers. Empty encrypted payload columns keep them
            // readable without touching their source URL or SAF cleanup state.
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN headers_nonce BLOB NOT NULL DEFAULT X''");
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN headers_ciphertext BLOB NOT NULL DEFAULT X''");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN season_label TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN episode_label TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN known_size_bytes INTEGER NOT NULL DEFAULT -1");
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN artwork_ref TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN queue_order INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN run_token TEXT NOT NULL DEFAULT ''");
            // Preserve each v3 row's original FIFO order and all persisted document references.
            db.execSQL("UPDATE " + TABLE + " SET queue_order=created_at");
            db.execSQL("DROP INDEX IF EXISTS downloads_status_order");
            db.execSQL("CREATE INDEX downloads_status_order ON " + TABLE
                    + " (status, queue_order ASC, created_at ASC, id ASC)");
        }
        if (oldVersion < 5) {
            // The row-local journal reserves a completed item while a provider rename is in flight.
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN rename_old_uri TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN rename_old_name TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN rename_target_name TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 6) {
            // Existing rows predate verifier recovery metadata. Keep all queue/document state,
            // and leave rows from other provider sources tokenless. The prior CS3 adapter wrote
            // only this source ID shape, so those rows fail closed rather than lose verifier ownership.
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN verifier_state TEXT NOT NULL DEFAULT 'NONE'");
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN verifier_nonce BLOB NOT NULL DEFAULT X''");
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN verifier_ciphertext BLOB NOT NULL DEFAULT X''");
            db.execSQL("UPDATE " + TABLE + " SET verifier_state='LEGACY' "
                    + "WHERE source_id LIKE 'provider-source-%'");
        }
    }

    synchronized void insert(DownloadRequest request, String id, String fileName, String mimeType,
            byte[] nonce, byte[] encryptedUrl, byte[] headersNonce, byte[] encryptedHeaders,
            String verifierState, byte[] verifierNonce, byte[] encryptedVerifierToken,
            String folderUri, long createdAt) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("profile_id", request.profileId.trim());
        values.put("stable_item_id", request.stableItemId.trim());
        values.put("source_id", request.sourceId == null ? "" : request.sourceId.trim());
        values.put("title", request.title.trim());
        values.put("file_name", fileName);
        values.put("mime_type", mimeType);
        values.put("season_label", request.seasonLabel);
        values.put("episode_label", request.episodeLabel);
        values.put("known_size_bytes", request.knownSizeBytes);
        values.put("artwork_ref", request.artworkReference);
        values.put("source_nonce", nonce);
        values.put("source_ciphertext", encryptedUrl);
        values.put("headers_nonce", headersNonce);
        values.put("headers_ciphertext", encryptedHeaders);
        values.put("verifier_state", verifierState);
        values.put("verifier_nonce", verifierNonce);
        values.put("verifier_ciphertext", encryptedVerifierToken);
        values.put("status", DownloadRecord.QUEUED);
        values.put("created_at", createdAt);
        SQLiteDatabase db = getWritableDatabase();
        values.put("queue_order", nextQueueOrder(db));
        values.put("folder_uri", folderUri);
        db.insertOrThrow(TABLE, null, values);
    }

    synchronized DownloadRecord get(String id) {
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, "id=?", new String[]{id},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    synchronized DownloadRecord getByIdentity(String profileId, String sourceId, String stableItemId) {
        try (Cursor cursor = getReadableDatabase().query(TABLE, null,
                "profile_id=? AND source_id=? AND stable_item_id=? AND status IN (?,?,?,?,?)",
                new String[]{profileId, sourceId, stableItemId, DownloadRecord.QUEUED,
                        DownloadRecord.DOWNLOADING, DownloadRecord.PAUSED, DownloadRecord.COMPLETED,
                        DownloadRecord.REMOVING},
                null, null, "queue_order DESC, created_at DESC", "1")) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    synchronized List<DownloadRecord> listAll() {
        ArrayList<DownloadRecord> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, null, null,
                null, null, "created_at DESC, id ASC")) {
            while (cursor.moveToNext()) result.add(read(cursor));
        }
        return Collections.unmodifiableList(result);
    }

    synchronized List<DownloadRecord> listOffline() {
        ArrayList<DownloadRecord> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, "status=?",
                new String[]{DownloadRecord.COMPLETED}, null, null, "created_at DESC, id ASC")) {
            while (cursor.moveToNext()) result.add(read(cursor));
        }
        return Collections.unmodifiableList(result);
    }

    synchronized List<String> listIds(String status) {
        ArrayList<String> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{"id"}, "status=?",
                new String[]{status}, null, null, "queue_order ASC, created_at ASC, id ASC")) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return result;
    }

    synchronized PendingSource getPendingSource(String id) {
        try (Cursor cursor = getReadableDatabase().query(TABLE,
                new String[]{"source_nonce", "source_ciphertext", "headers_nonce", "headers_ciphertext",
                        "folder_uri", "file_name", "mime_type", "source_id", "verifier_state",
                        "verifier_nonce", "verifier_ciphertext"},
                "id=?", new String[]{id}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;
            return new PendingSource(cursor.getBlob(0), cursor.getBlob(1), cursor.getBlob(2),
                    cursor.getBlob(3), cursor.getString(4), cursor.getString(5), cursor.getString(6),
                    cursor.getString(7), cursor.getString(8), cursor.getBlob(9), cursor.getBlob(10));
        }
    }

    synchronized String getPartialUri(String id) {
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{"partial_uri"},
                "id=?", new String[]{id}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return "";
            return cursor.getString(0);
        }
    }

    synchronized void setStatus(String id, String status, String error) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("error_message", error == null ? "" : error);
        if (!DownloadRecord.DOWNLOADING.equals(status)) {
            values.put("partial_uri", "");
            values.put("partial_name", "");
            values.put("run_token", "");
        }
        getWritableDatabase().update(TABLE, values, "id=?", new String[]{id});
    }

    synchronized boolean setTaskStatus(String id, String runToken, String status, String error) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("error_message", error == null ? "" : error);
        values.put("run_token", "");
        return getWritableDatabase().update(TABLE, values,
                "id=? AND status=? AND run_token=?",
                new String[]{id, DownloadRecord.DOWNLOADING, runToken}) == 1;
    }

    synchronized boolean setRemovalPending(String id) {
        ContentValues values = new ContentValues();
        values.put("status", DownloadRecord.REMOVING);
        values.put("error_message", "Removing the selected file…");
        return getWritableDatabase().update(TABLE, values,
                "id=? AND status IN (?,?,?,?,?) AND rename_old_uri=''",
                new String[]{id, DownloadRecord.QUEUED, DownloadRecord.PAUSED,
                        DownloadRecord.FAILED, DownloadRecord.CANCELLED, DownloadRecord.COMPLETED}) == 1;
    }

    synchronized boolean setTaskRemovalPending(String id, String runToken) {
        ContentValues values = new ContentValues();
        values.put("status", DownloadRecord.REMOVING);
        values.put("error_message", "Removing the selected file…");
        return getWritableDatabase().update(TABLE, values,
                "id=? AND status=? AND run_token=?",
                new String[]{id, DownloadRecord.DOWNLOADING, runToken}) == 1;
    }

    synchronized boolean setRemovalFailure(String id, String partialUri, String fileUri, String error) {
        ContentValues values = new ContentValues();
        values.put("status", DownloadRecord.FAILED);
        values.put("partial_uri", partialUri == null ? "" : partialUri);
        values.put("file_uri", fileUri == null ? "" : fileUri);
        values.put("error_message", error == null ? "Orbit could not remove the selected file." : error);
        values.put("run_token", "");
        return getWritableDatabase().update(TABLE, values, "id=?", new String[]{id}) == 1;
    }

    synchronized boolean setTaskRemovalFailure(String id, String runToken,
            String partialUri, String fileUri, String error) {
        ContentValues values = new ContentValues();
        values.put("status", DownloadRecord.FAILED);
        values.put("partial_uri", partialUri == null ? "" : partialUri);
        values.put("file_uri", fileUri == null ? "" : fileUri);
        values.put("error_message", error == null ? "Orbit could not remove the selected file." : error);
        values.put("run_token", "");
        return getWritableDatabase().update(TABLE, values,
                "id=? AND run_token=? AND status IN (?,?)",
                new String[]{id, runToken, DownloadRecord.DOWNLOADING, DownloadRecord.REMOVING}) == 1;
    }

    /** Claims a queued item once; the manager serializes this with pause and cancel actions. */
    synchronized boolean claimQueued(String id) {
        return claimQueued(id, "test-claim-" + id);
    }

    synchronized boolean claimQueued(String id, String runToken) {
        ContentValues values = new ContentValues();
        values.put("status", DownloadRecord.DOWNLOADING);
        values.put("error_message", "");
        values.put("run_token", runToken == null ? "" : runToken);
        return getWritableDatabase().update(TABLE, values, "id=? AND status=?",
                new String[]{id, DownloadRecord.QUEUED}) == 1;
    }

    synchronized void updateProgress(String id, long bytes, long total) {
        ContentValues values = new ContentValues();
        values.put("bytes_downloaded", Math.max(0L, bytes));
        values.put("total_bytes", Math.max(0L, total));
        getWritableDatabase().update(TABLE, values, "id=?", new String[]{id});
    }

    synchronized boolean updateProgress(String id, String runToken, long bytes, long total) {
        ContentValues values = new ContentValues();
        values.put("bytes_downloaded", Math.max(0L, bytes));
        values.put("total_bytes", Math.max(0L, total));
        return getWritableDatabase().update(TABLE, values,
                "id=? AND status=? AND run_token=?",
                new String[]{id, DownloadRecord.DOWNLOADING, runToken}) == 1;
    }

    synchronized void setPartialUri(String id, String uri) {
        ContentValues values = new ContentValues();
        values.put("partial_uri", uri == null ? "" : uri);
        getWritableDatabase().update(TABLE, values, "id=?", new String[]{id});
    }

    synchronized boolean setPartialUri(String id, String runToken, String uri) {
        ContentValues values = new ContentValues();
        values.put("partial_uri", uri == null ? "" : uri);
        return getWritableDatabase().update(TABLE, values,
                "id=? AND status=? AND run_token=?",
                new String[]{id, DownloadRecord.DOWNLOADING, runToken}) == 1;
    }

    /** Persists the deterministic SAF display name before createDocument can leave an orphan. */
    synchronized void setPartialReservation(String id, String name) {
        ContentValues values = new ContentValues();
        values.put("partial_name", name == null ? "" : name);
        getWritableDatabase().update(TABLE, values, "id=?", new String[]{id});
    }

    synchronized boolean setPartialReservation(String id, String runToken, String name) {
        ContentValues values = new ContentValues();
        values.put("partial_name", name == null ? "" : name);
        return getWritableDatabase().update(TABLE, values,
                "id=? AND status=? AND run_token=?",
                new String[]{id, DownloadRecord.DOWNLOADING, runToken}) == 1;
    }

    synchronized RetainedDocuments getRetainedDocuments(String id) {
        try (Cursor cursor = getReadableDatabase().query(TABLE,
                new String[]{"partial_uri", "file_uri", "partial_name", "folder_uri", "file_name"},
                "id=?", new String[]{id}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;
            return new RetainedDocuments(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getString(4));
        }
    }

    /** Persists the rename intent before any SAF provider mutation is attempted. */
    synchronized boolean beginPendingRename(String id, String originalUri, String originalName,
            String targetName) {
        if (isBlank(id) || isBlank(originalUri) || isBlank(originalName) || isBlank(targetName)) return false;
        ContentValues values = new ContentValues();
        values.put("rename_old_uri", originalUri);
        values.put("rename_old_name", originalName);
        values.put("rename_target_name", targetName);
        return getWritableDatabase().update(TABLE, values,
                "id=? AND status=? AND file_uri=? AND file_name=? AND rename_old_uri=''",
                new String[]{id, DownloadRecord.COMPLETED, originalUri, originalName}) == 1;
    }

    /** Atomically commits the provider's current document URI/name and clears its journal. */
    synchronized boolean finishPendingRename(String id, String originalUri, String originalName,
            String targetName, String currentUri, String currentName) {
        if (isBlank(id) || isBlank(originalUri) || isBlank(originalName) || isBlank(targetName)
                || isBlank(currentUri) || isBlank(currentName)) return false;
        ContentValues values = new ContentValues();
        values.put("file_uri", currentUri);
        values.put("file_name", currentName);
        clearPendingRenameValues(values);
        return getWritableDatabase().update(TABLE, values,
                "id=? AND status=? AND file_uri=? AND file_name=? AND rename_old_uri=? "
                        + "AND rename_old_name=? AND rename_target_name=?",
                new String[]{id, DownloadRecord.COMPLETED, originalUri, originalName,
                        originalUri, originalName, targetName}) == 1;
    }

    /** Clears an intent only after reconciliation confirms the original document is unchanged. */
    synchronized boolean clearPendingRename(String id, String originalUri, String originalName,
            String targetName) {
        if (isBlank(id) || isBlank(originalUri) || isBlank(originalName) || isBlank(targetName)) return false;
        ContentValues values = new ContentValues();
        clearPendingRenameValues(values);
        return getWritableDatabase().update(TABLE, values,
                "id=? AND status=? AND file_uri=? AND file_name=? AND rename_old_uri=? "
                        + "AND rename_old_name=? AND rename_target_name=?",
                new String[]{id, DownloadRecord.COMPLETED, originalUri, originalName,
                        originalUri, originalName, targetName}) == 1;
    }

    synchronized List<PendingRename> getPendingRenames() {
        ArrayList<PendingRename> pending = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE,
                new String[]{"id", "status", "file_uri", "file_name", "folder_uri", "mime_type",
                        "bytes_downloaded", "rename_old_uri", "rename_old_name", "rename_target_name"},
                "rename_old_uri<>''", null, null, null, "created_at ASC, id ASC")) {
            while (cursor.moveToNext()) {
                pending.add(new PendingRename(cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5),
                        cursor.getLong(6), cursor.getString(7), cursor.getString(8), cursor.getString(9)));
            }
        }
        return pending;
    }

    synchronized boolean clearRetainedDocuments(String id) {
        ContentValues values = new ContentValues();
        values.put("partial_uri", "");
        values.put("file_uri", "");
        values.put("partial_name", "");
        return getWritableDatabase().update(TABLE, values, "id=?", new String[]{id}) == 1;
    }

    synchronized boolean clearTaskRetainedDocuments(String id, String runToken) {
        ContentValues values = new ContentValues();
        values.put("partial_uri", "");
        values.put("file_uri", "");
        values.put("partial_name", "");
        return getWritableDatabase().update(TABLE, values,
                "id=? AND run_token=? AND status IN (?,?)",
                new String[]{id, runToken, DownloadRecord.DOWNLOADING, DownloadRecord.REMOVING}) == 1;
    }

    synchronized void markComplete(String id, String fileUri, long bytes) {
        markCompleteValues(id, null, fileUri, bytes);
    }

    synchronized boolean markComplete(String id, String runToken, String fileUri, long bytes) {
        return markCompleteValues(id, runToken, fileUri, bytes);
    }

    private boolean markCompleteValues(String id, String runToken, String fileUri, long bytes) {
        ContentValues values = new ContentValues();
        values.put("status", DownloadRecord.COMPLETED);
        values.put("file_uri", fileUri);
        values.put("partial_uri", "");
        values.put("partial_name", "");
        values.put("bytes_downloaded", bytes);
        values.put("total_bytes", bytes);
        values.put("error_message", "");
        values.put("run_token", "");
        if (runToken == null) {
            return getWritableDatabase().update(TABLE, values, "id=?", new String[]{id}) == 1;
        }
        return getWritableDatabase().update(TABLE, values,
                "id=? AND status=? AND run_token=?",
                new String[]{id, DownloadRecord.DOWNLOADING, runToken}) == 1;
    }

    synchronized void resetForRetry(String id) {
        resetForRetry(id, null);
    }

    synchronized boolean resetTaskForRetry(String id, String runToken) {
        return resetForRetry(id, runToken);
    }

    private boolean resetForRetry(String id, String runToken) {
        ContentValues values = new ContentValues();
        values.put("status", DownloadRecord.QUEUED);
        values.put("bytes_downloaded", 0L);
        values.put("total_bytes", 0L);
        values.put("file_uri", "");
        values.put("partial_uri", "");
        values.put("partial_name", "");
        values.put("error_message", "");
        values.put("run_token", "");
        values.put("queue_order", nextQueueOrder(getWritableDatabase()));
        if (runToken == null) {
            return getWritableDatabase().update(TABLE, values, "id=?", new String[]{id}) == 1;
        }
        return getWritableDatabase().update(TABLE, values,
                "id=? AND status=? AND run_token=?",
                new String[]{id, DownloadRecord.DOWNLOADING, runToken}) == 1;
    }

    synchronized void delete(String id) {
        getWritableDatabase().delete(TABLE, "id=?", new String[]{id});
    }

    synchronized boolean deleteTask(String id, String runToken, String status) {
        return getWritableDatabase().delete(TABLE,
                "id=? AND run_token=? AND status=?", new String[]{id, runToken, status}) == 1;
    }

    synchronized boolean deleteIfStatus(String id, String status) {
        return getWritableDatabase().delete(TABLE, "id=? AND status=?",
                new String[]{id, status}) == 1;
    }

    /**
     * Returns interrupted rows without changing their state. The manager must finish SAF cleanup
     * before it can clear the partial-file reservation or make a row retryable; leaving
     * DOWNLOADING/REMOVING persisted also lets the next process retry cleanup after a crash.
     */
    synchronized List<InterruptedFile> recoverInterrupted() {
        ArrayList<InterruptedFile> partialFiles = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE,
                new String[]{"id", "status", "partial_uri", "partial_name", "folder_uri", "file_name"},
                "status IN (?,?)", new String[]{DownloadRecord.DOWNLOADING, DownloadRecord.REMOVING},
                null, null, null)) {
            while (cursor.moveToNext()) {
                partialFiles.add(new InterruptedFile(cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5)));
            }
        }
        ContentValues resetProgress = new ContentValues();
        resetProgress.put("bytes_downloaded", 0L);
        resetProgress.put("total_bytes", 0L);
        getWritableDatabase().update(TABLE, resetProgress, "status=?",
                new String[]{DownloadRecord.DOWNLOADING});
        return partialFiles;
    }

    synchronized void clearPartialAfterCleanup(String id, String status, String message) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("partial_uri", "");
        values.put("partial_name", "");
        values.put("error_message", message == null ? "" : message);
        values.put("run_token", "");
        if (!DownloadRecord.DOWNLOADING.equals(status)) {
            getWritableDatabase().update(TABLE, values, "id=?", new String[]{id});
        }
    }

    private static DownloadRecord read(Cursor cursor) {
        return new DownloadRecord(cursor.getString(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("profile_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("stable_item_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("source_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("title")),
                cursor.getString(cursor.getColumnIndexOrThrow("file_name")),
                cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
                cursor.getString(cursor.getColumnIndexOrThrow("status")),
                cursor.getLong(cursor.getColumnIndexOrThrow("bytes_downloaded")),
                cursor.getLong(cursor.getColumnIndexOrThrow("total_bytes")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getString(cursor.getColumnIndexOrThrow("file_uri")),
                cursor.getString(cursor.getColumnIndexOrThrow("folder_uri")),
                cursor.getString(cursor.getColumnIndexOrThrow("error_message")),
                cursor.getLong(cursor.getColumnIndexOrThrow("queue_order")),
                cursor.getString(cursor.getColumnIndexOrThrow("season_label")),
                cursor.getString(cursor.getColumnIndexOrThrow("episode_label")),
                cursor.getLong(cursor.getColumnIndexOrThrow("known_size_bytes")),
                cursor.getString(cursor.getColumnIndexOrThrow("artwork_ref")));
    }

    private static long nextQueueOrder(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COALESCE(MAX(queue_order),0) FROM " + TABLE, null)) {
            long maximum = cursor.moveToFirst() ? cursor.getLong(0) : 0L;
            return maximum == Long.MAX_VALUE ? Long.MAX_VALUE : maximum + 1L;
        }
    }

    private static void clearPendingRenameValues(ContentValues values) {
        values.put("rename_old_uri", "");
        values.put("rename_old_name", "");
        values.put("rename_target_name", "");
    }

    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }

    static final class PendingSource {
        final byte[] nonce;
        final byte[] ciphertext;
        final byte[] headersNonce;
        final byte[] encryptedHeaders;
        final String folderUri;
        final String fileName;
        final String mimeType;
        final String sourceId;
        final String verifierState;
        final byte[] verifierNonce;
        final byte[] encryptedVerifierToken;
        PendingSource(byte[] nonce, byte[] ciphertext, byte[] headersNonce, byte[] encryptedHeaders,
                String folderUri, String fileName, String mimeType, String sourceId,
                String verifierState, byte[] verifierNonce, byte[] encryptedVerifierToken) {
            this.nonce = nonce;
            this.ciphertext = ciphertext;
            this.headersNonce = headersNonce;
            this.encryptedHeaders = encryptedHeaders;
            this.folderUri = folderUri;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.sourceId = sourceId;
            this.verifierState = verifierState;
            this.verifierNonce = verifierNonce;
            this.encryptedVerifierToken = encryptedVerifierToken;
        }
    }

    static final class InterruptedFile {
        final String id;
        final String status;
        final String uri;
        final String partialName;
        final String folderUri;
        final String fileName;
        InterruptedFile(String id, String status, String uri, String partialName, String folderUri, String fileName) {
            this.id = id;
            this.status = status;
            this.uri = uri;
            this.partialName = partialName;
            this.folderUri = folderUri;
            this.fileName = fileName;
        }
    }

    static final class RetainedDocuments {
        final String partialUri;
        final String fileUri;
        final String partialName;
        final String folderUri;
        final String fileName;
        RetainedDocuments(String partialUri, String fileUri, String partialName,
                String folderUri, String fileName) {
            this.partialUri = partialUri;
            this.fileUri = fileUri;
            this.partialName = partialName;
            this.folderUri = folderUri;
            this.fileName = fileName;
        }
    }

    static final class PendingRename {
        final String id;
        final String status;
        final String fileUri;
        final String fileName;
        final String folderUri;
        final String mimeType;
        final long bytesDownloaded;
        final String originalUri;
        final String originalName;
        final String targetName;
        PendingRename(String id, String status, String fileUri, String fileName, String folderUri,
                String mimeType, long bytesDownloaded, String originalUri, String originalName,
                String targetName) {
            this.id = id;
            this.status = status;
            this.fileUri = fileUri;
            this.fileName = fileName;
            this.folderUri = folderUri;
            this.mimeType = mimeType;
            this.bytesDownloaded = bytesDownloaded;
            this.originalUri = originalUri;
            this.originalName = originalName;
            this.targetName = targetName;
        }
    }
}
