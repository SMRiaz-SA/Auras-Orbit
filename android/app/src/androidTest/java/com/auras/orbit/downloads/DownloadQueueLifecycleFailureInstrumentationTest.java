package com.auras.orbit.downloads;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.os.Build;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.player.PlaybackDnsPinning;

import okhttp3.Dns;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
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
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Failure-path queue lifecycle coverage using only loopback HTTP and the AndroidTest SAF fixture. */
@RunWith(AndroidJUnit4.class)
public final class DownloadQueueLifecycleFailureInstrumentationTest {
    private static final String TEST_PROFILE = "queue-lifecycle-failure-test-profile";
    private static final String TEST_FILE_PREFIX = "Orbit-Queue-Lifecycle-Failure-";
    private static final String ORIGIN_HOST = "download-origin.example";
    private static final int PERSISTED_MODE_FLAGS = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final byte[] COMPLETE_BODY = payloadBytes(192 * 1024, 37);
    private static final byte[] TRUNCATED_BODY = payloadBytes(96 * 1024, 81);
    private static final byte[] SAME_TREE_SENTINEL =
            "same-tree sentinel must survive exact-name recovery".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_TREE_SENTINEL =
            "other-tree sentinel must survive exact-name recovery".getBytes(StandardCharsets.UTF_8);

    private Context target;
    private Uri treeA;
    private Uri treeB;
    private DownloadQueueManager manager;
    private LocalDownloadServer server;
    private final List<String> ownedIds = new ArrayList<>();
    private final List<FixtureDocumentRef> manuallyCreatedDocuments = new ArrayList<>();

    @BeforeClass public static void persistHostSelectedFixtureTreesForOrbit() {
        Assume.assumeTrue("package-scoped URI grant cleanup requires API 26 or later",
                Build.VERSION.SDK_INT >= 26);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri treeA = selectedFixtureTree(InstrumentationRegistry.getArguments().getString("tree_a"),
                QueueLifecycleDocumentsProvider.TREE_A);
        Uri treeB = selectedFixtureTree(InstrumentationRegistry.getArguments().getString("tree_b"),
                QueueLifecycleDocumentsProvider.TREE_B);
        assertFalse("the two host-selected fixture roots must be distinct", treeA.equals(treeB));
        // am instrument force-stops Orbit before starting instrumentation, which clears any
        // transient grant delegated before this invocation. Re-delegate from the helper now,
        // while the target process is alive, then persist the exact grants in Orbit.
        delegateSelectedFixtureTreesFromHelper(target);
        persistTargetTreeGrant(target, treeA);
        persistTargetTreeGrant(target, treeB);
        assertEquals(treeA, persistedFixtureTree(target, QueueLifecycleDocumentsProvider.TREE_A));
        assertEquals(treeB, persistedFixtureTree(target, QueueLifecycleDocumentsProvider.TREE_B));
    }

    @AfterClass public static void releaseFixtureTreesAfterTheWholeSuite() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        List<Throwable> cleanupErrors = new ArrayList<>();
        Uri treeA = DocumentsContract.buildTreeDocumentUri(QueueLifecycleDocumentsProvider.AUTHORITY,
                QueueLifecycleDocumentsProvider.TREE_A);
        Uri treeB = DocumentsContract.buildTreeDocumentUri(QueueLifecycleDocumentsProvider.AUTHORITY,
                QueueLifecycleDocumentsProvider.TREE_B);
        releaseOrbitFixtureGrants(target, Arrays.asList(treeA, treeB), cleanupErrors);
        runHelperCleanupAndAwait(target, cleanupErrors);
        verifyTargetGrantRevoked(target, treeA, cleanupErrors);
        verifyTargetGrantRevoked(target, treeB, cleanupErrors);
        if (!cleanupErrors.isEmpty()) {
            AssertionError failure = new AssertionError(
                    "Queue lifecycle grant cleanup failed after all exact A/B attempts");
            for (Throwable cleanupError : cleanupErrors) failure.addSuppressed(cleanupError);
            throw failure;
        }
    }

    @Before public void requireBothPersistedTargetTreeGrants() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        treeA = selectedFixtureTree(InstrumentationRegistry.getArguments().getString("tree_a"),
                QueueLifecycleDocumentsProvider.TREE_A);
        treeB = selectedFixtureTree(InstrumentationRegistry.getArguments().getString("tree_b"),
                QueueLifecycleDocumentsProvider.TREE_B);
        assertEquals(treeA, persistedFixtureTree(target, QueueLifecycleDocumentsProvider.TREE_A));
        assertEquals(treeB, persistedFixtureTree(target, QueueLifecycleDocumentsProvider.TREE_B));
    }

    @After public void closeFixturesAndCleanOnlyOwnedRows() throws Exception {
        Throwable teardownFailure = null;
        try {
            if (server != null) server.close();
        } catch (Throwable error) {
            teardownFailure = error;
        } finally {
            server = null;
            if (manager != null) {
                try {
                    closeManagerAndAwaitWorkers();
                } catch (Throwable error) {
                    teardownFailure = appendTeardownFailure(teardownFailure, error);
                }
            }
        }

        if (target != null) {
            try {
                QueueLifecycleDocumentsProvider.clearOutputWriteFailure(target, treeA);
            } catch (Throwable error) {
                teardownFailure = appendTeardownFailure(teardownFailure, error);
            }
        }
        if (target == null || teardownFailure != null) {
            throwTeardownFailure(teardownFailure);
            return;
        }

        QueueLifecycleDocumentsProvider.setDeleteFailure(target, treeA, null);
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            for (String id : ownedIds) {
                DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(id);
                if (retained != null) {
                    Uri rowTree = retained.folderUri == null || retained.folderUri.isEmpty()
                            ? treeA : Uri.parse(retained.folderUri);
                    forceDeleteOwnedUri(retained.partialUri, id, rowTree);
                    forceDeleteOwnedUri(retained.fileUri, id, rowTree);
                }
                DownloadRecord record = store.get(id);
                if (record != null && record.fileName != null
                        && record.fileName.startsWith(TEST_FILE_PREFIX)) {
                    deleteOwnedNamedChildren(treeA, record.fileName);
                    deleteOwnedNamedChildren(treeB, record.fileName);
                }
                deleteOwnedNamedChildren(treeA, ".orbit-part-" + id);
                deleteOwnedNamedChildren(treeA, ".orbit-part-" + id + "-keep");
                deleteOwnedNamedChildren(treeB, ".orbit-part-" + id);
                store.delete(id);
            }
        } finally {
            store.close();
        }
        for (FixtureDocumentRef document : manuallyCreatedDocuments) {
            forceDeleteFixtureDocument(document.tree, document.documentId);
        }
    }

    private static Throwable appendTeardownFailure(Throwable primary, Throwable additional) {
        if (primary == null) return additional;
        if (primary != additional) primary.addSuppressed(additional);
        return primary;
    }

    private static void throwTeardownFailure(Throwable error) throws Exception {
        if (error == null) return;
        if (error instanceof Exception) throw (Exception) error;
        if (error instanceof Error) throw (Error) error;
        throw new AssertionError(error);
    }

    @Test public void serverFailureAndTruncatedBodyRemainRetryableAndRestartAtByteZero()
            throws Exception {
        server = new LocalDownloadServer();
        server.addRoute("/server-error", attempt -> attempt == 1
                ? HttpResponse.error(503, "Service Unavailable") : HttpResponse.ok(COMPLETE_BODY));
        server.addRoute("/truncated", attempt -> attempt == 1
                ? HttpResponse.truncated(TRUNCATED_BODY, TRUNCATED_BODY.length + 4096)
                : HttpResponse.ok(COMPLETE_BODY));
        manager = newManagerWithFixtureDns();

        String serverErrorId = enqueue("/server-error");
        manager.startQueue();
        DownloadRecord serverError = awaitStatus(serverErrorId, DownloadRecord.FAILED, 12_000L);
        assertNotNull(serverError);
        assertTrue("a 5xx response should leave a useful Retry error", nonEmpty(serverError.errorMessage));
        assertEquals("the server failure should be requested once before explicit retry", 1,
                server.requestCount("/server-error"));
        assertNoOwnedRowDocuments(serverErrorId, serverError.fileName, treeA);

        manager.retry(serverErrorId);
        DownloadRecord serverRetry = awaitStatus(serverErrorId, DownloadRecord.COMPLETED, 12_000L);
        assertCompletedOnce(serverErrorId, serverRetry, "/server-error");

        String truncatedId = enqueue("/truncated");
        manager.startQueue();
        DownloadRecord truncated = awaitStatus(truncatedId, DownloadRecord.FAILED, 15_000L);
        assertNotNull(truncated);
        assertTrue("the truncated response must fail after bytes reached the partial file",
                truncated.bytesDownloaded > 0L);
        assertTrue("a truncated response should leave a useful Retry error", nonEmpty(truncated.errorMessage));
        assertEquals("the truncated response should be requested once before explicit retry", 1,
                server.requestCount("/truncated"));
        assertNoOwnedRowDocuments(truncatedId, truncated.fileName, treeA);

        manager.retry(truncatedId);
        DownloadRecord truncatedRetry = awaitStatus(truncatedId, DownloadRecord.COMPLETED, 12_000L);
        assertCompletedOnce(truncatedId, truncatedRetry, "/truncated");
    }

    @Test public void safOutputWriteFailureAfterPartialBytesCleansUpAndRetryStartsAtByteZero()
            throws Exception {
        final String path = "/saf-output-write-failure";
        final long partialBytesBeforeFailure = 8L * 1024L;
        server = new LocalDownloadServer();
        server.addRoute(path, attempt -> HttpResponse.ok(COMPLETE_BODY));
        manager = newManagerWithFixtureDns();

        String id = enqueue(path);
        QueueLifecycleDocumentsProvider.failNextOutputWriteAfter(target, treeA,
                partialBytesBeforeFailure);
        manager.startQueue();

        DownloadRecord failed = awaitStatus(id, DownloadRecord.FAILED, 15_000L);
        assertTrue("the SAF fixture must persist bytes before injecting its write error",
                QueueLifecycleDocumentsProvider.failedOutputBytes(target, treeA) > 0L);
        assertEquals("the injected SAF failure must happen after the configured byte prefix",
                partialBytesBeforeFailure,
                QueueLifecycleDocumentsProvider.failedOutputBytes(target, treeA));
        assertTrue("a SAF output write failure should remain actionable", nonEmpty(failed.errorMessage));
        assertEquals("the first source request should not use a range", Collections.singletonList(""),
                server.rangeHeaders(path));
        assertEquals("a failed SAF write must leave no HTTP continuation attempt", 1,
                server.requestCount(path));
        assertNoOwnedRowDocuments(id, failed.fileName, treeA);
        assertEquals("failed output cleanup must clear its retained partial URI", "",
                retained(id).partialUri);
        assertEquals("failed output cleanup must clear its reserved partial name", "",
                retained(id).partialName);

        QueueLifecycleDocumentsProvider.clearOutputWriteFailure(target, treeA);
        manager.retry(id);
        DownloadRecord completed = awaitStatus(id, DownloadRecord.COMPLETED, 15_000L);
        assertCompletedOnce(id, completed, path, 2);
    }

    @Test public void coldStartRemovesOnlyTheReservedNameWhenPartialUriWasNotPersisted()
            throws Exception {
        manager = newManagerWithFixtureDns();
        String id = enqueue("http://127.0.0.1:9/cold-start-unused");
        String runToken = "reservation-before-uri-" + UUID.randomUUID();
        String reservedName = ".orbit-part-" + id;
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            assertTrue(store.claimQueued(id, runToken));
            assertTrue(store.setPartialReservation(id, runToken, reservedName));
            Uri exactPartial = createFixtureDocument(treeA, reservedName,
                    "partial bytes before crash".getBytes(StandardCharsets.UTF_8));
            Uri sameTreeNearMatch = createFixtureDocument(treeA, reservedName + "-keep",
                    SAME_TREE_SENTINEL);
            trackFixtureDocument(treeA, exactPartial);
            trackFixtureDocument(treeA, sameTreeNearMatch);
            assertEquals("the crash boundary leaves the reserved name but no partial URI", "",
                    store.getRetainedDocuments(id).partialUri);
            assertTrue("the exact reserved partial exists before cold start",
                    documentExists(treeA, reservedName));
            assertTrue("the similarly prefixed sentinel exists before cold start",
                    documentExists(treeA, reservedName + "-keep"));
        } finally {
            store.close();
        }

        closeManagerAndAwaitWorkers();
        manager = newManagerWithFixtureDns();
        DownloadRecord recovered = record(id);
        assertNotNull(recovered);
        assertEquals("recovery should make the interrupted row retryable only after cleanup",
                DownloadRecord.PAUSED, recovered.status);
        DownloadQueueStore.RetainedDocuments retained = retained(id);
        assertEquals("the cleaned reservation URI should be cleared", "", retained.partialUri);
        assertEquals("the cleaned reservation name should be cleared", "", retained.partialName);
        assertFalse("cold start must remove the exact reserved partial", documentExists(treeA, reservedName));
        assertTrue("cold start must preserve a neighboring file whose name only shares the prefix",
                documentExists(treeA, reservedName + "-keep"));
        assertArrayEquals("cold start must not alter the similarly prefixed sentinel bytes",
                SAME_TREE_SENTINEL, readDocument(treeA, reservedName + "-keep"));
    }

    @Test public void deleteFailureKeepsFailedReservationAndBlocksTransferUntilCleanupSucceeds()
            throws Exception {
        server = new LocalDownloadServer();
        server.addRoute("/delete-failure", attempt -> HttpResponse.ok(COMPLETE_BODY));
        manager = newManagerWithFixtureDns();
        String id = enqueue(server.url("/delete-failure"));
        String runToken = "delete-failure-" + UUID.randomUUID();
        String partialName = ".orbit-part-" + id;
        DownloadQueueStore store = new DownloadQueueStore(target);
        String partialDocumentId;
        try {
            assertTrue(store.claimQueued(id, runToken));
            assertTrue(store.setPartialReservation(id, runToken, partialName));
            Uri partial = createFixtureDocument(treeA, partialName,
                    "retained partial content".getBytes(StandardCharsets.UTF_8));
            partialDocumentId = DocumentsContract.getDocumentId(partial);
            trackFixtureDocument(treeA, partial);
            assertTrue(store.setPartialUri(id, runToken, partial.toString()));
        } finally {
            store.close();
        }
        QueueLifecycleDocumentsProvider.setDeleteFailure(target, treeA, partialDocumentId);

        closeManagerAndAwaitWorkers();
        manager = newManagerWithFixtureDns();
        DownloadRecord failed = record(id);
        assertNotNull(failed);
        assertEquals(DownloadRecord.FAILED, failed.status);
        assertTrue("cleanup failure must remain visible and actionable", nonEmpty(failed.errorMessage));
        assertEquals("failure must preserve the exact partial URI", uriForDocument(treeA, partialDocumentId).toString(),
                retained(id).partialUri);
        assertEquals("failure must preserve the exact partial-name reservation", partialName,
                retained(id).partialName);
        assertTrue("the refused delete must leave the exact reserved file", documentExists(treeA, partialName));

        manager.retry(id);
        assertEquals("retry cleanup failure must leave an actionable FAILED row", DownloadRecord.FAILED,
                record(id).status);
        assertEquals("the retained reservation remains available for another cleanup attempt", partialName,
                retained(id).partialName);
        assertEquals("retry must not start the HTTP transfer while cleanup is still failing", 0,
                server.requestCount("/delete-failure"));

        QueueLifecycleDocumentsProvider.setDeleteFailure(target, treeA, null);
        manager.retry(id);
        DownloadRecord completed = awaitStatus(id, DownloadRecord.COMPLETED, 12_000L);
        assertCompletedOnce(id, completed, "/delete-failure", 1);
        assertFalse("retry should remove the old partial before writing the replacement",
                documentExists(treeA, partialName));
    }

    @Test public void pauseWithPartialDeleteFailureKeepsReservationUntilExplicitRetrySucceeds()
            throws Exception {
        assertActiveStopWithPartialDeleteFailure("/pause-delete-failure", false);
    }

    @Test public void cancelWithPartialDeleteFailureKeepsReservationUntilExplicitRetrySucceeds()
            throws Exception {
        assertActiveStopWithPartialDeleteFailure("/cancel-delete-failure", true);
    }

    @Test public void interruptedCleanupNeverTouchesSameNamedDocumentInAnotherTree()
            throws Exception {
        manager = newManagerWithFixtureDns();
        String id = enqueue("http://127.0.0.1:9/two-tree-unused");
        String runToken = "two-tree-recovery-" + UUID.randomUUID();
        String reservedName = ".orbit-part-" + id;
        DownloadQueueStore store = new DownloadQueueStore(target);
        Uri partialA;
        Uri sentinelB;
        try {
            assertTrue(store.claimQueued(id, runToken));
            assertTrue(store.setPartialReservation(id, runToken, reservedName));
            partialA = createFixtureDocument(treeA, reservedName,
                    "tree A partial".getBytes(StandardCharsets.UTF_8));
            sentinelB = createFixtureDocument(treeB, reservedName, OTHER_TREE_SENTINEL);
            trackFixtureDocument(treeA, partialA);
            trackFixtureDocument(treeB, sentinelB);
            // Deliberately stop before partial_uri persistence, as with a process death after createDocument.
            assertEquals("", store.getRetainedDocuments(id).partialUri);
        } finally {
            store.close();
        }

        closeManagerAndAwaitWorkers();
        manager = newManagerWithFixtureDns();
        assertEquals(DownloadRecord.PAUSED, record(id).status);
        assertFalse("startup recovery should remove the partial from the row's selected tree",
                documentExists(treeA, reservedName));
        assertTrue("startup recovery must not delete the same-named child in the other tree",
                documentExists(treeB, reservedName));
        assertArrayEquals("the other tree's same-named sentinel bytes must remain unchanged",
                OTHER_TREE_SENTINEL, readDocument(treeB, reservedName));
        assertEquals("the row must remain bound to tree A", treeA.toString(), record(id).folderUri);
    }

    private String enqueue(String path) throws Exception {
        String stableId = "queue-failure-" + UUID.randomUUID();
        String url = path.startsWith("http") ? path : server.url(path);
        String id = manager.enqueue(new DownloadRequest(TEST_PROFILE, stableId,
                "queue-lifecycle-failure-fixture", "Queue failure fixture",
                TEST_FILE_PREFIX + stableId + ".mp4", "video/mp4", url, true), treeA);
        ownedIds.add(id);
        return id;
    }

    private void assertActiveStopWithPartialDeleteFailure(String path, boolean cancel)
            throws Exception {
        server = new LocalDownloadServer();
        CountDownLatch firstResponseGate = new CountDownLatch(1);
        server.addRoute(path, attempt -> attempt == 1
                ? HttpResponse.blocked(COMPLETE_BODY, 8 * 1024, firstResponseGate)
                : HttpResponse.ok(COMPLETE_BODY));
        manager = newManagerWithFixtureDns();

        String id = enqueue(server.url(path));
        manager.startQueue();
        assertTrue("the local transfer must start before the user action",
                awaitRequestCount(path, 1, 8_000L));
        DownloadRecord active = awaitPartialProgress(id, 8_000L);
        assertNotNull("the row must have entered the active state", active);
        assertEquals(DownloadRecord.DOWNLOADING, active.status);
        assertEquals("the action test should target the first and only request so far", 1,
                server.requestCount(path));

        DownloadQueueStore.RetainedDocuments beforeStop = retained(id);
        assertTrue("the active transfer must have persisted its exact partial URI",
                nonEmpty(beforeStop.partialUri));
        String reservedName = beforeStop.partialName;
        assertEquals("the row must retain its exact partial-name reservation",
                ".orbit-part-" + id, reservedName);
        Uri partial = Uri.parse(beforeStop.partialUri);
        assertEquals("the active transfer partial must belong to Tree A",
                DocumentsContract.getTreeDocumentId(treeA), DocumentsContract.getTreeDocumentId(partial));
        String partialDocumentId = DocumentsContract.getDocumentId(partial);
        QueueLifecycleDocumentsProvider.setDeleteFailure(target, treeA, partialDocumentId);

        if (cancel) manager.cancel(id);
        else manager.pause(id);
        firstResponseGate.countDown();

        DownloadRecord cleanupFailed = awaitStatus(id, DownloadRecord.FAILED, 8_000L);
        awaitInactiveTask(id, 8_000L);
        assertTrue("failed pause/cancel cleanup must leave an actionable error",
                nonEmpty(cleanupFailed.errorMessage));
        DownloadQueueStore.RetainedDocuments afterStop = retained(id);
        assertEquals("failed cleanup must retain the exact partial URI", beforeStop.partialUri,
                afterStop.partialUri);
        assertEquals("failed cleanup must retain the exact name reservation", reservedName,
                afterStop.partialName);
        assertTrue("failed cleanup must leave the exact partial document available for recovery",
                documentExists(treeA, reservedName));
        assertTrue("the stopped transfer must have written some bytes before recovery",
                readDocument(partial).length > 0);
        assertEquals("stopping the active request must not create a second HTTP attempt", 1,
                server.requestCount(path));

        manager.retry(id);
        DownloadRecord stillFailed = record(id);
        assertNotNull(stillFailed);
        assertEquals("retry must remain actionable while the partial still cannot be deleted",
                DownloadRecord.FAILED, stillFailed.status);
        assertEquals("a blocked retry must retain the same partial reservation", reservedName,
                retained(id).partialName);
        assertFalse("a blocked retry must not start another HTTP request",
                awaitRequestCount(path, 2, 250L));
        assertEquals("the failed cleanup retry must preserve the old partial bytes", beforeStop.partialUri,
                retained(id).partialUri);

        QueueLifecycleDocumentsProvider.setDeleteFailure(target, treeA, null);
        manager.retry(id);
        DownloadRecord completed = awaitStatus(id, DownloadRecord.COMPLETED, 12_000L);
        assertCompletedOnce(id, completed, path);
        assertFalse("successful retry must remove the old reserved partial before finalization",
                documentExists(treeA, reservedName));
    }

    private DownloadRecord awaitPartialProgress(String id, long timeoutMs)
            throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        DownloadRecord latest = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = record(id);
            DownloadQueueStore.RetainedDocuments retained = retained(id);
            if (latest != null && DownloadRecord.DOWNLOADING.equals(latest.status)
                    && latest.bytesDownloaded > 0L && nonEmpty(retained.partialUri)) return latest;
            SystemClock.sleep(20L);
        }
        assertNotNull("active row did not reach persisted partial bytes before the stop action", latest);
        assertEquals("active row must still be downloading", DownloadRecord.DOWNLOADING, latest.status);
        assertTrue("active row must have persisted partial bytes", latest.bytesDownloaded > 0L);
        return latest;
    }

    private boolean awaitRequestCount(String path, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (server.requestCount(path) >= expected) return true;
            SystemClock.sleep(20L);
        }
        return server.requestCount(path) >= expected;
    }

    private void awaitInactiveTask(String id, long timeoutMs) throws Exception {
        Field field = DownloadQueueManager.class.getDeclaredField("activeTasks");
        field.setAccessible(true);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            @SuppressWarnings("unchecked") Map<String, ?> activeTasks =
                    (Map<String, ?>) field.get(manager);
            if (!activeTasks.containsKey(id)) return;
            SystemClock.sleep(20L);
        }
        @SuppressWarnings("unchecked") Map<String, ?> activeTasks =
                (Map<String, ?>) field.get(manager);
        assertFalse("stopped worker must leave the active-task map before retry is pressed",
                activeTasks.containsKey(id));
    }

    private DownloadQueueManager newManagerWithFixtureDns() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        Dns publicResolver = PlaybackDnsPinning.newDns(false);
        Dns localOnlyFixtureResolver = host -> ORIGIN_HOST.equals(host)
                ? Collections.singletonList(loopback) : publicResolver.lookup(host);
        Constructor<DownloadQueueManager> constructor = DownloadQueueManager.class
                .getDeclaredConstructor(Context.class, Dns.class);
        constructor.setAccessible(true);
        return constructor.newInstance(target, localOnlyFixtureResolver);
    }

    private DownloadRecord awaitStatus(String id, String expected, long timeoutMs)
            throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        DownloadRecord latest = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = record(id);
            if (latest != null && expected.equals(latest.status)) return latest;
            SystemClock.sleep(25L);
        }
        String latestStatus = latest == null ? "missing" : latest.status;
        String latestError = latest == null ? "" : latest.errorMessage;
        long latestBytes = latest == null ? 0L : latest.bytesDownloaded;
        assertEquals("row did not reach " + expected + " (latest status=" + latestStatus
                        + ", error=" + latestError + ", bytes=" + latestBytes + ")",
                expected, latestStatus);
        return latest;
    }

    private DownloadRecord record(String id) {
        if (manager == null) return null;
        for (DownloadRecord item : manager.getItems()) if (id.equals(item.id)) return item;
        return null;
    }

    private DownloadQueueStore.RetainedDocuments retained(String id) {
        DownloadQueueStore store = new DownloadQueueStore(target);
        try { return store.getRetainedDocuments(id); }
        finally { store.close(); }
    }

    private void assertCompletedOnce(String id, DownloadRecord complete, String path) throws Exception {
        assertCompletedOnce(id, complete, path, 2);
    }

    private void assertCompletedOnce(String id, DownloadRecord complete, String path,
            int expectedAttempts) throws Exception {
        assertNotNull(complete);
        assertEquals(DownloadRecord.COMPLETED, complete.status);
        assertEquals("the route should receive the expected number of complete attempts", expectedAttempts,
                server.requestCount(path));
        assertEquals("the queue must not use HTTP range continuation after retry",
                Collections.nCopies(expectedAttempts, ""), server.rangeHeaders(path));
        assertArrayEquals("retry must write the complete source body from byte zero", COMPLETE_BODY,
                readDocument(Uri.parse(complete.fileUri)));
        assertEquals("the completed row should have one finalized child in its selected tree", 1,
                countNamedChildren(treeA, complete.fileName));
        assertEquals("a completed download should not retain a partial-name reservation", "",
                retained(id).partialName);
    }

    private void assertNoOwnedRowDocuments(String id, String finalName, Uri tree) {
        assertFalse("failed transfer must not leave a completed file", documentExists(tree, finalName));
        assertFalse("failed transfer must remove its partial reservation file",
                documentExists(tree, ".orbit-part-" + id));
    }

    private Uri createFixtureDocument(Uri tree, String name, byte[] contents) throws Exception {
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        Uri child = DocumentsContract.createDocument(target.getContentResolver(), parent,
                "video/mp4", name);
        assertNotNull("the AndroidTest SAF provider should create the requested fixture document", child);
        try (OutputStream output = target.getContentResolver().openOutputStream(child, "wt")) {
            assertNotNull(output);
            output.write(contents);
            output.flush();
        }
        return child;
    }

    private byte[] readDocument(Uri tree, String name) throws IOException {
        Uri document = findNamedChild(tree, name);
        assertNotNull("fixture document should exist: " + name, document);
        return readDocument(document);
    }

    private byte[] readDocument(Uri document) throws IOException {
        try (InputStream input = target.getContentResolver().openInputStream(document);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Could not open the queue lifecycle fixture document");
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private boolean documentExists(Uri tree, String name) {
        return findNamedChild(tree, name) != null;
    }

    private Uri findNamedChild(Uri tree, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = target.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            assertNotNull("the queue lifecycle test tree should remain readable", cursor);
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                }
            }
        }
        return null;
    }

    private int countNamedChildren(Uri tree, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        int matches = 0;
        try (Cursor cursor = target.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            assertNotNull(cursor);
            while (cursor.moveToNext()) if (name.equals(cursor.getString(0))) matches++;
        }
        return matches;
    }

    private Uri uriForDocument(Uri tree, String documentId) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
    }

    private void forceDeleteOwnedUri(String rawUri, String rowId, Uri tree) {
        if (rawUri == null || rawUri.isEmpty()) return;
        Uri uri = Uri.parse(rawUri);
        assertEquals("cleanup URI must belong to the queue fixture provider",
                QueueLifecycleDocumentsProvider.AUTHORITY, uri.getAuthority());
        assertEquals("cleanup URI must stay in the row's exact selected tree",
                DocumentsContract.getTreeDocumentId(tree), DocumentsContract.getTreeDocumentId(uri));
        String id = DocumentsContract.getDocumentId(uri);
        String expectedPrefix = ".orbit-part-" + rowId;
        String name = displayName(uri);
        if (expectedPrefix.equals(name) || name.startsWith(TEST_FILE_PREFIX)) {
            forceDeleteFixtureDocument(tree, id);
        } else if (documentIdExists(tree, id)) {
            throw new AssertionError("Refusing cleanup of an unexpected fixture document name: " + name);
        }
    }

    private void deleteOwnedNamedChildren(Uri tree, String name) {
        Uri child = findNamedChild(tree, name);
        if (child == null) return;
        forceDeleteFixtureDocument(tree, DocumentsContract.getDocumentId(child));
    }

    private void forceDeleteFixtureDocument(Uri tree, String documentId) {
        QueueLifecycleDocumentsProvider.forceDeleteFixtureDocument(target, tree, documentId);
        assertFalse("teardown must verify the SAF child disappeared after backing-file deletion",
                documentIdExists(tree, documentId));
    }

    private boolean documentIdExists(Uri tree, String documentId) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = target.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            assertNotNull("the queue lifecycle test tree should remain readable during cleanup", cursor);
            while (cursor.moveToNext()) if (documentId.equals(cursor.getString(0))) return true;
        }
        return false;
    }

    private void trackFixtureDocument(Uri tree, Uri document) {
        manuallyCreatedDocuments.add(new FixtureDocumentRef(tree,
                DocumentsContract.getDocumentId(document)));
    }

    private String displayName(Uri document) {
        try (Cursor cursor = target.getContentResolver().query(document,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return "";
            return cursor.getString(0);
        }
    }

    private static Uri selectedFixtureTree(String rawTree, String expectedRoot) {
        assertNotNull("pass the host-selected " + expectedRoot + " URI as an instrumentation argument",
                rawTree);
        Uri tree;
        try { tree = Uri.parse(rawTree); }
        catch (RuntimeException malformed) {
            throw new AssertionError("host-selected tree argument is not a valid URI", malformed);
        }
        assertEquals("host selection must use a content URI", "content", tree.getScheme());
        assertTrue("host selection must return a SAF tree URI", DocumentsContract.isTreeUri(tree));
        assertEquals("host selection must use the queue lifecycle test provider",
                QueueLifecycleDocumentsProvider.AUTHORITY, tree.getAuthority());
        assertEquals("host selection must match the expected fixture root", expectedRoot,
                DocumentsContract.getTreeDocumentId(tree));
        assertTrue("host selection must be the canonical root tree URI, not a child tree URI",
                QueueLifecycleDocumentsProvider.isCanonicalTreeRoot(tree, expectedRoot));
        return tree;
    }

    private static void delegateSelectedFixtureTreesFromHelper(Context target) {
        String nonce = UUID.randomUUID().toString();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<List<String>> helperErrors = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!nonce.equals(intent.getStringExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_DELEGATE_NONCE))) return;
                ArrayList<String> messages = intent.getStringArrayListExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_DELEGATE_ERRORS);
                helperErrors.set(messages == null ? Collections.emptyList() : messages);
                completed.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(QueueLifecycleTreeGrantActivity.ACTION_DELEGATE_RESULT);
        try {
            if (Build.VERSION.SDK_INT >= 33) target.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else target.registerReceiver(receiver, filter);
            Intent delegate = new Intent(QueueLifecycleTreeGrantActivity.ACTION_DELEGATE_SELECTED_TREES)
                    .setClassName("com.auras.orbit.test",
                            "com.auras.orbit.downloads.QueueLifecycleTreeGrantActivity")
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_DELEGATE_NONCE, nonce)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            target.startActivity(delegate);
            if (!completed.await(20L, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for helper fixture grant delegation");
            }
            List<String> messages = helperErrors.get();
            if (messages != null && !messages.isEmpty()) {
                throw new AssertionError("Helper fixture grant delegation failed: " + messages);
            }
        } catch (Exception | AssertionError error) {
            throw new AssertionError("Could not delegate selected fixture trees to Orbit", error);
        } finally {
            try { target.unregisterReceiver(receiver); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private static void persistTargetTreeGrant(Context target, Uri tree) {
        assertEquals("Orbit must receive the exact delegated read/write tree grant before it persists it",
                android.content.pm.PackageManager.PERMISSION_GRANTED,
                target.checkUriPermission(tree, Process.myPid(), Process.myUid(), PERSISTED_MODE_FLAGS));
        int currentlyPersisted = persistedModeFlags(target, tree);
        int missingFlags = PERSISTED_MODE_FLAGS & ~currentlyPersisted;
        if (missingFlags != 0) {
            target.getContentResolver().takePersistableUriPermission(tree, missingFlags);
        }
    }

    private static void releaseOrbitFixtureGrants(Context target, List<Uri> canonicalTrees,
            List<Throwable> errors) {
        List<UriPermission> persisted;
        try {
            persisted = target.getContentResolver().getPersistedUriPermissions();
        } catch (RuntimeException readError) {
            errors.add(new AssertionError("Could not inspect Orbit's persisted fixture grants", readError));
            return;
        }
        for (Uri tree : canonicalTrees) {
            int modes = 0;
            for (UriPermission permission : persisted) {
                Uri granted = permission.getUri();
                if (!QueueLifecycleDocumentsProvider.isCanonicalTreeRoot(granted,
                        DocumentsContract.getTreeDocumentId(tree)) || !tree.equals(granted)) continue;
                if (permission.isReadPermission()) modes |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if (permission.isWritePermission()) modes |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            }
            if (modes == 0) continue;
            try { target.getContentResolver().releasePersistableUriPermission(tree, modes); }
            catch (RuntimeException releaseError) {
                errors.add(new AssertionError("Could not release Orbit's exact persisted grant for "
                        + DocumentsContract.getTreeDocumentId(tree), releaseError));
            }
        }
    }

    private static void runHelperCleanupAndAwait(Context target, List<Throwable> errors) {
        String nonce = UUID.randomUUID().toString();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<List<String>> helperErrors = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!nonce.equals(intent.getStringExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_CLEANUP_NONCE))) return;
                ArrayList<String> messages = intent.getStringArrayListExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_CLEANUP_ERRORS);
                helperErrors.set(messages == null ? Collections.emptyList() : messages);
                completed.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(QueueLifecycleTreeGrantActivity.ACTION_CLEANUP_RESULT);
        try {
            if (Build.VERSION.SDK_INT >= 33) target.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else target.registerReceiver(receiver, filter);
            Intent cleanup = new Intent(QueueLifecycleTreeGrantActivity.ACTION_CLEANUP_ALL)
                    .setClassName("com.auras.orbit.test",
                            "com.auras.orbit.downloads.QueueLifecycleTreeGrantActivity")
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_CLEANUP_NONCE, nonce)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            target.startActivity(cleanup);
            if (!completed.await(20L, TimeUnit.SECONDS)) {
                errors.add(new AssertionError("Timed out waiting for helper cleanup-all completion"));
            } else {
                for (String message : helperErrors.get()) {
                    errors.add(new AssertionError("Helper cleanup-all: " + message));
                }
            }
        } catch (Exception | AssertionError startError) {
            errors.add(new AssertionError("Could not run or await helper cleanup-all", startError));
        } finally {
            try { target.unregisterReceiver(receiver); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private static void verifyTargetGrantRevoked(Context target, Uri tree, List<Throwable> errors) {
        try {
            for (UriPermission permission : target.getContentResolver().getPersistedUriPermissions()) {
                if (!tree.equals(permission.getUri())
                        || !QueueLifecycleDocumentsProvider.isCanonicalTreeRoot(permission.getUri(),
                                DocumentsContract.getTreeDocumentId(tree))) continue;
                errors.add(new AssertionError("Orbit still owns persisted modes for " + tree));
                break;
            }
        } catch (RuntimeException readError) {
            errors.add(new AssertionError("Could not verify Orbit's persisted grant cleanup for " + tree,
                    readError));
        }
        int[] accessModes = {Intent.FLAG_GRANT_READ_URI_PERMISSION,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION};
        String[] accessNames = {"read", "write"};
        for (int index = 0; index < accessModes.length; index++) {
            try {
                int permission = target.checkUriPermission(tree, Process.myPid(), Process.myUid(),
                        accessModes[index]);
                if (permission != android.content.pm.PackageManager.PERMISSION_DENIED) {
                    errors.add(new AssertionError("Orbit still has transient " + accessNames[index]
                            + " access to " + tree));
                }
            } catch (RuntimeException checkError) {
                errors.add(new AssertionError("Could not verify Orbit's transient " + accessNames[index]
                        + " access for " + tree, checkError));
            }
        }
    }

    private static int persistedModeFlags(Context context, Uri tree) {
        int flags = 0;
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (!tree.equals(permission.getUri())) continue;
            if (permission.isReadPermission()) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (permission.isWritePermission()) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        return flags;
    }

    private static Uri persistedFixtureTree(Context context, String expectedRoot) {
        Uri found = null;
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            Uri candidate = permission.getUri();
            if (!DocumentsContract.isTreeUri(candidate)
                    || !QueueLifecycleDocumentsProvider.AUTHORITY.equals(candidate.getAuthority())
                    || !expectedRoot.equals(DocumentsContract.getTreeDocumentId(candidate))) continue;
            assertTrue("Orbit's persisted fixture grant must include read access",
                    permission.isReadPermission());
            assertTrue("Orbit's persisted fixture grant must include write access",
                    permission.isWritePermission());
            assertTrue("Orbit must have only one exact persisted URI for each fixture root",
                    found == null || found.equals(candidate));
            found = candidate;
        }
        assertNotNull("Orbit must own a persisted grant for fixture root " + expectedRoot, found);
        return found;
    }

    private void closeManagerAndAwaitWorkers() throws Exception {
        DownloadQueueManager closing = manager;
        if (closing == null) return;
        manager = null;
        ExecutorService admission = executorField(closing, "admission");
        ExecutorService transfers = executorField(closing, "transfers");
        closing.close();
        long deadline = SystemClock.elapsedRealtime() + 30_000L;
        while ((!admission.isTerminated() || !transfers.isTerminated())
                && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(25L);
        }
        assertTrue("manager admission executor must terminate before fixture cleanup",
                admission.isTerminated());
        assertTrue("manager transfer executor must terminate before fixture cleanup",
                transfers.isTerminated());
    }

    private static ExecutorService executorField(DownloadQueueManager manager, String name)
            throws Exception {
        Field field = DownloadQueueManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ExecutorService) field.get(manager);
    }

    private static boolean nonEmpty(String value) { return value != null && !value.trim().isEmpty(); }

    private static byte[] payloadBytes(int size, int seed) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) bytes[index] = (byte) ((index * 31 + seed) & 0xff);
        return bytes;
    }

    private static final class FixtureDocumentRef {
        final Uri tree;
        final String documentId;

        FixtureDocumentRef(Uri tree, String documentId) {
            this.tree = tree;
            this.documentId = documentId;
        }
    }

    private interface Route { HttpResponse responseForAttempt(int attempt); }

    private static final class HttpResponse {
        final int code;
        final String reason;
        final byte[] body;
        final int declaredLength;
        final int blockAfterBytes;
        final CountDownLatch releaseGate;

        private HttpResponse(int code, String reason, byte[] body, int declaredLength,
                int blockAfterBytes, CountDownLatch releaseGate) {
            this.code = code;
            this.reason = reason;
            this.body = body;
            this.declaredLength = declaredLength;
            this.blockAfterBytes = blockAfterBytes;
            this.releaseGate = releaseGate;
        }

        static HttpResponse ok(byte[] body) {
            return new HttpResponse(200, "OK", body.clone(), body.length, 0, null);
        }

        static HttpResponse error(int code, String reason) {
            return new HttpResponse(code, reason, new byte[0], 0, 0, null);
        }

        static HttpResponse truncated(byte[] body, int declaredLength) {
            return new HttpResponse(200, "OK", body.clone(), declaredLength, 0, null);
        }

        static HttpResponse blocked(byte[] body, int blockAfterBytes, CountDownLatch releaseGate) {
            if (blockAfterBytes <= 0 || blockAfterBytes >= body.length || releaseGate == null) {
                throw new IllegalArgumentException("A blocked response requires an in-body gate");
            }
            return new HttpResponse(200, "OK", body.clone(), body.length,
                    blockAfterBytes, releaseGate);
        }
    }

    /** Bounded HTTP/1.1 server bound to loopback; it does not contact an external endpoint. */
    private static final class LocalDownloadServer implements Closeable {
        private final ServerSocket server;
        private final Map<String, Route> routes = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        private final Map<String, List<String>> ranges = new ConcurrentHashMap<>();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final List<Thread> workers = new CopyOnWriteArrayList<>();
        private final List<CountDownLatch> responseGates = new CopyOnWriteArrayList<>();
        private final Thread acceptor;

        LocalDownloadServer() throws IOException {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(500);
            acceptor = new Thread(this::acceptLoop, "orbit-queue-failure-http-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        void addRoute(String path, Route route) { routes.put(path, route); }

        String url(String path) { return "http://" + ORIGIN_HOST + ":" + server.getLocalPort() + path; }

        int requestCount(String path) {
            AtomicInteger count = requests.get(path);
            return count == null ? 0 : count.get();
        }

        List<String> rangeHeaders(String path) {
            List<String> values = ranges.get(path);
            return values == null ? Collections.emptyList() : new ArrayList<>(values);
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    sockets.add(socket);
                    Thread worker = new Thread(() -> serve(socket), "orbit-queue-failure-http-response");
                    worker.setDaemon(true);
                    workers.add(worker);
                    worker.start();
                } catch (java.net.SocketTimeoutException ignored) {
                    // The bounded poll lets close() stop the acceptor promptly.
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
                String range = "";
                while (true) {
                    String line = input.readLine();
                    if (line == null || line.isEmpty()) break;
                    if (line.regionMatches(true, 0, "Range:", 0, 6)) range = line.substring(6).trim();
                }
                String[] requestParts = first.split(" ");
                String path = requestParts.length > 1 ? requestParts[1] : "/missing";
                Route route = routes.get(path);
                if (route == null) return;
                int attempt = requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
                ranges.computeIfAbsent(path, ignored -> new CopyOnWriteArrayList<>()).add(range);
                HttpResponse response = route.responseForAttempt(attempt);
                OutputStream output = connection.getOutputStream();
                output.write(("HTTP/1.1 " + response.code + " " + response.reason
                        + "\r\nContent-Type: video/mp4\r\nContent-Length: "
                        + response.declaredLength + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                int offset = 0;
                boolean gateReleased = response.releaseGate == null;
                if (response.releaseGate != null) responseGates.add(response.releaseGate);
                while (offset < response.body.length) {
                    int count = Math.min(8 * 1024, response.body.length - offset);
                    output.write(response.body, offset, count);
                    output.flush();
                    offset += count;
                    if (!gateReleased && offset >= response.blockAfterBytes) {
                        try {
                            if (!response.releaseGate.await(30L, TimeUnit.SECONDS)) return;
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        gateReleased = true;
                    }
                    if (response.declaredLength > response.body.length) {
                        try { TimeUnit.MILLISECONDS.sleep(40L); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            } catch (IOException ignored) {
                // Queue cleanup or cancellation may close this loopback socket.
            } finally {
                sockets.remove(socket);
            }
        }

        @Override public void close() throws IOException {
            server.close();
            for (CountDownLatch gate : responseGates) gate.countDown();
            for (Socket socket : sockets) {
                try { socket.close(); } catch (IOException ignored) { }
            }
            for (Thread worker : workers) {
                try { worker.join(1_000L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            try { acceptor.join(1_000L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
