package com.auras.orbit.downloads;

import android.content.ContentValues;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.player.PlaybackDnsPinning;

import okhttp3.Dns;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Offline-only lifecycle checks using local HTTP and the dedicated SAF fixture tree. */
@RunWith(AndroidJUnit4.class)
public final class DownloadQueueConcurrencyInstrumentationTest {
    private static final String TEST_PROFILE = "download-test-profile";
    private static final String TEST_STABLE_PREFIX = "queue-lifecycle-";
    private static final String TEST_FILE_PREFIX = "Orbit-Queue-Fixture-";
    private static final String DOWNLOADS_PREFS = "orbit_downloads_shell_v1";
    private static final String FOLDER_KEY = "folder_uri";
    private static final String TREE_DOCUMENT_ID =
            "primary:Download/Orbit Download Queue Fixture";
    private static final String ORIGIN_HOST = "download-origin.example";
    private static final byte[] PAYLOAD = payloadBytes();

    private Context target;
    private Uri folder;
    private DownloadQueueManager manager;
    private final Map<String, String> requestPathByRowId = new HashMap<>();

    @Before public void requireDedicatedWritableFixtureTree() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String raw = target.getSharedPreferences(DOWNLOADS_PREFS, Context.MODE_PRIVATE)
                .getString(FOLDER_KEY, null);
        assertNotNull("select Download/Orbit Download Queue Fixture in Orbit > Downloads", raw);
        folder = Uri.parse(raw);
        assertTrue("the test may touch only the dedicated SAF tree",
                DocumentsContract.isTreeUri(folder)
                        && "com.android.externalstorage.documents".equals(folder.getAuthority())
                        && TREE_DOCUMENT_ID.equals(DocumentsContract.getTreeDocumentId(folder)));
        boolean read = false;
        boolean write = false;
        for (UriPermission permission : target.getContentResolver().getPersistedUriPermissions()) {
            if (folder.equals(permission.getUri())) {
                read |= permission.isReadPermission();
                write |= permission.isWritePermission();
            }
        }
        assertTrue("the fixture tree must retain read access", read);
        assertTrue("the fixture tree must retain write access", write);
        assertFixtureFolderEmpty();
    }

    @After public void closeManagerAndRemoveOnlyOwnedFixtureRows() throws Exception {
        if (manager != null) {
            manager.close();
            assertTrue("manager must finish synthetic transfer cleanup before fixture teardown",
                    awaitNoActiveRows(10_000L));
            manager = null;
        }
        if (target != null && folder != null) cleanOwnedRows();
    }

    @Test public void twoWorkersStartInFifoOrderAndThirdWaitsUntilAWorkerFinishes()
            throws Exception {
        manager = newManagerWithFixtureDns();
        try (LocalTransferServer server = new LocalTransferServer()) {
            List<String> ids = enqueueItems(3, server);
            manager.startQueue();

            assertTrue("the two worker slots should start the first two rows",
                    server.awaitRequestCount(2, 8_000L));
            awaitStatus(ids.get(0), DownloadRecord.DOWNLOADING, 5_000L);
            awaitStatus(ids.get(1), DownloadRecord.DOWNLOADING, 5_000L);
            assertEquals("the manager admits exactly two active transfers",
                    2, activeCount());
            DownloadQueueStore store = new DownloadQueueStore(target);
            try {
                assertEquals("the third row remains FIFO queued while both slots are occupied",
                        Collections.singletonList(ids.get(2)), store.listIds(DownloadRecord.QUEUED));
            } finally {
                store.close();
            }
            assertEquals("initial HTTP requests must follow stable claim order",
                    Arrays.asList(requestPathFor(ids.get(0)), requestPathFor(ids.get(1))),
                    server.requestPaths());
            assertFalse("a third request must not start before an active slot is released",
                    server.awaitRequestCount(3, 400L));

            server.releaseResponses();
            for (String id : ids) awaitStatus(id, DownloadRecord.COMPLETED, 12_000L);
            for (String id : ids) {
                DownloadRecord completed = record(id);
                assertNotNull("each completed row has an offline URI", completed);
                assertArrayEquals("offline bytes must match the local fixture exactly",
                        PAYLOAD, readDocument(manager.getOfflineUri(id)));
            }
            assertEquals(Arrays.asList(requestPathFor(ids.get(0)), requestPathFor(ids.get(1)),
                    requestPathFor(ids.get(2))), server.requestPaths());

            DownloadRecord remove = record(ids.get(1));
            assertNotNull(remove);
            String fileName = remove.fileName;
            assertTrue("removing a completed item deletes only its own row and file",
                    manager.remove(ids.get(1)));
            assertNull(documentForName(fileName));
            assertNull(record(ids.get(1)));
        }
    }

    @Test public void pauseCancelAndRetryPreserveSlotsAndRetryReturnsToFifoTail()
            throws Exception {
        manager = newManagerWithFixtureDns();
        try (LocalTransferServer server = new LocalTransferServer()) {
            List<String> ids = enqueueItems(4, server);
            manager.startQueue();
            assertTrue("two local streams should become active", server.awaitRequestCount(2, 8_000L));
            awaitPartialUri(ids.get(0), 5_000L);
            awaitPartialUri(ids.get(1), 5_000L);

            manager.pause(ids.get(0));
            awaitStatus(ids.get(0), DownloadRecord.PAUSED, 8_000L);
            assertNull("pause cleans that row's partial SAF file", partialDocument(ids.get(0)));
            assertTrue("the next FIFO item should occupy the released slot",
                    server.awaitRequestCount(3, 8_000L));
            awaitStatus(ids.get(2), DownloadRecord.DOWNLOADING, 5_000L);

            manager.retry(ids.get(0));
            DownloadQueueStore store = new DownloadQueueStore(target);
            try {
                assertEquals("retry is appended after the existing queued tail",
                        Arrays.asList(ids.get(3), ids.get(0)), store.listIds(DownloadRecord.QUEUED));
            } finally {
                store.close();
            }

            manager.cancel(ids.get(1));
            awaitStatus(ids.get(1), DownloadRecord.CANCELLED, 8_000L);
            assertNull("cancel cleans only the canceled row's partial SAF file",
                    partialDocument(ids.get(1)));
            assertTrue("the pre-existing FIFO tail starts before the retried row",
                    server.awaitRequestCount(4, 8_000L));
            awaitStatus(ids.get(3), DownloadRecord.DOWNLOADING, 5_000L);
            assertEquals(Arrays.asList(requestPathFor(ids.get(0)), requestPathFor(ids.get(1)),
                    requestPathFor(ids.get(2)), requestPathFor(ids.get(3))), server.requestPaths());
            store = new DownloadQueueStore(target);
            try {
                assertEquals(Collections.singletonList(ids.get(0)),
                        store.listIds(DownloadRecord.QUEUED));
            } finally {
                store.close();
            }

            // Finish a different active transfer while the canceled task remains stopped.
            server.releaseItem(requestPathFor(ids.get(2)));
            awaitStatus(ids.get(2), DownloadRecord.COMPLETED, 8_000L);
            assertTrue("retry starts after the already-started older FIFO tail",
                    server.awaitRequestCount(5, 8_000L));
            assertEquals("retry must follow the older queued row", requestPathFor(ids.get(0)),
                    server.requestPaths().get(4));
            awaitStatus(ids.get(0), DownloadRecord.DOWNLOADING, 5_000L);
            server.releaseItem(requestPathFor(ids.get(3)));
            server.releaseItem(requestPathFor(ids.get(0)));
            awaitStatus(ids.get(0), DownloadRecord.COMPLETED, 12_000L);
            awaitStatus(ids.get(3), DownloadRecord.COMPLETED, 12_000L);
        }
    }

    @Test public void staleWorkerTokenCannotChangeARetriedRow() throws Exception {
        manager = newManagerWithFixtureDns();
        String stableId = TEST_STABLE_PREFIX + UUID.randomUUID();
        String id = manager.enqueue(request("http://127.0.0.1:9/offline-unused", stableId), folder);
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            assertTrue(store.claimQueued(id, "old-run"));
            assertTrue(store.setPartialReservation(id, "old-run", ".orbit-part-" + id));
            assertTrue(store.setPartialUri(id, "old-run", "content://fixture/old-partial"));
            store.resetForRetry(id);
            assertTrue(store.claimQueued(id, "new-run"));

            assertFalse("an old worker cannot publish stale progress",
                    store.updateProgress(id, "old-run", 41L, 100L));
            assertFalse("an old worker cannot replace the new partial reservation",
                    store.setPartialReservation(id, "old-run", ".orbit-part-stale"));
            assertFalse("an old worker cannot set a terminal status",
                    store.setTaskStatus(id, "old-run", DownloadRecord.CANCELLED, "stale"));
            assertTrue(store.updateProgress(id, "new-run", 12L, 50L));
            assertTrue(store.setPartialReservation(id, "new-run", ".orbit-part-" + id));
            DownloadRecord current = store.get(id);
            assertNotNull(current);
            assertEquals(DownloadRecord.DOWNLOADING, current.status);
            assertEquals(12L, current.bytesDownloaded);
            assertEquals(50L, current.totalBytes);
            assertTrue(store.deleteTask(id, "new-run", DownloadRecord.DOWNLOADING));
        } finally {
            store.close();
        }
    }

    @Test public void v3MigrationKeepsDocumentReservationsAndRecoveryState() throws Exception {
        String databaseName = "orbit_download_queue_v3_fixture_" + UUID.randomUUID() + ".db";
        long createdAt = System.currentTimeMillis();
        String id = "migration-" + UUID.randomUUID();
        String cs3Id = "migration-cs3-" + UUID.randomUUID();
        byte[] nonce = new byte[]{7, 8, 9};
        byte[] ciphertext = new byte[]{3, 4, 5, 6};
        SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(
                target.getDatabasePath(databaseName), null);
        try {
            old.execSQL("CREATE TABLE downloads ("
                    + "id TEXT PRIMARY KEY NOT NULL, profile_id TEXT NOT NULL, stable_item_id TEXT NOT NULL,"
                    + "source_id TEXT NOT NULL, title TEXT NOT NULL, file_name TEXT NOT NULL, mime_type TEXT NOT NULL,"
                    + "source_nonce BLOB NOT NULL, source_ciphertext BLOB NOT NULL,"
                    + "headers_nonce BLOB NOT NULL DEFAULT X'', headers_ciphertext BLOB NOT NULL DEFAULT X'',"
                    + "status TEXT NOT NULL, bytes_downloaded INTEGER NOT NULL DEFAULT 0,"
                    + "total_bytes INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,"
                    + "file_uri TEXT NOT NULL DEFAULT '', partial_uri TEXT NOT NULL DEFAULT '',"
                    + "partial_name TEXT NOT NULL DEFAULT '', folder_uri TEXT NOT NULL,"
                    + "error_message TEXT NOT NULL DEFAULT '')");
            old.execSQL("CREATE INDEX downloads_status_order ON downloads(status, created_at DESC)");
            old.execSQL("CREATE INDEX downloads_profile_order ON downloads(profile_id, created_at DESC)");
            ContentValues row = new ContentValues();
            row.put("id", id);
            row.put("profile_id", TEST_PROFILE);
            row.put("stable_item_id", TEST_STABLE_PREFIX + id);
            row.put("source_id", "migration-fixture-source");
            row.put("title", "Synthetic migration item");
            row.put("file_name", TEST_FILE_PREFIX + id + ".mp4");
            row.put("mime_type", "video/mp4");
            row.put("source_nonce", nonce);
            row.put("source_ciphertext", ciphertext);
            row.put("status", DownloadRecord.DOWNLOADING);
            row.put("bytes_downloaded", 37L);
            row.put("total_bytes", 99L);
            row.put("created_at", createdAt);
            row.put("file_uri", "content://fixture/" + id + "/final");
            row.put("partial_uri", "content://fixture/" + id + "/partial");
            row.put("partial_name", ".orbit-part-" + id);
            row.put("folder_uri", "content://fixture/tree/" + id);
            row.put("error_message", "");
            old.insertOrThrow("downloads", null, row);
            ContentValues cs3Row = new ContentValues();
            cs3Row.put("id", cs3Id);
            cs3Row.put("profile_id", TEST_PROFILE);
            cs3Row.put("stable_item_id", TEST_STABLE_PREFIX + cs3Id);
            cs3Row.put("source_id", "provider-source-0");
            cs3Row.put("title", "Synthetic CS3 migration item");
            cs3Row.put("file_name", TEST_FILE_PREFIX + cs3Id + ".mp4");
            cs3Row.put("mime_type", "video/mp4");
            cs3Row.put("source_nonce", nonce);
            cs3Row.put("source_ciphertext", ciphertext);
            cs3Row.put("status", DownloadRecord.PAUSED);
            cs3Row.put("created_at", createdAt + 1L);
            cs3Row.put("folder_uri", "content://fixture/tree/" + cs3Id);
            old.insertOrThrow("downloads", null, cs3Row);
            old.setVersion(3);
        } finally {
            old.close();
        }

        DownloadQueueStore migrated = new DownloadQueueStore(target, databaseName);
        try {
            DownloadRecord beforeRecovery = migrated.get(id);
            assertNotNull(beforeRecovery);
            assertEquals(6, migrated.getReadableDatabase().getVersion());
            assertEquals(DownloadRecord.DOWNLOADING, beforeRecovery.status);
            assertEquals(createdAt, beforeRecovery.queueOrder);
            assertEquals("content://fixture/" + id + "/final", beforeRecovery.fileUri);
            assertEquals("content://fixture/" + id + "/partial", migrated.getRetainedDocuments(id).partialUri);
            assertEquals(".orbit-part-" + id, migrated.getRetainedDocuments(id).partialName);
            assertEquals("content://fixture/tree/" + id, beforeRecovery.folderUri);
            DownloadQueueStore.PendingSource source = migrated.getPendingSource(id);
            assertArrayEquals(nonce, source.nonce);
            assertArrayEquals(ciphertext, source.ciphertext);
            assertEquals("NONE", source.verifierState);
            assertEquals("LEGACY", migrated.getPendingSource(cs3Id).verifierState);
            assertEquals("", beforeRecovery.seasonLabel);
            assertEquals("", beforeRecovery.episodeLabel);
            assertEquals(-1L, beforeRecovery.knownSizeBytes);
            assertEquals("", beforeRecovery.artworkReference);

            List<DownloadQueueStore.InterruptedFile> interrupted = migrated.recoverInterrupted();
            assertEquals(1, interrupted.size());
            DownloadRecord recovered = migrated.get(id);
            assertEquals(DownloadRecord.DOWNLOADING, recovered.status);
            assertEquals(0L, recovered.bytesDownloaded);
            assertEquals(0L, recovered.totalBytes);
            assertEquals("content://fixture/" + id + "/partial", migrated.getRetainedDocuments(id).partialUri);
            assertEquals(".orbit-part-" + id, migrated.getRetainedDocuments(id).partialName);
        } finally {
            migrated.close();
            target.deleteDatabase(databaseName);
        }
    }

    private List<String> enqueueItems(int count, LocalTransferServer server) throws Exception {
        requestPathByRowId.clear();
        ArrayList<String> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String stableId = TEST_STABLE_PREFIX + UUID.randomUUID();
            String url = server.url(stableId);
            String rowId = manager.enqueue(request(url, stableId), folder);
            ids.add(rowId);
            requestPathByRowId.put(rowId, "/" + stableId);
        }
        return ids;
    }

    private String requestPathFor(String queueRowId) {
        String requestPath = requestPathByRowId.get(queueRowId);
        assertNotNull("each synthetic queue row must map to its local request path", requestPath);
        return requestPath;
    }

    private DownloadRequest request(String url, String stableId) {
        return new DownloadRequest(TEST_PROFILE, stableId, "download-test-source",
                "Synthetic queue fixture", TEST_FILE_PREFIX + stableId + ".mp4",
                "video/mp4", url, true);
    }

    private DownloadQueueManager newManagerWithFixtureDns() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        Dns pinned = PlaybackDnsPinning.newDns(false);
        Dns localOnlyFixtureResolver = host -> ORIGIN_HOST.equals(host)
                ? Collections.singletonList(loopback) : pinned.lookup(host);
        Constructor<DownloadQueueManager> constructor = DownloadQueueManager.class
                .getDeclaredConstructor(Context.class, Dns.class);
        constructor.setAccessible(true);
        return constructor.newInstance(target, localOnlyFixtureResolver);
    }

    private DownloadRecord record(String id) {
        for (DownloadRecord item : manager.getItems()) if (id.equals(item.id)) return item;
        return null;
    }

    private int activeCount() {
        int count = 0;
        for (DownloadRecord item : manager.getItems()) {
            if (TEST_PROFILE.equals(item.profileId)
                    && DownloadRecord.DOWNLOADING.equals(item.status)) count++;
        }
        return count;
    }

    private DownloadRecord awaitStatus(String id, String expected, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            DownloadRecord item = record(id);
            if (item != null && expected.equals(item.status)) return item;
            SystemClock.sleep(25L);
        }
        DownloadRecord item = record(id);
        assertEquals("queue row did not reach " + expected, expected,
                item == null ? "missing" : item.status);
        return item;
    }

    private void awaitPartialUri(String id, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            DownloadQueueStore store = new DownloadQueueStore(target);
            String value;
            try { value = store.getPartialUri(id); }
            finally { store.close(); }
            if (value != null && !value.isEmpty()) return;
            SystemClock.sleep(25L);
        }
        assertTrue("the active row must have created its synthetic partial file",
                partialDocument(id) != null);
    }

    private boolean awaitNoActiveRows(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean active = false;
            try {
                for (DownloadRecord item : manager.getItems()) {
                    if (TEST_PROFILE.equals(item.profileId)
                            && (DownloadRecord.DOWNLOADING.equals(item.status)
                            || DownloadRecord.REMOVING.equals(item.status))) active = true;
                }
            } catch (RuntimeException ignored) {
                return true;
            }
            if (!active) return true;
            SystemClock.sleep(25L);
        }
        return false;
    }

    private void cleanOwnedRows() throws Exception {
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            for (DownloadRecord item : store.listAll()) {
                if (!TEST_PROFILE.equals(item.profileId)
                        || !item.stableItemId.startsWith(TEST_STABLE_PREFIX)
                        || !folder.toString().equals(item.folderUri)) continue;
                ArrayList<String> allowedNames = new ArrayList<>();
                String partialBase = ".orbit-part-" + item.id;
                allowedNames.add(partialBase);
                allowedNames.add(partialBase + ".mp4");
                if (item.fileName.startsWith(TEST_FILE_PREFIX)) allowedNames.add(item.fileName);
                DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(item.id);
                if (retained != null) {
                    if (!retained.partialName.isEmpty() && !partialBase.equals(retained.partialName)) {
                        throw new IOException("Refusing cleanup for an unexpected partial reservation");
                    }
                    deleteOwnedUri(retained.partialUri, allowedNames);
                    deleteOwnedUri(retained.fileUri, allowedNames);
                }
                for (String name : allowedNames) deleteNamedChild(name, allowedNames);
                store.delete(item.id);
            }
        } finally {
            store.close();
        }
    }

    private void deleteOwnedUri(String rawUri, List<String> allowedNames) throws IOException {
        if (rawUri == null || rawUri.isEmpty()) return;
        Uri document = Uri.parse(rawUri);
        if (!folder.getAuthority().equals(document.getAuthority())
                || !DocumentsContract.isTreeUri(document)
                || !TREE_DOCUMENT_ID.equals(DocumentsContract.getTreeDocumentId(document))) {
            throw new IOException("Refusing cleanup outside the exact synthetic SAF tree");
        }
        String id = DocumentsContract.getDocumentId(document);
        String name = queryName(id);
        if (name == null) return;
        if (!allowedNames.contains(name)) throw new IOException("Refusing cleanup of an unrelated SAF child");
        deleteAndConfirm(id);
    }

    private void deleteNamedChild(String name, List<String> allowedNames) throws IOException {
        if (!allowedNames.contains(name)) throw new IOException("Invalid synthetic cleanup name");
        for (FixtureDocument document : listFixtureDocuments()) {
            if (name.equals(document.name)) deleteAndConfirm(document.id);
        }
    }

    private void deleteAndConfirm(String documentId) throws IOException {
        Uri document = DocumentsContract.buildDocumentUriUsingTree(folder, documentId);
        try { DocumentsContract.deleteDocument(target.getContentResolver(), document); }
        catch (java.io.FileNotFoundException ignored) { }
        if (documentIdPresent(documentId)) throw new IOException("Synthetic SAF child remains after delete");
    }

    private String queryName(String documentId) {
        Uri document = DocumentsContract.buildDocumentUriUsingTree(folder, documentId);
        try (Cursor cursor = target.getContentResolver().query(document,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return cursor.getString(0);
        }
    }

    private Uri documentForName(String name) {
        for (FixtureDocument item : listFixtureDocuments()) {
            if (name.equals(item.name)) return DocumentsContract.buildDocumentUriUsingTree(folder, item.id);
        }
        return null;
    }

    private Uri partialDocument(String id) {
        String base = ".orbit-part-" + id;
        for (FixtureDocument item : listFixtureDocuments()) {
            if (base.equals(item.name) || (base + ".mp4").equals(item.name)) {
                return DocumentsContract.buildDocumentUriUsingTree(folder, item.id);
            }
        }
        return null;
    }

    private boolean documentIdPresent(String documentId) {
        for (FixtureDocument item : listFixtureDocuments()) if (documentId.equals(item.id)) return true;
        return false;
    }

    private List<FixtureDocument> listFixtureDocuments() {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, TREE_DOCUMENT_ID);
        ArrayList<FixtureDocument> items = new ArrayList<>();
        try (Cursor cursor = target.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            assertNotNull("the dedicated fixture tree must remain readable", cursor);
            while (cursor.moveToNext()) items.add(new FixtureDocument(cursor.getString(0), cursor.getString(1)));
        }
        return items;
    }

    private void assertFixtureFolderEmpty() {
        ArrayList<String> names = new ArrayList<>();
        for (FixtureDocument item : listFixtureDocuments()) {
            if (!".nomedia".equals(item.name)) names.add(item.name);
        }
        assertTrue("the dedicated fixture tree must be empty before each test: " + names, names.isEmpty());
    }

    private byte[] readDocument(Uri uri) throws IOException {
        assertNotNull(uri);
        try (InputStream input = target.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertNotNull(input);
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static byte[] payloadBytes() {
        byte[] result = new byte[4096];
        for (int i = 0; i < result.length; i++) result[i] = (byte) ((i * 37 + 11) & 0xff);
        return result;
    }

    private static final class FixtureDocument {
        final String id;
        final String name;
        FixtureDocument(String id, String name) { this.id = id; this.name = name; }
    }

    private static final class LocalTransferServer implements Closeable {
        private final ServerSocket server;
        private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private final Object requestMonitor = new Object();
        private final ConcurrentMap<String, CountDownLatch> releases = new ConcurrentHashMap<>();
        private volatile boolean releaseAll;
        private final Thread acceptor;

        LocalTransferServer() throws IOException {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(1_000);
            acceptor = new Thread(this::acceptLoop, "orbit-queue-local-transfer-fixture");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String url(String itemId) {
            return "http://" + ORIGIN_HOST + ":" + server.getLocalPort() + "/" + itemId;
        }

        boolean awaitRequestCount(int count, long timeoutMs) throws InterruptedException {
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            synchronized (requestMonitor) {
                while (requests.size() < count) {
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0L) return false;
                    TimeUnit.MILLISECONDS.timedWait(requestMonitor, remaining);
                }
                return true;
            }
        }

        List<String> requestPaths() {
            synchronized (requestMonitor) { return new ArrayList<>(requests); }
        }

        void releaseItem(String path) {
            releases.computeIfAbsent(path, ignored -> new CountDownLatch(1)).countDown();
        }

        void releaseResponses() {
            releaseAll = true;
            for (CountDownLatch latch : releases.values()) latch.countDown();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread worker = new Thread(() -> serve(socket), "orbit-queue-local-response");
                    worker.setDaemon(true);
                    worker.start();
                } catch (java.net.SocketTimeoutException ignored) {
                    // Keep the local fixture cancellable during test teardown.
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private void serve(Socket socket) {
            try (Socket connection = socket;
                 BufferedReader input = new BufferedReader(new InputStreamReader(
                         connection.getInputStream(), StandardCharsets.ISO_8859_1))) {
                String first = input.readLine();
                if (first == null) return;
                while (true) {
                    String line = input.readLine();
                    if (line == null || line.isEmpty()) break;
                }
                String[] parts = first.split(" ");
                String path = parts.length > 1 ? parts[1] : "/invalid";
                CountDownLatch release = releases.computeIfAbsent(path,
                        ignored -> new CountDownLatch(1));
                if (releaseAll) release.countDown();
                synchronized (requestMonitor) {
                    requests.add(path);
                    requestMonitor.notifyAll();
                }
                OutputStream output = connection.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: "
                        + PAYLOAD.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                output.write(PAYLOAD, 0, 512);
                output.flush();
                release.await(30L, TimeUnit.SECONDS);
                output.write(PAYLOAD, 512, PAYLOAD.length - 512);
                output.flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The manager cancels a local response when a row is paused or canceled.
            }
        }

        @Override public void close() throws IOException {
            releaseResponses();
            server.close();
            try { acceptor.join(1_000L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
