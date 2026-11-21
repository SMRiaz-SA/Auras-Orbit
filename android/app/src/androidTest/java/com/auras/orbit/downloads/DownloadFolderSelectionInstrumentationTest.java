package com.auras.orbit.downloads;

import android.content.Intent;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises SAF folder authorization and pending-request behavior without external providers. */
@RunWith(AndroidJUnit4.class)
public final class DownloadFolderSelectionInstrumentationTest {
    private static final Uri TREE = Uri.parse(
            "content://com.example.test.documents/tree/primary%3ADownload%2FQueueFixture");
    private static final Uri NEW_TREE = Uri.parse(
            "content://com.example.test.documents/tree/primary%3ADownload%2FNewQueueFixture");
    private static final String OLD_FOLDER =
            "content://com.example.test.documents/tree/primary%3ADownload%2FOldFolder";

    @Test public void missingWriteFlagPreservesOldFolderAndDoesNotDispatch() {
        FakeGrantAccess grants = new FakeGrantAccess();
        FakeFolderPreference folder = new FakeFolderPreference(OLD_FOLDER);
        FakePendingRequests pending = new FakePendingRequests(folder);

        boolean accepted = DownloadFolderSelection.acceptSelection(NEW_TREE,
                Intent.FLAG_GRANT_READ_URI_PERMISSION, grants, folder, pending);

        assertFalse(accepted);
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, grants.lastPersistableFlags);
        assertEquals(OLD_FOLDER, folder.value);
        assertEquals(0, folder.commitCount);
        assertEquals(1, pending.clearCount);
        assertEquals(1, pending.rejectedCount);
        assertEquals(0, pending.dispatchCount);
        assertEquals(1, grants.releaseCount);
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, grants.lastReleasedFlags);
        assertEquals(null, grants.persistedReadTree);
    }

    @Test public void existingExactWriteGrantAllowsSelectionWithoutNewWriteFlag() {
        FakeGrantAccess grants = new FakeGrantAccess();
        grants.persistedWriteTree = TREE;
        grants.failTake = true;
        FakeFolderPreference folder = new FakeFolderPreference(TREE.toString());
        FakePendingRequests pending = new FakePendingRequests(folder);

        boolean accepted = DownloadFolderSelection.acceptSelection(TREE,
                Intent.FLAG_GRANT_READ_URI_PERMISSION, grants, folder, pending);

        assertTrue(accepted);
        assertEquals(0, grants.takeCount);
        assertEquals(TREE.toString(), folder.value);
        assertEquals(1, folder.commitCount);
        assertEquals(0, folder.restoreCount);
        assertEquals(0, pending.clearCount);
        assertEquals(0, pending.rejectedCount);
        assertEquals(1, pending.dispatchCount);
    }

    @Test public void preexistingReadGrantIsNotReleasedWhenWriteIsUnavailable() {
        FakeGrantAccess grants = new FakeGrantAccess();
        grants.persistedReadTree = NEW_TREE;
        FakeFolderPreference folder = new FakeFolderPreference(OLD_FOLDER);
        FakePendingRequests pending = new FakePendingRequests(folder);

        boolean accepted = DownloadFolderSelection.acceptSelection(NEW_TREE,
                Intent.FLAG_GRANT_READ_URI_PERMISSION, grants, folder, pending);

        assertFalse(accepted);
        assertEquals(OLD_FOLDER, folder.value);
        assertEquals(0, grants.releaseCount);
        assertEquals(NEW_TREE, grants.persistedReadTree);
        assertEquals(1, pending.clearCount);
        assertEquals(1, pending.rejectedCount);
        assertEquals(0, pending.dispatchCount);
    }

    @Test public void persistableGrantFailurePreservesOldFolderAndDoesNotDispatch() {
        FakeGrantAccess grants = new FakeGrantAccess();
        grants.failTake = true;
        grants.persistReadBeforeFailure = true;
        FakeFolderPreference folder = new FakeFolderPreference(OLD_FOLDER);
        FakePendingRequests pending = new FakePendingRequests(folder);

        boolean accepted = DownloadFolderSelection.acceptSelection(TREE,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                grants, folder, pending);

        assertFalse(accepted);
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                grants.lastPersistableFlags);
        assertEquals(OLD_FOLDER, folder.value);
        assertEquals(0, folder.commitCount);
        assertEquals(1, pending.clearCount);
        assertEquals(1, pending.rejectedCount);
        assertEquals(0, pending.dispatchCount);
        assertEquals(1, grants.releaseCount);
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, grants.lastReleasedFlags);
        assertEquals(null, grants.persistedReadTree);
    }

    @Test public void exactWriteGrantAfterPartialTakeFailureAllowsDispatch() {
        FakeGrantAccess grants = new FakeGrantAccess();
        grants.failTake = true;
        grants.persistWriteBeforeFailure = true;
        FakeFolderPreference folder = new FakeFolderPreference(OLD_FOLDER);
        FakePendingRequests pending = new FakePendingRequests(folder);

        boolean accepted = DownloadFolderSelection.acceptSelection(TREE,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                grants, folder, pending);

        assertTrue(accepted);
        assertEquals(TREE.toString(), folder.value);
        assertEquals(1, folder.commitCount);
        assertEquals(0, folder.restoreCount);
        assertEquals(0, grants.releaseCount);
        assertEquals(0, pending.clearCount);
        assertEquals(0, pending.rejectedCount);
        assertEquals(1, pending.dispatchCount);
    }

    @Test public void preferenceCommitFailureRestoresOldFolderAndDoesNotDispatch() {
        FakeGrantAccess grants = new FakeGrantAccess();
        grants.persistedWriteTree = TREE;
        FakeFolderPreference folder = new FakeFolderPreference(OLD_FOLDER);
        folder.failCommitAfterMemoryWrite = true;
        FakePendingRequests pending = new FakePendingRequests(folder);

        boolean accepted = DownloadFolderSelection.acceptSelection(TREE,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                grants, folder, pending);

        assertFalse(accepted);
        assertEquals(OLD_FOLDER, folder.value);
        assertEquals(1, folder.commitCount);
        assertEquals(1, folder.restoreCount);
        assertEquals(1, pending.clearCount);
        assertEquals(1, pending.rejectedCount);
        assertEquals(0, pending.dispatchCount);
    }

    @Test public void exactPersistedWriteGrantCommitsBeforeDispatch() {
        FakeGrantAccess grants = new FakeGrantAccess();
        grants.persistedWriteTree = TREE;
        FakeFolderPreference folder = new FakeFolderPreference(OLD_FOLDER);
        FakePendingRequests pending = new FakePendingRequests(folder);

        boolean accepted = DownloadFolderSelection.acceptSelection(TREE,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                grants, folder, pending);

        assertTrue(accepted);
        assertEquals(TREE.toString(), folder.value);
        assertEquals(0, grants.takeCount);
        assertEquals(1, folder.commitCount);
        assertEquals(0, folder.restoreCount);
        assertEquals(0, pending.clearCount);
        assertEquals(0, pending.rejectedCount);
        assertEquals(1, pending.dispatchCount);
    }

    private static final class FakeGrantAccess implements DownloadFolderSelection.GrantAccess {
        int lastPersistableFlags = -1;
        int takeCount;
        boolean failTake;
        boolean persistReadBeforeFailure;
        boolean persistWriteBeforeFailure;
        int releaseCount;
        int lastReleasedFlags;
        Uri persistedReadTree;
        Uri persistedWriteTree;

        @Override public void takePersistable(Uri tree, int flags) {
            takeCount++;
            lastPersistableFlags = flags;
            if ((!failTake || persistReadBeforeFailure)
                    && (flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                persistedReadTree = tree;
            }
            if ((!failTake || persistWriteBeforeFailure)
                    && (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                persistedWriteTree = tree;
            }
            if (failTake) throw new SecurityException("synthetic grant failure");
        }

        @Override public boolean hasPersistedWrite(Uri tree) {
            return tree.equals(persistedWriteTree);
        }

        @Override public int persistedFlags(Uri tree) {
            int flags = 0;
            if (tree.equals(persistedReadTree)) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (tree.equals(persistedWriteTree)) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            return flags;
        }

        @Override public void releasePersistable(Uri tree, int flags) {
            releaseCount++;
            lastReleasedFlags = flags;
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                    && tree.equals(persistedReadTree)) persistedReadTree = null;
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                    && tree.equals(persistedWriteTree)) persistedWriteTree = null;
        }
    }

    private static final class FakeFolderPreference implements DownloadFolderSelection.FolderPreference {
        String value;
        int commitCount;
        int restoreCount;
        boolean failCommitAfterMemoryWrite;

        FakeFolderPreference(String initialValue) { value = initialValue; }
        @Override public String read() { return value; }

        @Override public boolean commit(String treeUri) {
            commitCount++;
            value = treeUri;
            return !failCommitAfterMemoryWrite;
        }

        @Override public boolean restore(String oldValue) {
            restoreCount++;
            value = oldValue;
            return true;
        }
    }

    private static final class FakePendingRequests implements DownloadFolderSelection.PendingRequests {
        final FakeFolderPreference folder;
        int clearCount;
        int rejectedCount;
        int dispatchCount;

        FakePendingRequests(FakeFolderPreference folder) { this.folder = folder; }
        @Override public void clear() { clearCount++; }
        @Override public void rejected() { rejectedCount++; }
        @Override public void accepted(Uri tree) {
            assertEquals("folder must be durable before pending work dispatches", tree.toString(), folder.value);
            dispatchCount++;
        }
    }
}
