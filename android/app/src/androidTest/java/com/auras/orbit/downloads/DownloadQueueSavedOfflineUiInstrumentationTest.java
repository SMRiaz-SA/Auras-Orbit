package com.auras.orbit.downloads;

import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.profile.LocalProfileStore;
import com.auras.orbit.profile.LocalProfileStore.Profile;

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
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Creates a real completed SAF download for the active profile so the normal app process can
 * render its populated Downloads / Saved offline screen after instrumentation exits.
 */
@RunWith(AndroidJUnit4.class)
public final class DownloadQueueSavedOfflineUiInstrumentationTest {
    private static final String TREE_DOCUMENT_ID = "primary:Download/OrbitQueuePickerA";
    private static final String TEST_STABLE_PREFIX = "queue-saved-offline-ui-";
    private static final String TEST_FILE_PREFIX = "Orbit-Saved-Offline-UI-";
    private static final String TEST_SOURCE_ID = "orbit-local-saved-offline-ui-fixture";
    private static final String TEST_TITLE = "Orbit Saved Offline UI Fixture";
    private static final String ORIGIN_HOST = "saved-offline-fixture.example";
    private static final long TRANSFER_TIMEOUT_MS = 20_000L;
    private static final long CLEANUP_TIMEOUT_MS = 10_000L;

    private Context target;
    private Uri tree;
    private Profile activeProfile;
    private DownloadQueueManager manager;
    private LocalHttpFixture server;
    private String ownedId;
    private String expectedFileName;
    private Set<String> partialNamesBefore = Collections.emptySet();
    private boolean retainCompletedFixture;
    private boolean completedFixtureVerified;

    @Before public void requirePersistedPickerTreeAndActiveProfile() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle arguments = InstrumentationRegistry.getArguments();
        String rawTree = arguments.getString("savedOfflineTreeUri");
        assertNotNull("the runner must pass savedOfflineTreeUri selected through Orbit's SAF picker",
                rawTree);
        tree = Uri.parse(rawTree);
        assertExactTree(tree, TREE_DOCUMENT_ID, "savedOfflineTreeUri");
        assertPersistedReadWriteGrant(tree);

        activeProfile = new LocalProfileStore(target).getActiveProfile();
        assertNotNull("the existing Orbit LocalProfileStore must have an active profile", activeProfile);
        assertFalse("the active Orbit profile ID must be non-empty", activeProfile.id.trim().isEmpty());

        retainCompletedFixture = Boolean.parseBoolean(
                arguments.getString("retainCompletedFixture", "false"));
    }

    @After public void closeFixtureAndCleanOnlyOwnedDownload() throws Exception {
        try {
            if (server != null) server.close();
        } finally {
            server = null;
            try {
                if (manager != null && !(retainCompletedFixture && completedFixtureVerified)) {
                    cleanupOwnedDownload();
                }
            } finally {
                if (manager != null) {
                    manager.close();
                    manager = null;
                }
            }
        }
    }

    @Test public void completedTransferPersistsExactOfflineRecordAndSafDocument()
            throws Exception {
        assertNoPreexistingWorkBeforeManagerRecovery();
        manager = newManagerWithLocalOnlyFixtureDns();
        assertNoPreexistingTransfers();
        partialNamesBefore = partialNames(tree);
        server = new LocalHttpFixture();

        String runToken = UUID.randomUUID().toString();
        String stableId = TEST_STABLE_PREFIX + runToken;
        String path = "/" + stableId;
        byte[] body = payloadBytes(24_577, 53);
        server.setResponse(path, body);

        String requestedName = TEST_FILE_PREFIX + runToken.substring(0, 8) + ".mp4";
        DownloadRequest request = new DownloadRequest(activeProfile.id, stableId,
                TEST_SOURCE_ID, TEST_TITLE, requestedName, "video/mp4", server.url(path), true);
        ownedId = manager.enqueue(request, tree);
        expectedFileName = expectedFinalName(requestedName, ownedId);

        DownloadRecord queued = record(ownedId);
        assertNotNull("the queue API must persist its own synthetic row", queued);
        assertEquals("the synthetic record must use the existing active profile",
                activeProfile.id, queued.profileId);
        assertEquals("the synthetic record must use only its passed persisted tree",
                tree.toString(), queued.folderUri);
        assertEquals("the persisted final name must be deterministic from the requested name and row ID",
                expectedFileName, queued.fileName);

        manager.startQueue();
        assertTrue("the local-only HTTP fixture must receive the one expected request",
                server.awaitRequest(10_000L));
        assertEquals("the fixture must receive only its deterministic local route",
                path, server.requestPath());

        DownloadRecord completed = awaitStatus(ownedId, DownloadRecord.COMPLETED,
                TRANSFER_TIMEOUT_MS);
        assertEquals("the local fixture must receive exactly one transfer request",
                1, server.requestCount());
        assertEquals("completion must preserve the active profile ID",
                activeProfile.id, completed.profileId);
        assertEquals("completion must preserve the assigned persisted tree URI",
                tree.toString(), completed.folderUri);
        assertEquals("completion must retain the exact final document name",
                expectedFileName, completed.fileName);
        assertEquals("completion must account for every fixture byte", body.length,
                completed.bytesDownloaded);
        assertEquals("completion must report the exact final byte count", body.length,
                completed.totalBytes);

        FixtureDocument document = findNamedChild(tree, expectedFileName);
        assertNotNull("the assigned SAF tree must contain the exact completed document name", document);
        Uri expectedUri = DocumentsContract.buildDocumentUriUsingTree(tree, document.id);
        Uri finalUri = Uri.parse(completed.fileUri);
        assertEquals("the record must retain the exact URI of the final SAF document",
                expectedUri.toString(), completed.fileUri);
        assertTrue("the final document URI must preserve its selected tree identity",
                DocumentsContract.isTreeUri(finalUri));
        assertEquals("the final URI must be inside only the assigned tree",
                TREE_DOCUMENT_ID, DocumentsContract.getTreeDocumentId(finalUri));
        assertEquals("the final document ID must be the direct child found by exact name",
                document.id, DocumentsContract.getDocumentId(finalUri));
        assertArrayEquals("the final SAF document must contain the deterministic fixture payload",
                body, readDocument(finalUri));

        assertEquals("the transfer must not leave new partial-name documents in the assigned tree",
                partialNamesBefore, partialNames(tree));
        assertNull("this row's reserved partial document must not remain in the assigned tree",
                findNamedChild(tree, ".orbit-part-" + ownedId));

        completedFixtureVerified = true;
        System.out.println("SAVED_OFFLINE_FIXTURE profileId=" + activeProfile.id
                + " recordId=" + ownedId
                + " fileName=" + completed.fileName
                + " fileUri=" + completed.fileUri
                + " bytes=" + completed.bytesDownloaded
                + " retained=" + retainCompletedFixture);
    }

    private void assertNoPreexistingWorkBeforeManagerRecovery() {
        DownloadQueueStore preflightStore = new DownloadQueueStore(target);
        try {
            for (DownloadRecord item : preflightStore.listAll()) {
                assertFalse("fixture setup refuses to construct a manager while pre-existing queue work "
                                + "could be recovered or started",
                        DownloadRecord.QUEUED.equals(item.status)
                                || DownloadRecord.DOWNLOADING.equals(item.status)
                                || DownloadRecord.REMOVING.equals(item.status));
            }
        } finally {
            preflightStore.close();
        }
    }

    private void assertNoPreexistingTransfers() {
        for (DownloadRecord item : manager.getItems()) {
            assertFalse("fixture setup requires a clean queue so startQueue cannot start unrelated work",
                    DownloadRecord.QUEUED.equals(item.status)
                            || DownloadRecord.DOWNLOADING.equals(item.status)
                            || DownloadRecord.REMOVING.equals(item.status));
        }
    }

    private void cleanupOwnedDownload() {
        if (ownedId == null || manager == null) return;
        DownloadRecord item = record(ownedId);
        if (item != null) {
            assertTrue("cleanup may remove only this test's unique active-profile row in its selected tree",
                    item.profileId.equals(activeProfile.id)
                            && item.stableItemId.startsWith(TEST_STABLE_PREFIX)
                            && item.folderUri.equals(tree.toString())
                            && item.fileName.startsWith(TEST_FILE_PREFIX));
            assertTrue("the test manager must remove only its owned row and document",
                    manager.remove(ownedId));
            awaitRowGone(ownedId, CLEANUP_TIMEOUT_MS);
        }
        if (expectedFileName != null) {
            assertNull("default cleanup must remove the test's unique completed SAF document",
                    findNamedChild(tree, expectedFileName));
        }
        assertEquals("default cleanup must not leave new partial-name documents",
                partialNamesBefore, partialNames(tree));
    }

    private static void assertExactTree(Uri selectedTree, String expectedDocumentId, String label) {
        assertTrue(label + " must be a Storage Access Framework tree URI",
                DocumentsContract.isTreeUri(selectedTree));
        assertEquals(label + " must use the external-storage documents provider",
                "com.android.externalstorage.documents", selectedTree.getAuthority());
        assertEquals(label + " must be the exact previously selected Orbit queue fixture folder",
                expectedDocumentId, DocumentsContract.getTreeDocumentId(selectedTree));
    }

    private void assertPersistedReadWriteGrant(Uri selectedTree) {
        boolean read = false;
        boolean write = false;
        for (UriPermission permission : target.getContentResolver().getPersistedUriPermissions()) {
            if (selectedTree.equals(permission.getUri())) {
                read |= permission.isReadPermission();
                write |= permission.isWritePermission();
            }
        }
        assertTrue("the exact selected SAF tree must have a persisted read grant", read);
        assertTrue("the exact selected SAF tree must have a persisted write grant", write);
    }

    private DownloadQueueManager newManagerWithLocalOnlyFixtureDns() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        Dns localOnlyFixtureResolver = host -> {
            if (ORIGIN_HOST.equals(host)) return Collections.singletonList(loopback);
            throw new UnknownHostException("Only the local Saved offline fixture host is resolvable.");
        };
        Constructor<DownloadQueueManager> constructor = DownloadQueueManager.class
                .getDeclaredConstructor(Context.class, Dns.class);
        constructor.setAccessible(true);
        return constructor.newInstance(target, localOnlyFixtureResolver);
    }

    private DownloadRecord record(String id) {
        for (DownloadRecord item : manager.getItems()) if (id.equals(item.id)) return item;
        return null;
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

    private void awaitRowGone(String id, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (record(id) == null) return;
            SystemClock.sleep(25L);
        }
        assertNull("the owned row must disappear after cleanup", record(id));
    }

    private FixtureDocument findNamedChild(Uri selectedTree, String name) {
        for (FixtureDocument document : listChildren(selectedTree)) {
            if (name.equals(document.name)) return document;
        }
        return null;
    }

    private Set<String> partialNames(Uri selectedTree) {
        HashSet<String> names = new HashSet<>();
        for (FixtureDocument document : listChildren(selectedTree)) {
            if (document.name != null && document.name.startsWith(".orbit-part-")) {
                names.add(document.name);
            }
        }
        return names;
    }

    private List<FixtureDocument> listChildren(Uri selectedTree) {
        String treeDocumentId = DocumentsContract.getTreeDocumentId(selectedTree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(selectedTree, treeDocumentId);
        ArrayList<FixtureDocument> documents = new ArrayList<>();
        try (Cursor cursor = target.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            assertNotNull("the exact selected tree must remain readable", cursor);
            while (cursor.moveToNext()) {
                documents.add(new FixtureDocument(cursor.getString(0), cursor.getString(1)));
            }
        }
        return documents;
    }

    private byte[] readDocument(Uri uri) throws IOException {
        try (InputStream input = target.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertNotNull("the created SAF document must open through ContentResolver", input);
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String expectedFinalName(String requestedName, String id) {
        int dot = requestedName.lastIndexOf('.');
        if (dot > 0 && dot < requestedName.length() - 1) {
            return requestedName.substring(0, dot) + "-" + id.substring(0, 8)
                    + requestedName.substring(dot);
        }
        return requestedName + "-" + id.substring(0, 8);
    }

    private static byte[] payloadBytes(int size, int seed) {
        byte[] result = new byte[size];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) ((index * 31 + seed) & 0xff);
        }
        return result;
    }

    private static final class FixtureDocument {
        final String id;
        final String name;

        FixtureDocument(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class LocalHttpFixture implements Closeable {
        private final ServerSocket server;
        private final CountDownLatch requestReceived = new CountDownLatch(1);
        private final Thread acceptor;
        private volatile String expectedPath;
        private volatile String receivedPath;
        private volatile int requestCount;
        private volatile byte[] responseBody;
        private volatile Socket activeSocket;

        LocalHttpFixture() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            server.setSoTimeout(250);
            acceptor = new Thread(this::acceptLoop, "orbit-saved-offline-local-http-fixture");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        void setResponse(String path, byte[] body) {
            expectedPath = path;
            responseBody = body.clone();
        }

        String url(String path) {
            return "http://" + ORIGIN_HOST + ":" + server.getLocalPort() + path;
        }

        boolean awaitRequest(long timeoutMs) throws InterruptedException {
            return requestReceived.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        String requestPath() { return receivedPath; }
        int requestCount() { return requestCount; }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    activeSocket = socket;
                    serve(socket);
                } catch (java.net.SocketTimeoutException ignored) {
                    // Bounded polling allows close() to stop the fixture cleanly.
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
                receivedPath = parts.length > 1 ? parts[1] : "";
                requestCount++;
                requestReceived.countDown();
                byte[] body = responseBody;
                if (body == null || !expectedPath.equals(receivedPath)) return;
                OutputStream output = connection.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                output.write(body);
                output.flush();
            } catch (IOException ignored) {
                // The queue may close a failed or canceled fixture connection during cleanup.
            } finally {
                activeSocket = null;
            }
        }

        @Override public void close() throws IOException {
            server.close();
            Socket socket = activeSocket;
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) { }
            }
            try {
                acceptor.join(1_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
