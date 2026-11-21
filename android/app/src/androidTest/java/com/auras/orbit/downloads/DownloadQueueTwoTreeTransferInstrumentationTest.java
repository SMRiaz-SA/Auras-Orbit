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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Verifies simultaneous offline downloads stay in their independently selected SAF trees. */
@RunWith(AndroidJUnit4.class)
public final class DownloadQueueTwoTreeTransferInstrumentationTest {
    private static final String TEST_PROFILE = "download-two-tree-test-profile";
    private static final String TEST_STABLE_PREFIX = "queue-two-tree-";
    private static final String TEST_FILE_PREFIX = "Orbit-Queue-Two-Tree-";
    private static final String ORIGIN_HOST = "download-origin.example";
    private static final String TREE_A_DOCUMENT_ID = "primary:Download/OrbitQueuePickerA";
    private static final String TREE_B_DOCUMENT_ID = "primary:Download/OrbitQueuePickerB";
    private static final long TRANSFER_TIMEOUT_MS = 20_000L;
    private static final long CLEANUP_TIMEOUT_MS = 10_000L;

    private Context target;
    private Uri treeA;
    private Uri treeB;
    private DownloadQueueManager manager;
    private LocalTransferServer server;
    private final List<String> ownedIds = new ArrayList<>();

    @Before public void requireExactPersistedPickerTrees() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle arguments = InstrumentationRegistry.getArguments();
        String rawTreeA = arguments.getString("treeAUri");
        String rawTreeB = arguments.getString("treeBUri");
        assertNotNull("the root runner must pass treeAUri from the Orbit picker", rawTreeA);
        assertNotNull("the root runner must pass treeBUri from the Orbit picker", rawTreeB);
        treeA = Uri.parse(rawTreeA);
        treeB = Uri.parse(rawTreeB);
        assertExactTree(treeA, TREE_A_DOCUMENT_ID, "treeAUri");
        assertExactTree(treeB, TREE_B_DOCUMENT_ID, "treeBUri");
        assertFalse("the two user-selected trees must be distinct", treeA.equals(treeB));
        assertPersistedReadWriteGrant(treeA);
        assertPersistedReadWriteGrant(treeB);
    }

    @After public void closeFixturesAndRemoveOnlyOwnedRows() throws Exception {
        try {
            try {
                if (server != null) server.close();
            } finally {
                server = null;
            }
        } finally {
            try {
                if (manager != null) {
                    for (String id : new ArrayList<>(ownedIds)) {
                        if (record(id) != null) manager.remove(id);
                    }
                    assertTrue("owned rows must finish cleanup before manager shutdown",
                            awaitOwnedRowsGone(CLEANUP_TIMEOUT_MS));
                }
            } finally {
                if (manager != null) {
                    manager.close();
                    manager = null;
                }
            }
        }
    }

    @Test public void simultaneousRowsRetainTheirExactSelectedTreesAndPayloads()
            throws Exception {
        manager = newManagerWithFixtureDns();
        server = new LocalTransferServer();

        String runToken = UUID.randomUUID().toString();
        String stableA = TEST_STABLE_PREFIX + runToken + "-a";
        String stableB = TEST_STABLE_PREFIX + runToken + "-b";
        String pathA = "/" + stableA;
        String pathB = "/" + stableB;
        byte[] bodyA = payloadBytes(257, 17);
        byte[] bodyB = payloadBytes(389, 91);
        server.addRoute(pathA, bodyA);
        server.addRoute(pathB, bodyB);

        String idA = manager.enqueue(request(server.url(pathA), stableA), treeA);
        ownedIds.add(idA);
        String idB = manager.enqueue(request(server.url(pathB), stableB), treeB);
        ownedIds.add(idB);
        assertFalse("each request must receive an independent queue row", idA.equals(idB));

        manager.startQueue();
        assertTrue("both queue workers must reach the local server before either body is released",
                server.awaitRequestCount(2, 10_000L));
        assertEquals("both expected local routes must be served concurrently",
                new HashSet<>(Arrays.asList(pathA, pathB)), new HashSet<>(server.requestPaths()));
        server.releaseResponses();

        awaitStatus(idA, DownloadRecord.COMPLETED, TRANSFER_TIMEOUT_MS);
        awaitStatus(idB, DownloadRecord.COMPLETED, TRANSFER_TIMEOUT_MS);
        DownloadRecord recordA = record(idA);
        DownloadRecord recordB = record(idB);
        assertNotNull(recordA);
        assertNotNull(recordB);

        assertEquals("row A must retain the exact selected tree URI",
                treeA.toString(), recordA.folderUri);
        assertEquals("row B must retain the exact selected tree URI",
                treeB.toString(), recordB.folderUri);
        assertFileInAssignedTree(recordA, treeA);
        assertFileInAssignedTree(recordB, treeB);
        assertNull("tree B must not contain row A's created filename",
                findNamedChild(treeB, recordA.fileName));
        assertNull("tree A must not contain row B's created filename",
                findNamedChild(treeA, recordB.fileName));
        assertArrayEquals("row A must contain only its route's deterministic bytes",
                bodyA, readDocument(Uri.parse(recordA.fileUri)));
        assertArrayEquals("row B must contain only its route's deterministic bytes",
                bodyB, readDocument(Uri.parse(recordB.fileUri)));

        String fileNameA = recordA.fileName;
        String fileNameB = recordB.fileName;
        assertTrue("removing row A must remove its owned SAF document", manager.remove(idA));
        awaitRowGone(idA, CLEANUP_TIMEOUT_MS);
        assertNull(findNamedChild(treeA, fileNameA));
        assertNull(findNamedChild(treeB, fileNameA));

        assertTrue("removing row B must remove its owned SAF document", manager.remove(idB));
        awaitRowGone(idB, CLEANUP_TIMEOUT_MS);
        assertNull(findNamedChild(treeB, fileNameB));
        assertNull(findNamedChild(treeA, fileNameB));
    }

    private static void assertExactTree(Uri tree, String expectedDocumentId, String label) {
        assertTrue(label + " must be a Storage Access Framework tree URI",
                DocumentsContract.isTreeUri(tree));
        assertEquals(label + " must use the external-storage documents provider",
                "com.android.externalstorage.documents", tree.getAuthority());
        assertEquals(label + " must identify only its exact picker fixture folder",
                expectedDocumentId, DocumentsContract.getTreeDocumentId(tree));
    }

    private void assertPersistedReadWriteGrant(Uri tree) {
        boolean read = false;
        boolean write = false;
        for (UriPermission permission : target.getContentResolver().getPersistedUriPermissions()) {
            if (tree.equals(permission.getUri())) {
                read |= permission.isReadPermission();
                write |= permission.isWritePermission();
            }
        }
        assertTrue("the exact selected tree must have a persisted read grant", read);
        assertTrue("the exact selected tree must have a persisted write grant", write);
    }

    private DownloadRequest request(String url, String stableId) {
        return new DownloadRequest(TEST_PROFILE, stableId, "download-two-tree-test-source",
                "Synthetic two-tree transfer", TEST_FILE_PREFIX + stableId + ".mp4",
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
        assertNull("removed row must disappear from the queue", record(id));
    }

    private boolean awaitOwnedRowsGone(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean found = false;
            for (String id : ownedIds) found |= record(id) != null;
            if (!found) return true;
            SystemClock.sleep(25L);
        }
        for (String id : ownedIds) assertNull("owned row remains after cleanup", record(id));
        return true;
    }

    private void assertFileInAssignedTree(DownloadRecord item, Uri expectedTree) {
        assertNotNull("completed row must retain a created SAF file URI", item.fileUri);
        Uri file = Uri.parse(item.fileUri);
        assertEquals("file URI authority must match the assigned tree",
                expectedTree.getAuthority(), file.getAuthority());
        assertTrue("file URI must retain the selected SAF tree identity",
                DocumentsContract.isTreeUri(file));
        assertEquals("file URI must resolve under the exact assigned tree document ID",
                DocumentsContract.getTreeDocumentId(expectedTree),
                DocumentsContract.getTreeDocumentId(file));
        String fileDocumentId = DocumentsContract.getDocumentId(file);
        assertFalse("created file cannot be the selected folder itself",
                DocumentsContract.getTreeDocumentId(expectedTree).equals(fileDocumentId));
        FixtureDocument child = findNamedChild(expectedTree, item.fileName);
        assertNotNull("assigned tree must list the row's created filename", child);
        assertEquals("file URI must resolve to the row's direct child document",
                child.id, fileDocumentId);
    }

    private FixtureDocument findNamedChild(Uri tree, String name) {
        for (FixtureDocument document : listChildren(tree)) {
            if (name.equals(document.name)) return document;
        }
        return null;
    }

    private List<FixtureDocument> listChildren(Uri tree) {
        String treeDocumentId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocumentId);
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
            assertNotNull("created document must open through ContentResolver", input);
            byte[] buffer = new byte[512];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
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

    private static final class LocalTransferServer implements Closeable {
        private final ServerSocket server;
        private final Map<String, byte[]> routes = new ConcurrentHashMap<>();
        private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
        private final List<Thread> workers = Collections.synchronizedList(new ArrayList<>());
        private final Object requestMonitor = new Object();
        private final CountDownLatch bothRequestsReceived = new CountDownLatch(2);
        private final CountDownLatch releaseResponses = new CountDownLatch(1);
        private final Thread acceptor;

        LocalTransferServer() throws IOException {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(500);
            acceptor = new Thread(this::acceptLoop, "orbit-two-tree-local-http-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        void addRoute(String path, byte[] body) {
            routes.put(path, Arrays.copyOf(body, body.length));
        }

        String url(String path) {
            return "http://" + ORIGIN_HOST + ":" + server.getLocalPort() + path;
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
            synchronized (requestMonitor) {
                return new ArrayList<>(requests);
            }
        }

        void releaseResponses() {
            releaseResponses.countDown();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    sockets.add(socket);
                    Thread worker = new Thread(() -> serve(socket), "orbit-two-tree-local-http-response");
                    worker.setDaemon(true);
                    workers.add(worker);
                    worker.start();
                } catch (java.net.SocketTimeoutException ignored) {
                    // Polling keeps fixture shutdown bounded.
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
                byte[] body = routes.get(path);
                synchronized (requestMonitor) {
                    requests.add(path);
                    requestMonitor.notifyAll();
                }
                bothRequestsReceived.countDown();
                if (body == null) return;
                OutputStream output = connection.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                output.flush();
                if (!bothRequestsReceived.await(10L, TimeUnit.SECONDS)
                        || !releaseResponses.await(10L, TimeUnit.SECONDS)) return;
                output.write(body);
                output.flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Queue cancellation or bounded teardown closes local fixture sockets.
            } finally {
                sockets.remove(socket);
            }
        }

        @Override public void close() throws IOException {
            bothRequestsReceived.countDown();
            bothRequestsReceived.countDown();
            releaseResponses();
            server.close();
            try {
                acceptor.join(1_000L);
                List<Socket> openSockets;
                synchronized (sockets) {
                    openSockets = new ArrayList<>(sockets);
                }
                for (Socket socket : openSockets) {
                    try { socket.close(); } catch (IOException ignored) { }
                }
                List<Thread> activeWorkers;
                synchronized (workers) {
                    activeWorkers = new ArrayList<>(workers);
                }
                for (Thread worker : activeWorkers) worker.join(1_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
