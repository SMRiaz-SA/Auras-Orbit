package com.auras.orbit.downloads;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.profile.LocalProfileStore;
import com.auras.orbit.profile.LocalProfileStore.Profile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Seeds a database-only completed row for visual rendering. It creates no file and is not
 * download-runtime evidence; the two test methods are run separately around screenshot capture.
 */
@RunWith(AndroidJUnit4.class)
public final class DownloadQueueSavedOfflineRenderFixtureInstrumentationTest {
    private static final String TEST_STABLE_PREFIX = "render-only-saved-offline-ui-";
    private static final String TEST_RECORD_PREFIX = "render-only-tv-";
    private static final String TEST_SOURCE_ID = "orbit-render-only-ui-fixture";
    private static final String TEST_TITLE = "Saved Offline Render Fixture";
    private static final String TEST_FILE_PREFIX = "Orbit-Render-Only-TV-";
    private static final String DUMMY_AUTHORITY = "orbit-render-only.invalid";
    private static final String DOWNLOADS_PREFS_NAME = "orbit_downloads_shell_v1";
    private static final String DOWNLOAD_FOLDER_KEY = "folder_uri";
    private static final String RESTORE_STATE_PREFS_NAME = "orbit_render_only_downloads_restore_v1";
    private static final String RESTORE_PENDING_KEY = "restore_pending";
    private static final String ORIGINAL_FOLDER_PRESENT_KEY = "original_folder_present";
    private static final String ORIGINAL_FOLDER_VALUE_KEY = "original_folder_value";
    private static final String DOWNLOADS_TABLE = "downloads";
    private static final long FIXTURE_BYTES = 4_616_987_123L;
    private static final byte[] FIXTURE_SOURCE_NONCE = {0x52, 0x45, 0x4e, 0x44};
    private static final byte[] FIXTURE_SOURCE_CIPHERTEXT = {0x4f, 0x4e, 0x4c, 0x59};

    private Context target;
    private DownloadQueueStore store;
    private String activeProfileId;
    private String seededRecordId;
    private String seededStableItemId;
    private String seededFolderDisplayUri;
    private boolean retainRenderOnlyFixture;
    private boolean seedAssertionsPassed;

    @Before public void openLocalStoresWithoutStartingAQueueManager() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Profile active = new LocalProfileStore(target).getActiveProfile();
        assertNotNull("the fixture must use an existing active Orbit profile", active);
        assertFalse("the active Orbit profile ID must be non-empty", active.id.trim().isEmpty());
        activeProfileId = active.id;
        retainRenderOnlyFixture = Boolean.parseBoolean(
                InstrumentationRegistry.getArguments().getString("retainRenderOnlyFixture", "false"));
        store = new DownloadQueueStore(target);
    }

    @After public void cleanUnretainedSeedAndCloseStore() {
        boolean preserveFixtureForCapture = retainRenderOnlyFixture
                && seedAssertionsPassed && seededRecordId != null;
        try {
            if (store != null && seededRecordId != null && !preserveFixtureForCapture) {
                deleteOwnedFixture(seededRecordId, seededStableItemId,
                        seededFolderDisplayUri, false);
            }
        } finally {
            try {
                if (!preserveFixtureForCapture) restoreFolderDisplayPreference();
            } finally {
                if (store != null) {
                    store.close();
                    store = null;
                }
            }
        }
    }

    /** Run only this method with retainRenderOnlyFixture=true before normal-app UI capture. */
    @Test public void seedCompletedRenderOnlyFixtureForOfflineUiCapture() {
        restoreFolderDisplayPreference();
        String runToken = UUID.randomUUID().toString();
        String recordId = TEST_RECORD_PREFIX + runToken;
        String stableItemId = TEST_STABLE_PREFIX + runToken;
        assertSafeQueueStateAndUnusedIds(recordId, stableItemId);

        String rawFolderDisplayUri = requiredArgument(InstrumentationRegistry.getArguments(),
                "renderOnlyFolderDisplayUri");
        Uri folderDisplayUri = validateExternalStorageTreeUri(rawFolderDisplayUri);
        String fileName = expectedFileName(stableItemId);
        String fileUri = dummyUri("file", stableItemId);
        assertNull("render-only file URI must not resolve to an installed document provider",
                target.getPackageManager().resolveContentProvider(DUMMY_AUTHORITY, 0));
        saveAndApplyFolderDisplayPreference(folderDisplayUri.toString());

        long createdAt = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("id", recordId);
        values.put("profile_id", activeProfileId);
        values.put("stable_item_id", stableItemId);
        values.put("source_id", TEST_SOURCE_ID);
        values.put("title", TEST_TITLE);
        values.put("file_name", fileName);
        values.put("mime_type", "video/mp4");
        values.put("season_label", "");
        values.put("episode_label", "");
        values.put("known_size_bytes", FIXTURE_BYTES);
        values.put("artwork_ref", "");
        // Opaque sentinels are deliberately not valid source credentials. The completed row is
        // inserted atomically; no manager exists that could recover, decrypt, or transfer it.
        values.put("source_nonce", FIXTURE_SOURCE_NONCE);
        values.put("source_ciphertext", FIXTURE_SOURCE_CIPHERTEXT);
        values.put("headers_nonce", new byte[0]);
        values.put("headers_ciphertext", new byte[0]);
        values.put("status", DownloadRecord.COMPLETED);
        values.put("bytes_downloaded", FIXTURE_BYTES);
        values.put("total_bytes", FIXTURE_BYTES);
        values.put("queue_order", createdAt);
        values.put("run_token", "");
        values.put("created_at", createdAt);
        values.put("file_uri", fileUri);
        values.put("partial_uri", "");
        values.put("partial_name", "");
        values.put("folder_uri", folderDisplayUri.toString());
        values.put("error_message", "");
        SQLiteDatabase database = store.getWritableDatabase();
        assertTrue("the render-only completed row must be inserted once",
                database.insertOrThrow(DOWNLOADS_TABLE, null, values) > 0L);
        seededRecordId = recordId;
        seededStableItemId = stableItemId;
        seededFolderDisplayUri = folderDisplayUri.toString();

        DownloadRecord row = store.get(recordId);
        assertNotNull("the store must return the inserted render-only row", row);
        assertFixtureRow(row, recordId, stableItemId, folderDisplayUri.toString(), true);
        assertTrue("the normal offline list must include this completed profile row",
                containsRecord(store.listOffline(), recordId));
        assertEquals("a render-only row must have no partial file reservation",
                "", retainedDocuments(recordId).partialUri);
        assertEquals("a render-only row must have no partial name reservation",
                "", retainedDocuments(recordId).partialName);
        assertEquals("the temporary folder display preference must match the validated URI",
                folderDisplayUri.toString(), downloadsPreferences().getString(DOWNLOAD_FOLDER_KEY, null));

        seedAssertionsPassed = true;
        System.out.println("SAVED_OFFLINE_RENDER_ONLY_NOT_TRANSFER_EVIDENCE"
                + " profileId=" + activeProfileId
                + " recordId=" + recordId
                + " stableItemId=" + stableItemId
                + " sourceId=" + TEST_SOURCE_ID
                + " title=" + TEST_TITLE
                + " fileName=" + fileName
                + " bytes=" + FIXTURE_BYTES
                + " fileUri=" + fileUri
                + " folderDisplayUri=" + folderDisplayUri
                + " retained=" + retainRenderOnlyFixture);
    }

    /** Run separately after screenshot capture; every identity and owner field must match. */
    @Test public void cleanupRetainedRenderOnlyFixtureAfterScreenshots() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assertTrue("cleanup requires the caller to confirm screenshots are complete",
                Boolean.parseBoolean(arguments.getString("renderScreenshotsComplete", "false")));
        try {
            String expectedRecordId = requiredArgument(arguments, "renderFixtureRecordId");
            String expectedProfileId = requiredArgument(arguments, "renderFixtureProfileId");
            String expectedStableItemId = requiredArgument(arguments, "renderFixtureStableItemId");
            assertEquals("cleanup may target only the currently active Orbit profile",
                    activeProfileId, expectedProfileId);
            assertTrue("cleanup record ID must use this fixture's unique namespace",
                    expectedRecordId.startsWith(TEST_RECORD_PREFIX));
            assertTrue("cleanup stable item ID must use this fixture's unique namespace",
                    expectedStableItemId.startsWith(TEST_STABLE_PREFIX));
            String folderDisplayUri = downloadsPreferences().getString(DOWNLOAD_FOLDER_KEY, null);
            assertNotNull("cleanup must read the retained render-only URI from the app preference",
                    folderDisplayUri);
            Uri validatedFolderUri = validateExternalStorageTreeUri(folderDisplayUri);
            assertEquals("the retained folder preference must already be in canonical URI form",
                    folderDisplayUri, validatedFolderUri.toString());

            deleteOwnedFixture(expectedRecordId, expectedStableItemId,
                    validatedFolderUri.toString(), true);
            System.out.println("SAVED_OFFLINE_RENDER_ONLY_FIXTURE_CLEANED_AFTER_SCREENSHOTS"
                    + " profileId=" + expectedProfileId
                    + " recordId=" + expectedRecordId
                    + " stableItemId=" + expectedStableItemId
                    + " folderDisplayUri=" + validatedFolderUri);
        } finally {
            restoreFolderDisplayPreference();
        }
    }

    private Uri validateExternalStorageTreeUri(String rawValue) {
        Uri uri = Uri.parse(rawValue.trim());
        assertEquals("folder display URI must use the content scheme", "content", uri.getScheme());
        assertEquals("folder display URI must use Android external storage DocumentsProvider",
                "com.android.externalstorage.documents", uri.getAuthority());
        assertTrue("folder display URI must have a tree path", DocumentsContract.isTreeUri(uri));
        assertNull("folder display URI cannot include user info", uri.getUserInfo());
        assertNull("folder display URI cannot include a query", uri.getQuery());
        assertNull("folder display URI cannot include a fragment", uri.getFragment());

        String documentId = DocumentsContract.getTreeDocumentId(uri);
        assertNotNull("external storage tree URI must contain a document ID", documentId);
        int separator = documentId.indexOf(':');
        assertTrue("external storage tree document ID must contain a volume and path",
                separator > 0 && separator < documentId.length() - 1
                        && separator == documentId.lastIndexOf(':'));
        String volume = documentId.substring(0, separator);
        assertTrue("external storage volume name must be syntactically valid",
                volume.matches("[A-Za-z0-9_-]+"));
        String path = documentId.substring(separator + 1);
        assertFalse("external storage tree path must not be absolute", path.startsWith("/"));
        assertFalse("external storage tree path must not end with a separator", path.endsWith("/"));
        assertFalse("external storage tree path cannot contain backslashes", path.contains("\\"));
        for (String segment : path.split("/", -1)) {
            assertFalse("external storage tree path cannot contain empty or traversal segments",
                    segment.isEmpty() || ".".equals(segment) || "..".equals(segment));
        }
        return uri;
    }

    private void saveAndApplyFolderDisplayPreference(String folderUri) {
        SharedPreferences appPreferences = downloadsPreferences();
        SharedPreferences restorePreferences = restoreStatePreferences();
        boolean originalWasPresent = appPreferences.contains(DOWNLOAD_FOLDER_KEY);
        String originalValue = originalWasPresent
                ? appPreferences.getString(DOWNLOAD_FOLDER_KEY, null) : null;
        assertFalse("a present download folder preference must have its exact string value",
                originalWasPresent && originalValue == null);

        SharedPreferences.Editor backup = restorePreferences.edit()
                .putBoolean(RESTORE_PENDING_KEY, true)
                .putBoolean(ORIGINAL_FOLDER_PRESENT_KEY, originalWasPresent)
                .remove(ORIGINAL_FOLDER_VALUE_KEY);
        if (originalWasPresent) backup.putString(ORIGINAL_FOLDER_VALUE_KEY, originalValue);
        assertTrue("the original folder preference state must be saved in test-package preferences",
                backup.commit());

        // Set this before commit so @After restores even if commit reports a write failure after
        // changing the in-memory SharedPreferences view.
        assertTrue("the temporary render-only folder display preference must be committed",
                appPreferences.edit().putString(DOWNLOAD_FOLDER_KEY, folderUri).commit());
    }

    private void restoreFolderDisplayPreference() {
        SharedPreferences restorePreferences = restoreStatePreferences();
        if (!restorePreferences.getBoolean(RESTORE_PENDING_KEY, false)) return;

        boolean originallyPresent = restorePreferences.getBoolean(ORIGINAL_FOLDER_PRESENT_KEY, false);
        String originalValue = restorePreferences.getString(ORIGINAL_FOLDER_VALUE_KEY, null);
        assertFalse("a saved present folder preference must have its exact string value",
                originallyPresent && originalValue == null);
        SharedPreferences.Editor restore = downloadsPreferences().edit();
        if (originallyPresent) restore.putString(DOWNLOAD_FOLDER_KEY, originalValue);
        else restore.remove(DOWNLOAD_FOLDER_KEY);
        assertTrue("the exact pre-test folder preference must be restored", restore.commit());

        boolean isPresent = downloadsPreferences().contains(DOWNLOAD_FOLDER_KEY);
        assertEquals("folder preference presence must match its exact pre-test state",
                originallyPresent, isPresent);
        if (originallyPresent) {
            assertEquals("folder preference value must match its exact pre-test value",
                    originalValue, downloadsPreferences().getString(DOWNLOAD_FOLDER_KEY, null));
        }
        assertTrue("the restored preference backup must be cleared from target-app preferences",
                restorePreferences.edit().remove(RESTORE_PENDING_KEY)
                        .remove(ORIGINAL_FOLDER_PRESENT_KEY).remove(ORIGINAL_FOLDER_VALUE_KEY).commit());
    }

    private SharedPreferences downloadsPreferences() {
        return target.getSharedPreferences(DOWNLOADS_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private SharedPreferences restoreStatePreferences() {
        return target.getSharedPreferences(RESTORE_STATE_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void assertSafeQueueStateAndUnusedIds(String recordId, String stableItemId) {
        List<DownloadRecord> existingRows = store.listAll();
        for (DownloadRecord row : existingRows) {
            assertFalse("refusing to seed while pre-existing queue work could be recovered or started",
                    DownloadRecord.QUEUED.equals(row.status)
                            || DownloadRecord.DOWNLOADING.equals(row.status)
                            || DownloadRecord.REMOVING.equals(row.status));
            assertFalse("the generated render-only record ID must be unused",
                    recordId.equals(row.id));
            assertFalse("the generated render-only stable item ID must be unused",
                    stableItemId.equals(row.stableItemId));
        }
        assertNull("the generated render-only record ID must not exist in the store",
                store.get(recordId));
    }

    private void deleteOwnedFixture(String recordId, String stableItemId,
            String expectedFolderDisplayUri, boolean requireCompleted) {
        DownloadRecord row = store.get(recordId);
        assertNotNull("the explicitly targeted render-only fixture row must exist", row);
        assertEquals("cleanup must match the exact active Orbit profile",
                activeProfileId, row.profileId);
        assertEquals("cleanup must match the exact stable item ID", stableItemId, row.stableItemId);
        assertEquals("cleanup must match the render-only source ID", TEST_SOURCE_ID, row.sourceId);
        assertEquals("cleanup must match the fixture title", TEST_TITLE, row.title);
        assertEquals("cleanup must match the deterministic fixture filename",
                expectedFileName(stableItemId), row.fileName);
        if (requireCompleted) {
            assertEquals("cleanup must match the inactive dummy file URI",
                    dummyUri("file", stableItemId), row.fileUri);
        } else {
            assertTrue("failed seed setup may have only the empty or dummy file URI",
                    row.fileUri.isEmpty() || dummyUri("file", stableItemId).equals(row.fileUri));
        }
        assertNotNull("cleanup requires the exact validated render-only folder URI",
                expectedFolderDisplayUri);
        assertEquals("cleanup must match the validated folder URI retained in the app preference",
                expectedFolderDisplayUri, row.folderUri);
        if (requireCompleted) {
            assertEquals("retained visual fixture must be completed before cleanup",
                    DownloadRecord.COMPLETED, row.status);
        } else {
            assertTrue("failed seed setup may clean only its own queued or completed row",
                    DownloadRecord.QUEUED.equals(row.status)
                            || DownloadRecord.COMPLETED.equals(row.status));
        }

        store.delete(recordId);
        assertNull("the exact render-only fixture row must be deleted", store.get(recordId));
    }

    private void assertFixtureRow(DownloadRecord row, String recordId,
            String stableItemId, String expectedFolderDisplayUri, boolean requireCompleted) {
        assertEquals("fixture row ID", recordId, row.id);
        assertEquals("fixture row profile", activeProfileId, row.profileId);
        assertEquals("fixture stable item ID", stableItemId, row.stableItemId);
        assertEquals("fixture source ID", TEST_SOURCE_ID, row.sourceId);
        assertEquals("fixture title", TEST_TITLE, row.title);
        assertEquals("fixture filename", expectedFileName(stableItemId), row.fileName);
        assertEquals("fixture MIME type", "video/mp4", row.mimeType);
        assertEquals("fixture byte count", FIXTURE_BYTES, row.bytesDownloaded);
        assertEquals("fixture total byte count", FIXTURE_BYTES, row.totalBytes);
        assertEquals("fixture inactive file URI", dummyUri("file", stableItemId), row.fileUri);
        assertEquals("fixture folder URI must use the validated render-only display URI",
                expectedFolderDisplayUri, row.folderUri);
        if (requireCompleted) {
            assertTrue("render fixture must be marked completed", row.isComplete());
        }
    }

    private DownloadQueueStore.RetainedDocuments retainedDocuments(String recordId) {
        DownloadQueueStore.RetainedDocuments documents = store.getRetainedDocuments(recordId);
        assertNotNull("render-only row must be present in retained-document state", documents);
        return documents;
    }

    private static boolean containsRecord(List<DownloadRecord> rows, String recordId) {
        for (DownloadRecord row : rows) if (recordId.equals(row.id)) return true;
        return false;
    }

    private static String requiredArgument(Bundle arguments, String key) {
        String value = arguments.getString(key);
        assertNotNull("cleanup runner must pass " + key, value);
        assertFalse("cleanup runner argument " + key + " must not be empty", value.trim().isEmpty());
        return value;
    }

    private static String expectedFileName(String stableItemId) {
        assertTrue("stable item ID must use the render-only namespace",
                stableItemId.startsWith(TEST_STABLE_PREFIX)
                        && stableItemId.length() >= TEST_STABLE_PREFIX.length() + 8);
        String suffix = stableItemId.substring(TEST_STABLE_PREFIX.length(),
                TEST_STABLE_PREFIX.length() + 8);
        return TEST_FILE_PREFIX + suffix + ".mp4";
    }

    private static String dummyUri(String kind, String stableItemId) {
        return new Uri.Builder().scheme("content").authority(DUMMY_AUTHORITY)
                .appendPath(kind).appendPath(stableItemId).build().toString();
    }
}
