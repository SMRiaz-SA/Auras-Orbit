package com.auras.orbit.extensions;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises the ambiguous SharedPreferences false-return path without touching plugin storage. */
@RunWith(AndroidJUnit4.class)
public final class ProfileGrantCommitFailureInstrumentationTest {
    @Test
    public void failedCommitRestoresPreviousProfileGrantInMemoryAndOnDisk() {
        String previous = "{\"profile-a\":[\"repo|plugin\"]}";
        String updated = "{\"profile-a\":[]}";
        FakeGrantStore store = new FakeGrantStore(previous, 1);
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File journalFile = new File(targetContext.getNoBackupFilesDir(),
                "profile-grant-commit-test-" + UUID.randomUUID() + ".json");
        CloudStreamExtensionManager.AtomicProfileGrantRecoveryJournal journal =
                new CloudStreamExtensionManager.AtomicProfileGrantRecoveryJournal(journalFile);

        CloudStreamExtensionManager.ProfileGrantUpdateOutcome outcome =
                CloudStreamExtensionManager.commitProfileGrantUpdate(
                        previous, updated, store::write, journal);

        assertEquals(CloudStreamExtensionManager.ProfileGrantUpdateOutcome.ROLLED_BACK, outcome);
        assertEquals("the prior in-memory grant must be restored", previous, store.memory);
        assertEquals("the prior persisted grant must be restored", previous, store.disk);
        assertEquals("the update and rollback must both be attempted", 2, store.writeCount);
        assertFalse("the recovery journal should clear after confirmed rollback", journal.hasPending());
        journal.clear();
    }

    @Test
    public void failedRollbackLeavesDurableRecoveryJournalUntilPriorGrantIsRestored() {
        String previous = "{\"profile-a\":[]}";
        String updated = "{\"profile-a\":[\"repo|plugin\"]}";
        FakeGrantStore store = new FakeGrantStore(previous, 0);
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File journalFile = new File(targetContext.getNoBackupFilesDir(),
                "profile-grant-commit-test-" + UUID.randomUUID() + ".json");
        CloudStreamExtensionManager.AtomicProfileGrantRecoveryJournal journal =
                new CloudStreamExtensionManager.AtomicProfileGrantRecoveryJournal(journalFile);
        int[] writeCount = {0};

        CloudStreamExtensionManager.ProfileGrantWriter ambiguousWriter = value -> {
            writeCount[0]++;
            store.memory = value;
            if (writeCount[0] == 1) {
                store.disk = value;
                return false; // update reached disk, but commit reported failure
            }
            if (writeCount[0] == 2) return false; // rollback is unconfirmed; disk remains updated
            store.disk = value;
            return true;
        };
        CloudStreamExtensionManager.ProfileGrantUpdateOutcome outcome =
                CloudStreamExtensionManager.commitProfileGrantUpdate(
                        previous, updated, ambiguousWriter, journal);

        assertEquals(CloudStreamExtensionManager.ProfileGrantUpdateOutcome.RECOVERY_REQUIRED, outcome);
        assertTrue("uncertain grants must remain behind a recovery journal", journal.hasPending());
        assertEquals(previous, journal.readPrevious());
        assertEquals("the in-memory view must fail closed to the previous grant", previous, store.memory);
        assertEquals("the simulated disk still has the uncertain update", updated, store.disk);

        CloudStreamExtensionManager.AtomicProfileGrantRecoveryJournal afterRestart =
                new CloudStreamExtensionManager.AtomicProfileGrantRecoveryJournal(journalFile);
        try {
            assertTrue("recovery must restore the previous grant before unlocking runtime",
                    CloudStreamExtensionManager.recoverProfileGrantUpdate(store::write, afterRestart));
            assertEquals(previous, store.memory);
            assertEquals(previous, store.disk);
            assertFalse(afterRestart.hasPending());
        } finally {
            afterRestart.clear();
        }
    }

    private static final class FakeGrantStore {
        String memory;
        String disk;
        int writeCount;
        int failedWritesRemaining;

        FakeGrantStore(String initial, int failedWritesRemaining) {
            memory = initial;
            disk = initial;
            this.failedWritesRemaining = failedWritesRemaining;
        }

        boolean write(String value) {
            writeCount++;
            memory = value;
            disk = value;
            if (failedWritesRemaining > 0) {
                failedWritesRemaining--;
                return false;
            }
            return true;
        }
    }

}
