package com.auras.orbit.downloads;

import android.app.Application;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.OrbitApplication;
import com.auras.orbit.player.PlaybackDnsPinning;

import okhttp3.Dns;

import org.junit.After;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Regression coverage for a process dying after download recovery starts but before SAF cleanup. */
@RunWith(AndroidJUnit4.class)
public final class DownloadQueueRecoveryInstrumentationTest {
    private static final byte[] PARTIAL_BYTES = "orphaned-partial".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EXPECTED_FILE = "synthetic-download-after-recovery-v1"
            .getBytes(StandardCharsets.UTF_8);
    private static final String TEST_PROFILE = "download-test-profile";
    private static final String TEST_FILE_PREFIX = "Orbit-Queue-Fixture-";
    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";
    private static final String FIXTURE_TREE_DOCUMENT_ID =
            "primary:Download/Orbit Download Queue Fixture";
    private static final String PROCESS_CYCLE_PREFS = "orbit_download_process_cycle_test_v1";
    private static final String PROCESS_CYCLE_ROW_ID = "prepared_row_id";
    private static final String PROCESS_CYCLE_CYCLE_NONCE = "cycle_nonce";
    private static final String PROCESS_CYCLE_CYCLE_NONCE_ARG = "orbitDownloadProcessDeathCycleNonce";
    private static final String PROCESS_CYCLE_STABLE_ITEM_ID = "stable_item_id";
    private static final String PROCESS_CYCLE_TREE_URI = "tree_uri";
    private static final String PROCESS_CYCLE_SERVER_PORT = "server_port";
    private static final String PROCESS_CYCLE_PARTIAL_NAME = "partial_name";
    private static final String PROCESS_CYCLE_PARTIAL_DOCUMENT_ID = "partial_document_id";
    private static final String PROCESS_CYCLE_FINAL_NAME = "final_name";
    private static final String PROCESS_CYCLE_SENTINEL_NAME = "sentinel_name";
    private static final String PROCESS_CYCLE_SENTINEL_DOCUMENT_ID = "sentinel_document_id";
    private static final String PROCESS_CYCLE_ORIGINAL_INVENTORY = "original_inventory";
    private static final String PROCESS_CYCLE_PHASE_ARG = "orbitDownloadProcessDeathPhase";
    private static final String PROCESS_CYCLE_PRESERVE_ARG = "orbitDownloadProcessDeathPreserve";
    private static final String PROCESS_CYCLE_RECOVERY_ROW_ID_ARG =
            "orbitDownloadProcessDeathRecoveryRowId";
    private static final String PROCESS_CYCLE_RECOVERY_STABLE_ITEM_ID_ARG =
            "orbitDownloadProcessDeathRecoveryStableItemId";
    private static final String PROCESS_CYCLE_PREPARE_PHASE = "prepare";
    private static final String PROCESS_CYCLE_VERIFY_PHASE = "verify";
    private static final String PROCESS_CYCLE_RECOVER_PHASE = "recover";
    private static final byte[] PROCESS_CYCLE_BODY =
            "process-restart-cold-start-retry-from-byte-zero-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROCESS_CYCLE_PARTIAL_BYTES =
            "process-restart".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROCESS_CYCLE_SENTINEL_BYTES =
            "neighboring-sentinel-must-survive-orbit-startup-v1".getBytes(StandardCharsets.UTF_8);
    private boolean processDeathRecoveryValidated;
    private static final String COMPLETED_REMOVAL_PREFS =
            "orbit_download_completed_removal_process_cycle_v1";
    private static final String COMPLETED_REMOVAL_PHASE_ARG =
            "orbitDownloadCompletedRemovalPhase";
    private static final String COMPLETED_REMOVAL_NONCE_ARG =
            "orbitDownloadCompletedRemovalCycleNonce";
    private static final String COMPLETED_REMOVAL_PRESERVE_ARG =
            "orbitDownloadCompletedRemovalPreserve";
    private static final String COMPLETED_REMOVAL_PREPARE_PHASE = "prepare";
    private static final String COMPLETED_REMOVAL_VERIFY_PHASE = "verify";
    private static final String COMPLETED_REMOVAL_RECOVER_PHASE = "recover";
    private static final String COMPLETED_REMOVAL_NONCE = "cycle_nonce";
    private static final String COMPLETED_REMOVAL_ROW_ID = "row_id";
    private static final String COMPLETED_REMOVAL_STABLE_ID = "stable_item_id";
    private static final String COMPLETED_REMOVAL_TREE_URI = "tree_uri";
    private static final String COMPLETED_REMOVAL_REQUESTED_NAME = "requested_file_name";
    private static final String COMPLETED_REMOVAL_FILE_NAME = "file_name";
    private static final String COMPLETED_REMOVAL_FILE_URI = "file_uri";
    private static final String COMPLETED_REMOVAL_FILE_DOCUMENT_ID = "file_document_id";
    private static final String COMPLETED_REMOVAL_FILE_SHA256 = "file_sha256";
    private static final String COMPLETED_REMOVAL_SENTINEL_NAME = "sentinel_name";
    private static final String COMPLETED_REMOVAL_SENTINEL_DOCUMENT_ID = "sentinel_document_id";
    private static final String COMPLETED_REMOVAL_SENTINEL_SHA256 = "sentinel_sha256";
    private static final String COMPLETED_REMOVAL_ORIGINAL_INVENTORY = "original_inventory";
    private static final String COMPLETED_REMOVAL_ORIGINAL_QUEUE = "original_queue";
    private static final String COMPLETED_REMOVAL_ORIGINAL_PREFERENCES = "original_preferences";
    private static final String COMPLETED_REMOVAL_REMOVING_INTENT = "removing_intent";
    private static final String COMPLETED_REMOVAL_CLEANUP_STARTED = "cleanup_started";
    private static final String COMPLETED_REMOVAL_PROFILE = "download-test-profile";
    private static final String COMPLETED_REMOVAL_SOURCE_ID = "download-test-source";
    private static final String COMPLETED_REMOVAL_TITLE = "Completed removal cold-start fixture";
    private static final String COMPLETED_REMOVAL_FILE_PREFIX = "Orbit-Queue-Completed-Removal-";
    private static final byte[] COMPLETED_REMOVAL_FILE_BYTES =
            "completed-removal-cold-start-exact-file-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COMPLETED_REMOVAL_SENTINEL_BYTES =
            "completed-removal-neighbor-must-survive-startup-v1".getBytes(StandardCharsets.UTF_8);
    private boolean completedRemovalFixturePreparedInThisRun;
    private boolean completedRemovalRecoveryValidated;
    private boolean completedRemovalFixtureCleaned;
    private static final String SAF_DELETE_FAILURE_PHASE_ARG = "orbitSafDeleteFailurePhase";
    private static final String SAF_DELETE_FAILURE_CYCLE_NONCE_ARG =
            "orbitSafDeleteFailureCycleNonce";
    private static final String SAF_DELETE_FAILURE_PRESERVE_ARG = "orbitSafDeleteFailurePreserve";
    private static final String SAF_DELETE_FAILURE_PREPARE_PHASE = "prepare";
    private static final String SAF_DELETE_FAILURE_VERIFY_PHASE = "verify";
    private static final String SAF_DELETE_FAILURE_PROFILE = "saf-cold-start-cleanup-failure-profile";
    private static final String SAF_DELETE_FAILURE_FILE_PREFIX = "Orbit-SAF-Cold-Start-Failure-";
    private static final String SAF_DELETE_FAILURE_PREFS = "orbit_saf_cold_start_failure_v1";
    private static final String SAF_DELETE_FAILURE_CYCLE_NONCE = "cycle_nonce";
    private static final String SAF_DELETE_FAILURE_ROW_ID = "row_id";
    private static final String SAF_DELETE_FAILURE_TREE_URI = "tree_uri";
    private static final String SAF_DELETE_FAILURE_DOCUMENT_ID = "partial_document_id";
    private static final String SAF_DELETE_FAILURE_PARTIAL_NAME = "partial_name";
    private static final String SAF_DELETE_FAILURE_SERVER_PORT = "server_port";
    private static final String SAF_GRANT_LOSS_PHASE_ARG = "orbitSafGrantLossPhase";
    private static final String SAF_GRANT_LOSS_NONCE_ARG = "orbitSafGrantLossCycleNonce";
    private static final String SAF_GRANT_LOSS_PRESERVE_ARG = "orbitSafGrantLossPreserve";
    private static final String SAF_GRANT_LOSS_PREPARE_PHASE = "prepare";
    private static final String SAF_GRANT_LOSS_COLD_START_PHASE = "cold-start";
    private static final String SAF_GRANT_LOSS_RESELECTED_PHASE = "reselected";
    private static final String SAF_GRANT_LOSS_RECOVERY_PHASE = "recover-incomplete";
    private static final String SAF_GRANT_LOSS_PROFILE = "saf-grant-loss-reselection-profile";
    private static final String SAF_GRANT_LOSS_FILE_PREFIX = "Orbit-SAF-Grant-Loss-";
    private static final String SAF_GRANT_LOSS_PREFS = "orbit_saf_grant_loss_reselection_v1";
    private static final String SAF_GRANT_LOSS_CYCLE_NONCE = "cycle_nonce";
    private static final String SAF_GRANT_LOSS_ROW_ID = "row_id";
    private static final String SAF_GRANT_LOSS_STABLE_ITEM_ID = "stable_item_id";
    private static final String SAF_GRANT_LOSS_TREE_URI = "tree_uri";
    private static final String SAF_GRANT_LOSS_DOCUMENT_ID = "partial_document_id";
    private static final String SAF_GRANT_LOSS_PARTIAL_NAME = "partial_name";
    private static final String SAF_GRANT_LOSS_SERVER_PORT = "server_port";
    private static final String SAF_GRANT_LOSS_ORIGINAL_FOLDER_PRESENT = "original_folder_present";
    private static final String SAF_GRANT_LOSS_ORIGINAL_FOLDER_URI = "original_folder_uri";
    private static final String SAF_GRANT_LOSS_INITIAL_TREE_A_FLAGS = "initial_tree_a_flags";
    private static final String SAF_GRANT_LOSS_INITIAL_TREE_B_FLAGS = "initial_tree_b_flags";
    private static final String SAF_GRANT_LOSS_POST_SETUP_TREE_B_FLAGS = "post_setup_tree_b_flags";
    private static final String DOWNLOAD_FOLDER_PREFS = "orbit_downloads_shell_v1";
    private static final String DOWNLOAD_FOLDER_PREF_KEY = "folder_uri";
    private static final byte[] SAF_GRANT_LOSS_BODY =
            "saf-grant-loss-reselection-retry-starts-at-byte-zero-v1"
                    .getBytes(StandardCharsets.UTF_8);
    private static final String QUEUE_TREE_A_ARG = "tree_a";
    private static final String QUEUE_TREE_B_ARG = "tree_b";
    private static final int PERSISTED_TREE_MODE_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final byte[] SAF_DELETE_FAILURE_BODY =
            "retry-must-restart-from-byte-zero-after-cold-start-cleanup-failure-v1"
                    .getBytes(StandardCharsets.UTF_8);
    private boolean processDeathFixturePreparedInThisRun;
    private boolean safDeleteFailureFixturePreparedInThisRun;
    private String safDeleteFailureRowIdPreparedInThisRun;
    private boolean safGrantLossFixturePreparedInThisRun;
    private String safGrantLossRowIdPreparedInThisRun;

    @BeforeClass public static void prepareSafDeleteFailureTreeGrantsWhenRequested()
            throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String phase = args.getString(SAF_DELETE_FAILURE_PHASE_ARG);
        if (phase == null) return;
        requireSafDeleteFailureCycleNonce(args);
        Assume.assumeTrue("package-scoped URI grants require API 26 or later",
                Build.VERSION.SDK_INT >= 26);
        Uri treeA = selectedQueueLifecycleTree(args.getString(QUEUE_TREE_A_ARG),
                QueueLifecycleDocumentsProvider.TREE_A);
        Uri treeB = selectedQueueLifecycleTree(args.getString(QUEUE_TREE_B_ARG),
                QueueLifecycleDocumentsProvider.TREE_B);
        assertFalse("the helper's two selected fixture roots must be distinct", treeA.equals(treeB));

        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (SAF_DELETE_FAILURE_PREPARE_PHASE.equals(phase)) {
            delegateSelectedQueueTreesFromHelper(target);
            persistTargetQueueTreeGrant(target, treeA);
            persistTargetQueueTreeGrant(target, treeB);
        } else if (SAF_DELETE_FAILURE_VERIFY_PHASE.equals(phase)) {
            assertPersistedQueueTreeGrant(target, treeA);
            assertPersistedQueueTreeGrant(target, treeB);
        } else {
            throw new AssertionError("Unknown SAF delete-failure phase: " + phase);
        }
    }

    @BeforeClass public static void prepareSafGrantLossTreeGrantsWhenRequested()
            throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String phase = args.getString(SAF_GRANT_LOSS_PHASE_ARG);
        if (phase == null) return;
        requireSafGrantLossCycleNonce(args);
        Assume.assumeTrue("package-scoped URI grants require API 26 or later",
                Build.VERSION.SDK_INT >= 26);
        Uri treeA = selectedQueueLifecycleTree(args.getString(QUEUE_TREE_A_ARG),
                QueueLifecycleDocumentsProvider.TREE_A);
        Uri treeB = selectedQueueLifecycleTree(args.getString(QUEUE_TREE_B_ARG),
                QueueLifecycleDocumentsProvider.TREE_B);
        assertFalse("the helper's selected fixture roots must be distinct", treeA.equals(treeB));

        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (SAF_GRANT_LOSS_PREPARE_PHASE.equals(phase)) {
            android.content.SharedPreferences marker = target.getSharedPreferences(
                    SAF_GRANT_LOSS_PREFS, Context.MODE_PRIVATE);
            if (!marker.getAll().isEmpty()) {
                throw new AssertionError("Prior SAF grant-loss setup or cleanup state remains; preserve it for exact recovery.");
            }
            int initialTreeAFlags = persistedTreeModeFlags(target, treeA);
            int initialTreeBFlags = persistedTreeModeFlags(target, treeB);
            android.content.SharedPreferences folderPreferences = target.getSharedPreferences(
                    DOWNLOAD_FOLDER_PREFS, Context.MODE_PRIVATE);
            boolean originalFolderPresent = folderPreferences.contains(DOWNLOAD_FOLDER_PREF_KEY);
            String originalFolderUri = folderPreferences.getString(DOWNLOAD_FOLDER_PREF_KEY, null);
            String stableItemId = "saf-grant-loss-" + UUID.randomUUID();
            Assume.assumeTrue("refusing grant-loss setup when Orbit already owns a Tree A or B grant",
                    initialTreeAFlags == 0 && initialTreeBFlags == 0);
            Assume.assumeTrue("refusing grant-loss setup when Orbit has transient access to Tree A or B",
                    !hasTargetUriMode(target, treeA, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            && !hasTargetUriMode(target, treeA, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            && !hasTargetUriMode(target, treeB, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            && !hasTargetUriMode(target, treeB, Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            Assume.assumeTrue("refusing grant-loss setup if the current Downloads folder is a fixture tree",
                    !treeA.toString().equals(originalFolderUri)
                            && !treeB.toString().equals(originalFolderUri));
            boolean saved = marker.edit()
                    .putString(SAF_GRANT_LOSS_CYCLE_NONCE, requireSafGrantLossCycleNonce(args))
                    .putString(SAF_GRANT_LOSS_TREE_URI, treeA.toString())
                    .putBoolean(SAF_GRANT_LOSS_ORIGINAL_FOLDER_PRESENT, originalFolderPresent)
                    .putString(SAF_GRANT_LOSS_ORIGINAL_FOLDER_URI,
                            originalFolderUri == null ? "" : originalFolderUri)
                    .putInt(SAF_GRANT_LOSS_INITIAL_TREE_A_FLAGS, initialTreeAFlags)
                    .putInt(SAF_GRANT_LOSS_INITIAL_TREE_B_FLAGS, initialTreeBFlags)
                    .putString(SAF_GRANT_LOSS_STABLE_ITEM_ID, stableItemId)
                    .commit();
            assertTrue("the fixture must preserve pre-test Download-folder and exact-tree grant state",
                    saved);
            try {
                delegateSelectedQueueTreesFromHelper(target);
                persistTargetQueueTreeGrant(target, treeA);
                persistTargetQueueTreeGrant(target, treeB);
                boolean postSetupSaved = marker.edit().putInt(
                        SAF_GRANT_LOSS_POST_SETUP_TREE_B_FLAGS,
                        persistedTreeModeFlags(target, treeB)).commit();
                assertTrue("the control Tree B mode snapshot must be durable", postSetupSaved);
            } catch (Throwable setupFailure) {
                Throwable rollbackFailure = rollbackSafGrantLossSetup(target, marker,
                        originalFolderPresent, originalFolderUri,
                        treeA, initialTreeAFlags, treeB, initialTreeBFlags);
                if (rollbackFailure != null) setupFailure.addSuppressed(rollbackFailure);
                if (setupFailure instanceof Exception) throw (Exception) setupFailure;
                if (setupFailure instanceof Error) throw (Error) setupFailure;
                throw new AssertionError(setupFailure);
            }
        } else if (SAF_GRANT_LOSS_COLD_START_PHASE.equals(phase)) {
            assertEquals("cold-start verification must observe actual exact Tree A grant loss", 0,
                    persistedTreeModeFlags(target, treeA));
            assertPersistedQueueTreeGrant(target, treeB);
        } else if (SAF_GRANT_LOSS_RESELECTED_PHASE.equals(phase)) {
            assertPersistedQueueTreeGrant(target, treeA);
            assertPersistedQueueTreeGrant(target, treeB);
        } else if (SAF_GRANT_LOSS_RECOVERY_PHASE.equals(phase)) {
            android.content.SharedPreferences marker = target.getSharedPreferences(
                    SAF_GRANT_LOSS_PREFS, Context.MODE_PRIVATE);
            validateSafGrantLossRecoveryState(target, marker, treeA, treeB,
                    requireSafGrantLossCycleNonce(args));
            delegateSelectedQueueTreesFromHelper(target);
            persistTargetQueueTreeGrant(target, treeA);
            persistTargetQueueTreeGrant(target, treeB);
        } else {
            throw new AssertionError("Unknown SAF grant-loss phase: " + phase);
        }
    }

    @After public void cleanSyntheticTestArtifacts() throws Exception {
        Context target = null;
        Throwable teardownFailure = null;
        try {
            target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            cleanSyntheticTestArtifactsForRun(target);
        } catch (Throwable error) {
            teardownFailure = error;
        }
        Bundle args = InstrumentationRegistry.getArguments();
        boolean processCycleRecovery = PROCESS_CYCLE_RECOVER_PHASE.equals(
                args.getString(PROCESS_CYCLE_PHASE_ARG));
        String completedRemovalPhase = args.getString(COMPLETED_REMOVAL_PHASE_ARG);
        boolean completedRemovalMayClearGate =
                COMPLETED_REMOVAL_VERIFY_PHASE.equals(completedRemovalPhase)
                        && completedRemovalFixtureCleaned
                        && target != null
                        && target.getSharedPreferences(COMPLETED_REMOVAL_PREFS,
                                Context.MODE_PRIVATE).getAll().isEmpty();
        if (target != null && completedRemovalMayClearGate
                && (!processCycleRecovery || processDeathRecoveryValidated)) {
            try {
                clearProductionApplicationGate(target);
            } catch (Throwable error) {
                teardownFailure = appendCleanupFailure(teardownFailure, error);
            }
        }
        if (teardownFailure instanceof Exception) throw (Exception) teardownFailure;
        if (teardownFailure instanceof Error) throw (Error) teardownFailure;
        if (teardownFailure != null) throw new AssertionError(teardownFailure);
    }

    private void cleanSyntheticTestArtifactsForRun(Context target) throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String completedRemovalPhase = args.getString(COMPLETED_REMOVAL_PHASE_ARG);
        if (completedRemovalPhase != null) {
            if (!COMPLETED_REMOVAL_PREPARE_PHASE.equals(completedRemovalPhase)
                    && !COMPLETED_REMOVAL_VERIFY_PHASE.equals(completedRemovalPhase)
                    && !COMPLETED_REMOVAL_RECOVER_PHASE.equals(completedRemovalPhase)) {
                throw new IOException("Unknown completed-removal phase; retaining its exact fixture marker");
            }
            if (COMPLETED_REMOVAL_PREPARE_PHASE.equals(completedRemovalPhase)) {
                if (!shouldPreservePreparedCompletedRemovalFixture(target)) {
                    cleanupCompletedRemovalFixture(target);
                    completedRemovalFixtureCleaned = true;
                }
            } else if (completedRemovalRecoveryValidated) {
                cleanupCompletedRemovalFixture(target);
                completedRemovalFixtureCleaned = true;
            }
            return;
        }
        String processDeathPhase = args.getString(PROCESS_CYCLE_PHASE_ARG);
        if (processDeathPhase != null) {
            if (!PROCESS_CYCLE_PREPARE_PHASE.equals(processDeathPhase)
                    && !PROCESS_CYCLE_VERIFY_PHASE.equals(processDeathPhase)
                    && !PROCESS_CYCLE_RECOVER_PHASE.equals(processDeathPhase)) {
                throw new IOException("Unknown process-cycle phase; retaining its fixture marker");
            }
            if (PROCESS_CYCLE_RECOVER_PHASE.equals(processDeathPhase)) {
                if (processDeathRecoveryValidated) cleanupInterruptedProcessCycleFixture(target);
                return;
            }
            if (PROCESS_CYCLE_VERIFY_PHASE.equals(processDeathPhase)
                    || !shouldPreservePreparedProcessDeathFixture(target)) {
                cleanupInterruptedProcessCycleFixture(target);
            }
            return;
        }
        String safDeleteFailurePhase = args.getString(SAF_DELETE_FAILURE_PHASE_ARG);
        if (safDeleteFailurePhase != null) {
            if (!shouldPreserveSafDeleteFailureFixture(target, args)) {
                cleanupSafDeleteFailureFixture(target, safDeleteFailureRowIdPreparedInThisRun);
            }
            return;
        }
        String safGrantLossPhase = args.getString(SAF_GRANT_LOSS_PHASE_ARG);
        if (safGrantLossPhase != null) {
            if (!shouldPreserveSafGrantLossFixture(target, args)) {
                cleanupSafGrantLossFixture(target, safGrantLossRowIdPreparedInThisRun);
            }
            return;
        }
        Uri folder = selectedFixtureFolderOrNull(target);
        if (!isFixtureTree(folder)) return;
        if (!target.getSharedPreferences(PROCESS_CYCLE_PREFS, Context.MODE_PRIVATE)
                .getAll().isEmpty()) {
            throw new IOException("A process-cycle marker requires its exact recovery phase");
        }
        cleanSyntheticQueueItems(target, folder);
    }

    @Test public void interruptedRowKeepsItsCleanupReservationUntilRestartRemovesPartial()
            throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri folder = requireSelectedFixtureFolder(target);
        assertPersistedTreeGrant(target, folder);
        cleanSyntheticQueueItems(target, folder);
        assertFixtureFolderIsEmpty(target, folder);

        try (LocalDownloadServer server = new LocalDownloadServer(EXPECTED_FILE)) {
            DownloadQueueManager beforeInterruption = newDownloadQueueManagerWithDns(target,
                    loopbackFixtureDns());
            String id = "";
            DownloadQueueStore store = null;
            try {
                DownloadRequest request = request(server.url("download-origin.example"),
                        "recovery-" + UUID.randomUUID());
                id = beforeInterruption.enqueue(request, folder);
                // @After can now remove this synthetic row even when the expected RED assertion
                // terminates the method before the normal retry path is reached.
                store = new DownloadQueueStore(target);
                assertTrue("new test item should be claimed for the interrupted state",
                        store.claimQueued(id));

                String partialName = ".orbit-part-" + id;
                Uri root = DocumentsContract.buildDocumentUriUsingTree(folder,
                        DocumentsContract.getTreeDocumentId(folder));
                Uri partial = DocumentsContract.createDocument(target.getContentResolver(), root,
                        "video/mp4", partialName);
                assertNotNull("the selected SAF fixture should create a partial file", partial);
                try (OutputStream output = target.getContentResolver().openOutputStream(partial, "wt")) {
                    assertNotNull(output);
                    output.write(PARTIAL_BYTES);
                }

                store.setPartialReservation(id, partialName);
                store.setPartialUri(id, partial.toString());
                store.updateProgress(id, 37L, 123L);

                // Model the persistent boundary after the old recovery method's first DB update,
                // before the process reaches its SAF delete call.
                List<DownloadQueueStore.InterruptedFile> interrupted = store.recoverInterrupted();
                assertEquals(1, interrupted.size());
                DownloadQueueRecordSnapshot recoveredState = snapshot(store, id);
                assertEquals("DOWNLOADING", recoveredState.status);
                assertEquals(partial.toString(), recoveredState.partialUri);
                assertEquals(partialName, recoveredState.partialName);
                assertEquals("interrupted progress must restart from byte zero", 0L,
                        recoveredState.bytesDownloaded);
                assertEquals("interrupted total must restart from byte zero", 0L,
                        recoveredState.totalBytes);

                store.close();
                store = null;
                beforeInterruption.close();

                // A new manager stands in for the next ordinary application startup.
                DownloadQueueManager afterInterruption = newDownloadQueueManagerWithDns(target,
                        loopbackFixtureDns());
                try {
                    DownloadQueueRecordSnapshot cleaned = snapshot(target, id);
                    assertEquals(DownloadRecord.PAUSED, cleaned.status);
                    assertEquals("", cleaned.partialUri);
                    assertEquals("", cleaned.partialName);
                    assertEquals("the orphan must be removed before Retry begins",
                            null, documentIdForName(target, folder, partialName));
                    assertFalse("the partial document must be deleted before Retry begins",
                            documentExists(target, folder, partialName));

                    afterInterruption.retry(id);
                    DownloadRecord complete = awaitStatus(afterInterruption, id,
                            Collections.singletonList(DownloadRecord.COMPLETED), 8_000L);
                    assertEquals(DownloadRecord.COMPLETED, complete.status);
                    String finalName = complete.fileName;
                    String finalId = documentIdForName(target, folder, finalName);
                    assertNotNull("the successful retry should leave one finalized SAF document", finalId);
                    Uri finalUri = DocumentsContract.buildDocumentUriUsingTree(folder, finalId);
                    assertArrayEquals(EXPECTED_FILE, readDocument(target, finalUri));
                } finally {
                    afterInterruption.close();
                }
            } finally {
                if (store != null) store.close();
                beforeInterruption.close();
            }
        }
    }

    @Test public void interruptedRemovalKeepsCompletedFileReferenceAndSize() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri folder = requireSelectedFixtureFolder(target);
        assertPersistedTreeGrant(target, folder);
        cleanSyntheticQueueItems(target, folder);
        assertFixtureFolderIsEmpty(target, folder);

        DownloadQueueManager manager = new DownloadQueueManager(target);
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            String stableId = "removing-" + UUID.randomUUID();
            String id = manager.enqueue(request("http://127.0.0.1:9/removal.mp4", stableId), folder);
            DownloadRecord queued = store.get(id);
            assertNotNull(queued);

            Uri root = DocumentsContract.buildDocumentUriUsingTree(folder,
                    DocumentsContract.getTreeDocumentId(folder));
            Uri completedFile = DocumentsContract.createDocument(target.getContentResolver(), root,
                    queued.mimeType, queued.fileName);
            assertNotNull("the selected SAF fixture should create the completed test file", completedFile);
            assertEquals("the final synthetic file name should be preserved by the fixture folder",
                    queued.fileName, queryDisplayName(target, completedFile));
            try (OutputStream output = target.getContentResolver().openOutputStream(completedFile, "wt")) {
                assertNotNull(output);
                output.write(EXPECTED_FILE);
            }

            long completedBytes = EXPECTED_FILE.length;
            store.markComplete(id, completedFile.toString(), completedBytes);
            store.setRemovalPending(id);

            List<DownloadQueueStore.InterruptedFile> interrupted = store.recoverInterrupted();
            assertEquals(1, interrupted.size());
            DownloadQueueRecordSnapshot recovered = snapshot(store, id);
            assertEquals(DownloadRecord.REMOVING, recovered.status);
            assertEquals("startup reconciliation must retain the completed-file URI",
                    completedFile.toString(), recovered.fileUri);
            assertEquals("a pending removal must keep the completed file size", completedBytes,
                    recovered.bytesDownloaded);
            assertEquals("a pending removal must keep the completed total size", completedBytes,
                    recovered.totalBytes);
        } finally {
            store.close();
            manager.close();
        }
    }

    @Test public void prepareCompletedRemovalForProcessRestart() throws Exception {
        String cycleNonce = requireCompletedRemovalPhase(COMPLETED_REMOVAL_PREPARE_PHASE);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("completed-removal prepare requires the production startup gate to be absent",
                productionApplicationGate(target).exists());
        assertEquals("prepare must run under the isolated bare Application",
                Application.class.getName(), target.getApplicationContext().getClass().getName());
        Uri tree = requireSelectedFixtureFolder(target);
        assertPersistedTreeGrant(target, tree);
        assertEquals("prepare must keep the exact selected Downloads tree",
                tree.toString(), selectedFixtureFolderOrNull(target).toString());

        android.content.SharedPreferences marker = target.getSharedPreferences(
                COMPLETED_REMOVAL_PREFS, Context.MODE_PRIVATE);
        assertTrue("refusing to overwrite an earlier completed-removal marker",
                marker.getAll().isEmpty());
        assertNoStartupRecoveryEligibleRows(target);
        assertNoQueueRowsForFixtureTree(target, tree, null);
        List<FixtureDocument> initialDocuments = listDocuments(target, tree);
        String originalInventory = encodeFixtureInventory(initialDocuments,
                Collections.<String>emptySet());
        assertTrue("the dedicated completed-removal fixture tree must be empty before setup",
                originalInventory.isEmpty());
        String originalQueue = completedRemovalQueueSnapshot(target, null);
        String originalPreferences = completedRemovalPreferenceSnapshot(target);

        String stableId = "completed-removal-" + UUID.randomUUID();
        assertNoQueueStableItemId(target, stableId);
        String requestedName = COMPLETED_REMOVAL_FILE_PREFIX + stableId + ".mp4";
        String sentinelName = COMPLETED_REMOVAL_FILE_PREFIX + "Sentinel-" + cycleNonce + ".txt";
        assertFalse("the nonce sentinel name must be unique in the fixture tree",
                documentExists(target, tree, sentinelName));
        assertFalse("the requested output name must be absent before setup",
                documentExists(target, tree, requestedName));
        boolean markerSaved = marker.edit()
                .putString(COMPLETED_REMOVAL_NONCE, cycleNonce)
                .putString(COMPLETED_REMOVAL_TREE_URI, tree.toString())
                .putString(COMPLETED_REMOVAL_STABLE_ID, stableId)
                .putString(COMPLETED_REMOVAL_REQUESTED_NAME, requestedName)
                .putString(COMPLETED_REMOVAL_SENTINEL_NAME, sentinelName)
                .putString(COMPLETED_REMOVAL_SENTINEL_SHA256,
                        sha256Hex(COMPLETED_REMOVAL_SENTINEL_BYTES))
                .putString(COMPLETED_REMOVAL_ORIGINAL_INVENTORY, originalInventory)
                .putString(COMPLETED_REMOVAL_ORIGINAL_QUEUE, originalQueue)
                .putString(COMPLETED_REMOVAL_ORIGINAL_PREFERENCES, originalPreferences)
                .putBoolean(COMPLETED_REMOVAL_REMOVING_INTENT, false)
                .putBoolean(COMPLETED_REMOVAL_CLEANUP_STARTED, false)
                .commit();
        assertTrue("completed-removal identity and baseline snapshots must be durable first",
                markerSaved);

        Uri treeRoot = DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        Uri sentinel = DocumentsContract.createDocument(target.getContentResolver(), treeRoot,
                "text/plain", sentinelName);
        assertNotNull("the fixture tree must create the adjacent sentinel", sentinel);
        String sentinelId = DocumentsContract.getDocumentId(sentinel);
        assertTrue("persist the exact sentinel ID before writing its contents",
                marker.edit().putString(COMPLETED_REMOVAL_SENTINEL_DOCUMENT_ID,
                        sentinelId).commit());
        try (OutputStream output = target.getContentResolver().openOutputStream(sentinel, "wt")) {
            assertNotNull(output);
            output.write(COMPLETED_REMOVAL_SENTINEL_BYTES);
        }
        assertArrayEquals("the fixture sentinel must be byte-exact before manager setup",
                COMPLETED_REMOVAL_SENTINEL_BYTES, readDocument(target, sentinel));

        DownloadQueueManager manager = newDownloadQueueManagerWithDns(target,
                loopbackFixtureDnsForLiteralAddress());
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            DownloadRequest request = new DownloadRequest(COMPLETED_REMOVAL_PROFILE, stableId,
                    COMPLETED_REMOVAL_SOURCE_ID, COMPLETED_REMOVAL_TITLE, requestedName,
                    "video/mp4", "http://127.0.0.1:9/completed-removal.mp4", true);
            String id = manager.enqueue(request, tree);
            String actualName = processCycleUniqueFileName(requestedName, id);
            assertTrue("persist row ID and manager-generated name before creating the output",
                    marker.edit().putString(COMPLETED_REMOVAL_ROW_ID, id)
                            .putString(COMPLETED_REMOVAL_FILE_NAME, actualName).commit());
            DownloadRecord queued = store.get(id);
            assertNotNull("the manager-created test row must be persisted", queued);
            assertTrue("the manager-generated row must match the exact cycle identity",
                    matchesCompletedRemovalRow(queued, stableId, id, tree, actualName));
            assertEquals("the row must remain queued until the completed fixture is written",
                    DownloadRecord.QUEUED, queued.status);
            assertFalse("the unique manager-generated name must not exist before file creation",
                    documentExists(target, tree, actualName));

            Uri completedFile = DocumentsContract.createDocument(target.getContentResolver(),
                    treeRoot, queued.mimeType, actualName);
            assertNotNull("the ExternalStorageProvider must create the exact completed file",
                    completedFile);
            String fileId = DocumentsContract.getDocumentId(completedFile);
            Uri exactFileUri = DocumentsContract.buildDocumentUriUsingTree(tree, fileId);
            assertEquals("the provider's output must be the canonical exact-tree child URI",
                    exactFileUri, completedFile);
            assertEquals("the created output must keep the manager-generated name", actualName,
                    queryDisplayName(target, exactFileUri));
            String fileSha256 = sha256Hex(COMPLETED_REMOVAL_FILE_BYTES);
            assertTrue("persist exact completed file URI, document ID, and digest before REMOVING",
                    marker.edit().putString(COMPLETED_REMOVAL_FILE_URI,
                                    exactFileUri.toString())
                            .putString(COMPLETED_REMOVAL_FILE_DOCUMENT_ID, fileId)
                            .putString(COMPLETED_REMOVAL_FILE_SHA256, fileSha256)
                            .commit());
            try (OutputStream output = target.getContentResolver()
                    .openOutputStream(exactFileUri, "wt")) {
                assertNotNull(output);
                output.write(COMPLETED_REMOVAL_FILE_BYTES);
            }
            byte[] writtenFile = readDocument(target, exactFileUri);
            assertArrayEquals("the completed test file must contain the exact known payload",
                    COMPLETED_REMOVAL_FILE_BYTES, writtenFile);
            assertEquals("the exact output payload hash must match the durable marker",
                    fileSha256, sha256Hex(writtenFile));
            store.markComplete(id, exactFileUri.toString(), writtenFile.length);
            DownloadRecord completed = store.get(id);
            DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(id);
            assertNotNull(completed);
            assertNotNull(retained);
            assertEquals(DownloadRecord.COMPLETED, completed.status);
            assertEquals(actualName, completed.fileName);
            assertEquals(tree.toString(), completed.folderUri);
            assertEquals(exactFileUri.toString(), completed.fileUri);
            assertEquals(writtenFile.length, completed.bytesDownloaded);
            assertEquals(writtenFile.length, completed.totalBytes);
            assertEquals("the completed row must not retain a partial URI", "",
                    retained.partialUri);
            assertEquals("the completed row must not retain a partial reservation", "",
                    retained.partialName);
            assertEquals(exactFileUri.toString(), retained.fileUri);
            assertEquals(actualName, retained.fileName);

            assertTrue("the exact completed-file identity must remain durable before REMOVING",
                    marker.getString(COMPLETED_REMOVAL_ROW_ID, null).equals(id)
                            && marker.getString(COMPLETED_REMOVAL_FILE_URI, null)
                                    .equals(exactFileUri.toString())
                            && marker.getString(COMPLETED_REMOVAL_FILE_DOCUMENT_ID, null)
                                    .equals(fileId)
                            && marker.getString(COMPLETED_REMOVAL_FILE_SHA256, null)
                                    .equals(fileSha256));
            assertTrue("persist removal intent before the REMOVING status update",
                    marker.edit().putBoolean(COMPLETED_REMOVAL_REMOVING_INTENT, true).commit());
            assertTrue("the completed row must enter the startup-recovery REMOVING state",
                    store.setRemovalPending(id));
            DownloadRecord removing = store.get(id);
            assertNotNull(removing);
            assertEquals(DownloadRecord.REMOVING, removing.status);
            assertEquals(exactFileUri.toString(), removing.fileUri);
            assertEquals(writtenFile.length, removing.bytesDownloaded);
            assertEquals(writtenFile.length, removing.totalBytes);
            assertTrue("the exact completed file must still exist before process restart",
                    documentIdExists(target, tree, fileId));
            assertArrayEquals("the completed file must remain byte-exact before process restart",
                    COMPLETED_REMOVAL_FILE_BYTES, readDocument(target, exactFileUri));
            assertCompletedRemovalSentinel(target, tree, marker);
            assertCompletedRemovalOriginalNeighborsUnchanged(target, tree, marker, id);
            completedRemovalFixturePreparedInThisRun = true;
        } finally {
            store.close();
            manager.close();
        }
    }

    @Test public void verifyCompletedRemovalAfterProcessRestart() throws Exception {
        String cycleNonce = requireCompletedRemovalPhase(COMPLETED_REMOVAL_VERIFY_PHASE);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertProductionApplicationGate(target, cycleNonce);
        Uri tree = requireSelectedFixtureFolder(target);
        assertPersistedTreeGrant(target, tree);
        assertEquals("normal cold start must keep the exact selected Downloads tree",
                tree.toString(), selectedFixtureFolderOrNull(target).toString());
        android.content.SharedPreferences marker = target.getSharedPreferences(
                COMPLETED_REMOVAL_PREFS, Context.MODE_PRIVATE);
        assertCompletedRemovalMarkerIdentity(marker, cycleNonce, tree);
        assertTrue("the marker must record that the exact row entered REMOVING",
                marker.getBoolean(COMPLETED_REMOVAL_REMOVING_INTENT, false));
        String rowId = marker.getString(COMPLETED_REMOVAL_ROW_ID, null);
        String fileName = marker.getString(COMPLETED_REMOVAL_FILE_NAME, null);
        String fileId = marker.getString(COMPLETED_REMOVAL_FILE_DOCUMENT_ID, null);
        String fileUri = marker.getString(COMPLETED_REMOVAL_FILE_URI, null);
        assertEquals("the completed-file marker must carry the expected payload hash",
                sha256Hex(COMPLETED_REMOVAL_FILE_BYTES),
                marker.getString(COMPLETED_REMOVAL_FILE_SHA256, null));
        assertNotNull("the completed-removal fixture must retain the exact row ID", rowId);
        assertNotNull("the completed-removal fixture must retain the exact file name", fileName);
        assertNotNull("the completed-removal fixture must retain the exact document ID", fileId);
        assertNotNull("the completed-removal fixture must retain the exact file URI", fileUri);
        assertEquals("the saved output URI must be the canonical exact-tree child",
                DocumentsContract.buildDocumentUriUsingTree(tree, fileId).toString(), fileUri);
        assertEquals("the manager filename must derive from the exact row ID",
                processCycleUniqueFileName(marker.getString(COMPLETED_REMOVAL_REQUESTED_NAME,
                        null), rowId), fileName);

        assertTrue("verification must use the normally launched production OrbitApplication",
                target.getApplicationContext() instanceof OrbitApplication);
        OrbitApplication application = (OrbitApplication) target.getApplicationContext();
        DownloadQueueManager manager = application.getDownloadQueueManager();
        assertNotNull("OrbitApplication must own its production DownloadQueueManager", manager);
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            assertEquals("startup recovery must delete the exact REMOVING row after file cleanup",
                    null, store.get(rowId));
            assertTrue("startup recovery must not introduce a pending rename journal",
                    store.getPendingRenames().isEmpty());
            for (DownloadRecord row : store.listAll()) {
                assertFalse("startup must not retain another row with the test stable ID",
                        marker.getString(COMPLETED_REMOVAL_STABLE_ID, null)
                                .equals(row.stableItemId));
            }
            assertFalse("startup must remove the exact completed document ID",
                    documentIdExists(target, tree, fileId));
            assertFalse("startup must remove the exact completed display name",
                    documentExists(target, tree, fileName));
            assertCompletedRemovalSentinel(target, tree, marker);
            assertCompletedRemovalOriginalNeighborsUnchanged(target, tree, marker, rowId);
            assertEquals("normal startup must preserve all unrelated queue rows byte-for-byte",
                    marker.getString(COMPLETED_REMOVAL_ORIGINAL_QUEUE, null),
                    completedRemovalQueueSnapshot(target, rowId));
            assertEquals("normal startup must preserve user profile/provider/Downloads preferences",
                    marker.getString(COMPLETED_REMOVAL_ORIGINAL_PREFERENCES, null),
                    completedRemovalPreferenceSnapshot(target));
            assertNoQueueRowsForFixtureTree(target, tree, null);
        } finally {
            store.close();
        }
        completedRemovalRecoveryValidated = true;
    }

    @Test public void recoverFailedCompletedRemovalPrepare() throws Exception {
        String cycleNonce = requireCompletedRemovalPhase(COMPLETED_REMOVAL_RECOVER_PHASE);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("exact completed-removal recovery must not launch Orbit through its gate",
                productionApplicationGate(target).exists());
        assertEquals("exact recovery must use the isolated bare Application",
                Application.class.getName(), target.getApplicationContext().getClass().getName());
        Uri tree = requireSelectedFixtureFolder(target);
        assertPersistedTreeGrant(target, tree);
        android.content.SharedPreferences marker = target.getSharedPreferences(
                COMPLETED_REMOVAL_PREFS, Context.MODE_PRIVATE);
        assertCompletedRemovalMarkerIdentity(marker, cycleNonce, tree);
        validateCompletedRemovalFixtureForCleanup(target, marker, tree);
        completedRemovalRecoveryValidated = true;
        cleanupCompletedRemovalFixture(target);
        completedRemovalFixtureCleaned = true;
        assertTrue("recovery may clear the marker only after exact row/file/sentinel cleanup",
                marker.getAll().isEmpty());
    }

    @Test public void prepareInterruptedDownloadForProcessRestart() throws Exception {
        String cycleNonce = requireProcessDeathPhase(PROCESS_CYCLE_PREPARE_PHASE);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri folder = requireSelectedFixtureFolder(target);
        assertPersistedTreeGrant(target, folder);
        assertEquals("the selected Downloads preference must remain the exact fixture tree",
                folder.toString(), selectedFixtureFolderOrNull(target).toString());
        android.content.SharedPreferences marker = target.getSharedPreferences(
                PROCESS_CYCLE_PREFS, Context.MODE_PRIVATE);
        assertTrue("refusing to overwrite an earlier interrupted-process fixture marker",
                marker.getAll().isEmpty());
        assertNoStartupRecoveryEligibleRows(target);
        assertNoQueueRowsForFixtureTree(target, folder, null);

        String stableId = "process-death-" + UUID.randomUUID();
        String requestedFinalName = TEST_FILE_PREFIX + stableId + ".mp4";
        String sentinelName = TEST_FILE_PREFIX + "Sentinel-" + cycleNonce + ".txt";
        assertFalse("the unique sentinel name must not already exist in the fixture tree",
                documentExists(target, folder, sentinelName));
        assertFalse("the unique final output name must not already exist in the fixture tree",
                documentExists(target, folder, requestedFinalName));
        String originalInventory = encodeFixtureInventory(listDocuments(target, folder),
                Collections.<String>emptySet());
        assertTrue("the dedicated process-cycle fixture folder must be empty before setup",
                originalInventory.isEmpty());
        int serverPort = reserveLoopbackPort();
        boolean markerSaved = marker.edit()
                .putString(PROCESS_CYCLE_CYCLE_NONCE, cycleNonce)
                .putString(PROCESS_CYCLE_TREE_URI, folder.toString())
                .putString(PROCESS_CYCLE_STABLE_ITEM_ID, stableId)
                .putString(PROCESS_CYCLE_SERVER_PORT, Integer.toString(serverPort))
                .putString(PROCESS_CYCLE_FINAL_NAME, requestedFinalName)
                .putString(PROCESS_CYCLE_SENTINEL_NAME, sentinelName)
                .putString(PROCESS_CYCLE_ORIGINAL_INVENTORY, originalInventory)
                .commit();
        assertTrue("the recovery fixture identity and original neighbor inventory must be durable",
                markerSaved);

        Uri root = DocumentsContract.buildDocumentUriUsingTree(folder,
                DocumentsContract.getTreeDocumentId(folder));
        Uri sentinel = DocumentsContract.createDocument(target.getContentResolver(), root,
                "text/plain", sentinelName);
        assertNotNull("the fixture folder must create its adjacent sentinel", sentinel);
        try (OutputStream output = target.getContentResolver().openOutputStream(sentinel, "wt")) {
            assertNotNull(output);
            output.write(PROCESS_CYCLE_SENTINEL_BYTES);
        }
        String sentinelDocumentId = DocumentsContract.getDocumentId(sentinel);
        assertTrue("the exact sentinel document ID must be saved before row setup",
                marker.edit().putString(PROCESS_CYCLE_SENTINEL_DOCUMENT_ID,
                        sentinelDocumentId).commit());

        String sourceUrl = "http://127.0.0.1:" + serverPort + "/process-restart.mp4";
        DownloadQueueManager manager = newDownloadQueueManagerWithDns(target,
                loopbackFixtureDnsForLiteralAddress());
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            String id = manager.enqueue(request(sourceUrl, stableId), folder);
            String partialName = ".orbit-part-" + id;
            String actualFinalName = processCycleUniqueFileName(requestedFinalName, id);
            assertTrue("the exact row, unique final name, and reservation must be durable before setup",
                    marker.edit().putString(PROCESS_CYCLE_ROW_ID, id)
                            .putString(PROCESS_CYCLE_PARTIAL_NAME, partialName)
                            .putString(PROCESS_CYCLE_FINAL_NAME, actualFinalName).commit());
            DownloadRecord enqueued = store.get(id);
            assertNotNull("the exact enqueued queue row must be durable", enqueued);
            assertTrue("the queue row filename must equal the manager's deterministic unique name",
                    matchesProcessCycleRow(enqueued, stableId, folder.toString(), actualFinalName));
            assertEquals("the row must still be queued before its partial is created",
                    DownloadRecord.QUEUED, enqueued.status);
            assertFalse("the unique final output name must not already exist in the fixture tree",
                    documentExists(target, folder, actualFinalName));
            assertTrue("the test row should enter the interrupted download state", store.claimQueued(id));

            Uri partial = DocumentsContract.createDocument(target.getContentResolver(), root,
                    "video/mp4", partialName);
            assertNotNull("the partial SAF file must exist before process restart", partial);
            try (OutputStream output = target.getContentResolver().openOutputStream(partial, "wt")) {
                assertNotNull(output);
                output.write(PROCESS_CYCLE_PARTIAL_BYTES);
            }
            String partialDocumentId = DocumentsContract.getDocumentId(partial);
            assertTrue("the exact partial document ID must be durable before interruption",
                    marker.edit().putString(PROCESS_CYCLE_PARTIAL_DOCUMENT_ID,
                            partialDocumentId).commit());

            store.setPartialReservation(id, partialName);
            store.setPartialUri(id, partial.toString());
            store.updateProgress(id, PROCESS_CYCLE_PARTIAL_BYTES.length, PROCESS_CYCLE_BODY.length);

            DownloadRecord staged = store.get(id);
            DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(id);
            assertNotNull(staged);
            assertNotNull(retained);
            assertEquals(TEST_PROFILE, staged.profileId);
            assertEquals(stableId, staged.stableItemId);
            assertEquals("download-test-source", staged.sourceId);
            assertEquals(actualFinalName, staged.fileName);
            assertEquals(DownloadRecord.DOWNLOADING, staged.status);
            assertEquals(PROCESS_CYCLE_PARTIAL_BYTES.length, staged.bytesDownloaded);
            assertEquals(PROCESS_CYCLE_BODY.length, staged.totalBytes);
            assertEquals(partial.toString(), retained.partialUri);
            assertEquals(partialName, retained.partialName);
            assertEquals(folder.toString(), retained.folderUri);
            FixtureDocument stagedFile = findPartialFixtureDocument(target, folder, id);
            assertNotNull("the partial SAF file must exist before process restart", stagedFile);
            assertArrayEquals("the interrupted partial must be a byte-exact prefix of the local source",
                    PROCESS_CYCLE_PARTIAL_BYTES, readDocument(target,
                            DocumentsContract.buildDocumentUriUsingTree(folder, stagedFile.id)));
            assertSentinelDocument(target, folder, marker);
            assertOriginalFixtureNeighborsUnchanged(target, folder, marker,
                    processCycleOwnedNames(marker, id));
            processDeathFixturePreparedInThisRun = true;
        } finally {
            store.close();
            manager.close();
        }
    }

    @Test public void recoverFailedInterruptedDownloadPrepare() throws Exception {
        String cycleNonce = requireProcessDeathPhase(PROCESS_CYCLE_RECOVER_PHASE);
        Bundle args = InstrumentationRegistry.getArguments();
        String expectedRowId = args.getString(PROCESS_CYCLE_RECOVERY_ROW_ID_ARG);
        String expectedStableId = args.getString(PROCESS_CYCLE_RECOVERY_STABLE_ITEM_ID_ARG);
        assertNotNull("exact failed-prepare row ID is required for recovery", expectedRowId);
        assertNotNull("exact failed-prepare stable item ID is required for recovery",
                expectedStableId);
        assertEquals("the supplied row ID must be canonical UUID text", expectedRowId,
                UUID.fromString(expectedRowId).toString());
        assertTrue("the supplied stable ID must be the process-death fixture identity",
                expectedStableId.startsWith("process-death-")
                        && UUID.fromString(expectedStableId.substring("process-death-".length()))
                                .toString().equals(expectedStableId.substring(
                                        "process-death-".length())));

        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("recovery must not start with the production-Application gate present",
                productionApplicationGate(target).exists());
        assertEquals("recovery must run with the isolated bare Application",
                Application.class.getName(), target.getApplicationContext().getClass().getName());

        Uri folder = requireSelectedFixtureFolder(target);
        assertPersistedTreeGrant(target, folder);
        assertEquals("the selected Downloads preference must remain the exact fixture tree",
                folder.toString(), selectedFixtureFolderOrNull(target).toString());
        android.content.SharedPreferences marker = target.getSharedPreferences(
                PROCESS_CYCLE_PREFS, Context.MODE_PRIVATE);
        assertEquals("recovery must match the exact failed-prepare nonce", cycleNonce,
                marker.getString(PROCESS_CYCLE_CYCLE_NONCE, null));
        assertEquals("recovery must use the exact ExternalStorageProvider fixture tree",
                folder.toString(), marker.getString(PROCESS_CYCLE_TREE_URI, null));
        assertEquals("recovery must match the exact failed-prepare row ID", expectedRowId,
                marker.getString(PROCESS_CYCLE_ROW_ID, null));
        assertEquals("recovery must match the exact failed-prepare stable item ID", expectedStableId,
                marker.getString(PROCESS_CYCLE_STABLE_ITEM_ID, null));
        assertEquals("the failed fixture must have started with an empty folder inventory", "",
                marker.getString(PROCESS_CYCLE_ORIGINAL_INVENTORY, null));

        // A crash before enqueue identity was committed leaves this check unsatisfied. Keep that
        // boundary fail-closed; do not infer a row or delete documents by stable ID alone.
        String requestedFinalName = TEST_FILE_PREFIX + expectedStableId + ".mp4";
        String expectedActualFinalName = processCycleUniqueFileName(requestedFinalName,
                expectedRowId);
        String recordedFinalName = marker.getString(PROCESS_CYCLE_FINAL_NAME, null);
        assertTrue("the marker must contain the requested base name or its verified unique name",
                requestedFinalName.equals(recordedFinalName)
                        || expectedActualFinalName.equals(recordedFinalName));
        int serverPort = parseProcessCycleServerPort(marker);
        assertTrue("the failed fixture must retain its exact local loopback port",
                serverPort > 0 && serverPort <= 65535);
        String expectedPartialName = ".orbit-part-" + expectedRowId;
        assertEquals("the reservation name must derive from the exact failed row ID",
                expectedPartialName, marker.getString(PROCESS_CYCLE_PARTIAL_NAME, null));
        String partialDocumentId = marker.getString(PROCESS_CYCLE_PARTIAL_DOCUMENT_ID, null);
        assertNotNull("the failed fixture must retain its exact partial document ID",
                partialDocumentId);
        Uri partialUri = DocumentsContract.buildDocumentUriUsingTree(folder, partialDocumentId);
        String expectedPartialUri = partialUri.toString();

        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            DownloadRecord row = store.get(expectedRowId);
            DownloadQueueStore.RetainedDocuments retained =
                    store.getRetainedDocuments(expectedRowId);
            assertNotNull("the exact failed-prepare queue row must still exist", row);
            assertNotNull("the exact failed-prepare row must retain its SAF reservation", retained);
            assertTrue("the row must have the expected deterministic unique output name",
                    matchesProcessCycleRow(row, expectedStableId, folder.toString(),
                            expectedActualFinalName));
            assertEquals("the failed fixture row must still be interrupted", DownloadRecord.DOWNLOADING,
                    row.status);
            assertEquals("the row must retain exactly the partial byte count",
                    PROCESS_CYCLE_PARTIAL_BYTES.length, row.bytesDownloaded);
            assertEquals("the row must retain the exact full response length",
                    PROCESS_CYCLE_BODY.length, row.totalBytes);
            assertEquals("the exact partial reservation name must remain in the queue row",
                    expectedPartialName, retained.partialName);
            assertEquals("the exact partial URI must remain in the queue row",
                    expectedPartialUri, retained.partialUri);
            assertEquals("the retained folder must remain the exact selected tree",
                    folder.toString(), retained.folderUri);
            assertEquals("the row must retain the deterministic unique final name",
                    expectedActualFinalName, retained.fileName);
            assertEquals("the queue row must not point to a final output URI", "", row.fileUri);
            assertEquals("the interrupted row must not own a final output document", "",
                    retained.fileUri);
            int stableIdRows = 0;
            for (DownloadRecord item : store.listAll()) {
                if (!expectedStableId.equals(item.stableItemId)) continue;
                stableIdRows++;
                assertEquals("the stable ID must identify only the exact failed row",
                        expectedRowId, item.id);
            }
            assertEquals("exactly one queue row must carry the failed fixture stable ID", 1,
                    stableIdRows);
        } finally {
            store.close();
        }
        assertNoOtherStartupRecoveryEligibleRows(target, expectedRowId);
        assertNoQueueRowsForFixtureTree(target, folder, expectedRowId);

        String sentinelName = marker.getString(PROCESS_CYCLE_SENTINEL_NAME, null);
        String sentinelDocumentId = marker.getString(PROCESS_CYCLE_SENTINEL_DOCUMENT_ID, null);
        assertNotNull("the failed fixture must retain its exact neighboring sentinel name",
                sentinelName);
        assertNotNull("the failed fixture must retain its exact neighboring sentinel ID",
                sentinelDocumentId);
        assertFalse("the requested output name must have no final document",
                documentExists(target, folder, requestedFinalName));
        assertFalse("the deterministic unique output name must have no final document",
                documentExists(target, folder, expectedActualFinalName));

        List<FixtureDocument> currentDocuments = listDocuments(target, folder);
        assertEquals("the failed fixture folder must contain only its marked partial and sentinel",
                2, currentDocuments.size());
        FixtureDocument exactPartial = null;
        FixtureDocument exactSentinel = null;
        for (FixtureDocument document : currentDocuments) {
            if (partialDocumentId.equals(document.id)) exactPartial = document;
            if (sentinelDocumentId.equals(document.id)) exactSentinel = document;
        }
        assertNotNull("the exact marked partial child must exist", exactPartial);
        assertNotNull("the exact marked sentinel child must exist", exactSentinel);
        assertTrue("the partial child must use the exact reservation name or MediaProvider suffix",
                expectedPartialName.equals(exactPartial.displayName)
                        || (expectedPartialName + ".mp4").equals(exactPartial.displayName));
        assertEquals("the neighboring sentinel name must match exactly", sentinelName,
                exactSentinel.displayName);
        assertArrayEquals("the exact partial child must contain only the prepared byte prefix",
                PROCESS_CYCLE_PARTIAL_BYTES, readDocument(target, partialUri));
        assertArrayEquals("the exact sentinel child must retain its prepared bytes",
                PROCESS_CYCLE_SENTINEL_BYTES, readDocument(target,
                        DocumentsContract.buildDocumentUriUsingTree(folder, sentinelDocumentId)));
        assertOriginalFixtureNeighborsUnchanged(target, folder, marker,
                processCycleOwnedNames(marker, expectedRowId));

        assertTrue("the verified actual unique filename must be committed before cleanup",
                marker.edit().putString(PROCESS_CYCLE_FINAL_NAME,
                        expectedActualFinalName).commit());
        processDeathRecoveryValidated = true;
        cleanupInterruptedProcessCycleFixture(target);

        assertTrue("the exact failed-prepare marker must be cleared after recovery",
                marker.getAll().isEmpty());
        assertEquals("recovery must restore the exact original fixture inventory", "",
                encodeFixtureInventory(listDocuments(target, folder), Collections.<String>emptySet()));
        assertFalse("the exact partial document must be absent after recovery",
                documentIdExists(target, folder, partialDocumentId));
        assertFalse("the exact sentinel document must be absent after recovery",
                documentIdExists(target, folder, sentinelDocumentId));
        assertFalse("the requested output must remain absent after recovery",
                documentExists(target, folder, requestedFinalName));
        assertFalse("the unique output must remain absent after recovery",
                documentExists(target, folder, expectedActualFinalName));
        DownloadQueueStore verificationStore = new DownloadQueueStore(target);
        try {
            assertEquals("the exact failed-prepare row must be removed", null,
                    verificationStore.get(expectedRowId));
            for (DownloadRecord item : verificationStore.listAll()) {
                assertFalse("the failed-prepare stable ID must have no remaining row",
                        expectedStableId.equals(item.stableItemId));
            }
        } finally {
            verificationStore.close();
        }
    }

    @Test public void verifyInterruptedDownloadAfterProcessRestart() throws Exception {
        String cycleNonce = requireProcessDeathPhase(PROCESS_CYCLE_VERIFY_PHASE);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri folder = requireSelectedFixtureFolder(target);
        assertPersistedTreeGrant(target, folder);
        assertProductionApplicationGate(target, cycleNonce);
        assertEquals("normal cold start must preserve the selected Downloads tree",
                folder.toString(), selectedFixtureFolderOrNull(target).toString());
        android.content.SharedPreferences marker = target.getSharedPreferences(
                PROCESS_CYCLE_PREFS, Context.MODE_PRIVATE);
        assertEquals("verification must match the prepare cycle nonce", cycleNonce,
                marker.getString(PROCESS_CYCLE_CYCLE_NONCE, null));
        assertEquals("verification must use the exact selected fixture tree", folder.toString(),
                marker.getString(PROCESS_CYCLE_TREE_URI, null));
        String id = marker.getString(PROCESS_CYCLE_ROW_ID, null);
        assertNotNull("run the prepare phase and normally relaunch Orbit before verification", id);
        String stableId = marker.getString(PROCESS_CYCLE_STABLE_ITEM_ID, null);
        String partialName = marker.getString(PROCESS_CYCLE_PARTIAL_NAME, null);
        String partialDocumentId = marker.getString(PROCESS_CYCLE_PARTIAL_DOCUMENT_ID, null);
        String finalName = marker.getString(PROCESS_CYCLE_FINAL_NAME, null);
        String expectedFinalName = processCycleUniqueFileName(
                TEST_FILE_PREFIX + stableId + ".mp4", id);
        int serverPort = parseProcessCycleServerPort(marker);
        assertEquals("the interrupted reservation must derive from the exact row ID",
                ".orbit-part-" + id, partialName);
        assertNotNull("the prepared fixture must record its exact partial document ID",
                partialDocumentId);
        assertEquals("the output filename must use the exact manager-generated unique name",
                expectedFinalName, finalName);
        assertTrue("the prepared local server port must be valid", serverPort > 0
                && serverPort <= 65535);

        assertTrue("verification must use the normally launched production OrbitApplication",
                target.getApplicationContext() instanceof OrbitApplication);
        OrbitApplication application = (OrbitApplication) target.getApplicationContext();
        DownloadQueueManager manager = application.getDownloadQueueManager();
        assertNotNull("OrbitApplication should own its production DownloadQueueManager", manager);

        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            DownloadRecord recovered = store.get(id);
            DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(id);
            assertNotNull("the interrupted queue row should remain available for Retry", recovered);
            assertNotNull(retained);
            assertEquals(TEST_PROFILE, recovered.profileId);
            assertEquals(stableId, recovered.stableItemId);
            assertEquals("download-test-source", recovered.sourceId);
            assertEquals(finalName, recovered.fileName);
            assertEquals(folder.toString(), recovered.folderUri);
            assertEquals(DownloadRecord.PAUSED, recovered.status);
            assertEquals(0L, recovered.bytesDownloaded);
            assertEquals(0L, recovered.totalBytes);
            assertEquals("", retained.partialUri);
            assertEquals("", retained.partialName);
            assertEquals("", retained.fileUri);
            assertFalse("normal startup must remove the exact reserved partial document",
                    documentIdExists(target, folder, partialDocumentId));
            assertEquals("normal startup must remove the exact partial display name", null,
                    findPartialFixtureDocument(target, folder, id));
            assertFalse("normal startup must not invent a completed test file",
                    documentExists(target, folder, finalName));
            assertSentinelDocument(target, folder, marker);
            assertOriginalFixtureNeighborsUnchanged(target, folder, marker,
                    processCycleOwnedNames(marker, id));
            assertNoQueueRowsForFixtureTree(target, folder, id);
        } finally {
            store.close();
        }

        Dns originalTestDns = setProductionManagerFixtureDns(manager);
        try (LocalDownloadServer server = new LocalDownloadServer(PROCESS_CYCLE_BODY, serverPort)) {
            assertNotNull("the recovered row must remain visible in the production manager",
                    findQueueRecord(manager, id));
            manager.retry(id);
            DownloadRecord completed = awaitStatus(manager, id,
                    Collections.singletonList(DownloadRecord.COMPLETED), 15_000L);
            assertNotNull("explicit production-manager retry must complete", completed);
            assertEquals(DownloadRecord.COMPLETED, completed.status);
            assertEquals("explicit retry must issue exactly one fresh request", 1,
                    server.requestCount());
            assertEquals("the retry must start at byte zero and send no Range header",
                    Collections.singletonList(""), server.rangeHeaders());
            assertTrue("the local loopback response must be served", server.awaitServed(1_000L));
            assertEquals(finalName, completed.fileName);
            assertEquals(folder.toString(), completed.folderUri);
            assertTrue("the completed document must stay within the exact fixture tree",
                    completed.fileUri != null && isWithinTree(Uri.parse(completed.fileUri), folder));
            assertArrayEquals("the final SAF output must equal the complete loopback source body",
                    PROCESS_CYCLE_BODY, readDocument(target, Uri.parse(completed.fileUri)));
            DownloadQueueStore.RetainedDocuments completedDocuments = retainedDocuments(target, id);
            assertNotNull(completedDocuments);
            assertEquals("retry completion must clear the partial URI", "",
                    completedDocuments.partialUri);
            assertEquals("retry completion must clear the partial-name reservation", "",
                    completedDocuments.partialName);
            assertEquals(completed.fileUri, completedDocuments.fileUri);
            assertFalse("the exact interrupted partial must remain absent after retry",
                    documentIdExists(target, folder, partialDocumentId));
            assertSentinelDocument(target, folder, marker);
            assertOriginalFixtureNeighborsUnchanged(target, folder, marker,
                    processCycleOwnedNames(marker, id));
            assertNoQueueRowsForFixtureTree(target, folder, id);
        } finally {
            restoreProductionManagerDns(manager, originalTestDns);
        }
    }
    @Test public void prepareSafDeleteFailureForColdStart() throws Exception {
        requireSafDeleteFailurePhase(SAF_DELETE_FAILURE_PREPARE_PHASE);
        String cycleNonce = requireSafDeleteFailureCycleNonce(
                InstrumentationRegistry.getArguments());
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri treeA = selectedQueueLifecycleTree(
                InstrumentationRegistry.getArguments().getString(QUEUE_TREE_A_ARG),
                QueueLifecycleDocumentsProvider.TREE_A);
        assertPersistedQueueTreeGrant(target, treeA);

        cleanupStaleSafDeleteFailureRows(target, treeA);
        QueueLifecycleDocumentsProvider.clearOutputWriteFailure(target, treeA);
        QueueLifecycleDocumentsProvider.setDeleteFailure(target, treeA, null);
        int serverPort = reserveLoopbackPort();
        String stableId = "cold-start-delete-failure-" + UUID.randomUUID();
        DownloadQueueManager manager = newDownloadQueueManagerWithDns(target,
                loopbackFixtureDnsForLiteralAddress());
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            String sourceUrl = "http://127.0.0.1:" + serverPort + "/synthetic.mp4";
            DownloadRequest request = new DownloadRequest(SAF_DELETE_FAILURE_PROFILE, stableId,
                    "download-test-source", "Cold start SAF cleanup fixture",
                    SAF_DELETE_FAILURE_FILE_PREFIX + stableId + ".mp4", "video/mp4",
                    sourceUrl, true);
            String id = manager.enqueue(request, treeA);
            safDeleteFailureRowIdPreparedInThisRun = id;
            String partialName = ".orbit-part-" + id;
            saveSafDeleteFailureMarker(target, cycleNonce, id, treeA, "", partialName,
                    serverPort);

            assertTrue("the synthetic queue row should be persisted as DOWNLOADING",
                    store.claimQueued(id, "saf-cold-start-" + id));
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeA,
                    DocumentsContract.getTreeDocumentId(treeA));
            Uri partial = DocumentsContract.createDocument(target.getContentResolver(), parent,
                    "video/mp4", partialName);
            assertNotNull("Tree A should create the synthetic partial document", partial);
            try (OutputStream output = target.getContentResolver().openOutputStream(partial, "wt")) {
                assertNotNull(output);
                output.write(PARTIAL_BYTES);
            }

            String partialDocumentId = DocumentsContract.getDocumentId(partial);
            store.setPartialReservation(id, partialName);
            store.setPartialUri(id, partial.toString());
            store.updateProgress(id, PARTIAL_BYTES.length, SAF_DELETE_FAILURE_BODY.length);
            saveSafDeleteFailureMarker(target, cycleNonce, id, treeA, partialDocumentId,
                    partialName, serverPort);
            QueueLifecycleDocumentsProvider.setDeleteFailure(target, treeA, partialDocumentId);

            DownloadRecord staged = store.get(id);
            DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(id);
            assertNotNull(staged);
            assertNotNull(retained);
            assertEquals(SAF_DELETE_FAILURE_PROFILE, staged.profileId);
            assertEquals(DownloadRecord.DOWNLOADING, staged.status);
            assertEquals(partial.toString(), retained.partialUri);
            assertEquals(partialName, retained.partialName);
            assertEquals(treeA.toString(), retained.folderUri);
            assertArrayEquals("the prepared Tree A partial should contain the known prefix",
                    PARTIAL_BYTES, readDocument(target, partial));
            assertTrue("the exact partial document should remain visible before process restart",
                    documentExists(target, treeA, partialName));
            safDeleteFailureFixturePreparedInThisRun = true;
        } finally {
            store.close();
            manager.close();
        }
    }

    @Test public void verifySafDeleteFailureAfterColdStart() throws Exception {
        requireSafDeleteFailurePhase(SAF_DELETE_FAILURE_VERIFY_PHASE);
        Bundle args = InstrumentationRegistry.getArguments();
        String cycleNonce = requireSafDeleteFailureCycleNonce(args);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertProductionApplicationGate(target, cycleNonce);
        Uri treeA = selectedQueueLifecycleTree(args.getString(QUEUE_TREE_A_ARG),
                QueueLifecycleDocumentsProvider.TREE_A);
        Uri treeB = selectedQueueLifecycleTree(args.getString(QUEUE_TREE_B_ARG),
                QueueLifecycleDocumentsProvider.TREE_B);
        assertPersistedQueueTreeGrant(target, treeA);
        assertPersistedQueueTreeGrant(target, treeB);

        android.content.SharedPreferences marker = target.getSharedPreferences(
                SAF_DELETE_FAILURE_PREFS, Context.MODE_PRIVATE);
        String id = marker.getString(SAF_DELETE_FAILURE_ROW_ID, null);
        assertNotNull("run prepare, stop both packages, then normally restart Orbit before verify", id);
        assertEquals("verification must match the host nonce saved by this prepare cycle",
                cycleNonce, marker.getString(SAF_DELETE_FAILURE_CYCLE_NONCE, null));
        assertEquals("the prepared marker must still name exact Tree A", treeA.toString(),
                marker.getString(SAF_DELETE_FAILURE_TREE_URI, null));
        String partialName = marker.getString(SAF_DELETE_FAILURE_PARTIAL_NAME, null);
        String partialDocumentId = marker.getString(SAF_DELETE_FAILURE_DOCUMENT_ID, null);
        int serverPort = marker.getInt(SAF_DELETE_FAILURE_SERVER_PORT, -1);
        assertEquals("the marker must retain the exact row reservation name", ".orbit-part-" + id,
                partialName);
        assertNotNull("the marker must retain the exact partial document ID", partialDocumentId);
        assertTrue("the prepared loopback port must be valid", serverPort > 0 && serverPort <= 65535);

        assertTrue("verification must use the production application instance",
                target.getApplicationContext() instanceof OrbitApplication);
        OrbitApplication application = (OrbitApplication) target.getApplicationContext();
        DownloadQueueManager manager = application.getDownloadQueueManager();
        assertNotNull("OrbitApplication should own its production DownloadQueueManager", manager);
        DownloadQueueStore store = new DownloadQueueStore(target);
        DownloadRecord recovered;
        DownloadQueueStore.RetainedDocuments retained;
        try {
            recovered = store.get(id);
            retained = store.getRetainedDocuments(id);
            assertNotNull("startup recovery must retain the synthetic queue row", recovered);
            assertNotNull(retained);
            assertEquals(SAF_DELETE_FAILURE_PROFILE, recovered.profileId);
            assertTrue(recovered.stableItemId.startsWith("cold-start-delete-failure-"));
            assertEquals(DownloadRecord.FAILED, recovered.status);
            assertEquals(0L, recovered.bytesDownloaded);
            assertEquals(0L, recovered.totalBytes);
            assertEquals("startup recovery must preserve the exact failed partial URI",
                    markerPartialUri(treeA, partialDocumentId).toString(), retained.partialUri);
            assertEquals("startup recovery must preserve the exact name reservation",
                    partialName, retained.partialName);
            assertEquals(treeA.toString(), retained.folderUri);
            assertEquals("startup recovery must not invent a completed URI", "", retained.fileUri);
            assertArrayEquals("the failed exact-document delete must preserve partial bytes",
                    PARTIAL_BYTES, readDocument(target, Uri.parse(retained.partialUri)));
            assertTrue("the row must remain actionable after failed startup cleanup",
                    recovered.errorMessage != null && !recovered.errorMessage.trim().isEmpty());
        } finally {
            store.close();
        }

        Dns originalTestDns = setProductionManagerFixtureDns(manager);
        try (LocalDownloadServer server = new LocalDownloadServer(SAF_DELETE_FAILURE_BODY, serverPort)) {
            manager.retry(id);
            DownloadRecord retryBlocked = findQueueRecord(manager, id);
            assertNotNull(retryBlocked);
            assertEquals("Retry must stay failed while exact partial deletion remains blocked",
                    DownloadRecord.FAILED, retryBlocked.status);
            DownloadQueueStore.RetainedDocuments afterBlockedRetry = retainedDocuments(target, id);
            assertEquals(retained.partialUri, afterBlockedRetry.partialUri);
            assertEquals(partialName, afterBlockedRetry.partialName);
            assertArrayEquals("blocked Retry must preserve the old partial bytes", PARTIAL_BYTES,
                    readDocument(target, Uri.parse(afterBlockedRetry.partialUri)));
            SystemClock.sleep(300L);
            assertEquals("Retry must not contact the loopback source while SAF cleanup fails", 0,
                    server.requestCount());
            assertTrue("blocked Retry must not add a Range request", server.rangeHeaders().isEmpty());

            QueueLifecycleDocumentsProvider.setDeleteFailure(target, treeA, null);
            manager.retry(id);
            DownloadRecord completed = awaitStatus(manager, id,
                    Collections.singletonList(DownloadRecord.COMPLETED), 15_000L);
            assertNotNull("retry should complete after exact partial cleanup succeeds", completed);
            assertEquals(DownloadRecord.COMPLETED, completed.status);
            assertEquals("successful retry must issue one fresh request", 1, server.requestCount());
            assertEquals("successful retry must begin at byte zero without a Range header",
                    Collections.singletonList(""), server.rangeHeaders());
            assertTrue("the completed row must keep its finalized SAF URI",
                    completed.fileUri != null && !completed.fileUri.isEmpty());
            assertTrue("the finalized file name must remain inside the dedicated test prefix",
                    completed.fileName != null
                            && completed.fileName.startsWith(SAF_DELETE_FAILURE_FILE_PREFIX));
            assertArrayEquals("the final SAF document must exactly match the complete source body",
                    SAF_DELETE_FAILURE_BODY, readDocument(target, Uri.parse(completed.fileUri)));
            DownloadQueueStore.RetainedDocuments completedDocuments = retainedDocuments(target, id);
            assertNotNull(completedDocuments);
            assertEquals(completed.fileUri, completedDocuments.fileUri);
            assertEquals("completion must clear the partial-name reservation", "",
                    completedDocuments.partialName);
            assertEquals("completion must clear the partial URI", "", completedDocuments.partialUri);
        } finally {
            restoreProductionManagerDns(manager, originalTestDns);
        }
    }

    @Test public void prepareSafGrantLossForColdStart() throws Exception {
        requireSafGrantLossPhase(SAF_GRANT_LOSS_PREPARE_PHASE, true);
        Bundle args = InstrumentationRegistry.getArguments();
        String cycleNonce = requireSafGrantLossCycleNonce(args);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context helper = InstrumentationRegistry.getInstrumentation().getContext();
        Uri treeA = selectedQueueLifecycleTree(args.getString(QUEUE_TREE_A_ARG),
                QueueLifecycleDocumentsProvider.TREE_A);
        Uri treeB = selectedQueueLifecycleTree(args.getString(QUEUE_TREE_B_ARG),
                QueueLifecycleDocumentsProvider.TREE_B);
        assertPersistedQueueTreeGrant(target, treeA);
        assertPersistedQueueTreeGrant(target, treeB);

        android.content.SharedPreferences marker = target.getSharedPreferences(
                SAF_GRANT_LOSS_PREFS, Context.MODE_PRIVATE);
        assertEquals("the grant-loss test must use the exact host cycle nonce", cycleNonce,
                marker.getString(SAF_GRANT_LOSS_CYCLE_NONCE, null));
        assertEquals("the prepared row must be assigned to exact Tree A", treeA.toString(),
                marker.getString(SAF_GRANT_LOSS_TREE_URI, null));
        cleanupStaleSafGrantLossRows(target, treeA);
        assertFixtureFolderIsEmpty(helper, treeA);
        assertNoOtherPendingDownloadRows(target, null);

        int serverPort = reserveLoopbackPort();
        String stableId = marker.getString(SAF_GRANT_LOSS_STABLE_ITEM_ID, null);
        if (!isSafGrantLossStableItemId(stableId)) {
            throw new IOException("The exact pre-enqueue stable item ID marker is invalid");
        }
        saveSafGrantLossPendingRowMarker(target, cycleNonce, treeA, stableId, serverPort);
        DownloadQueueManager manager = newDownloadQueueManagerWithDns(target,
                loopbackFixtureDnsForLiteralAddress());
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            DownloadRequest request = new DownloadRequest(SAF_GRANT_LOSS_PROFILE, stableId,
                    "download-test-source", "SAF grant-loss fixture",
                    SAF_GRANT_LOSS_FILE_PREFIX + stableId + ".mp4", "video/mp4",
                    "http://127.0.0.1:" + serverPort + "/synthetic.mp4", true);
            String id = manager.enqueue(request, treeA);
            safGrantLossRowIdPreparedInThisRun = id;
            String partialName = ".orbit-part-" + id;
            saveSafGrantLossMarker(target, cycleNonce, stableId, id, treeA, "", partialName,
                    serverPort);
            assertTrue("the grant-loss fixture must begin as a claimed interrupted item",
                    store.claimQueued(id, "saf-grant-loss-" + id));

            Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeA,
                    DocumentsContract.getTreeDocumentId(treeA));
            Uri partial = DocumentsContract.createDocument(target.getContentResolver(), parent,
                    "video/mp4", partialName);
            assertNotNull("exact Tree A should create the grant-loss partial", partial);
            try (OutputStream output = target.getContentResolver().openOutputStream(partial, "wt")) {
                assertNotNull(output);
                output.write(PARTIAL_BYTES);
            }

            String partialDocumentId = DocumentsContract.getDocumentId(partial);
            store.setPartialReservation(id, partialName);
            store.setPartialUri(id, partial.toString());
            store.updateProgress(id, PARTIAL_BYTES.length, SAF_GRANT_LOSS_BODY.length);
            saveSafGrantLossMarker(target, cycleNonce, stableId, id, treeA, partialDocumentId,
                    partialName, serverPort);

            DownloadRecord staged = store.get(id);
            DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(id);
            assertNotNull(staged);
            assertNotNull(retained);
            assertEquals(SAF_GRANT_LOSS_PROFILE, staged.profileId);
            assertEquals(DownloadRecord.DOWNLOADING, staged.status);
            assertEquals(treeA.toString(), retained.folderUri);
            assertEquals(partial.toString(), retained.partialUri);
            assertEquals(partialName, retained.partialName);
            assertArrayEquals("the helper must verify the exact Tree A partial bytes",
                    PARTIAL_BYTES, readFixtureDocumentThroughHelper(target, partial));
            assertEquals("Tree B grant must remain at the exact post-setup mode set",
                    marker.getInt(SAF_GRANT_LOSS_POST_SETUP_TREE_B_FLAGS, -1),
                    persistedTreeModeFlags(target, treeB));
            assertTrue("the prepared row marker must be durable before grant revocation",
                    marker.contains(SAF_GRANT_LOSS_ROW_ID));
        } finally {
            store.close();
            manager.close();
        }

        // Remove Orbit's durable grant first, then revoke only the helper's package-scoped
        // transient grant. The helper itself keeps its persisted Tree A read/write grant.
        int targetTreeAModes = persistedTreeModeFlags(target, treeA);
        assertTrue("Orbit must own both selected Tree A modes before loss injection",
                (targetTreeAModes & PERSISTED_TREE_MODE_FLAGS) == PERSISTED_TREE_MODE_FLAGS);
        target.getContentResolver().releasePersistableUriPermission(treeA, targetTreeAModes);
        assertEquals("Orbit's persisted Tree A grant must be gone before helper revocation", 0,
                persistedTreeModeFlags(target, treeA));
        revokeOrbitTreeGrantFromHelper(target, QueueLifecycleDocumentsProvider.TREE_A);
        assertEquals("Orbit must retain no Tree A persisted mode after exact package revoke", 0,
                persistedTreeModeFlags(target, treeA));
        assertEquals("Tree B must remain available to Orbit throughout Tree A grant loss",
                marker.getInt(SAF_GRANT_LOSS_POST_SETUP_TREE_B_FLAGS, -1),
                persistedTreeModeFlags(target, treeB));
        assertArrayEquals("the test helper's own grant must still read the prepared partial",
                PARTIAL_BYTES, readFixtureDocumentThroughHelper(target, markerPartialUri(treeA,
                        marker.getString(SAF_GRANT_LOSS_DOCUMENT_ID, null))));
        safGrantLossFixturePreparedInThisRun = true;
    }

    @Test public void recoverIncompleteSafGrantLossMarker() throws Exception {
        requireSafGrantLossPhase(SAF_GRANT_LOSS_RECOVERY_PHASE, false);
        Bundle args = InstrumentationRegistry.getArguments();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri treeA = selectedQueueLifecycleTree(args.getString(QUEUE_TREE_A_ARG),
                QueueLifecycleDocumentsProvider.TREE_A);
        android.content.SharedPreferences marker = target.getSharedPreferences(
                SAF_GRANT_LOSS_PREFS, Context.MODE_PRIVATE);
        assertEquals("recovery must retain the requested exact cycle nonce",
                requireSafGrantLossCycleNonce(args),
                marker.getString(SAF_GRANT_LOSS_CYCLE_NONCE, null));
        String rowId = resolveSafGrantLossRowId(target, marker, treeA, null);
        assertNoOtherPendingDownloadRows(target, rowId);
    }

    @Test public void verifySafGrantLossAfterColdStartBeforeReselection() throws Exception {
        requireSafGrantLossPhase(SAF_GRANT_LOSS_COLD_START_PHASE, true);
        Bundle args = InstrumentationRegistry.getArguments();
        String cycleNonce = requireSafGrantLossCycleNonce(args);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri treeA = selectedQueueLifecycleTree(args.getString(QUEUE_TREE_A_ARG),
                QueueLifecycleDocumentsProvider.TREE_A);
        Uri treeB = selectedQueueLifecycleTree(args.getString(QUEUE_TREE_B_ARG),
                QueueLifecycleDocumentsProvider.TREE_B);
        assertEquals("Orbit must still lack the exact Tree A grant before reselection", 0,
                persistedTreeModeFlags(target, treeA));
        assertPersistedQueueTreeGrant(target, treeB);

        android.content.SharedPreferences marker = target.getSharedPreferences(
                SAF_GRANT_LOSS_PREFS, Context.MODE_PRIVATE);
        String id = marker.getString(SAF_GRANT_LOSS_ROW_ID, null);
        assertNotNull("prepare and grant revocation must precede cold-start verification", id);
        assertEquals("cold-start verification must match the prepared cycle nonce", cycleNonce,
                marker.getString(SAF_GRANT_LOSS_CYCLE_NONCE, null));
        assertEquals(treeA.toString(), marker.getString(SAF_GRANT_LOSS_TREE_URI, null));
        String partialName = marker.getString(SAF_GRANT_LOSS_PARTIAL_NAME, null);
        String partialDocumentId = marker.getString(SAF_GRANT_LOSS_DOCUMENT_ID, null);
        assertEquals("the row must keep the exact partial-name reservation", ".orbit-part-" + id,
                partialName);
        assertNotNull("the row must keep the exact Tree A partial document ID", partialDocumentId);

        assertTrue("verification must observe Orbit's real production application",
                target.getApplicationContext() instanceof OrbitApplication);
        OrbitApplication application = (OrbitApplication) target.getApplicationContext();
        DownloadQueueManager manager = application.getDownloadQueueManager();
        assertNotNull("the normal cold launch must have initialized the production queue manager", manager);
        assertTrue("cold-start recovery must not auto-start queued work", manager.isQueuePaused());

        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            DownloadRecord recovered = store.get(id);
            DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(id);
            assertNotNull("the queue row must survive cold-start grant loss", recovered);
            assertNotNull(retained);
            assertEquals(SAF_GRANT_LOSS_PROFILE, recovered.profileId);
            assertTrue(recovered.stableItemId.startsWith("saf-grant-loss-"));
            if (marker.contains(SAF_GRANT_LOSS_STABLE_ITEM_ID)) {
                assertEquals("the cold-start row must match the pre-enqueue stable ID marker",
                        marker.getString(SAF_GRANT_LOSS_STABLE_ITEM_ID, null),
                        recovered.stableItemId);
            }
            assertEquals(DownloadRecord.FAILED, recovered.status);
            assertEquals(0L, recovered.bytesDownloaded);
            assertEquals(0L, recovered.totalBytes);
            assertEquals("the row must retain its exact partial URI while Tree A is unavailable",
                    markerPartialUri(treeA, partialDocumentId).toString(), retained.partialUri);
            assertEquals(partialName, retained.partialName);
            assertEquals(treeA.toString(), retained.folderUri);
            assertEquals("the failed retry must not invent a completed file", "", retained.fileUri);
            assertTrue("the row must tell the user to restore folder access",
                    recovered.errorMessage != null
                            && recovered.errorMessage.toLowerCase(java.util.Locale.ROOT)
                                    .contains("restore folder access"));
            assertArrayEquals("the helper grant must read exact bytes preserved after cold start",
                    PARTIAL_BYTES, readFixtureDocumentThroughHelper(target,
                            markerPartialUri(treeA, partialDocumentId)));
        } finally {
            store.close();
        }

        assertEquals("Tree B's grant must remain unchanged during cold-start verification",
                marker.getInt(SAF_GRANT_LOSS_POST_SETUP_TREE_B_FLAGS, -1),
                persistedTreeModeFlags(target, treeB));

        assertNoOtherPendingDownloadRows(target, id);
        Dns originalTestDns = setProductionManagerFixtureDns(manager);
        try (LocalDownloadServer server = new LocalDownloadServer(SAF_GRANT_LOSS_BODY,
                marker.getInt(SAF_GRANT_LOSS_SERVER_PORT, -1))) {
            manager.retry(id);
            SystemClock.sleep(500L);
            assertEquals("explicit Retry without Tree A access must make zero source requests", 0,
                    server.requestCount());
            assertTrue("denied retry must not issue any range request", server.rangeHeaders().isEmpty());
            DownloadQueueStore.RetainedDocuments afterBlockedRetry = retainedDocuments(target, id);
            DownloadRecord stillFailed = findQueueRecord(manager, id);
            assertNotNull(stillFailed);
            assertNotNull(afterBlockedRetry);
            assertEquals("blocked Retry must leave the row actionable", DownloadRecord.FAILED,
                    stillFailed.status);
            assertEquals("blocked Retry must preserve exact partial URI",
                    markerPartialUri(treeA, partialDocumentId).toString(),
                    afterBlockedRetry.partialUri);
            assertEquals("blocked Retry must preserve exact reservation name", partialName,
                    afterBlockedRetry.partialName);
            assertArrayEquals("blocked Retry must preserve the exact partial bytes", PARTIAL_BYTES,
                    readFixtureDocumentThroughHelper(target,
                            markerPartialUri(treeA, partialDocumentId)));
            assertTrue("blocked Retry must report restored folder access",
                    stillFailed.errorMessage != null
                            && stillFailed.errorMessage.toLowerCase(java.util.Locale.ROOT)
                                    .contains("restore folder access"));
            manager.pauseQueue();
            assertTrue("the synthetic queue must be left paused before the user reselects Tree A",
                    manager.isQueuePaused());
        } finally {
            restoreProductionManagerDns(manager, originalTestDns);
        }
    }

    @Test public void verifySafGrantLossAfterSameTreeReselectionAndExplicitRetry()
            throws Exception {
        requireSafGrantLossPhase(SAF_GRANT_LOSS_RESELECTED_PHASE, false);
        Bundle args = InstrumentationRegistry.getArguments();
        String cycleNonce = requireSafGrantLossCycleNonce(args);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri treeA = selectedQueueLifecycleTree(args.getString(QUEUE_TREE_A_ARG),
                QueueLifecycleDocumentsProvider.TREE_A);
        Uri treeB = selectedQueueLifecycleTree(args.getString(QUEUE_TREE_B_ARG),
                QueueLifecycleDocumentsProvider.TREE_B);
        assertPersistedQueueTreeGrant(target, treeA);
        assertPersistedQueueTreeGrant(target, treeB);
        android.content.SharedPreferences marker = target.getSharedPreferences(
                SAF_GRANT_LOSS_PREFS, Context.MODE_PRIVATE);
        String id = marker.getString(SAF_GRANT_LOSS_ROW_ID, null);
        assertNotNull("reselection verification requires the preserved queue fixture marker", id);
        assertEquals(cycleNonce, marker.getString(SAF_GRANT_LOSS_CYCLE_NONCE, null));
        assertEquals(treeA.toString(), marker.getString(SAF_GRANT_LOSS_TREE_URI, null));
        assertEquals("Orbit Downloads > Change folder must persist the exact reselected Tree A",
                treeA.toString(), target.getSharedPreferences(DOWNLOAD_FOLDER_PREFS,
                        Context.MODE_PRIVATE).getString(DOWNLOAD_FOLDER_PREF_KEY, null));

        int expectedTreeBFlags = marker.getInt(SAF_GRANT_LOSS_POST_SETUP_TREE_B_FLAGS, -1);
        assertTrue("the marker must retain exact post-setup Tree B grant modes", expectedTreeBFlags >= 0);
        assertEquals("Tree B must remain unchanged while Tree A is reselected",
                expectedTreeBFlags, persistedTreeModeFlags(target, treeB));

        String partialName = marker.getString(SAF_GRANT_LOSS_PARTIAL_NAME, null);
        String partialDocumentId = marker.getString(SAF_GRANT_LOSS_DOCUMENT_ID, null);
        int serverPort = marker.getInt(SAF_GRANT_LOSS_SERVER_PORT, -1);
        assertEquals("the exact .orbit-part reservation must be restored", ".orbit-part-" + id,
                partialName);
        assertNotNull(partialDocumentId);
        assertTrue("the prepared loopback port must be valid", serverPort > 0 && serverPort <= 65535);

        assertTrue("verification must use the production Orbit application instance",
                target.getApplicationContext() instanceof OrbitApplication);
        OrbitApplication application = (OrbitApplication) target.getApplicationContext();
        DownloadQueueManager manager = application.getDownloadQueueManager();
        assertNotNull(manager);
        assertTrue("reselecting a folder must not auto-retry a failed item", manager.isQueuePaused());

        DownloadQueueStore store = new DownloadQueueStore(target);
        DownloadRecord beforeRetry;
        DownloadQueueStore.RetainedDocuments retained;
        try {
            beforeRetry = store.get(id);
            retained = store.getRetainedDocuments(id);
            assertNotNull(beforeRetry);
            assertNotNull(retained);
            assertEquals(DownloadRecord.FAILED, beforeRetry.status);
            if (marker.contains(SAF_GRANT_LOSS_STABLE_ITEM_ID)) {
                assertEquals("the retry row must match the pre-enqueue stable ID marker",
                        marker.getString(SAF_GRANT_LOSS_STABLE_ITEM_ID, null),
                        beforeRetry.stableItemId);
            }
            assertEquals(treeA.toString(), retained.folderUri);
            assertEquals(markerPartialUri(treeA, partialDocumentId).toString(), retained.partialUri);
            assertEquals(partialName, retained.partialName);
            assertTrue("the partial document must still exist before explicit Retry",
                    documentExists(target, treeA, partialName));
        } finally {
            store.close();
        }

        assertNoOtherPendingDownloadRows(target, id);
        Dns originalTestDns = setProductionManagerFixtureDns(manager);
        try (LocalDownloadServer server = new LocalDownloadServer(SAF_GRANT_LOSS_BODY, serverPort)) {
            assertEquals("no source request may occur before explicit Retry", 0, server.requestCount());
            manager.retry(id);
            DownloadRecord completed = awaitStatus(manager, id,
                    Collections.singletonList(DownloadRecord.COMPLETED), 15_000L);
            assertNotNull("explicit retry must complete after the same Tree A grant is restored",
                    completed);
            assertEquals(DownloadRecord.COMPLETED, completed.status);
            assertEquals("explicit retry must send one complete request", 1, server.requestCount());
            assertEquals("retry must start at byte zero and send no Range header",
                    Collections.singletonList(""), server.rangeHeaders());
            assertTrue("retry must remove the exact orphan partial before writing the new file",
                    !documentExists(target, treeA, partialName));
            assertArrayEquals("the finalized synthetic file must match the exact source bytes",
                    SAF_GRANT_LOSS_BODY, readDocument(target, Uri.parse(completed.fileUri)));
            DownloadQueueStore.RetainedDocuments completedDocuments = retainedDocuments(target, id);
            assertNotNull(completedDocuments);
            assertEquals(completed.fileUri, completedDocuments.fileUri);
            assertEquals("completion must clear the partial URI reservation", "",
                    completedDocuments.partialUri);
            assertEquals("completion must clear the partial name reservation", "",
                    completedDocuments.partialName);
        } finally {
            restoreProductionManagerDns(manager, originalTestDns);
        }

        // The @After cleanup restores this preference and the exact pre-test Tree A/B modes.
    }

    private static void requireSafGrantLossPhase(String expectedPhase, boolean preserve) {
        Bundle args = InstrumentationRegistry.getArguments();
        assertEquals("SAF grant-loss phase argument is required", expectedPhase,
                args.getString(SAF_GRANT_LOSS_PHASE_ARG));
        assertEquals("fixture preservation must match the phase boundary", preserve,
                Boolean.parseBoolean(args.getString(SAF_GRANT_LOSS_PRESERVE_ARG)));
        requireSafGrantLossCycleNonce(args);
    }

    private static String requireSafGrantLossCycleNonce(Bundle args) {
        String nonce = args.getString(SAF_GRANT_LOSS_NONCE_ARG);
        assertNotNull("a host-generated nonce is required for each grant-loss cycle", nonce);
        assertFalse("the grant-loss nonce must not be empty or whitespace", nonce.trim().isEmpty());
        return nonce;
    }

    private static void requireSafDeleteFailurePhase(String expectedPhase) {
        Bundle args = InstrumentationRegistry.getArguments();
        assertEquals("SAF delete-failure process-cycle phase argument is required", expectedPhase,
                args.getString(SAF_DELETE_FAILURE_PHASE_ARG));
        assertEquals("fixture preservation is enabled only for the prepare phase",
                SAF_DELETE_FAILURE_PREPARE_PHASE.equals(expectedPhase),
                Boolean.parseBoolean(args.getString(SAF_DELETE_FAILURE_PRESERVE_ARG)));
        requireSafDeleteFailureCycleNonce(args);
    }

    private static String requireSafDeleteFailureCycleNonce(Bundle args) {
        String nonce = args.getString(SAF_DELETE_FAILURE_CYCLE_NONCE_ARG);
        assertNotNull("a new host-generated cycle nonce is required for every SAF failure cycle",
                nonce);
        assertFalse("the SAF failure cycle nonce must not be empty or whitespace",
                nonce.trim().isEmpty());
        return nonce;
    }

    private static void saveSafGrantLossPendingRowMarker(Context target, String cycleNonce,
            Uri treeA, String stableItemId, int serverPort) {
        android.content.SharedPreferences marker = target.getSharedPreferences(
                SAF_GRANT_LOSS_PREFS, Context.MODE_PRIVATE);
        assertEquals("pending row marker must belong to the exact cycle nonce", cycleNonce,
                marker.getString(SAF_GRANT_LOSS_CYCLE_NONCE, null));
        assertEquals("pending row marker must belong to exact Tree A", treeA.toString(),
                marker.getString(SAF_GRANT_LOSS_TREE_URI, null));
        boolean saved = marker.edit()
                .putString(SAF_GRANT_LOSS_STABLE_ITEM_ID, stableItemId)
                .putInt(SAF_GRANT_LOSS_SERVER_PORT, serverPort)
                .commit();
        assertTrue("the stable item identity must be durable before the queue insert", saved);
    }

    private static void saveSafGrantLossMarker(Context target, String cycleNonce,
            String stableItemId, String id, Uri treeA, String partialDocumentId,
            String partialName, int serverPort) {
        android.content.SharedPreferences marker = target.getSharedPreferences(
                SAF_GRANT_LOSS_PREFS, Context.MODE_PRIVATE);
        boolean saved = marker.edit()
                .putString(SAF_GRANT_LOSS_CYCLE_NONCE, cycleNonce)
                .putString(SAF_GRANT_LOSS_ROW_ID, id)
                .putString(SAF_GRANT_LOSS_STABLE_ITEM_ID, stableItemId)
                .putString(SAF_GRANT_LOSS_TREE_URI, treeA.toString())
                .putString(SAF_GRANT_LOSS_DOCUMENT_ID,
                        partialDocumentId == null ? "" : partialDocumentId)
                .putString(SAF_GRANT_LOSS_PARTIAL_NAME, partialName)
                .putInt(SAF_GRANT_LOSS_SERVER_PORT, serverPort)
                .commit();
        assertTrue("the test marker must persist the exact synthetic Tree A row", saved);
    }

    private static boolean hasTargetUriMode(Context target, Uri tree, int mode) {
        return target.checkUriPermission(tree, Process.myPid(), Process.myUid(), mode)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Rollback for @BeforeClass, where no instance @After callback is guaranteed. */
    private static Throwable rollbackSafGrantLossSetup(Context target,
            android.content.SharedPreferences marker, boolean originalFolderPresent,
            String originalFolderUri, Uri treeA, int originalTreeAFlags,
            Uri treeB, int originalTreeBFlags) {
        Throwable rollbackFailure = null;
        try { restoreDownloadFolderPreference(target, treeA, treeB,
                originalFolderPresent, originalFolderUri); }
        catch (Throwable error) { rollbackFailure = appendCleanupFailure(rollbackFailure, error); }

        for (Uri tree : new Uri[]{treeA, treeB}) {
            int expected = tree.equals(treeA) ? originalTreeAFlags : originalTreeBFlags;
            try {
                int current = persistedTreeModeFlags(target, tree);
                int introduced = current & ~expected;
                if (introduced != 0) {
                    target.getContentResolver().releasePersistableUriPermission(tree, introduced);
                }
                if (persistedTreeModeFlags(target, tree) != expected) {
                    throw new IOException("The setup rollback did not restore an exact Tree A/B mode set");
                }
            } catch (Throwable error) {
                rollbackFailure = appendCleanupFailure(rollbackFailure, error);
            }
        }

        if (rollbackFailure == null) {
            try { runHelperQueueTreeCleanup(target); }
            catch (Throwable error) { rollbackFailure = appendCleanupFailure(rollbackFailure, error); }
        }
        Context helper = InstrumentationRegistry.getInstrumentation().getContext();
        if (rollbackFailure == null) {
            try {
                revokePickerGrantForExactFixtureTree(helper, treeA,
                        QueueLifecycleDocumentsProvider.TREE_A);
                revokePickerGrantForExactFixtureTree(helper, treeB,
                        QueueLifecycleDocumentsProvider.TREE_B);
            } catch (Throwable error) {
                rollbackFailure = appendCleanupFailure(rollbackFailure, error);
            }
        }
        if (rollbackFailure == null) {
            try {
                if (persistedTreeModeFlags(target, treeA) != originalTreeAFlags
                        || persistedTreeModeFlags(target, treeB) != originalTreeBFlags) {
                    throw new IOException("The exact original Tree A/B persisted modes remain changed");
                }
                if (originalTreeAFlags == 0 && originalTreeBFlags == 0) {
                    verifyTargetQueueTreeGrantsAbsent(target, treeA, treeB);
                }
                verifyHelperQueueTreeGrantsAbsent(target);
                android.content.SharedPreferences folder = target.getSharedPreferences(
                        DOWNLOAD_FOLDER_PREFS, Context.MODE_PRIVATE);
                if (originalFolderPresent != folder.contains(DOWNLOAD_FOLDER_PREF_KEY)
                        || (originalFolderPresent && !originalFolderUri.equals(
                                folder.getString(DOWNLOAD_FOLDER_PREF_KEY, null)))) {
                    throw new IOException("The setup rollback did not restore the original folder preference");
                }
            } catch (Throwable error) {
                rollbackFailure = appendCleanupFailure(rollbackFailure, error);
            }
        }
        try { clearProductionApplicationGate(target); }
        catch (Throwable error) { rollbackFailure = appendCleanupFailure(rollbackFailure, error); }

        if (rollbackFailure == null) {
            if (!marker.edit().clear().commit()) {
                rollbackFailure = new IOException("The setup rollback could not clear its marker");
            }
        }
        if (rollbackFailure != null) {
            marker.edit().putString("setup_rollback_error",
                    rollbackFailure.getClass().getSimpleName()).commit();
            android.util.Log.e("OrbitSafGrantLoss", "AndroidTest setup failed; exact rollback also failed",
                    rollbackFailure);
        }
        return rollbackFailure;
    }

    private static void cleanupStaleSafGrantLossRows(Context target, Uri treeA) throws Exception {
        DownloadQueueStore store = new DownloadQueueStore(target);
        ArrayList<String> staleIds = new ArrayList<>();
        try {
            for (DownloadRecord item : store.listAll()) {
                if (!SAF_GRANT_LOSS_PROFILE.equals(item.profileId)) continue;
                if (!treeA.toString().equals(item.folderUri)) {
                    throw new IOException("Refusing stale grant-loss cleanup outside exact Tree A");
                }
                staleIds.add(item.id);
            }
        } finally {
            store.close();
        }
        for (String id : staleIds) cleanSafGrantLossRow(target, treeA, id);
    }

    private static void validateSafGrantLossRecoveryState(Context target,
            android.content.SharedPreferences marker, Uri treeA, Uri treeB, String cycleNonce)
            throws Exception {
        if (marker.getAll().isEmpty()) {
            throw new IOException("Explicit recovery requires a nonempty SAF grant-loss marker");
        }
        if (!cycleNonce.equals(marker.getString(SAF_GRANT_LOSS_CYCLE_NONCE, null))
                || !treeA.toString().equals(marker.getString(SAF_GRANT_LOSS_TREE_URI, null))) {
            throw new IOException("Recovery marker nonce or canonical Tree A does not match this cycle");
        }
        if (!marker.contains(SAF_GRANT_LOSS_INITIAL_TREE_A_FLAGS)
                || !marker.contains(SAF_GRANT_LOSS_INITIAL_TREE_B_FLAGS)
                || marker.getInt(SAF_GRANT_LOSS_INITIAL_TREE_A_FLAGS, -1) != 0
                || marker.getInt(SAF_GRANT_LOSS_INITIAL_TREE_B_FLAGS, -1) != 0
                || !marker.contains(SAF_GRANT_LOSS_ORIGINAL_FOLDER_PRESENT)
                || !marker.contains(SAF_GRANT_LOSS_ORIGINAL_FOLDER_URI)) {
            throw new IOException("Recovery marker lacks the exact zero-grant/preference preflight snapshot");
        }
        boolean originalFolderPresent = marker.getBoolean(
                SAF_GRANT_LOSS_ORIGINAL_FOLDER_PRESENT, false);
        String originalFolderUri = marker.getString(SAF_GRANT_LOSS_ORIGINAL_FOLDER_URI, null);
        validateSavedFolderSnapshot(treeA, treeB, originalFolderPresent, originalFolderUri);
        validateCurrentFolderPreference(target, treeA, originalFolderPresent, originalFolderUri);

        int postSetupTreeBFlags = marker.contains(SAF_GRANT_LOSS_POST_SETUP_TREE_B_FLAGS)
                ? marker.getInt(SAF_GRANT_LOSS_POST_SETUP_TREE_B_FLAGS, -1)
                : PERSISTED_TREE_MODE_FLAGS;
        if (postSetupTreeBFlags != PERSISTED_TREE_MODE_FLAGS) {
            throw new IOException("Recovery marker contains an unexpected Tree B setup mode snapshot");
        }
        assertAllowedFixtureGrantModes("Tree A", treeA, target,
                marker.getInt(SAF_GRANT_LOSS_INITIAL_TREE_A_FLAGS, 0),
                PERSISTED_TREE_MODE_FLAGS);
        assertAllowedFixtureGrantModes("Tree B", treeB, target,
                marker.getInt(SAF_GRANT_LOSS_INITIAL_TREE_B_FLAGS, 0), postSetupTreeBFlags);

        boolean hasRowId = marker.contains(SAF_GRANT_LOSS_ROW_ID);
        boolean hasStableItemId = marker.contains(SAF_GRANT_LOSS_STABLE_ITEM_ID);
        if (!hasRowId && !hasStableItemId) {
            throw new IOException("Recovery refuses a marker with neither row ID nor stable item ID");
        }
        String rowId = resolveSafGrantLossRowId(target, marker, treeA, null);
        assertNoOtherPendingDownloadRows(target, rowId);
        DownloadRecord recoveryRow = null;
        if (rowId != null) {
            DownloadQueueStore store = new DownloadQueueStore(target);
            try { recoveryRow = store.get(rowId); }
            finally { store.close(); }
            if (recoveryRow == null) {
                String stableItemId = marker.getString(SAF_GRANT_LOSS_STABLE_ITEM_ID, null);
                if (!isSafGrantLossStableItemId(stableItemId)) {
                    throw new IOException("A deleted marked row requires its exact stable item ID");
                }
                // The row may already have been deleted by an interrupted cleanup. Retain the
                // exact derived name so the remaining document scan cannot widen its scope.
                expectedSafGrantLossFileName(stableItemId, rowId);
            }
        }
        String partialName = marker.getString(SAF_GRANT_LOSS_PARTIAL_NAME, "");
        String partialDocumentId = marker.getString(SAF_GRANT_LOSS_DOCUMENT_ID, "");
        if (rowId == null) {
            // This is the one allowed no-row setup window: the stable identity was committed
            // before enqueue, but no row or partial document was created yet.
            if (partialName != null && !partialName.isEmpty()
                    || partialDocumentId != null && !partialDocumentId.isEmpty()) {
                throw new IOException("A no-row setup marker cannot declare a partial name or document");
            }
        } else {
            if (!isCanonicalQueueRowId(rowId)) {
                throw new IOException("Recovery marker does not resolve to an exact queue row ID");
            }
            String expectedPartialName = ".orbit-part-" + rowId;
            if (partialName != null && !partialName.isEmpty()
                    && !expectedPartialName.equals(partialName)
                    && !(expectedPartialName + ".mp4").equals(partialName)) {
                throw new IOException("Recovery marker partial name does not match its exact row ID");
            }
            if (partialDocumentId != null && !partialDocumentId.isEmpty()
                    && !isCanonicalSafGrantLossDocumentId(partialDocumentId)) {
                throw new IOException("Recovery marker document ID is outside exact Tree A");
            }
        }
        String expectedFinalName = recoveryRow != null
                && DownloadRecord.COMPLETED.equals(recoveryRow.status)
                ? recoveryRow.fileName : "";
        validateRecoveryTreeAThroughHelper(target, rowId,
                recoveryRow != null, partialDocumentId, partialName, expectedFinalName);
    }

    private static void validateSavedFolderSnapshot(Uri treeA, Uri treeB, boolean present,
            String originalUri) throws IOException {
        if (present) {
            if (originalUri == null || originalUri.trim().isEmpty()
                    || treeA.toString().equals(originalUri)
                    || treeB.toString().equals(originalUri)) {
                throw new IOException("Saved Downloads-folder snapshot is malformed or names a fixture tree");
            }
        } else if (originalUri == null || !originalUri.isEmpty()) {
            throw new IOException("Absent Downloads-folder snapshot must retain an empty URI value");
        }
    }

    private static void validateCurrentFolderPreference(Context target, Uri treeA,
            boolean originalPresent, String originalUri) throws IOException {
        android.content.SharedPreferences preferences = target.getSharedPreferences(
                DOWNLOAD_FOLDER_PREFS, Context.MODE_PRIVATE);
        boolean currentPresent = preferences.contains(DOWNLOAD_FOLDER_PREF_KEY);
        String currentUri = preferences.getString(DOWNLOAD_FOLDER_PREF_KEY, null);
        boolean matchesOriginal = currentPresent == originalPresent
                && (!originalPresent || originalUri.equals(currentUri));
        boolean matchesExactTreeA = currentPresent && treeA.toString().equals(currentUri);
        if (!matchesOriginal && !matchesExactTreeA) {
            throw new IOException("Downloads-folder preference changed outside the saved value or exact Tree A");
        }
    }

    private static void assertAllowedFixtureGrantModes(String label, Uri tree, Context target,
            int initialModes, int postSetupModes) throws IOException {
        int persistedModes = persistedTreeModeFlags(target, tree);
        int availableModes = targetUriModeFlags(target, tree);
        if (!isAllowedFixtureMode(persistedModes, initialModes, postSetupModes)
                || !isAllowedFixtureMode(availableModes, initialModes, postSetupModes)) {
            throw new IOException(label + " modes are outside the exact fixture's recorded states");
        }
    }

    private static boolean isAllowedFixtureMode(int actual, int initialModes, int postSetupModes) {
        return actual == initialModes || actual == postSetupModes
                || actual == PERSISTED_TREE_MODE_FLAGS;
    }

    private static int targetUriModeFlags(Context target, Uri tree) {
        int flags = 0;
        if (hasTargetUriMode(target, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
        }
        if (hasTargetUriMode(target, tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        return flags;
    }

    private static boolean isCanonicalQueueRowId(String rowId) {
        return rowId != null
                && rowId.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static boolean isCanonicalSafGrantLossDocumentId(String documentId) {
        String prefix = QueueLifecycleDocumentsProvider.TREE_A + "-";
        if (documentId == null || !documentId.startsWith(prefix)) return false;
        try {
            String suffix = documentId.substring(prefix.length());
            return documentId.equals(prefix + UUID.fromString(suffix).toString());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static String resolveSafGrantLossRowId(Context target,
            android.content.SharedPreferences marker, Uri treeA, String fallbackRowId)
            throws IOException {
        boolean markerHasRowId = marker.contains(SAF_GRANT_LOSS_ROW_ID);
        String markedRowId = marker.getString(SAF_GRANT_LOSS_ROW_ID, null);
        if (markerHasRowId && (markedRowId == null || markedRowId.trim().isEmpty())) {
            throw new IOException("The marked SAF grant-loss row ID is empty");
        }
        String rowId = markedRowId == null ? fallbackRowId : markedRowId;
        boolean markerHasStableId = marker.contains(SAF_GRANT_LOSS_STABLE_ITEM_ID);
        String stableItemId = marker.getString(SAF_GRANT_LOSS_STABLE_ITEM_ID, null);
        if (markerHasStableId && !isSafGrantLossStableItemId(stableItemId)) {
            throw new IOException("The marked SAF grant-loss stable item ID is malformed");
        }

        ArrayList<DownloadRecord> matches = new ArrayList<>();
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            for (DownloadRecord item : store.listAll()) {
                boolean rowIdMatch = rowId != null && rowId.equals(item.id);
                boolean stableIdMatch = stableItemId != null
                        && stableItemId.equals(item.stableItemId);
                if (rowIdMatch || stableIdMatch) {
                    validateSafGrantLossRecord(item, treeA, stableItemId, rowId);
                    matches.add(item);
                } else if (SAF_GRANT_LOSS_PROFILE.equals(item.profileId)) {
                    throw new IOException("An unmatched row exists in the dedicated SAF grant-loss profile");
                }
            }
        } finally {
            store.close();
        }
        if (matches.size() > 1) {
            throw new IOException("Multiple rows match the exact SAF grant-loss recovery marker");
        }
        if (matches.size() == 1) return matches.get(0).id;
        // The row may already have been deleted while its exact marker or partial name remains.
        // Keep the recorded ID so cleanup can search only its derived .orbit-part reservation.
        return rowId;
    }

    private static void validateSafGrantLossRecord(DownloadRecord item, Uri treeA,
            String expectedStableItemId, String expectedRowId) throws IOException {
        if (!SAF_GRANT_LOSS_PROFILE.equals(item.profileId)
                || !"download-test-source".equals(item.sourceId)
                || !treeA.toString().equals(item.folderUri)
                || !isSafGrantLossStableItemId(item.stableItemId)
                || (expectedStableItemId != null
                        && !expectedStableItemId.equals(item.stableItemId))
                || (expectedRowId != null && !expectedRowId.equals(item.id))
                || !expectedSafGrantLossFileName(item.stableItemId, item.id)
                        .equals(item.fileName)) {
            throw new IOException("A marked SAF grant-loss row does not match its exact profile/source/stable ID/Tree A/file name");
        }
    }

    private static boolean isSafGrantLossStableItemId(String value) {
        String prefix = "saf-grant-loss-";
        if (value == null || !value.startsWith(prefix)) return false;
        try {
            String uuid = value.substring(prefix.length());
            return value.equals(prefix + UUID.fromString(uuid).toString());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static String expectedSafGrantLossFileName(String stableItemId, String rowId)
            throws IOException {
        if (!isSafGrantLossStableItemId(stableItemId) || rowId == null
                || !rowId.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IOException("Cannot derive the exact SAF grant-loss file name from malformed IDs");
        }
        return SAF_GRANT_LOSS_FILE_PREFIX + stableItemId + "-"
                + rowId.substring(0, 8) + ".mp4";
    }

    /** Refuses to start or retry the synthetic item beside any pending user queue work. */
    private static void assertNoOtherPendingDownloadRows(Context target, String excludedId) {
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            for (DownloadRecord item : store.listAll()) {
                if (excludedId != null && excludedId.equals(item.id)) continue;
                if (DownloadRecord.QUEUED.equals(item.status)
                        || DownloadRecord.DOWNLOADING.equals(item.status)
                        || DownloadRecord.REMOVING.equals(item.status)) {
                    throw new AssertionError("Refusing the SAF grant-loss retry while another queue item is pending");
                }
            }
        } finally {
            store.close();
        }
    }

    private static void cleanSafGrantLossRow(Context target, Uri treeA, String id) throws Exception {
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            DownloadRecord item = store.get(id);
            if (item == null) return;
            validateSafGrantLossRecord(item, treeA, null, id);
            DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(id);
            String expectedPartial = ".orbit-part-" + id;
            Set<String> allowedNames = new HashSet<>();
            allowedNames.add(expectedPartial);
            allowedNames.add(expectedPartial + ".mp4");
            if (item.fileName != null && item.fileName.startsWith(SAF_GRANT_LOSS_FILE_PREFIX)) {
                allowedNames.add(item.fileName);
            }

            Set<String> documentIds = new HashSet<>();
            if (retained != null) {
                if (retained.partialName != null && !retained.partialName.isEmpty()
                        && !expectedPartial.equals(retained.partialName)) {
                    throw new IOException("Refusing an unexpected partial reservation in the marked row");
                }
                addQueueTreeDocumentId(retained.partialUri, treeA, documentIds);
                if (retained.fileUri != null && !retained.fileUri.isEmpty()) {
                    if (retained.fileName == null || !retained.fileName.equals(item.fileName)) {
                        throw new IOException("Refusing a final file name that differs from its exact queue row");
                    }
                    allowedNames.add(retained.fileName);
                    addQueueTreeDocumentId(retained.fileUri, treeA, documentIds);
                }
            }
            addQueueTreeDocumentId(item.fileUri, treeA, documentIds);
            for (String allowedName : allowedNames) {
                String namedId = documentIdForName(target, treeA, allowedName);
                if (namedId != null) documentIds.add(namedId);
            }
            for (String documentId : documentIds) {
                if (!documentId.startsWith(DocumentsContract.getTreeDocumentId(treeA) + "-")) {
                    throw new IOException("Refusing a marked document outside exact Tree A");
                }
                Uri document = DocumentsContract.buildDocumentUriUsingTree(treeA, documentId);
                String actualName = queryDisplayName(target, document);
                if (actualName != null && !allowedNames.contains(actualName)) {
                    throw new IOException("Refusing a Tree A child outside this test row's exact names");
                }
                QueueLifecycleDocumentsProvider.forceDeleteFixtureDocument(target, treeA, documentId);
            }
            store.delete(id);
        } finally {
            store.close();
        }
    }

    private static void cleanupSafGrantLossFixture(Context target, String fallbackRowId)
            throws Exception {
        android.content.SharedPreferences marker = target.getSharedPreferences(
                SAF_GRANT_LOSS_PREFS, Context.MODE_PRIVATE);
        String rawTree = marker.getString(SAF_GRANT_LOSS_TREE_URI, null);
        if (rawTree == null) {
            rawTree = InstrumentationRegistry.getArguments().getString(QUEUE_TREE_A_ARG);
        }
        if (rawTree == null) return;
        Uri treeA = selectedQueueLifecycleTree(rawTree, QueueLifecycleDocumentsProvider.TREE_A);
        Uri treeB = selectedQueueLifecycleTree(
                InstrumentationRegistry.getArguments().getString(QUEUE_TREE_B_ARG),
                QueueLifecycleDocumentsProvider.TREE_B);
        String id = marker.getString(SAF_GRANT_LOSS_ROW_ID, null);
        if (id == null) id = fallbackRowId;

        Throwable cleanupFailure = null;
        try {
            id = resolveSafGrantLossRowId(target, marker, treeA, id);
            String markedPartialName = marker.getString(SAF_GRANT_LOSS_PARTIAL_NAME, "");
            if (id != null && !markedPartialName.isEmpty()) {
                String exactPartialName = ".orbit-part-" + id;
                if (!exactPartialName.equals(markedPartialName)
                        && !(exactPartialName + ".mp4").equals(markedPartialName)) {
                    throw new IOException("Marked partial name does not match the resolved row ID");
                }
            }
        }
        catch (Throwable error) { cleanupFailure = appendCleanupFailure(cleanupFailure, error); }
        if (cleanupFailure == null) {
            try { pauseSafGrantLossQueue(target, id); }
            catch (Throwable error) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, error);
            }
        }
        boolean originalFolderPresent = marker.getBoolean(
                SAF_GRANT_LOSS_ORIGINAL_FOLDER_PRESENT, false);
        String originalFolderUri = marker.getString(SAF_GRANT_LOSS_ORIGINAL_FOLDER_URI, "");
        // This preference is independent of the retained row's Tree A grant. Restore it even
        // when an active fixture item prevents safe row cleanup; keep all fixture grants and
        // the marker in that case so the exact partial remains accessible for a later cleanup.
        try { restoreDownloadFolderPreference(target, treeA, treeB,
                originalFolderPresent, originalFolderUri); }
        catch (Throwable error) { cleanupFailure = appendCleanupFailure(cleanupFailure, error); }
        if (cleanupFailure == null) {
            try {
                if ((persistedTreeModeFlags(target, treeA) & PERSISTED_TREE_MODE_FLAGS)
                        != PERSISTED_TREE_MODE_FLAGS) {
                    delegateSelectedQueueTreesFromHelper(target);
                    persistTargetQueueTreeGrant(target, treeA);
                }
                assertPersistedQueueTreeGrant(target, treeA);
                assertPersistedQueueTreeGrant(target, treeB);
            } catch (Throwable error) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, error);
            }
        }
        if (cleanupFailure == null) {
            try {
                QueueLifecycleDocumentsProvider.clearOutputWriteFailure(target, treeA);
                QueueLifecycleDocumentsProvider.setDeleteFailure(target, treeA, null);
            } catch (Throwable error) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, error);
            }
        }

        if (cleanupFailure == null) {
            try {
                if (id != null) cleanSafGrantLossRow(target, treeA, id);
                if (id != null) cleanupSafGrantLossPartialByExactReservation(target, treeA, id);
                String partialDocumentId = marker.getString(SAF_GRANT_LOSS_DOCUMENT_ID, "");
                String partialName = marker.getString(SAF_GRANT_LOSS_PARTIAL_NAME, "");
                if (!partialDocumentId.isEmpty() && documentIdExists(target, treeA, partialDocumentId)) {
                    Uri partial = DocumentsContract.buildDocumentUriUsingTree(treeA, partialDocumentId);
                    String actualName = queryDisplayName(target, partial);
                    if (!partialDocumentId.startsWith(
                            DocumentsContract.getTreeDocumentId(treeA) + "-")
                            || (!partialName.equals(actualName)
                            && !(partialName + ".mp4").equals(actualName))) {
                        throw new IOException("Refusing to remove a marked partial outside the exact row name");
                    }
                    QueueLifecycleDocumentsProvider.forceDeleteFixtureDocument(target, treeA,
                            partialDocumentId);
                    if (documentIdExists(target, treeA, partialDocumentId)) {
                        throw new IOException("The exact marked Tree A document remains after cleanup");
                    }
                }
                assertFixtureFolderIsEmpty(target, treeA);
            } catch (Throwable error) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, error);
            }
        }

        int originalTreeAFlags = marker.getInt(SAF_GRANT_LOSS_INITIAL_TREE_A_FLAGS, 0);
        int originalTreeBFlags = marker.getInt(SAF_GRANT_LOSS_INITIAL_TREE_B_FLAGS, 0);
        if (cleanupFailure == null) {
            try {
                restoreExactTargetTreeModes(target, treeA, originalTreeAFlags,
                        treeB, originalTreeBFlags);
                runHelperQueueTreeCleanup(target);
                Context helper = InstrumentationRegistry.getInstrumentation().getContext();
                revokePickerGrantForExactFixtureTree(helper, treeA,
                        QueueLifecycleDocumentsProvider.TREE_A);
                revokePickerGrantForExactFixtureTree(helper, treeB,
                        QueueLifecycleDocumentsProvider.TREE_B);
                verifyTargetQueueTreeGrantsAbsent(target, treeA, treeB);
                verifyHelperQueueTreeGrantsAbsent(target);
                android.content.SharedPreferences folder = target.getSharedPreferences(
                        DOWNLOAD_FOLDER_PREFS, Context.MODE_PRIVATE);
                if (originalFolderPresent != folder.contains(DOWNLOAD_FOLDER_PREF_KEY)
                        || (originalFolderPresent && !originalFolderUri.equals(
                                folder.getString(DOWNLOAD_FOLDER_PREF_KEY, null)))) {
                    throw new IOException("The original Downloads folder preference was not restored");
                }
            } catch (Throwable error) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, error);
            }
        }

        if (cleanupFailure == null) {
            if (!marker.edit().clear().commit()) {
                cleanupFailure = new IOException("Could not clear the exact SAF grant-loss marker");
            }
        }
        if (cleanupFailure instanceof Exception) throw (Exception) cleanupFailure;
        if (cleanupFailure instanceof Error) throw (Error) cleanupFailure;
        if (cleanupFailure != null) throw new AssertionError(cleanupFailure);
    }

    private static void cleanupSafGrantLossPartialByExactReservation(Context target, Uri treeA,
            String rowId) throws IOException {
        String exactPartialName = ".orbit-part-" + rowId;
        ArrayList<FixtureDocument> matches = new ArrayList<>();
        for (FixtureDocument document : listDocuments(target, treeA)) {
            if (exactPartialName.equals(document.displayName)
                    || (exactPartialName + ".mp4").equals(document.displayName)) {
                matches.add(document);
            }
        }
        if (matches.size() > 1) {
            throw new IOException("Multiple Tree A documents match the exact marked partial reservation");
        }
        if (matches.isEmpty()) return;
        FixtureDocument partial = matches.get(0);
        if (partial.id == null || !partial.id.startsWith(
                DocumentsContract.getTreeDocumentId(treeA) + "-")) {
            throw new IOException("The exact partial name resolves outside the canonical Tree A child namespace");
        }
        Uri document = DocumentsContract.buildDocumentUriUsingTree(treeA, partial.id);
        String actualName = queryDisplayName(target, document);
        if (!exactPartialName.equals(actualName)
                && !(exactPartialName + ".mp4").equals(actualName)) {
            throw new IOException("Refusing an unmarked partial whose exact Tree A name changed");
        }
        QueueLifecycleDocumentsProvider.forceDeleteFixtureDocument(target, treeA, partial.id);
        if (documentIdExists(target, treeA, partial.id)) {
            throw new IOException("The exact unmarked Tree A partial remains after cleanup");
        }
    }

    private static void restoreDownloadFolderPreference(Context target, Uri treeA, Uri treeB,
            boolean present, String originalUri) throws IOException {
        validateSavedFolderSnapshot(treeA, treeB, present, originalUri);
        android.content.SharedPreferences preferences = target.getSharedPreferences(
                DOWNLOAD_FOLDER_PREFS, Context.MODE_PRIVATE);
        boolean currentPresent = preferences.contains(DOWNLOAD_FOLDER_PREF_KEY);
        String currentUri = preferences.getString(DOWNLOAD_FOLDER_PREF_KEY, null);
        boolean matchesOriginal = currentPresent == present
                && (!present || originalUri.equals(currentUri));
        boolean matchesExactTreeA = currentPresent && treeA.toString().equals(currentUri);
        if (!matchesOriginal && !matchesExactTreeA) {
            throw new IOException("Refusing to overwrite a Downloads-folder preference changed outside this fixture");
        }
        if (matchesOriginal) return;
        android.content.SharedPreferences.Editor editor = preferences.edit();
        if (present) editor.putString(DOWNLOAD_FOLDER_PREF_KEY, originalUri);
        else editor.remove(DOWNLOAD_FOLDER_PREF_KEY);
        if (!editor.commit()) throw new IOException("Could not restore the original Downloads folder URI");
    }

    private static void pauseSafGrantLossQueue(Context target, String rowId) throws IOException {
        if (!(target.getApplicationContext() instanceof OrbitApplication)) return;
        DownloadQueueManager manager = ((OrbitApplication) target.getApplicationContext())
                .getDownloadQueueManager();
        if (manager == null) return;
        assertNoOtherPendingDownloadRows(target, rowId);
        manager.pauseQueue();
        long deadline = SystemClock.elapsedRealtime() + 10_000L;
        do {
            DownloadQueueStore store = new DownloadQueueStore(target);
            DownloadRecord item;
            try { item = rowId == null ? null : store.get(rowId); }
            finally { store.close(); }
            if (item == null || (!DownloadRecord.DOWNLOADING.equals(item.status)
                    && !DownloadRecord.REMOVING.equals(item.status))) return;
            SystemClock.sleep(50L);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new IOException("The synthetic queue item did not stop before exact cleanup");
    }

    private static void restoreExactTargetTreeModes(Context target, Uri treeA, int expectedA,
            Uri treeB, int expectedB) throws IOException {
        for (Uri tree : new Uri[]{treeA, treeB}) {
            int expected = tree.equals(treeA) ? expectedA : expectedB;
            int current = persistedTreeModeFlags(target, tree);
            int excess = current & ~expected;
            if (excess != 0) {
                try { target.getContentResolver().releasePersistableUriPermission(tree, excess); }
                catch (RuntimeException error) {
                    throw new IOException("Could not remove a test-added exact Tree A/B mode", error);
                }
            }
            current = persistedTreeModeFlags(target, tree);
            int missing = expected & ~current;
            if (missing != 0) {
                if (target.checkUriPermission(tree, Process.myPid(), Process.myUid(), missing)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    throw new IOException("The helper did not restore the exact prior Tree A/B grant");
                }
                try { target.getContentResolver().takePersistableUriPermission(tree, missing); }
                catch (RuntimeException error) {
                    throw new IOException("Could not restore the exact prior Tree A/B grant mode", error);
                }
            }
            if (persistedTreeModeFlags(target, tree) != expected) {
                throw new IOException("Exact pre-test Tree A/B grant mode mismatch");
            }
        }
    }

    private static void revokePickerGrantForExactFixtureTree(Context fixtureContext, Uri tree,
            String expectedRoot) throws IOException {
        if (Build.VERSION.SDK_INT < 26) {
            throw new IOException("Exact SAF tree revocation requires API 26 or later");
        }
        if (!QueueLifecycleDocumentsProvider.isCanonicalTreeRoot(tree, expectedRoot)) {
            throw new IOException("Refusing URI-only revocation outside its exact fixture root");
        }
        try { fixtureContext.revokeUriPermission(tree, PERSISTED_TREE_MODE_FLAGS); }
        catch (RuntimeException error) {
            throw new IOException("Could not revoke the picker-originated exact " + expectedRoot + " grant", error);
        }
    }

    private static void verifyHelperQueueTreeGrantsAbsent(Context target) throws Exception {
        String nonce = UUID.randomUUID().toString();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<List<String>> helperErrors = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!nonce.equals(intent.getStringExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_VERIFY_NONCE))) return;
                ArrayList<String> messages = intent.getStringArrayListExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_VERIFY_ERRORS);
                helperErrors.set(messages == null ? Collections.emptyList() : messages);
                completed.countDown();
            }
        };
        try {
            IntentFilter filter = new IntentFilter(
                    QueueLifecycleTreeGrantActivity.ACTION_VERIFY_NO_FIXTURE_GRANTS_RESULT);
            if (Build.VERSION.SDK_INT >= 33) {
                target.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                target.registerReceiver(receiver, filter);
            }
            Intent verify = new Intent(QueueLifecycleTreeGrantActivity.ACTION_VERIFY_NO_FIXTURE_GRANTS)
                    .setClassName("com.auras.orbit.test",
                            "com.auras.orbit.downloads.QueueLifecycleTreeGrantActivity")
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_VERIFY_NONCE, nonce)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            target.startActivity(verify);
            if (!completed.await(20L, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for helper-side fixture grant verification");
            }
            List<String> errors = helperErrors.get();
            if (errors != null && !errors.isEmpty()) {
                throw new IOException("Helper fixture grant verification failed: " + errors);
            }
        } finally {
            try { target.unregisterReceiver(receiver); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private static void revokeOrbitTreeGrantFromHelper(Context target, String expectedRoot)
            throws Exception {
        String nonce = UUID.randomUUID().toString();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<List<String>> helperErrors = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!nonce.equals(intent.getStringExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_REVOKE_NONCE))) return;
                ArrayList<String> messages = intent.getStringArrayListExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_REVOKE_ERRORS);
                helperErrors.set(messages == null ? Collections.emptyList() : messages);
                completed.countDown();
            }
        };
        try {
            IntentFilter filter = new IntentFilter(
                    QueueLifecycleTreeGrantActivity.ACTION_REVOKE_ORBIT_TREE_RESULT);
            if (Build.VERSION.SDK_INT >= 33) {
                target.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                target.registerReceiver(receiver, filter);
            }
            Intent revoke = new Intent(QueueLifecycleTreeGrantActivity.ACTION_REVOKE_ORBIT_TREE)
                    .setClassName("com.auras.orbit.test",
                            "com.auras.orbit.downloads.QueueLifecycleTreeGrantActivity")
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_REVOKE_ROOT, expectedRoot)
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_REVOKE_NONCE, nonce)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            target.startActivity(revoke);
            assertTrue("helper must revoke Orbit's exact Tree A package grant",
                    completed.await(20L, TimeUnit.SECONDS));
            List<String> errors = helperErrors.get();
            assertTrue("exact Tree A helper revoke must succeed without changing its own grant: "
                    + errors, errors == null || errors.isEmpty());
        } finally {
            try { target.unregisterReceiver(receiver); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private static byte[] readFixtureDocumentThroughHelper(Context target, Uri document)
            throws Exception {
        String nonce = UUID.randomUUID().toString();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<List<String>> helperErrors = new AtomicReference<>();
        AtomicReference<byte[]> documentBytes = new AtomicReference<>();
        AtomicReference<Integer> helperUid = new AtomicReference<>();
        AtomicReference<Integer> helperPid = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!nonce.equals(intent.getStringExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_READ_NONCE))) return;
                ArrayList<String> messages = intent.getStringArrayListExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_READ_ERRORS);
                helperErrors.set(messages == null ? Collections.emptyList() : messages);
                documentBytes.set(intent.getByteArrayExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_READ_DOCUMENT_BYTES));
                helperUid.set(intent.getIntExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_READ_HELPER_UID, -1));
                helperPid.set(intent.getIntExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_READ_HELPER_PID, -1));
                completed.countDown();
            }
        };
        try {
            IntentFilter filter = new IntentFilter(
                    QueueLifecycleTreeGrantActivity.ACTION_READ_FIXTURE_DOCUMENT_RESULT);
            if (Build.VERSION.SDK_INT >= 33) {
                target.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                target.registerReceiver(receiver, filter);
            }
            Intent read = new Intent(QueueLifecycleTreeGrantActivity.ACTION_READ_FIXTURE_DOCUMENT)
                    .setClassName("com.auras.orbit.test",
                            "com.auras.orbit.downloads.QueueLifecycleTreeGrantActivity")
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_READ_NONCE, nonce)
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_READ_DOCUMENT_URI,
                            document.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            target.startActivity(read);
            if (!completed.await(20L, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for helper-side fixture document read");
            }
            List<String> errors = helperErrors.get();
            if (errors != null && !errors.isEmpty()) {
                throw new IOException("Helper fixture document read failed: " + errors);
            }
            byte[] bytes = documentBytes.get();
            if (bytes == null) throw new IOException("Helper returned no fixture document bytes");
            assertTrue("fixture read must execute under the separate AndroidTest package UID",
                    helperUid.get() != null
                            && helperUid.get() != target.getApplicationInfo().uid);
            assertTrue("fixture read must report its helper process ID",
                    helperPid.get() != null && helperPid.get() > 0);
            return bytes;
        } finally {
            try { target.unregisterReceiver(receiver); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private static void validateRecoveryTreeAThroughHelper(Context target, String rowId,
            boolean rowPresent, String partialDocumentId, String partialName, String finalName)
            throws Exception {
        String nonce = UUID.randomUUID().toString();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<List<String>> helperErrors = new AtomicReference<>();
        AtomicReference<Integer> helperUid = new AtomicReference<>();
        AtomicReference<Integer> helperPid = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!nonce.equals(intent.getStringExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_RECOVERY_NONCE))) return;
                ArrayList<String> messages = intent.getStringArrayListExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_RECOVERY_ERRORS);
                helperErrors.set(messages == null ? Collections.emptyList() : messages);
                helperUid.set(intent.getIntExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_RECOVERY_HELPER_UID, -1));
                helperPid.set(intent.getIntExtra(
                        QueueLifecycleTreeGrantActivity.EXTRA_RECOVERY_HELPER_PID, -1));
                completed.countDown();
            }
        };
        try {
            IntentFilter filter = new IntentFilter(
                    QueueLifecycleTreeGrantActivity.ACTION_VALIDATE_RECOVERY_TREE_A_RESULT);
            if (Build.VERSION.SDK_INT >= 33) {
                target.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                target.registerReceiver(receiver, filter);
            }
            Intent validate = new Intent(
                    QueueLifecycleTreeGrantActivity.ACTION_VALIDATE_RECOVERY_TREE_A)
                    .setClassName("com.auras.orbit.test",
                            "com.auras.orbit.downloads.QueueLifecycleTreeGrantActivity")
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_RECOVERY_NONCE, nonce)
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_RECOVERY_ROW_ID,
                            rowId == null ? "" : rowId)
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_RECOVERY_ROW_PRESENT,
                            rowPresent)
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_RECOVERY_PARTIAL_ID,
                            partialDocumentId == null ? "" : partialDocumentId)
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_RECOVERY_PARTIAL_NAME,
                            partialName == null ? "" : partialName)
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_RECOVERY_FINAL_NAME,
                            finalName == null ? "" : finalName)
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_RECOVERY_PARTIAL_BYTES,
                            PARTIAL_BYTES)
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_RECOVERY_FINAL_BYTES,
                            SAF_GRANT_LOSS_BODY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            target.startActivity(validate);
            if (!completed.await(20L, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for helper-side Tree A recovery inventory");
            }
            List<String> errors = helperErrors.get();
            if (errors != null && !errors.isEmpty()) {
                throw new IOException("Helper Tree A recovery inventory failed: " + errors);
            }
            assertTrue("recovery inventory must run under the separate AndroidTest package UID",
                    helperUid.get() != null
                            && helperUid.get() != target.getApplicationInfo().uid);
            assertTrue("recovery inventory must report its helper process ID",
                    helperPid.get() != null && helperPid.get() > 0);
        } finally {
            try { target.unregisterReceiver(receiver); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private static File productionApplicationGate(Context target) {
        return new File(target.getFilesDir(),
                com.auras.orbit.testing.OrbitIsolatedAndroidJUnitRunner
                        .PRODUCTION_APPLICATION_GATE_FILE_NAME);
    }

    private static void assertProductionApplicationGate(Context target, String expectedNonce)
            throws IOException {
        File gate = productionApplicationGate(target);
        assertTrue("host must create the production-Application gate before verify", gate.isFile());
        try (InputStream input = new FileInputStream(gate);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            assertEquals("gate contents must exactly match this prepare/verify cycle nonce",
                    expectedNonce, new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static void clearProductionApplicationGate(Context target) throws IOException {
        File gate = productionApplicationGate(target);
        if (!gate.exists()) return;
        if (!gate.isFile()) {
            throw new IOException("Production-Application gate path is not a regular file");
        }
        if (!gate.delete() || gate.exists()) {
            throw new IOException("Could not remove the production-Application gate after verify");
        }
    }

    private boolean shouldPreserveSafDeleteFailureFixture(Context context, Bundle args) {
        return safDeleteFailureFixturePreparedInThisRun
                && SAF_DELETE_FAILURE_PREPARE_PHASE.equals(args.getString(SAF_DELETE_FAILURE_PHASE_ARG))
                && Boolean.parseBoolean(args.getString(SAF_DELETE_FAILURE_PRESERVE_ARG))
                && context.getSharedPreferences(SAF_DELETE_FAILURE_PREFS, Context.MODE_PRIVATE)
                        .contains(SAF_DELETE_FAILURE_ROW_ID);
    }

    private boolean shouldPreserveSafGrantLossFixture(Context context, Bundle args) {
        String phase = args.getString(SAF_GRANT_LOSS_PHASE_ARG);
        boolean cycleInProgress = SAF_GRANT_LOSS_PREPARE_PHASE.equals(phase)
                ? safGrantLossFixturePreparedInThisRun
                : SAF_GRANT_LOSS_COLD_START_PHASE.equals(phase);
        return cycleInProgress
                && Boolean.parseBoolean(args.getString(SAF_GRANT_LOSS_PRESERVE_ARG))
                && context.getSharedPreferences(SAF_GRANT_LOSS_PREFS, Context.MODE_PRIVATE)
                        .contains(SAF_GRANT_LOSS_ROW_ID);
    }

    private static Uri selectedQueueLifecycleTree(String rawTree, String expectedRoot) {
        assertNotNull("pass the exact selected " + expectedRoot + " tree as an instrumentation argument",
                rawTree);
        Uri tree;
        try { tree = Uri.parse(rawTree); }
        catch (RuntimeException malformed) {
            throw new AssertionError("host-selected queue lifecycle tree is not a URI", malformed);
        }
        assertTrue("host selection must be the canonical test-provider tree root",
                QueueLifecycleDocumentsProvider.isCanonicalTreeRoot(tree, expectedRoot));
        return tree;
    }

    private static void delegateSelectedQueueTreesFromHelper(Context target) throws Exception {
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
            if (Build.VERSION.SDK_INT >= 33) {
                target.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                target.registerReceiver(receiver, filter);
            }
            Intent delegate = new Intent(QueueLifecycleTreeGrantActivity.ACTION_DELEGATE_SELECTED_TREES)
                    .setClassName("com.auras.orbit.test",
                            "com.auras.orbit.downloads.QueueLifecycleTreeGrantActivity")
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_DELEGATE_NONCE, nonce)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            target.startActivity(delegate);
            assertTrue("helper must delegate the selected Tree A/B grants",
                    completed.await(20L, TimeUnit.SECONDS));
            List<String> errors = helperErrors.get();
            assertTrue("helper grant delegation must succeed for both selected roots: " + errors,
                    errors == null || errors.isEmpty());
        } finally {
            try { target.unregisterReceiver(receiver); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private static void persistTargetQueueTreeGrant(Context target, Uri tree) {
        assertEquals("Orbit must receive the exact delegated read/write fixture grant",
                android.content.pm.PackageManager.PERMISSION_GRANTED,
                target.checkUriPermission(tree, Process.myPid(), Process.myUid(),
                        PERSISTED_TREE_MODE_FLAGS));
        int modes = persistedTreeModeFlags(target, tree);
        int missing = PERSISTED_TREE_MODE_FLAGS & ~modes;
        if (missing != 0) target.getContentResolver().takePersistableUriPermission(tree, missing);
        assertPersistedQueueTreeGrant(target, tree);
    }

    private static void assertPersistedQueueTreeGrant(Context target, Uri tree) {
        boolean found = false;
        for (android.content.UriPermission permission : target.getContentResolver()
                .getPersistedUriPermissions()) {
            if (!tree.equals(permission.getUri())) continue;
            assertTrue("the exact selected fixture tree needs read permission",
                    permission.isReadPermission());
            assertTrue("the exact selected fixture tree needs write permission",
                    permission.isWritePermission());
            found = true;
        }
        assertTrue("Orbit must retain the exact selected fixture tree grant", found);
    }

    private static int persistedTreeModeFlags(Context target, Uri tree) {
        int modes = 0;
        for (android.content.UriPermission permission : target.getContentResolver()
                .getPersistedUriPermissions()) {
            if (!tree.equals(permission.getUri())) continue;
            if (permission.isReadPermission()) modes |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (permission.isWritePermission()) modes |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        return modes;
    }

    private static void saveSafDeleteFailureMarker(Context target, String cycleNonce, String id,
            Uri tree, String partialDocumentId, String partialName, int serverPort) {
        android.content.SharedPreferences marker = target.getSharedPreferences(
                SAF_DELETE_FAILURE_PREFS, Context.MODE_PRIVATE);
        boolean saved = marker.edit()
                .putString(SAF_DELETE_FAILURE_CYCLE_NONCE, cycleNonce)
                .putString(SAF_DELETE_FAILURE_ROW_ID, id)
                .putString(SAF_DELETE_FAILURE_TREE_URI, tree.toString())
                .putString(SAF_DELETE_FAILURE_DOCUMENT_ID,
                        partialDocumentId == null ? "" : partialDocumentId)
                .putString(SAF_DELETE_FAILURE_PARTIAL_NAME, partialName)
                .putInt(SAF_DELETE_FAILURE_SERVER_PORT, serverPort)
                .commit();
        assertTrue("the process-cycle marker must preserve the exact synthetic fixture", saved);
    }

    private static void cleanupStaleSafDeleteFailureRows(Context target, Uri treeA)
            throws Exception {
        QueueLifecycleDocumentsProvider.clearOutputWriteFailure(target, treeA);
        QueueLifecycleDocumentsProvider.setDeleteFailure(target, treeA, null);
        android.content.SharedPreferences marker = target.getSharedPreferences(
                SAF_DELETE_FAILURE_PREFS, Context.MODE_PRIVATE);
        String markedId = marker.getString(SAF_DELETE_FAILURE_ROW_ID, null);
        Set<String> rowIds = new HashSet<>();
        if (markedId != null) rowIds.add(markedId);
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            for (DownloadRecord item : store.listAll()) {
                if (!SAF_DELETE_FAILURE_PROFILE.equals(item.profileId)) continue;
                if (!treeA.toString().equals(item.folderUri)) {
                    throw new IOException("Refusing stale cleanup outside the dedicated Tree A profile row");
                }
                rowIds.add(item.id);
            }
        } finally {
            store.close();
        }
        for (String id : rowIds) cleanSafDeleteFailureRow(target, treeA, id);
        String markedDocumentId = marker.getString(SAF_DELETE_FAILURE_DOCUMENT_ID, "");
        if (!markedDocumentId.isEmpty()) {
            deleteMarkedSafFailureDocument(target, treeA, markedDocumentId,
                    marker.getString(SAF_DELETE_FAILURE_PARTIAL_NAME, ""));
        }
        if (!marker.edit().remove(SAF_DELETE_FAILURE_ROW_ID)
                .remove(SAF_DELETE_FAILURE_CYCLE_NONCE)
                .remove(SAF_DELETE_FAILURE_TREE_URI)
                .remove(SAF_DELETE_FAILURE_DOCUMENT_ID)
                .remove(SAF_DELETE_FAILURE_PARTIAL_NAME)
                .remove(SAF_DELETE_FAILURE_SERVER_PORT).commit()) {
            throw new IOException("Could not clear the stale SAF cold-start marker");
        }
    }

    private static void cleanupSafDeleteFailureFixture(Context target, String fallbackRowId)
            throws Exception {
        android.content.SharedPreferences marker = target.getSharedPreferences(
                SAF_DELETE_FAILURE_PREFS, Context.MODE_PRIVATE);
        String rawTree = marker.getString(SAF_DELETE_FAILURE_TREE_URI, null);
        if (rawTree == null) {
            rawTree = InstrumentationRegistry.getArguments().getString(QUEUE_TREE_A_ARG);
        }
        if (rawTree == null) return;
        Uri treeA = selectedQueueLifecycleTree(rawTree, QueueLifecycleDocumentsProvider.TREE_A);
        String markerId = marker.getString(SAF_DELETE_FAILURE_ROW_ID, null);
        String rowId = markerId == null ? fallbackRowId : markerId;
        Throwable cleanupFailure = null;
        try { QueueLifecycleDocumentsProvider.clearOutputWriteFailure(target, treeA); }
        catch (Throwable error) { cleanupFailure = appendCleanupFailure(cleanupFailure, error); }
        try { QueueLifecycleDocumentsProvider.setDeleteFailure(target, treeA, null); }
        catch (Throwable error) { cleanupFailure = appendCleanupFailure(cleanupFailure, error); }

        try {
            if (rowId != null) cleanSafDeleteFailureRow(target, treeA, rowId);
            else cleanupUnmarkedSafDeleteFailureRows(target, treeA);
            String markedDocumentId = marker.getString(SAF_DELETE_FAILURE_DOCUMENT_ID, "");
            if (!markedDocumentId.isEmpty()) {
                deleteMarkedSafFailureDocument(target, treeA, markedDocumentId,
                        marker.getString(SAF_DELETE_FAILURE_PARTIAL_NAME, ""));
            }
        } catch (Throwable error) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, error);
        }

        if (cleanupFailure == null) {
            try {
                runHelperQueueTreeCleanup(target);
                releaseTargetQueueTreeGrants(target);
                verifyTargetQueueTreeGrantsAbsent(target,
                        DocumentsContract.buildTreeDocumentUri(QueueLifecycleDocumentsProvider.AUTHORITY,
                                QueueLifecycleDocumentsProvider.TREE_A),
                        DocumentsContract.buildTreeDocumentUri(QueueLifecycleDocumentsProvider.AUTHORITY,
                                QueueLifecycleDocumentsProvider.TREE_B));
            } catch (Throwable error) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, error);
            }
        }

        if (cleanupFailure == null) {
            if (!marker.edit().remove(SAF_DELETE_FAILURE_ROW_ID)
                    .remove(SAF_DELETE_FAILURE_CYCLE_NONCE)
                    .remove(SAF_DELETE_FAILURE_TREE_URI)
                    .remove(SAF_DELETE_FAILURE_DOCUMENT_ID)
                    .remove(SAF_DELETE_FAILURE_PARTIAL_NAME)
                    .remove(SAF_DELETE_FAILURE_SERVER_PORT).commit()) {
                cleanupFailure = new IOException("Could not clear the SAF cold-start marker after cleanup");
            }
        }
        if (cleanupFailure != null) {
            if (cleanupFailure instanceof Exception) throw (Exception) cleanupFailure;
            if (cleanupFailure instanceof Error) throw (Error) cleanupFailure;
            throw new AssertionError(cleanupFailure);
        }
    }

    private static void cleanupUnmarkedSafDeleteFailureRows(Context target, Uri treeA)
            throws Exception {
        DownloadQueueStore store = new DownloadQueueStore(target);
        ArrayList<String> ids = new ArrayList<>();
        try {
            for (DownloadRecord item : store.listAll()) {
                if (!SAF_DELETE_FAILURE_PROFILE.equals(item.profileId)) continue;
                if (!treeA.toString().equals(item.folderUri)) {
                    throw new IOException("Refusing cleanup outside Tree A for the dedicated failure profile");
                }
                ids.add(item.id);
            }
        } finally {
            store.close();
        }
        for (String id : ids) cleanSafDeleteFailureRow(target, treeA, id);
    }

    private static void cleanSafDeleteFailureRow(Context target, Uri treeA, String id)
            throws Exception {
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            DownloadRecord item = store.get(id);
            if (item == null) return;
            if (!SAF_DELETE_FAILURE_PROFILE.equals(item.profileId)
                    || !treeA.toString().equals(item.folderUri)) {
                throw new IOException("Refusing cleanup outside the marked Tree A test row");
            }
            DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(id);
            String partialName = ".orbit-part-" + id;
            Set<String> allowedNames = new HashSet<>();
            allowedNames.add(partialName);
            allowedNames.add(partialName + ".mp4");
            if (retained != null && retained.partialName != null && !retained.partialName.isEmpty()
                    && !partialName.equals(retained.partialName)) {
                throw new IOException("Refusing to clean an unexpected partial reservation for the row");
            }
            if (item.fileName != null && item.fileName.startsWith(SAF_DELETE_FAILURE_FILE_PREFIX)) {
                allowedNames.add(item.fileName);
            }
            Set<String> documentIds = new HashSet<>();
            if (retained != null) {
                addQueueTreeDocumentId(retained.partialUri, treeA, documentIds);
                if (retained.fileName == null || !retained.fileName.startsWith(
                        SAF_DELETE_FAILURE_FILE_PREFIX)) {
                    if (retained.fileUri != null && !retained.fileUri.isEmpty()) {
                        throw new IOException("Refusing to clean a non-test final file reservation");
                    }
                } else {
                    allowedNames.add(retained.fileName);
                    addQueueTreeDocumentId(retained.fileUri, treeA, documentIds);
                }
            }
            addQueueTreeDocumentId(item.fileUri, treeA, documentIds);
            for (String allowedName : allowedNames) {
                String namedId = documentIdForName(target, treeA, allowedName);
                if (namedId != null) documentIds.add(namedId);
            }
            for (String documentId : documentIds) {
                Uri document = DocumentsContract.buildDocumentUriUsingTree(treeA, documentId);
                String actualName = queryDisplayName(target, document);
                if (actualName != null && !allowedNames.contains(actualName)) {
                    throw new IOException("Refusing to delete a Tree A child outside this test row's names");
                }
                QueueLifecycleDocumentsProvider.forceDeleteFixtureDocument(target, treeA, documentId);
            }
            store.delete(id);
        } finally {
            store.close();
        }
    }

    private static void deleteMarkedSafFailureDocument(Context target, Uri treeA,
            String documentId, String partialName) throws Exception {
        if (documentId == null || documentId.isEmpty()) return;
        if (!documentId.startsWith(DocumentsContract.getTreeDocumentId(treeA) + "-")) {
            throw new IOException("Refusing to clean a marked document outside Tree A");
        }
        if (!documentIdExists(target, treeA, documentId)) return;
        Uri document = DocumentsContract.buildDocumentUriUsingTree(treeA, documentId);
        String actualName = queryDisplayName(target, document);
        if (actualName == null || actualName.isEmpty()) {
            throw new IOException("Refusing to clean a marked document with no verifiable name");
        }
        if (!actualName.equals(partialName)
                && !actualName.equals(partialName + ".mp4")
                && !actualName.startsWith(SAF_DELETE_FAILURE_FILE_PREFIX)) {
            throw new IOException("Refusing to clean a marked document with an unexpected name");
        }
        QueueLifecycleDocumentsProvider.forceDeleteFixtureDocument(target, treeA, documentId);
    }

    private static void addQueueTreeDocumentId(String rawUri, Uri tree, Set<String> documentIds)
            throws IOException {
        if (rawUri == null || rawUri.isEmpty()) return;
        Uri document;
        try { document = Uri.parse(rawUri); }
        catch (RuntimeException malformed) {
            throw new IOException("Saved test fixture URI is malformed", malformed);
        }
        if (!DocumentsContract.isTreeUri(document)
                || !QueueLifecycleDocumentsProvider.AUTHORITY.equals(document.getAuthority())
                || !DocumentsContract.getTreeDocumentId(document)
                        .equals(DocumentsContract.getTreeDocumentId(tree))) {
            throw new IOException("Refusing to clean a test document outside exact Tree A");
        }
        documentIds.add(DocumentsContract.getDocumentId(document));
    }

    private static Throwable appendCleanupFailure(Throwable primary, Throwable additional) {
        if (primary == null) return additional;
        if (primary != additional) primary.addSuppressed(additional);
        return primary;
    }

    private static void runHelperQueueTreeCleanup(Context target) throws Exception {
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
        try {
            IntentFilter filter = new IntentFilter(QueueLifecycleTreeGrantActivity.ACTION_CLEANUP_RESULT);
            if (Build.VERSION.SDK_INT >= 33) {
                target.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                target.registerReceiver(receiver, filter);
            }
            Intent cleanup = new Intent(QueueLifecycleTreeGrantActivity.ACTION_CLEANUP_ALL)
                    .setClassName("com.auras.orbit.test",
                            "com.auras.orbit.downloads.QueueLifecycleTreeGrantActivity")
                    .putExtra(QueueLifecycleTreeGrantActivity.EXTRA_CLEANUP_NONCE, nonce)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            target.startActivity(cleanup);
            if (!completed.await(20L, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for helper cleanup-all completion");
            }
            List<String> errors = helperErrors.get();
            if (errors != null && !errors.isEmpty()) {
                throw new IOException("Helper cleanup-all reported errors: " + errors);
            }
        } finally {
            try { target.unregisterReceiver(receiver); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private static void releaseTargetQueueTreeGrants(Context target) throws IOException {
        Uri treeA = DocumentsContract.buildTreeDocumentUri(QueueLifecycleDocumentsProvider.AUTHORITY,
                QueueLifecycleDocumentsProvider.TREE_A);
        Uri treeB = DocumentsContract.buildTreeDocumentUri(QueueLifecycleDocumentsProvider.AUTHORITY,
                QueueLifecycleDocumentsProvider.TREE_B);
        for (Uri tree : new Uri[]{treeA, treeB}) {
            int modes = persistedTreeModeFlags(target, tree);
            if (modes == 0) continue;
            try { target.getContentResolver().releasePersistableUriPermission(tree, modes); }
            catch (RuntimeException error) {
                throw new IOException("Could not release Orbit's exact fixture tree grant", error);
            }
        }
    }

    private static void verifyTargetQueueTreeGrantsAbsent(Context target, Uri... trees)
            throws IOException {
        for (Uri tree : trees) {
            if (persistedTreeModeFlags(target, tree) != 0) {
                throw new IOException("Orbit still owns a persisted URI grant for " + tree);
            }
            for (int mode : new int[]{Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION}) {
                if (target.checkUriPermission(tree, Process.myPid(), Process.myUid(), mode)
                        != android.content.pm.PackageManager.PERMISSION_DENIED) {
                    throw new IOException("Orbit still owns transient URI access for " + tree);
                }
            }
        }
    }

    private static Uri markerPartialUri(Uri treeA, String documentId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeA, documentId);
    }

    private static DownloadQueueStore.RetainedDocuments retainedDocuments(Context target, String id) {
        DownloadQueueStore store = new DownloadQueueStore(target);
        try { return store.getRetainedDocuments(id); }
        finally { store.close(); }
    }

    private static DownloadRecord findQueueRecord(DownloadQueueManager manager, String id) {
        for (DownloadRecord item : manager.getItems()) if (id.equals(item.id)) return item;
        return null;
    }

    private static int reserveLoopbackPort() throws IOException {
        try (ServerSocket reservation = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            return reservation.getLocalPort();
        }
    }

    private static Dns loopbackFixtureDnsForLiteralAddress() throws IOException {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        Dns publicResolver = PlaybackDnsPinning.newDns(false);
        return hostname -> "127.0.0.1".equals(hostname)
                ? Collections.singletonList(loopback) : publicResolver.lookup(hostname);
    }

    private static Dns setProductionManagerFixtureDns(DownloadQueueManager manager)
            throws Exception {
        java.lang.reflect.Field field = DownloadQueueManager.class.getDeclaredField("testDnsOverride");
        field.setAccessible(true);
        Dns original = (Dns) field.get(manager);
        field.set(manager, loopbackFixtureDnsForLiteralAddress());
        return original;
    }

    private static void restoreProductionManagerDns(DownloadQueueManager manager, Dns original)
            throws Exception {
        java.lang.reflect.Field field = DownloadQueueManager.class.getDeclaredField("testDnsOverride");
        field.setAccessible(true);
        field.set(manager, original);
    }

    private static DownloadRequest request(String url, String stableId) {
        return new DownloadRequest(TEST_PROFILE, stableId, "download-test-source",
                "Synthetic queue fixture", TEST_FILE_PREFIX + stableId + ".mp4", "video/mp4", url, true);
    }

    private static DownloadQueueManager newDownloadQueueManagerWithDns(Context context, Dns dns)
            throws Exception {
        Constructor<DownloadQueueManager> constructor = DownloadQueueManager.class
                .getDeclaredConstructor(Context.class, Dns.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context, dns);
    }

    private static Dns loopbackFixtureDns() throws IOException {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        Dns publicResolver = PlaybackDnsPinning.newDns(false);
        return hostname -> "download-origin.example".equals(hostname)
                ? Collections.singletonList(loopback) : publicResolver.lookup(hostname);
    }

    private static String requireProcessDeathPhase(String expectedPhase) {
        Bundle args = InstrumentationRegistry.getArguments();
        assertEquals("process-cycle instrumentation phase argument is required", expectedPhase,
                args.getString(PROCESS_CYCLE_PHASE_ARG));
        assertEquals("fixture preservation is enabled only for the prepare phase",
                PROCESS_CYCLE_PREPARE_PHASE.equals(expectedPhase),
                processDeathPreserveRequested(args));
        return requireProcessDeathCycleNonce(args);
    }

    private static String requireProcessDeathCycleNonce(Bundle args) {
        String nonce = args.getString(PROCESS_CYCLE_CYCLE_NONCE_ARG);
        assertNotNull("pass a unique cycle nonce to correlate process-restart phases", nonce);
        try {
            assertEquals("the process-restart cycle nonce must be canonical UUID text", nonce,
                    UUID.fromString(nonce).toString());
        } catch (IllegalArgumentException malformed) {
            throw new AssertionError("the process-restart cycle nonce must be canonical UUID text",
                    malformed);
        }
        return nonce;
    }

    private static String requireCompletedRemovalPhase(String expectedPhase) {
        Bundle args = InstrumentationRegistry.getArguments();
        assertEquals("completed-removal process-cycle phase argument is required", expectedPhase,
                args.getString(COMPLETED_REMOVAL_PHASE_ARG));
        assertEquals("fixture preservation is enabled only for completed-removal prepare",
                COMPLETED_REMOVAL_PREPARE_PHASE.equals(expectedPhase),
                Boolean.parseBoolean(args.getString(COMPLETED_REMOVAL_PRESERVE_ARG)));
        String nonce = args.getString(COMPLETED_REMOVAL_NONCE_ARG);
        assertNotNull("a host-generated nonce must identify this completed-removal cycle", nonce);
        try {
            assertEquals("the completed-removal nonce must be canonical UUID text", nonce,
                    UUID.fromString(nonce).toString());
        } catch (IllegalArgumentException malformed) {
            throw new AssertionError("the completed-removal nonce must be canonical UUID text",
                    malformed);
        }
        return nonce;
    }

    private boolean shouldPreservePreparedCompletedRemovalFixture(Context target) {
        Bundle args = InstrumentationRegistry.getArguments();
        android.content.SharedPreferences marker = target.getSharedPreferences(
                COMPLETED_REMOVAL_PREFS, Context.MODE_PRIVATE);
        return completedRemovalFixturePreparedInThisRun
                && COMPLETED_REMOVAL_PREPARE_PHASE.equals(
                        args.getString(COMPLETED_REMOVAL_PHASE_ARG))
                && Boolean.parseBoolean(args.getString(COMPLETED_REMOVAL_PRESERVE_ARG))
                && marker.contains(COMPLETED_REMOVAL_ROW_ID)
                && marker.getBoolean(COMPLETED_REMOVAL_REMOVING_INTENT, false);
    }

    private static boolean isCompletedRemovalStableId(String value) {
        String prefix = "completed-removal-";
        if (value == null || !value.startsWith(prefix)) return false;
        try {
            String uuid = value.substring(prefix.length());
            return value.equals(prefix + UUID.fromString(uuid).toString());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean matchesCompletedRemovalRow(DownloadRecord row, String stableId,
            String rowId, Uri tree, String fileName) {
        return row != null && COMPLETED_REMOVAL_PROFILE.equals(row.profileId)
                && stableId.equals(row.stableItemId)
                && COMPLETED_REMOVAL_SOURCE_ID.equals(row.sourceId)
                && COMPLETED_REMOVAL_TITLE.equals(row.title)
                && rowId.equals(row.id) && tree.toString().equals(row.folderUri)
                && "video/mp4".equals(row.mimeType) && fileName.equals(row.fileName);
    }

    private static void assertCompletedRemovalMarkerIdentity(
            android.content.SharedPreferences marker, String nonce, Uri tree) {
        assertFalse("the completed-removal marker must remain present until exact cleanup",
                marker.getAll().isEmpty());
        assertEquals("the marker must match the host cycle nonce", nonce,
                marker.getString(COMPLETED_REMOVAL_NONCE, null));
        assertEquals("the marker must refer to the exact selected ExternalStorageProvider tree",
                tree.toString(), marker.getString(COMPLETED_REMOVAL_TREE_URI, null));
        assertTrue("the marker must contain a canonical completed-removal stable ID",
                isCompletedRemovalStableId(marker.getString(COMPLETED_REMOVAL_STABLE_ID, null)));
        String stableId = marker.getString(COMPLETED_REMOVAL_STABLE_ID, null);
        assertEquals("the requested manager filename must match this exact stable ID",
                COMPLETED_REMOVAL_FILE_PREFIX + stableId + ".mp4",
                marker.getString(COMPLETED_REMOVAL_REQUESTED_NAME, null));
        assertEquals("recovery must not overwrite the selected Downloads preference",
                tree.toString(), selectedFixtureFolderOrNull(
                        InstrumentationRegistry.getInstrumentation().getTargetContext()).toString());
    }

    private static void assertCompletedRemovalSentinel(Context target, Uri tree,
            android.content.SharedPreferences marker) throws IOException {
        String expectedName = COMPLETED_REMOVAL_FILE_PREFIX + "Sentinel-"
                + marker.getString(COMPLETED_REMOVAL_NONCE, "") + ".txt";
        String expectedHash = sha256Hex(COMPLETED_REMOVAL_SENTINEL_BYTES);
        assertEquals("the sentinel name must be derived from the exact cycle nonce", expectedName,
                marker.getString(COMPLETED_REMOVAL_SENTINEL_NAME, null));
        assertEquals("the sentinel marker must carry the exact fixture payload digest",
                expectedHash, marker.getString(COMPLETED_REMOVAL_SENTINEL_SHA256, null));
        String documentId = marker.getString(COMPLETED_REMOVAL_SENTINEL_DOCUMENT_ID, null);
        assertNotNull("the prepared cycle must retain the exact sentinel document ID", documentId);
        assertTrue("the neighboring sentinel must survive production startup",
                documentIdExists(target, tree, documentId));
        Uri sentinel = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
        assertEquals("the neighboring sentinel display name must remain exact", expectedName,
                queryDisplayName(target, sentinel));
        byte[] actual = readDocument(target, sentinel);
        assertArrayEquals("the neighboring sentinel bytes must remain exact",
                COMPLETED_REMOVAL_SENTINEL_BYTES, actual);
        assertEquals("the neighboring sentinel digest must remain exact", expectedHash,
                sha256Hex(actual));
    }

    private static void assertCompletedRemovalOriginalNeighborsUnchanged(Context target, Uri tree,
            android.content.SharedPreferences marker, String rowId) {
        String original = marker.getString(COMPLETED_REMOVAL_ORIGINAL_INVENTORY, null);
        assertNotNull("the cycle marker must preserve the original tree inventory", original);
        assertEquals("startup must preserve all pre-existing children in the fixture tree",
                original, encodeFixtureInventory(listDocuments(target, tree),
                        completedRemovalOwnedNames(marker, rowId)));
    }

    private static Set<String> completedRemovalOwnedNames(
            android.content.SharedPreferences marker, String rowId) {
        HashSet<String> names = new HashSet<>();
        String fileName = marker.getString(COMPLETED_REMOVAL_FILE_NAME, "");
        String sentinelName = marker.getString(COMPLETED_REMOVAL_SENTINEL_NAME, "");
        if (!fileName.isEmpty()) names.add(fileName);
        if (!sentinelName.isEmpty()) names.add(sentinelName);
        String requestedName = marker.getString(COMPLETED_REMOVAL_REQUESTED_NAME, "");
        if (!requestedName.isEmpty()) names.add(requestedName);
        return names;
    }

    private static String completedRemovalQueueSnapshot(Context target, String excludedId) {
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            ArrayList<DownloadRecord> rows = new ArrayList<>();
            for (DownloadRecord row : store.listAll()) {
                if (excludedId != null && excludedId.equals(row.id)) continue;
                rows.add(row);
            }
            Collections.sort(rows, (left, right) -> left.id.compareTo(right.id));
            StringBuilder encoded = new StringBuilder();
            for (DownloadRecord row : rows) {
                if (encoded.length() > 0) encoded.append('\n');
                appendEncodedQueueField(encoded, row.id);
                appendEncodedQueueField(encoded, row.profileId);
                appendEncodedQueueField(encoded, row.stableItemId);
                appendEncodedQueueField(encoded, row.sourceId);
                appendEncodedQueueField(encoded, row.title);
                appendEncodedQueueField(encoded, row.fileName);
                appendEncodedQueueField(encoded, row.mimeType);
                appendEncodedQueueField(encoded, row.status);
                appendEncodedQueueField(encoded, Long.toString(row.bytesDownloaded));
                appendEncodedQueueField(encoded, Long.toString(row.totalBytes));
                appendEncodedQueueField(encoded, Long.toString(row.createdAt));
                appendEncodedQueueField(encoded, row.fileUri);
                appendEncodedQueueField(encoded, row.folderUri);
                appendEncodedQueueField(encoded, row.errorMessage);
                appendEncodedQueueField(encoded, Long.toString(row.queueOrder));
                appendEncodedQueueField(encoded, row.seasonLabel);
                appendEncodedQueueField(encoded, row.episodeLabel);
                appendEncodedQueueField(encoded, Long.toString(row.knownSizeBytes));
                appendEncodedQueueField(encoded, row.artworkReference);
                DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(row.id);
                appendEncodedQueueField(encoded, retained == null ? "" : retained.partialUri);
                appendEncodedQueueField(encoded, retained == null ? "" : retained.partialName);
                appendEncodedQueueField(encoded, retained == null ? "" : retained.fileUri);
                appendEncodedQueueField(encoded, retained == null ? "" : retained.folderUri);
                appendEncodedQueueField(encoded, retained == null ? "" : retained.fileName);
            }
            return encoded.toString();
        } finally {
            store.close();
        }
    }

    private static void assertNoQueueStableItemId(Context target, String stableId)
            throws IOException {
        DownloadQueueStore store = new DownloadQueueStore(target);
        try {
            for (DownloadRecord row : store.listAll()) {
                if (stableId.equals(row.stableItemId)) {
                    throw new IOException("The generated completed-removal stable ID already belongs to a queue row");
                }
            }
        } finally {
            store.close();
        }
    }

    private static void appendEncodedQueueField(StringBuilder destination, String value) {
        if (destination.length() > 0 && destination.charAt(destination.length() - 1) != '\n') {
            destination.append('|');
        }
        destination.append(Uri.encode(value == null ? "" : value));
    }

    private static String completedRemovalPreferenceSnapshot(Context target) {
        String[] names = new String[]{"orbit_shell_local_profiles_v1",
                "orbit_provider_selection_v1", DOWNLOAD_FOLDER_PREFS};
        StringBuilder snapshot = new StringBuilder();
        for (String name : names) {
            if (snapshot.length() > 0) snapshot.append('\n');
            snapshot.append(Uri.encode(name)).append(':')
                    .append(Uri.encode(encodeSharedPreferenceContents(target, name)));
        }
        return snapshot.toString();
    }

    private static String encodeSharedPreferenceContents(Context target, String name) {
        TreeMap<String, ?> values = new TreeMap<>(target.getSharedPreferences(name,
                Context.MODE_PRIVATE).getAll());
        StringBuilder encoded = new StringBuilder();
        for (java.util.Map.Entry<String, ?> entry : values.entrySet()) {
            if (encoded.length() > 0) encoded.append('\n');
            Object value = entry.getValue();
            String canonical;
            if (value instanceof Set<?>) {
                ArrayList<String> members = new ArrayList<>();
                for (Object member : (Set<?>) value) members.add(String.valueOf(member));
                Collections.sort(members);
                canonical = members.toString();
            } else {
                canonical = String.valueOf(value);
            }
            encoded.append(Uri.encode(entry.getKey())).append(':')
                    .append(value == null ? "null" : value.getClass().getName()).append(':')
                    .append(Uri.encode(canonical));
        }
        return encoded.toString();
    }

    private static String sha256Hex(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean isPrefixOf(byte[] prefix, byte[] whole) {
        if (prefix == null || whole == null || prefix.length > whole.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (prefix[index] != whole[index]) return false;
        }
        return true;
    }

    private static String completedRemovalDocumentIdFromUri(String rawUri, Uri tree)
            throws IOException {
        if (rawUri == null || rawUri.isEmpty()) return null;
        Uri document;
        try { document = Uri.parse(rawUri); }
        catch (RuntimeException malformed) {
            throw new IOException("The completed-removal document URI is malformed", malformed);
        }
        if (!isWithinTree(document, tree)) {
            throw new IOException("Refusing a completed-removal document outside the exact fixture tree");
        }
        String documentId;
        try { documentId = DocumentsContract.getDocumentId(document); }
        catch (RuntimeException malformed) {
            throw new IOException("The completed-removal URI lacks a child document ID", malformed);
        }
        Uri canonical = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
        if (!canonical.equals(document)) {
            throw new IOException("The completed-removal URI is not the canonical exact-tree child URI");
        }
        return documentId;
    }

    private static CompletedRemovalFixtureIdentity validateCompletedRemovalFixtureForCleanup(
            Context target, android.content.SharedPreferences marker, Uri tree) throws Exception {
        String nonce = requireCompletedRemovalCycleNonce(InstrumentationRegistry.getArguments());
        assertCompletedRemovalMarkerIdentity(marker, nonce, tree);
        assertPersistedTreeGrant(target, tree);
        String originalInventory = marker.getString(COMPLETED_REMOVAL_ORIGINAL_INVENTORY, null);
        String originalQueue = marker.getString(COMPLETED_REMOVAL_ORIGINAL_QUEUE, null);
        String originalPreferences = marker.getString(COMPLETED_REMOVAL_ORIGINAL_PREFERENCES, null);
        if (originalInventory == null || originalQueue == null || originalPreferences == null
                || !originalInventory.isEmpty()) {
            throw new IOException("The completed-removal marker lacks its required empty-tree baseline");
        }
        String stableId = marker.getString(COMPLETED_REMOVAL_STABLE_ID, null);
        String requestedName = marker.getString(COMPLETED_REMOVAL_REQUESTED_NAME, null);
        String sentinelName = marker.getString(COMPLETED_REMOVAL_SENTINEL_NAME, null);
        String sentinelHash = marker.getString(COMPLETED_REMOVAL_SENTINEL_SHA256, null);
        String expectedSentinelName = COMPLETED_REMOVAL_FILE_PREFIX + "Sentinel-" + nonce + ".txt";
        String expectedSentinelHash = sha256Hex(COMPLETED_REMOVAL_SENTINEL_BYTES);
        if (!isCompletedRemovalStableId(stableId)
                || !Objects.equals(requestedName, COMPLETED_REMOVAL_FILE_PREFIX + stableId + ".mp4")
                || !Objects.equals(sentinelName, expectedSentinelName)
                || !Objects.equals(sentinelHash, expectedSentinelHash)) {
            throw new IOException("The completed-removal marker identity is malformed");
        }

        String markedRowId = marker.getString(COMPLETED_REMOVAL_ROW_ID, null);
        String markedFileName = marker.getString(COMPLETED_REMOVAL_FILE_NAME, null);
        String markedFileUri = marker.getString(COMPLETED_REMOVAL_FILE_URI, null);
        String markedFileId = marker.getString(COMPLETED_REMOVAL_FILE_DOCUMENT_ID, null);
        String markedHash = marker.getString(COMPLETED_REMOVAL_FILE_SHA256, null);
        String expectedHash = sha256Hex(COMPLETED_REMOVAL_FILE_BYTES);
        if (markedHash != null && !expectedHash.equals(markedHash)) {
            throw new IOException("The completed-removal marker contains an unexpected payload digest");
        }
        if ((markedFileUri != null || markedFileId != null) && !expectedHash.equals(markedHash)) {
            throw new IOException("The completed-removal file identity lacks its expected SHA-256 digest");
        }
        DownloadQueueStore store = new DownloadQueueStore(target);
        DownloadRecord row = null;
        String rowId = markedRowId;
        try {
            DownloadRecord rowByMarkedId = markedRowId == null ? null : store.get(markedRowId);
            if (rowByMarkedId != null && !stableId.equals(rowByMarkedId.stableItemId)) {
                throw new IOException("The marked completed-removal row ID belongs to another queue item");
            }
            int stableRows = 0;
            for (DownloadRecord candidate : store.listAll()) {
                if (!stableId.equals(candidate.stableItemId)) continue;
                stableRows++;
                if (row != null) throw new IOException("Multiple rows match the exact completed-removal stable ID");
                row = candidate;
                rowId = candidate.id;
            }
            if (stableRows > 1 || (markedRowId != null && row != null
                    && !markedRowId.equals(row.id))) {
                throw new IOException("The completed-removal row ID is ambiguous or changed");
            }
            if (row == null && rowByMarkedId != null) row = rowByMarkedId;
            if (rowId != null && !rowId.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                throw new IOException("The completed-removal row ID is not canonical UUID text");
            }
            String expectedFileName = rowId == null ? null
                    : processCycleUniqueFileName(requestedName, rowId);
            if (markedFileName != null && expectedFileName != null
                    && !expectedFileName.equals(markedFileName)) {
                throw new IOException("The marker filename does not match the manager-generated unique name");
            }
            if (row != null) {
                if (expectedFileName == null || !matchesCompletedRemovalRow(row, stableId,
                        row.id, tree, expectedFileName)) {
                    throw new IOException("The marked queue row does not match its exact completed-removal identity");
                }
                if (!DownloadRecord.QUEUED.equals(row.status)
                        && !DownloadRecord.COMPLETED.equals(row.status)
                        && !DownloadRecord.REMOVING.equals(row.status)) {
                    throw new IOException("The completed-removal row has an unexpected status");
                }
                DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(row.id);
                if (retained == null || !tree.toString().equals(retained.folderUri)
                        || !expectedFileName.equals(retained.fileName)
                        || (retained.partialUri != null && !retained.partialUri.isEmpty())
                        || (retained.partialName != null && !retained.partialName.isEmpty())) {
                    throw new IOException("The completed-removal row has unexpected retained-document state");
                }
                if (DownloadRecord.QUEUED.equals(row.status)) {
                    if ((row.fileUri != null && !row.fileUri.isEmpty())
                            || (retained.fileUri != null && !retained.fileUri.isEmpty())
                            || row.bytesDownloaded != 0L || row.totalBytes != 0L) {
                        throw new IOException("A queued setup row contains unexpected completion state");
                    }
                } else {
                    if (!marker.getBoolean(COMPLETED_REMOVAL_REMOVING_INTENT, false)
                            && DownloadRecord.REMOVING.equals(row.status)) {
                        throw new IOException("A REMOVING row lacks its durable operation marker");
                    }
                    String rowFileId = completedRemovalDocumentIdFromUri(row.fileUri, tree);
                    if (rowFileId == null || row.bytesDownloaded != COMPLETED_REMOVAL_FILE_BYTES.length
                            || row.totalBytes != COMPLETED_REMOVAL_FILE_BYTES.length
                            || !row.fileUri.equals(retained.fileUri)) {
                        throw new IOException("The completed/removing row lacks its exact complete file reference");
                    }
                }
            }
            assertNoOtherStartupRecoveryEligibleRows(store, rowId);
            if (!store.getPendingRenames().isEmpty()) {
                throw new IOException("Refusing completed-removal recovery while a rename journal exists");
            }
        } finally {
            store.close();
        }
        assertNoQueueRowsForFixtureTree(target, tree, rowId);
        if (!originalQueue.equals(completedRemovalQueueSnapshot(target, rowId))) {
            throw new IOException("Unrelated download queue rows changed during completed-removal startup");
        }
        if (!originalPreferences.equals(completedRemovalPreferenceSnapshot(target))) {
            throw new IOException("User profile/provider/Downloads preferences changed during the cycle");
        }

        String expectedFileName = rowId == null ? markedFileName
                : processCycleUniqueFileName(requestedName, rowId);
        String exactFileId = markedFileId;
        String rowFileId = row == null ? null : completedRemovalDocumentIdFromUri(row.fileUri, tree);
        if (markedFileUri != null) {
            String uriId = completedRemovalDocumentIdFromUri(markedFileUri, tree);
            if (markedFileId == null || !uriId.equals(markedFileId)) {
                throw new IOException("Marker URI and document ID do not identify the same exact file");
            }
        } else if (markedFileId != null) {
            throw new IOException("The completed-removal marker has a document ID without its URI");
        }
        if (rowFileId != null && exactFileId != null && !rowFileId.equals(exactFileId)) {
            throw new IOException("The queue row and marker point to different completed documents");
        }
        if (exactFileId == null) exactFileId = rowFileId;
        FixtureDocument namedFile = expectedFileName == null ? null
                : findUniqueCompletedRemovalDocumentByName(target, tree, expectedFileName);
        if (namedFile != null && exactFileId != null && !namedFile.id.equals(exactFileId)) {
            throw new IOException("The exact filename and stored document ID refer to different files");
        }
        if (exactFileId == null && namedFile != null) exactFileId = namedFile.id;
        if (exactFileId != null && expectedFileName == null) {
            throw new IOException("A completed output document exists without its exact row-derived filename");
        }
        FixtureDocument exactFile = exactFileId == null ? null
                : findCompletedRemovalDocumentById(target, tree, exactFileId);
        if (exactFile != null) {
            if (!expectedFileName.equals(exactFile.displayName)) {
                throw new IOException("The exact completed file ID has an unexpected display name");
            }
            Uri file = DocumentsContract.buildDocumentUriUsingTree(tree, exactFile.id);
            byte[] actual = readDocument(target, file);
            if (row != null && (DownloadRecord.COMPLETED.equals(row.status)
                    || DownloadRecord.REMOVING.equals(row.status))) {
                if (!java.util.Arrays.equals(COMPLETED_REMOVAL_FILE_BYTES, actual)) {
                    throw new IOException("The completed/removing file bytes changed before recovery");
                }
            } else if (!isPrefixOf(actual, COMPLETED_REMOVAL_FILE_BYTES)) {
                throw new IOException("A setup-only output is not a prefix of the known fixture payload");
            }
            if (markedHash != null && actual.length == COMPLETED_REMOVAL_FILE_BYTES.length
                    && !markedHash.equals(sha256Hex(actual))) {
                throw new IOException("The completed-removal file digest differs from its durable marker");
            }
        } else if (row != null && DownloadRecord.COMPLETED.equals(row.status)
                && !marker.getBoolean(COMPLETED_REMOVAL_CLEANUP_STARTED, false)) {
            throw new IOException("A COMPLETED row lost its file before a REMOVING transition");
        } else if (row == null && exactFile != null) {
            throw new IOException("The queue row is absent while its completed output still exists");
        }

        String sentinelId = marker.getString(COMPLETED_REMOVAL_SENTINEL_DOCUMENT_ID, null);
        FixtureDocument namedSentinel = findUniqueCompletedRemovalDocumentByName(target, tree,
                expectedSentinelName);
        if (sentinelId != null && namedSentinel != null && !sentinelId.equals(namedSentinel.id)) {
            throw new IOException("The sentinel name and saved document ID do not match");
        }
        if (sentinelId == null && namedSentinel != null) sentinelId = namedSentinel.id;
        FixtureDocument exactSentinel = sentinelId == null ? null
                : findCompletedRemovalDocumentById(target, tree, sentinelId);
        if (exactSentinel != null) {
            if (!expectedSentinelName.equals(exactSentinel.displayName)) {
                throw new IOException("The sentinel document ID has an unexpected display name");
            }
            byte[] actual = readDocument(target,
                    DocumentsContract.buildDocumentUriUsingTree(tree, exactSentinel.id));
            if (!isPrefixOf(actual, COMPLETED_REMOVAL_SENTINEL_BYTES)) {
                throw new IOException("The setup sentinel is not a prefix of its exact fixture bytes");
            }
            if (actual.length == COMPLETED_REMOVAL_SENTINEL_BYTES.length
                    && !sentinelHash.equals(sha256Hex(actual))) {
                throw new IOException("The setup sentinel digest differs from its marker");
            }
        }

        HashSet<String> allowedIds = new HashSet<>();
        if (exactFileId != null && exactFile != null) allowedIds.add(exactFileId);
        if (sentinelId != null && exactSentinel != null) allowedIds.add(sentinelId);
        for (FixtureDocument child : listDocuments(target, tree)) {
            if (!allowedIds.contains(child.id)) {
                throw new IOException("An unexpected child exists in the completed-removal fixture tree");
            }
        }
        return new CompletedRemovalFixtureIdentity(rowId, expectedFileName, exactFileId, sentinelId);
    }

    private static void cleanupCompletedRemovalFixture(Context target) throws Exception {
        android.content.SharedPreferences marker = target.getSharedPreferences(
                COMPLETED_REMOVAL_PREFS, Context.MODE_PRIVATE);
        if (marker.getAll().isEmpty()) return;
        Uri tree = Uri.parse(marker.getString(COMPLETED_REMOVAL_TREE_URI, ""));
        if (!isFixtureTree(tree) || !tree.equals(selectedFixtureFolderOrNull(target))) {
            throw new IOException("Refusing completed-removal cleanup after the selected tree changed");
        }
        CompletedRemovalFixtureIdentity identity =
                validateCompletedRemovalFixtureForCleanup(target, marker, tree);
        if (!marker.getBoolean(COMPLETED_REMOVAL_CLEANUP_STARTED, false)
                && !marker.edit().putBoolean(COMPLETED_REMOVAL_CLEANUP_STARTED, true).commit()) {
            throw new IOException("Could not record the exact completed-removal cleanup phase");
        }

        if (identity.fileDocumentId != null) {
            deleteCompletedRemovalFixtureDocument(target, tree, identity.fileDocumentId,
                    identity.fileName, COMPLETED_REMOVAL_FILE_BYTES);
        }
        if (identity.rowId != null) {
            DownloadQueueStore store = new DownloadQueueStore(target);
            try {
                DownloadRecord row = store.get(identity.rowId);
                if (row != null) {
                    if (!matchesCompletedRemovalRow(row,
                            marker.getString(COMPLETED_REMOVAL_STABLE_ID, null), row.id,
                            tree, identity.fileName)) {
                        throw new IOException("Refusing to remove a queue row that changed identity");
                    }
                    store.delete(identity.rowId);
                }
                if (store.get(identity.rowId) != null) {
                    throw new IOException("The exact completed-removal queue row remains after cleanup");
                }
            } finally {
                store.close();
            }
        }
        if (identity.sentinelDocumentId != null) {
            deleteCompletedRemovalFixtureDocument(target, tree, identity.sentinelDocumentId,
                    marker.getString(COMPLETED_REMOVAL_SENTINEL_NAME, null),
                    COMPLETED_REMOVAL_SENTINEL_BYTES);
        }

        if (identity.rowId != null) {
            DownloadQueueStore store = new DownloadQueueStore(target);
            try {
                if (store.get(identity.rowId) != null) {
                    throw new IOException("The exact completed-removal row returned after deletion");
                }
                for (DownloadRecord row : store.listAll()) {
                    if (marker.getString(COMPLETED_REMOVAL_STABLE_ID, null)
                            .equals(row.stableItemId)) {
                        throw new IOException("A completed-removal stable ID remains in the queue");
                    }
                }
            } finally {
                store.close();
            }
        }
        if (!marker.getString(COMPLETED_REMOVAL_ORIGINAL_INVENTORY, null)
                .equals(encodeFixtureInventory(listDocuments(target, tree),
                        Collections.<String>emptySet()))) {
            throw new IOException("The completed-removal cleanup did not restore the original tree inventory");
        }
        if (!marker.getString(COMPLETED_REMOVAL_ORIGINAL_QUEUE, null)
                .equals(completedRemovalQueueSnapshot(target, identity.rowId))) {
            throw new IOException("The completed-removal cleanup changed unrelated queue rows");
        }
        if (!marker.getString(COMPLETED_REMOVAL_ORIGINAL_PREFERENCES, null)
                .equals(completedRemovalPreferenceSnapshot(target))) {
            throw new IOException("The completed-removal cleanup changed user preferences");
        }
        if (!marker.edit().clear().commit() || !marker.getAll().isEmpty()) {
            throw new IOException("Could not clear the completed-removal marker after exact cleanup");
        }
    }

    private static FixtureDocument findUniqueCompletedRemovalDocumentByName(Context target,
            Uri tree, String exactName) throws IOException {
        if (exactName == null || exactName.isEmpty()) return null;
        FixtureDocument found = null;
        for (FixtureDocument child : listDocuments(target, tree)) {
            if (!exactName.equals(child.displayName)) continue;
            if (found != null) {
                throw new IOException("Multiple fixture children share an exact completed-removal name");
            }
            found = child;
        }
        return found;
    }

    private static FixtureDocument findCompletedRemovalDocumentById(Context target, Uri tree,
            String documentId) throws IOException {
        if (documentId == null || documentId.isEmpty()) return null;
        FixtureDocument found = null;
        for (FixtureDocument child : listDocuments(target, tree)) {
            if (!documentId.equals(child.id)) continue;
            if (found != null) throw new IOException("A document ID appeared more than once in the fixture tree");
            found = child;
        }
        return found;
    }

    private static void deleteCompletedRemovalFixtureDocument(Context target, Uri tree,
            String documentId, String exactName, byte[] expectedBytes) throws Exception {
        FixtureDocument exact = findCompletedRemovalDocumentById(target, tree, documentId);
        if (exact == null) return;
        if (exactName == null || !exactName.equals(exact.displayName)) {
            throw new IOException("Refusing to delete a completed-removal document with an unexpected name");
        }
        byte[] current = readDocument(target,
                DocumentsContract.buildDocumentUriUsingTree(tree, documentId));
        if (!isPrefixOf(current, expectedBytes)) {
            throw new IOException("Refusing to delete a fixture document with unexpected bytes");
        }
        DocumentsContract.deleteDocument(target.getContentResolver(),
                DocumentsContract.buildDocumentUriUsingTree(tree, documentId));
        if (documentIdExists(target, tree, documentId)) {
            throw new IOException("The exact completed-removal fixture child remains after deletion");
        }
    }

    private static String requireCompletedRemovalCycleNonce(Bundle args) throws IOException {
        String nonce = args.getString(COMPLETED_REMOVAL_NONCE_ARG);
        if (nonce == null) throw new IOException("The completed-removal cycle nonce is missing");
        try {
            if (!nonce.equals(UUID.fromString(nonce).toString())) {
                throw new IOException("The completed-removal cycle nonce is not canonical UUID text");
            }
        } catch (IllegalArgumentException malformed) {
            throw new IOException("The completed-removal cycle nonce is malformed", malformed);
        }
        return nonce;
    }

    private boolean shouldPreservePreparedProcessDeathFixture(Context context) {
        Bundle args = InstrumentationRegistry.getArguments();
        return processDeathFixturePreparedInThisRun
                && processDeathPreserveRequested(args)
                && PROCESS_CYCLE_PREPARE_PHASE.equals(args.getString(PROCESS_CYCLE_PHASE_ARG))
                && context.getSharedPreferences(PROCESS_CYCLE_PREFS, Context.MODE_PRIVATE)
                        .contains(PROCESS_CYCLE_ROW_ID);
    }

    private static boolean processDeathPreserveRequested(Bundle args) {
        return Boolean.parseBoolean(args.getString(PROCESS_CYCLE_PRESERVE_ARG));
    }

    private static int parseProcessCycleServerPort(
            android.content.SharedPreferences marker) throws IOException {
        String raw = marker.getString(PROCESS_CYCLE_SERVER_PORT, null);
        if (raw == null) throw new IOException("The process-cycle marker has no loopback port");
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException malformed) {
            throw new IOException("The process-cycle marker has an invalid loopback port", malformed);
        }
    }

    private static String processCycleUniqueFileName(String requestedName, String rowId) {
        int extensionIndex = requestedName.lastIndexOf('.');
        assertTrue("the requested process-cycle filename must have an extension",
                extensionIndex > 0 && extensionIndex < requestedName.length() - 1);
        assertTrue("the process-cycle row ID must be long enough for its filename suffix",
                rowId != null && rowId.length() >= 8);
        return requestedName.substring(0, extensionIndex) + "-" + rowId.substring(0, 8)
                + requestedName.substring(extensionIndex);
    }

    private static String encodeFixtureInventory(List<FixtureDocument> documents,
            Set<String> excludedNames) {
        ArrayList<FixtureDocument> ordered = new ArrayList<>();
        for (FixtureDocument document : documents) {
            if (excludedNames.contains(document.displayName)) continue;
            ordered.add(document);
        }
        Collections.sort(ordered, (left, right) -> left.id.compareTo(right.id));
        StringBuilder encoded = new StringBuilder();
        for (FixtureDocument document : ordered) {
            if (encoded.length() > 0) encoded.append('\n');
            encoded.append(Uri.encode(document.id)).append(':')
                    .append(Uri.encode(document.displayName));
        }
        return encoded.toString();
    }

    private static Set<String> processCycleOwnedNames(
            android.content.SharedPreferences marker, String rowId) {
        Set<String> names = new HashSet<>();
        String sentinelName = marker.getString(PROCESS_CYCLE_SENTINEL_NAME, "");
        String finalName = marker.getString(PROCESS_CYCLE_FINAL_NAME, "");
        if (!sentinelName.isEmpty()) names.add(sentinelName);
        if (!finalName.isEmpty()) names.add(finalName);
        if (rowId != null && !rowId.isEmpty()) {
            String partialName = ".orbit-part-" + rowId;
            names.add(partialName);
            names.add(partialName + ".mp4");
        }
        return names;
    }

    private static void assertSentinelDocument(Context context, Uri tree,
            android.content.SharedPreferences marker) throws IOException {
        String name = marker.getString(PROCESS_CYCLE_SENTINEL_NAME, null);
        String documentId = marker.getString(PROCESS_CYCLE_SENTINEL_DOCUMENT_ID, null);
        assertNotNull("the process-cycle marker must retain the exact neighboring sentinel name", name);
        assertNotNull("the process-cycle marker must retain the exact neighboring sentinel ID",
                documentId);
        assertTrue("the exact neighboring sentinel must remain in the fixture tree",
                documentIdExists(context, tree, documentId));
        Uri sentinel = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
        assertEquals("the neighboring sentinel display name must remain unchanged", name,
                queryDisplayName(context, sentinel));
        assertArrayEquals("the neighboring sentinel bytes must remain unchanged",
                PROCESS_CYCLE_SENTINEL_BYTES, readDocument(context, sentinel));
    }

    private static void assertOriginalFixtureNeighborsUnchanged(Context context, Uri tree,
            android.content.SharedPreferences marker, Set<String> ownedNames) {
        String original = marker.getString(PROCESS_CYCLE_ORIGINAL_INVENTORY, null);
        assertNotNull("the cycle marker must retain the original fixture-neighbor inventory", original);
        assertEquals("startup recovery must preserve every pre-existing fixture neighbor ID/name",
                original, encodeFixtureInventory(listDocuments(context, tree), ownedNames));
    }

    private static void assertNoQueueRowsForFixtureTree(Context context, Uri tree,
            String allowedRowId) throws IOException {
        DownloadQueueStore store = new DownloadQueueStore(context);
        try {
            for (DownloadRecord row : store.listAll()) {
                if (!tree.toString().equals(row.folderUri)) continue;
                if (allowedRowId != null && allowedRowId.equals(row.id)) continue;
                throw new IOException("An unrelated queue row already references the exact fixture tree");
            }
        } finally {
            store.close();
        }
    }

    private static void assertNoStartupRecoveryEligibleRows(Context context) throws IOException {
        DownloadQueueStore store = new DownloadQueueStore(context);
        try {
            assertNoOtherStartupRecoveryEligibleRows(store, null);
            if (!store.getPendingRenames().isEmpty()) {
                throw new IOException("Refusing process-restart setup while any queue row has a "
                        + "pending rename journal");
            }
        } finally {
            store.close();
        }
    }

    private static void assertNoOtherStartupRecoveryEligibleRows(Context context,
            String allowedRowId) throws IOException {
        DownloadQueueStore store = new DownloadQueueStore(context);
        try {
            assertNoOtherStartupRecoveryEligibleRows(store, allowedRowId);
        } finally {
            store.close();
        }
    }

    private static void assertNoOtherStartupRecoveryEligibleRows(DownloadQueueStore store,
            String allowedRowId) throws IOException {
        for (DownloadRecord row : store.listAll()) {
            if (DownloadRecord.DOWNLOADING.equals(row.status)
                    || DownloadRecord.REMOVING.equals(row.status)) {
                if (allowedRowId != null && allowedRowId.equals(row.id)) continue;
                throw new IOException("Refusing process-restart setup while any queue row is "
                        + "eligible for startup recovery: " + row.id);
            }
        }
    }

    private static boolean matchesProcessCycleRow(DownloadRecord row, String stableId,
            String folderUri, String finalName) {
        return row != null && TEST_PROFILE.equals(row.profileId)
                && stableId.equals(row.stableItemId)
                && "download-test-source".equals(row.sourceId)
                && folderUri.equals(row.folderUri) && finalName.equals(row.fileName);
    }

    private static String resolveProcessCycleRowId(Context context,
            android.content.SharedPreferences marker, Uri tree) throws IOException {
        String stableId = marker.getString(PROCESS_CYCLE_STABLE_ITEM_ID, null);
        String finalName = marker.getString(PROCESS_CYCLE_FINAL_NAME, null);
        String markedId = marker.getString(PROCESS_CYCLE_ROW_ID, null);
        if (stableId == null || stableId.isEmpty() || finalName == null || finalName.isEmpty()) {
            throw new IOException("The process-cycle marker lacks stable row identity");
        }
        DownloadQueueStore store = new DownloadQueueStore(context);
        try {
            DownloadRecord byMarkedId = markedId == null ? null : store.get(markedId);
            if (byMarkedId != null && !matchesProcessCycleRow(byMarkedId, stableId,
                    tree.toString(), finalName)) {
                throw new IOException("The marked process-cycle row identity does not match its fixture");
            }
            String foundId = byMarkedId == null ? null : markedId;
            for (DownloadRecord row : store.listAll()) {
                if (!stableId.equals(row.stableItemId)) continue;
                if (!matchesProcessCycleRow(row, stableId, tree.toString(), finalName)) {
                    throw new IOException("A matching stable ID has unexpected row metadata");
                }
                if (foundId != null && !foundId.equals(row.id)) {
                    throw new IOException("Multiple queue rows match the process-cycle stable ID");
                }
                foundId = row.id;
            }
            if (markedId != null && foundId != null && !foundId.equals(markedId)) {
                throw new IOException("The marked process-cycle row does not match its stable identity");
            }
            if (markedId == null && foundId != null) {
                if (!marker.edit().putString(PROCESS_CYCLE_ROW_ID, foundId).commit()) {
                    throw new IOException("Could not persist the resolved process-cycle row ID");
                }
            }
            return foundId;
        } finally {
            store.close();
        }
    }

    private static void deleteProcessCycleNamedDocument(Context context, Uri tree,
            String exactName, String documentId, byte[] expectedBytes, boolean allowPartialSuffix)
            throws Exception {
        if (exactName == null || exactName.isEmpty()) return;
        ArrayList<FixtureDocument> matches = new ArrayList<>();
        for (FixtureDocument document : listDocuments(context, tree)) {
            if (document.id.equals(documentId)) {
                matches.add(document);
            } else if ((documentId == null || documentId.isEmpty())
                    && (exactName.equals(document.displayName)
                    || (allowPartialSuffix && (exactName + ".mp4").equals(document.displayName)))) {
                matches.add(document);
            }
        }
        if (matches.size() > 1) {
            throw new IOException("More than one child matches an exact process-cycle document identity");
        }
        if (matches.isEmpty()) return;
        FixtureDocument fixture = matches.get(0);
        boolean nameMatches = exactName.equals(fixture.displayName)
                || (allowPartialSuffix && (exactName + ".mp4").equals(fixture.displayName));
        if (!nameMatches || !isTestFixtureDisplayName(fixture.displayName)) {
            throw new IOException("A marked process-cycle document has an unexpected display name");
        }
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, fixture.id);
        assertArrayEquals("an exact process-cycle file changed before cleanup",
                expectedBytes, readDocument(context, document));
        try {
            DocumentsContract.deleteDocument(context.getContentResolver(), document);
        } catch (java.io.FileNotFoundException ignored) {
            // A preceding exact startup cleanup may already have removed this document.
        }
        if (documentIdExists(context, tree, fixture.id)) {
            throw new IOException("An exact process-cycle fixture document remains after cleanup");
        }
    }

    private static void cleanupInterruptedProcessCycleFixture(Context target) throws Exception {
        android.content.SharedPreferences marker = target.getSharedPreferences(
                PROCESS_CYCLE_PREFS, Context.MODE_PRIVATE);
        if (marker.getAll().isEmpty()) return;
        String expectedNonce = requireProcessDeathCycleNonce(InstrumentationRegistry.getArguments());
        if (!expectedNonce.equals(marker.getString(PROCESS_CYCLE_CYCLE_NONCE, null))) {
            throw new IOException("Refusing cleanup for a different process-cycle nonce");
        }
        String rawTree = marker.getString(PROCESS_CYCLE_TREE_URI, null);
        Uri tree = rawTree == null ? null : Uri.parse(rawTree);
        if (!isFixtureTree(tree) || !tree.equals(selectedFixtureFolderOrNull(target))) {
            throw new IOException("Refusing cleanup after the selected fixture-tree preference changed");
        }
        String originalInventory = marker.getString(PROCESS_CYCLE_ORIGINAL_INVENTORY, null);
        if (originalInventory == null) {
            throw new IOException("Refusing cleanup without the pre-test fixture-neighbor inventory");
        }
        String recordedId = marker.getString(PROCESS_CYCLE_ROW_ID, null);
        String resolvedId = resolveProcessCycleRowId(target, marker, tree);
        if (recordedId != null && resolvedId != null && !recordedId.equals(resolvedId)) {
            throw new IOException("The process-cycle row ID changed during exact cleanup");
        }
        String exactId = resolvedId == null ? recordedId : resolvedId;
        String partialName = marker.getString(PROCESS_CYCLE_PARTIAL_NAME, "");
        if (exactId == null) {
            if (!partialName.isEmpty()) {
                throw new IOException("A partial reservation is marked without a resolved row identity");
            }
        } else {
            String expectedPartialName = ".orbit-part-" + exactId;
            if (!partialName.isEmpty() && !expectedPartialName.equals(partialName)) {
                throw new IOException("The process-cycle partial reservation does not match its row ID");
            }
        }
        if (resolvedId != null) cleanSyntheticQueueItems(target, tree, resolvedId);

        String expectedPartial = exactId == null ? "" : ".orbit-part-" + exactId;
        String partialId = marker.getString(PROCESS_CYCLE_PARTIAL_DOCUMENT_ID, null);
        if (!expectedPartial.isEmpty()) {
            deleteProcessCycleNamedDocument(target, tree, expectedPartial, partialId,
                    PROCESS_CYCLE_PARTIAL_BYTES, true);
        }
        String finalName = marker.getString(PROCESS_CYCLE_FINAL_NAME, null);
        deleteProcessCycleNamedDocument(target, tree, finalName, null,
                PROCESS_CYCLE_BODY, false);
        String sentinelName = marker.getString(PROCESS_CYCLE_SENTINEL_NAME, null);
        String sentinelId = marker.getString(PROCESS_CYCLE_SENTINEL_DOCUMENT_ID, null);
        deleteProcessCycleNamedDocument(target, tree, sentinelName, sentinelId,
                PROCESS_CYCLE_SENTINEL_BYTES, false);

        assertNoQueueRowsForFixtureTree(target, tree, null);
        DownloadQueueStore verificationStore = new DownloadQueueStore(target);
        try {
            if (exactId != null && verificationStore.get(exactId) != null) {
                throw new IOException("The exact process-cycle queue row remains after cleanup");
            }
            String stableId = marker.getString(PROCESS_CYCLE_STABLE_ITEM_ID, null);
            for (DownloadRecord row : verificationStore.listAll()) {
                if (stableId != null && stableId.equals(row.stableItemId)) {
                    throw new IOException("A row with the process-cycle stable ID remains after cleanup");
                }
            }
        } finally {
            verificationStore.close();
        }
        if (exactId != null && findPartialFixtureDocument(target, tree, exactId) != null) {
            throw new IOException("The exact process-cycle partial remains after cleanup");
        }
        if (finalName != null && documentIdForName(target, tree, finalName) != null) {
            throw new IOException("The exact process-cycle final file remains after cleanup");
        }
        if (sentinelName != null && documentIdForName(target, tree, sentinelName) != null) {
            throw new IOException("The process-cycle sentinel remains after cleanup");
        }
        if (!originalInventory.equals(encodeFixtureInventory(listDocuments(target, tree),
                Collections.<String>emptySet()))) {
            throw new IOException("Cleanup did not restore the original fixture-neighbor inventory");
        }
        if (!marker.edit().clear().commit()) {
            throw new IOException("Could not clear the completed process-cycle marker");
        }
    }
    private static void cleanSyntheticQueueItems(Context context, Uri tree) throws Exception {
        cleanSyntheticQueueItems(context, tree, null);
    }

    private static void cleanSyntheticQueueItems(Context context, Uri tree, String onlyRowId)
            throws Exception {
        if (!isFixtureTree(tree)) {
            throw new IllegalArgumentException("Refusing cleanup outside the dedicated queue fixture tree");
        }
        DownloadQueueStore store = new DownloadQueueStore(context);
        try {
            if (onlyRowId != null) {
                DownloadRecord targeted = store.get(onlyRowId);
                if (targeted == null || !TEST_PROFILE.equals(targeted.profileId)) {
                    throw new IOException("The marked process-cycle row is missing or is not test-owned");
                }
            }
            for (DownloadRecord item : store.listAll()) {
                if (!TEST_PROFILE.equals(item.profileId)) continue;
                if (onlyRowId != null && !onlyRowId.equals(item.id)) continue;
                ArrayList<String> allowedNames = new ArrayList<>();
                String expectedPartialName = ".orbit-part-" + item.id;
                allowedNames.add(expectedPartialName);
                // Android MediaProvider may add the MIME-derived extension to a SAF display name.
                allowedNames.add(expectedPartialName + ".mp4");
                if (item.fileName != null && item.fileName.startsWith(TEST_FILE_PREFIX)) {
                    allowedNames.add(item.fileName);
                }
                ArrayList<String> partialNames = new ArrayList<>();
                partialNames.add(expectedPartialName);
                partialNames.add(expectedPartialName + ".mp4");

                DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(item.id);
                if (retained != null) {
                    if (retained.partialName != null && !retained.partialName.isEmpty()
                            && !expectedPartialName.equals(retained.partialName)) {
                        throw new IOException("Unexpected partial-file reservation for synthetic queue row");
                    }
                    deleteIfPresent(context, tree, retained.partialUri, partialNames);
                    if (item.fileName != null && item.fileName.startsWith(TEST_FILE_PREFIX)
                            && item.fileName.equals(retained.fileName)) {
                        deleteIfPresent(context, tree, retained.fileUri,
                                Collections.singletonList(item.fileName));
                    } else if (retained.fileUri != null && !retained.fileUri.isEmpty()) {
                        throw new IOException("Unexpected final-file reservation for synthetic queue row");
                    }
                }
                for (String allowedName : allowedNames) {
                    deleteNamedFixtureDocument(context, tree, allowedName, allowedNames);
                }
                store.delete(item.id);
            }
        } finally {
            store.close();
        }
    }

    private static void deleteIfPresent(Context context, Uri tree, String rawUri,
            List<String> expectedDisplayNames) throws Exception {
        if (rawUri == null || rawUri.isEmpty()) return;
        if (!isFixtureTree(tree) || expectedDisplayNames.isEmpty()) {
            throw new IOException("Refusing to clean a SAF document outside its fixture row allowlist");
        }
        for (String name : expectedDisplayNames) {
            if (!isTestFixtureDisplayName(name)) {
                throw new IOException("Invalid synthetic SAF document name in row cleanup");
            }
        }
        Uri document = Uri.parse(rawUri);
        if (!isWithinTree(document, tree)) {
            throw new IOException("Saved SAF URI is outside the selected queue fixture tree");
        }
        String documentId;
        try {
            documentId = DocumentsContract.getDocumentId(document);
        } catch (IllegalArgumentException error) {
            throw new IOException("Saved SAF URI does not identify a fixture child document", error);
        }
        Uri canonicalDocument = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
        String actualName = queryDisplayName(context, canonicalDocument);
        if (actualName == null) {
            if (documentIdExists(context, tree, documentId)) {
                throw new IOException("Saved SAF child exists but its name could not be verified");
            }
            return;
        }
        if (!expectedDisplayNames.contains(actualName) || !isTestFixtureDisplayName(actualName)) {
            throw new IOException("Saved SAF URI identifies a different file than its synthetic row reservation");
        }
        try {
            DocumentsContract.deleteDocument(context.getContentResolver(), canonicalDocument);
        } catch (java.io.FileNotFoundException ignored) {
            // The exact synthetic document may already have been removed during startup recovery.
        }
        if (documentIdExists(context, tree, documentId)) {
            throw new IOException("Saved SAF child remains present after its delete attempt");
        }
    }

    private static void deleteNamedFixtureDocument(Context context, Uri tree, String displayName,
            List<String> rowOwnedNames) throws Exception {
        if (!isFixtureTree(tree) || !rowOwnedNames.contains(displayName)
                || !isTestFixtureDisplayName(displayName)) return;
        for (FixtureDocument item : listDocuments(context, tree)) {
            if (!displayName.equals(item.displayName)) continue;
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, item.id);
            try {
                DocumentsContract.deleteDocument(context.getContentResolver(), document);
            } catch (java.io.FileNotFoundException ignored) {
                // Startup recovery may already have removed this exact fixture document.
            }
            if (documentIdExists(context, tree, item.id)) {
                throw new IOException("Synthetic SAF child remains present after its delete attempt");
            }
            return;
        }
    }

    private static Uri requireSelectedFixtureFolder(Context context) {
        Uri tree = selectedFixtureFolderOrNull(context);
        assertNotNull("select Download/Orbit Download Queue Fixture from Orbit > Downloads before this test", tree);
        assertTrue("select the dedicated Download/Orbit Download Queue Fixture tree",
                isFixtureTree(tree));
        return tree;
    }

    private static Uri selectedFixtureFolderOrNull(Context context) {
        String raw = context.getSharedPreferences("orbit_downloads_shell_v1", Context.MODE_PRIVATE)
                .getString("folder_uri", null);
        return raw == null ? null : Uri.parse(raw);
    }

    private static void assertPersistedTreeGrant(Context context, Uri tree) {
        boolean hasRead = false;
        boolean hasWrite = false;
        for (android.content.UriPermission permission : context.getContentResolver()
                .getPersistedUriPermissions()) {
            if (!tree.equals(permission.getUri())) continue;
            hasRead |= permission.isReadPermission();
            hasWrite |= permission.isWritePermission();
        }
        assertTrue("the selected queue fixture tree must have a persisted read grant", hasRead);
        assertTrue("the selected queue fixture tree must have a persisted write grant", hasWrite);
    }

    private static void assertFixtureFolderIsEmpty(Context context, Uri tree) {
        ArrayList<String> unexpected = new ArrayList<>();
        for (FixtureDocument document : listDocuments(context, tree)) {
            if (!".nomedia".equals(document.displayName)) unexpected.add(document.displayName);
        }
        assertTrue("the dedicated queue fixture folder must be empty before the test: " + unexpected,
                unexpected.isEmpty());
    }

    private static List<FixtureDocument> listDocuments(Context context, Uri tree) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        ArrayList<FixtureDocument> documents = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            assertNotNull("the selected SAF fixture folder must be readable", cursor);
            int idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (cursor.moveToNext()) {
                documents.add(new FixtureDocument(cursor.getString(idIndex), cursor.getString(nameIndex)));
            }
        }
        return documents;
    }

    private static String documentIdForName(Context context, Uri tree, String name) {
        for (FixtureDocument document : listDocuments(context, tree)) {
            if (name.equals(document.displayName)) return document.id;
        }
        return null;
    }

    private static boolean documentExists(Context context, Uri tree, String name) {
        return documentIdForName(context, tree, name) != null;
    }

    private static boolean documentIdExists(Context context, Uri tree, String id) {
        for (FixtureDocument document : listDocuments(context, tree)) {
            if (id.equals(document.id)) return true;
        }
        return false;
    }

    private static FixtureDocument findPartialFixtureDocument(Context context, Uri tree, String id) {
        String base = ".orbit-part-" + id;
        for (FixtureDocument document : listDocuments(context, tree)) {
            if (base.equals(document.displayName) || (base + ".mp4").equals(document.displayName)) {
                return document;
            }
        }
        return null;
    }

    private static byte[] readDocument(Context context, Uri document) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(document);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Could not read the synthetic SAF download");
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static boolean isWithinTree(Uri document, Uri tree) {
        if (!isFixtureTree(tree)
                || !EXTERNAL_STORAGE_AUTHORITY.equals(document.getAuthority())) return false;
        try {
            return DocumentsContract.getTreeDocumentId(document)
                    .equals(DocumentsContract.getTreeDocumentId(tree));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static boolean isFixtureTree(Uri tree) {
        if (tree == null || !EXTERNAL_STORAGE_AUTHORITY.equals(tree.getAuthority())) return false;
        try {
            return FIXTURE_TREE_DOCUMENT_ID.equals(DocumentsContract.getTreeDocumentId(tree));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static boolean isTestFixtureDisplayName(String name) {
        return name != null && (name.startsWith(".orbit-part-")
                || name.startsWith(TEST_FILE_PREFIX));
    }

    private static String queryDisplayName(Context context, Uri document) {
        try (Cursor cursor = context.getContentResolver().query(document,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return cursor.getString(cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME));
        }
    }

    private static DownloadQueueRecordSnapshot snapshot(DownloadQueueStore store, String id) {
        DownloadQueueStore.RetainedDocuments docs = store.getRetainedDocuments(id);
        DownloadRecord record = store.get(id);
        assertNotNull(record);
        assertNotNull(docs);
        return new DownloadQueueRecordSnapshot(record.status, docs.partialUri, docs.partialName,
                docs.fileUri, record.bytesDownloaded, record.totalBytes);
    }

    private static DownloadQueueRecordSnapshot snapshot(Context context, String id) {
        DownloadQueueStore store = new DownloadQueueStore(context);
        try { return snapshot(store, id); }
        finally { store.close(); }
    }

    private static DownloadRecord awaitStatus(DownloadQueueManager manager, String id,
            List<String> terminalStatuses, long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        DownloadRecord latest = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (DownloadRecord item : manager.getItems()) {
                if (!id.equals(item.id)) continue;
                latest = item;
                if (terminalStatuses.contains(item.status)) return item;
            }
            SystemClock.sleep(25L);
        }
        return latest;
    }

    private static final class FixtureDocument {
        final String id;
        final String displayName;
        FixtureDocument(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }
    }

    private static final class CompletedRemovalFixtureIdentity {
        final String rowId;
        final String fileName;
        final String fileDocumentId;
        final String sentinelDocumentId;
        CompletedRemovalFixtureIdentity(String rowId, String fileName, String fileDocumentId,
                String sentinelDocumentId) {
            this.rowId = rowId;
            this.fileName = fileName;
            this.fileDocumentId = fileDocumentId;
            this.sentinelDocumentId = sentinelDocumentId;
        }
    }

    private static final class DownloadQueueRecordSnapshot {
        final String status;
        final String partialUri;
        final String partialName;
        final String fileUri;
        final long bytesDownloaded;
        final long totalBytes;
        DownloadQueueRecordSnapshot(String status, String partialUri, String partialName,
                String fileUri, long bytesDownloaded, long totalBytes) {
            this.status = status;
            this.partialUri = partialUri;
            this.partialName = partialName;
            this.fileUri = fileUri;
            this.bytesDownloaded = bytesDownloaded;
            this.totalBytes = totalBytes;
        }
    }

    /** A bounded loopback response used only as synthetic queue data. */
    private static final class LocalDownloadServer implements Closeable {
        private final ServerSocket server;
        private final byte[] body;
        private final CountDownLatch served = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();
        private final List<String> rangeHeaders = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean closed;
        private final Thread acceptThread;

        LocalDownloadServer(byte[] body) throws IOException {
            this(body, 0);
        }

        LocalDownloadServer(byte[] body, int port) throws IOException {
            this.body = body.clone();
            server = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(8_000);
            acceptThread = new Thread(this::acceptLoop, "orbit-download-test-http");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        String url(String host) {
            return "http://" + host + ":" + server.getLocalPort() + "/synthetic.mp4";
        }
        int requestCount() { return requests.get(); }
        List<String> rangeHeaders() {
            synchronized (rangeHeaders) { return new ArrayList<>(rangeHeaders); }
        }
        boolean awaitServed(long timeoutMs) throws InterruptedException {
            return served.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        private void acceptLoop() {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    requests.incrementAndGet();
                    Thread handler = new Thread(() -> respond(socket), "orbit-download-test-response");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException error) {
                    if (!closed) served.countDown();
                    return;
                }
            }
        }

        private void respond(Socket socket) {
            try (Socket closeable = socket) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        closeable.getInputStream(), StandardCharsets.US_ASCII));
                String line = reader.readLine();
                String range = "";
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.regionMatches(true, 0, "Range:", 0, 6)) {
                        range = line.substring(6).trim();
                    }
                }
                rangeHeaders.add(range);
                byte[] header = ("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII);
                OutputStream output = closeable.getOutputStream();
                output.write(header);
                output.write(body);
                output.flush();
                served.countDown();
            } catch (IOException ignored) {
                served.countDown();
            }
        }

        @Override public void close() throws IOException {
            closed = true;
            server.close();
            served.countDown();
        }
    }
}




