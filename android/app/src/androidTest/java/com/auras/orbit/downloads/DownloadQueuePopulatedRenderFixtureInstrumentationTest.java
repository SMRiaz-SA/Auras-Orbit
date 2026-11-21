package com.auras.orbit.downloads;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.app.UiAutomation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.OrbitApplication;
import com.auras.orbit.profile.LocalProfileStore;
import com.auras.orbit.profile.LocalProfileStore.Profile;
import com.auras.orbit.ui.OrbitShellActivity;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

/**
 * Creates a database-only queue snapshot for mockup comparison.
 *
 * <p>Rows are synthetic, have intentionally invalid encrypted source sentinels, contain no file
 * or partial URI, and never start a worker. The test launches the normal Orbit shell before
 * inserting active rows, then uses the normal Downloads navigation and queue renderer. This is
 * visual fixture evidence only; it does not prove transfer, retry, media, or provider behavior.
 */
@RunWith(AndroidJUnit4.class)
public final class DownloadQueuePopulatedRenderFixtureInstrumentationTest {
    private static final String TEST_PREFS = "orbit_render_only_queue_ui_fixture_v1";
    private static final String PENDING_KEY = "pending";
    private static final String IDS_KEY = "row_ids_json";
    private static final String STABLE_IDS_KEY = "stable_ids_json";
    private static final String PROFILE_ID_KEY = "profile_id";
    private static final String PROFILE_PREFS_DIGEST_KEY = "profile_prefs_sha256";
    private static final String DOWNLOAD_PREFS_DIGEST_KEY = "download_prefs_sha256";
    private static final String SCREENSHOT_NAME_KEY = "screenshot_name";
    private static final String TEST_RECORD_PREFIX = "render-only-queue-ui-";
    private static final String TEST_STABLE_PREFIX = "render-only-queue-ui-item-";
    private static final String TEST_SOURCE_ID = "orbit-render-only-queue-ui";
    private static final String TEST_FILE_PREFIX = "Orbit-Render-Only-Queue-";
    private static final String PROFILE_PREFS_NAME = "orbit_shell_local_profiles_v1";
    private static final String DOWNLOAD_PREFS_NAME = "orbit_downloads_shell_v1";
    private static final String RENDER_ONLY_FOLDER_URI =
            "content://render-only.invalid/tree/no-user-folder";
    private static final String DOWNLOADS_TABLE = "downloads";
    private static final String RENDER_ONLY_SCREENSHOT_NOTE =
            "QUEUE_RENDER_ONLY_NOT_TRANSFER_OR_PROVIDER_EVIDENCE";
    private static final byte[] INVALID_SOURCE_NONCE = {
            0x52, 0x45, 0x4e, 0x44, 0x45, 0x52, 0x4f, 0x4e, 0x4c, 0x59, 0x01, 0x02
    };
    private static final byte[] INVALID_SOURCE_CIPHERTEXT = {
            0x51, 0x55, 0x45, 0x55, 0x45, 0x2d, 0x46, 0x49, 0x58, 0x54, 0x55, 0x52, 0x45,
            0x01, 0x02, 0x03
    };

    private static final FixtureRow[] FIXTURE_ROWS = {
            new FixtureRow("The Quiet Between", "S1", "E6", DownloadRecord.DOWNLOADING,
                    gigabytes(1.8d), 64, 240L),
            new FixtureRow("Northbound", "S1", "E4", DownloadRecord.DOWNLOADING,
                    gigabytes(2.8d), 21, 660L),
            new FixtureRow("The Quiet Between", "S1", "E5", DownloadRecord.QUEUED,
                    gigabytes(1.6d), 0, -1L),
            new FixtureRow("Glass Harbor", "", "", DownloadRecord.QUEUED,
                    gigabytes(1.5d), 0, -1L),
            new FixtureRow("Northbound", "S1", "E3", DownloadRecord.QUEUED,
                    megabytes(690L), 0, -1L)
    };

    private Context target;
    private DownloadQueueStore store;
    private String activeProfileId;
    private String preexistingProfileSnapshot;
    private String preexistingDownloadSnapshot;
    private final ArrayList<String> seededIds = new ArrayList<>();
    private final ArrayList<String> seededStableIds = new ArrayList<>();
    private File screenshotFile;
    private boolean fixtureSeeded;
    private boolean screenshotCaptured;
    private boolean retainFixture;
    private boolean cleanupMode;

    @Before public void openStoresAndSnapshotUserPreferences() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("queue render fixture must run in its disposable application package",
                "com.auras.orbit.queuerender", target.getPackageName());
        LocalProfileStore profileStore = new LocalProfileStore(target);
        Profile active = profileStore.getActiveProfile();
        if (active == null) {
            Profile synthetic = null;
            for (Profile profile : profileStore.getProfiles()) {
                if ("My Profile".equals(profile.name)) {
                    synthetic = profile;
                    break;
                }
            }
            if (synthetic == null) synthetic = profileStore.createProfile("My Profile");
            assertNotNull("the disposable app must create a synthetic local profile", synthetic);
            profileStore.selectProfile(synthetic.id);
            active = profileStore.getActiveProfile();
        }
        assertNotNull("the render fixture requires an existing active Orbit profile", active);
        assertFalse("the active Orbit profile ID must be non-empty", active.id.trim().isEmpty());
        activeProfileId = active.id;

        SharedPreferences profilePreferences = target.getSharedPreferences(
                PROFILE_PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences downloadPreferences = target.getSharedPreferences(
                DOWNLOAD_PREFS_NAME, Context.MODE_PRIVATE);
        preexistingProfileSnapshot = canonicalPreferences(profilePreferences.getAll());
        preexistingDownloadSnapshot = canonicalPreferences(downloadPreferences.getAll());
        SharedPreferences fixturePreferences = testPreferences();
        retainFixture = Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("retainQueueRenderFixture", "false"));
        cleanupMode = Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("queueRenderCleanup", "false"));
        boolean pendingFixture = fixturePreferences.getBoolean(PENDING_KEY, false);
        if (cleanupMode) {
            assertTrue("cleanup mode requires a retained render fixture", pendingFixture);
        } else {
            assertFalse("clean up the earlier queue render fixture before seeding another",
                    pendingFixture);
        }
        store = new DownloadQueueStore(target);
    }

    @After public void cleanFailedOrUnretainedFixtureAndCloseStore() {
        boolean preserveForCapture = retainFixture && fixtureSeeded && screenshotCaptured;
        try {
            if (!preserveForCapture && !seededIds.isEmpty()) {
                deleteOnlySeededRows(seededIds, seededStableIds, activeProfileId);
                clearFixtureManifest();
            }
            if (target != null && preexistingProfileSnapshot != null) {
                assertUserPreferencesUnchanged();
            }
        } finally {
            if (store != null) {
                store.close();
                store = null;
            }
            if (!preserveForCapture && screenshotFile != null && screenshotFile.exists()) {
                assertTrue("failed render setup may delete only its exact screenshot file",
                        screenshotFile.delete() || !screenshotFile.exists());
            }
        }
    }

    /** Run with retainQueueRenderFixture=true once per form factor, then pull the logged PNG. */
    @Test public void seedAndCaptureMockupDirectedActiveAndWaitingQueue() throws Exception {
        assumeFalse("seed phase is skipped during queueRenderCleanup", cleanupMode);
        assertSafeCurrentProfileQueue();
        String token = UUID.randomUUID().toString().replace("-", "");
        for (int index = 0; index < FIXTURE_ROWS.length; index++) {
            seededIds.add(TEST_RECORD_PREFIX + token + "-" + index);
            seededStableIds.add(TEST_STABLE_PREFIX + token + "-" + index);
        }

        String folderUri = RENDER_ONLY_FOLDER_URI;
        persistFixtureManifest();

        OrbitShellActivity activity = startNormalOrbitShell();
        assertNotNull("the actual Orbit shell activity must be available for rendering", activity);
        // No manager start/resume entry point is called. The queue is rendered from its real
        // database records, and the private Activity list receives only display-time ETA values.
        Context applicationContext = target.getApplicationContext();
        assertTrue("render-only fixture must opt into the real OrbitApplication",
                applicationContext instanceof OrbitApplication);
        OrbitApplication app = (OrbitApplication) applicationContext;
        DownloadQueueManager manager = app.getDownloadQueueManager();
        assertNotNull(manager);
        for (DownloadRecord row : manager.getItems()) {
            if (!activeProfileId.equals(row.profileId)) continue;
            assertFalse("no existing active or waiting work may be present for this profile",
                    DownloadRecord.QUEUED.equals(row.status)
                            || DownloadRecord.DOWNLOADING.equals(row.status)
                            || DownloadRecord.REMOVING.equals(row.status));
        }
        insertRows(folderUri);
        fixtureSeeded = true;
        installRenderEstimatesIntoActivity(activity, manager);

        UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        clickIfVisible(ui, "Continue", 1_000L);
        clickVisibleText(ui, "Downloads", 12_000L);
        clickVisibleText(ui, "Download queue", 12_000L);
        assertVisibleText(ui, "Active", 8_000L);
        assertVisibleText(ui, "Up next", 8_000L);
        assertVisibleText(ui, "The Quiet Between", 8_000L);
        assertVisibleText(ui, "Glass Harbor", 8_000L);
        assertVisibleText(ui, "Northbound", 8_000L);

        if (isTelevisionDevice()) {
            awaitFirstActiveRowFocus(activity, 5_000L);
        }
        screenshotFile = captureNormalOrbitQueue(ui, isTelevisionDevice());
        screenshotCaptured = true;
        assertUserPreferencesUnchanged();

        System.out.println(RENDER_ONLY_SCREENSHOT_NOTE
                + " profileId=" + activeProfileId
                + " recordIds=" + new JSONArray(seededIds)
                + " stableItemIds=" + new JSONArray(seededStableIds)
                + " active=2 waiting=3 television=" + isTelevisionDevice()
                + " screenshotPath=" + screenshotFile.getAbsolutePath()
                + " screenshotSha256=" + sha256(screenshotFile)
                + " retained=" + retainFixture);
    }

    /** Run only after both screenshots have been pulled; deletion is limited to the saved IDs. */
    @Test public void cleanupRetainedQueueRenderFixtureAfterScreenshots() throws IOException {
        assumeTrue("cleanup phase runs only when queueRenderCleanup=true", cleanupMode);
        assertTrue("cleanup requires confirmation that screenshots have been pulled",
                Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                        .getString("queueRenderScreenshotsComplete", "false")));
        SharedPreferences prefs = testPreferences();
        assertTrue("cleanup requires a retained queue render fixture",
                prefs.getBoolean(PENDING_KEY, false));
        assertEquals("cleanup is restricted to the original active profile",
                prefs.getString(PROFILE_ID_KEY, ""), activeProfileId);
        assertUserPreferencesMatchManifest(prefs);

        List<String> recordIds = readJsonArray(prefs.getString(IDS_KEY, null));
        List<String> stableIds = readJsonArray(prefs.getString(STABLE_IDS_KEY, null));
        assertEquals("the fixture manifest must contain exactly five row IDs", 5, recordIds.size());
        assertEquals("record and stable-ID manifests must match", recordIds.size(), stableIds.size());

        String screenshotName = prefs.getString(SCREENSHOT_NAME_KEY, "");
        assertTrue("cleanup requires an exact fixture screenshot name",
                "queue-render-only-phone.png".equals(screenshotName)
                        || "queue-render-only-tv.png".equals(screenshotName));
        File screenshotRoot = screenshotDirectory().getCanonicalFile();
        File expectedScreenshot = new File(screenshotRoot, screenshotName).getAbsoluteFile();
        File exactScreenshot = expectedScreenshot.getCanonicalFile();
        assertEquals("fixture screenshot must not resolve through a file symlink",
                expectedScreenshot, exactScreenshot);
        String rootPath = screenshotRoot.getPath();
        String screenshotPath = exactScreenshot.getPath();
        assertTrue("resolved fixture screenshot must stay beneath its owned directory",
                screenshotPath.startsWith(rootPath + File.separator));
        assertEquals("resolved fixture screenshot must be a direct child of its owned directory",
                rootPath, exactScreenshot.getParentFile().getCanonicalPath());

        deleteOnlySeededRows(recordIds, stableIds, activeProfileId);
        assertUserPreferencesMatchManifest(prefs);

        assertTrue("cleanup may delete only the recorded fixture screenshot",
                !exactScreenshot.exists() || exactScreenshot.delete());
        clearFixtureManifest();
        assertUserPreferencesUnchanged();
        System.out.println("QUEUE_RENDER_ONLY_FIXTURE_CLEANED"
                + " profileId=" + activeProfileId
                + " recordIds=" + new JSONArray(recordIds));
    }

    private OrbitShellActivity startNormalOrbitShell() {
        Intent intent = new Intent(target, OrbitShellActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Activity launched = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        assertNotNull("normal OrbitShellActivity launch must succeed", launched);
        assertTrue("the foreground shell must be OrbitShellActivity",
                launched instanceof OrbitShellActivity);
        return (OrbitShellActivity) launched;
    }

    private void assertSafeCurrentProfileQueue() {
        for (DownloadRecord row : store.listAll()) {
            if (!activeProfileId.equals(row.profileId)) continue;
            assertFalse("refusing to seed over real active or waiting queue work",
                    DownloadRecord.QUEUED.equals(row.status)
                            || DownloadRecord.DOWNLOADING.equals(row.status)
                            || DownloadRecord.REMOVING.equals(row.status));
            assertFalse("refusing to seed over a prior render-only record",
                    row.id.startsWith(TEST_RECORD_PREFIX)
                            || row.stableItemId.startsWith(TEST_STABLE_PREFIX));
        }
    }

    private void insertRows(String folderUri) {
        SQLiteDatabase db = store.getWritableDatabase();
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            for (int index = 0; index < FIXTURE_ROWS.length; index++) {
                FixtureRow fixture = FIXTURE_ROWS[index];
                String id = seededIds.get(index);
                String stableId = seededStableIds.get(index);
                long knownSize = fixture.totalBytes;
                long downloaded = fixture.progressPercent == 0
                        ? 0L : minimumBytesForProgress(knownSize, fixture.progressPercent);

                ContentValues values = new ContentValues();
                values.put("id", id);
                values.put("profile_id", activeProfileId);
                values.put("stable_item_id", stableId);
                values.put("source_id", TEST_SOURCE_ID);
                values.put("title", fixture.title);
                values.put("file_name", TEST_FILE_PREFIX + stableId.substring(
                        TEST_STABLE_PREFIX.length()) + ".mp4");
                values.put("mime_type", "video/mp4");
                values.put("season_label", fixture.seasonLabel);
                values.put("episode_label", fixture.episodeLabel);
                values.put("known_size_bytes", knownSize);
                // Empty artwork is deliberate: it invokes the app's real no-data placeholder
                // without fetching media or touching a provider.
                values.put("artwork_ref", "");
                values.put("source_nonce", INVALID_SOURCE_NONCE);
                values.put("source_ciphertext", INVALID_SOURCE_CIPHERTEXT);
                values.put("headers_nonce", new byte[0]);
                values.put("headers_ciphertext", new byte[0]);
                values.put("status", fixture.status);
                values.put("bytes_downloaded", downloaded);
                values.put("total_bytes", fixture.progressPercent == 0 ? 0L : knownSize);
                values.put("queue_order", now + index);
                values.put("run_token", DownloadRecord.DOWNLOADING.equals(fixture.status)
                        ? "render-only-run-" + stableId : "");
                values.put("created_at", now + index);
                values.put("file_uri", "");
                values.put("rename_old_uri", "");
                values.put("rename_old_name", "");
                values.put("rename_target_name", "");
                values.put("partial_uri", "");
                values.put("partial_name", "");
                values.put("folder_uri", folderUri);
                values.put("error_message", "");
                assertNull("fixture row ID must be unused before insertion", store.get(id));
                assertTrue("each synthetic row must be inserted exactly once",
                        db.insertOrThrow(DOWNLOADS_TABLE, null, values) > 0L);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        for (int index = 0; index < FIXTURE_ROWS.length; index++) {
            DownloadRecord row = store.get(seededIds.get(index));
            FixtureRow fixture = FIXTURE_ROWS[index];
            assertNotNull("each mockup row must be readable from the app queue store", row);
            assertEquals(activeProfileId, row.profileId);
            assertEquals(fixture.title, row.title);
            assertEquals(fixture.status, row.status);
            assertEquals(fixture.totalBytes, row.knownSizeBytes);
            assertEquals(fixture.progressPercent, row.getProgressPercent());
            assertTrue("fixture rows must never contain an actual final-file URI",
                    row.fileUri == null || row.fileUri.isEmpty());
            DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(row.id);
            assertNotNull(retained);
            assertTrue("fixture rows must not contain actual partial/final file reservations",
                    retained.partialUri.isEmpty() && retained.partialName.isEmpty()
                            && retained.fileUri.isEmpty());
        }
    }

    private void installRenderEstimatesIntoActivity(OrbitShellActivity activity,
            DownloadQueueManager manager) throws Exception {
        ArrayList<DownloadRecord> displayRows = new ArrayList<>();
        for (DownloadRecord row : manager.getItems()) {
            if (seededIds.contains(row.id)) {
                int index = seededIds.indexOf(row.id);
                FixtureRow fixture = FIXTURE_ROWS[index];
                if (DownloadRecord.DOWNLOADING.equals(fixture.status)) {
                    row = row.withTransferEstimate(1L, fixture.etaSeconds);
                }
            }
            displayRows.add(row);
        }
        for (String id : seededIds) {
            assertTrue("manager snapshot must include every render-only row",
                    containsRecord(displayRows, id));
        }

        java.lang.reflect.Field records = OrbitShellActivity.class
                .getDeclaredField("downloadRecords");
        records.setAccessible(true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> setField(records, activity, Collections.unmodifiableList(displayRows)));
    }

    private void setField(java.lang.reflect.Field field, Object receiver, Object value) {
        try {
            field.set(receiver, value);
        } catch (IllegalAccessException error) {
            throw new AssertionError("Could not attach render-only estimate snapshot", error);
        }
    }

    private void clickIfVisible(UiAutomation ui, String text, long timeoutMs) {
        if (findExactText(ui, text, 250L) != null) clickVisibleText(ui, text, timeoutMs);
    }

    private void clickVisibleText(UiAutomation ui, String text, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo node = findExactText(ui, text, 150L);
            if (node != null) {
                AccessibilityNodeInfo clickable = node;
                while (clickable != null && !clickable.isClickable()) {
                    clickable = clickable.getParent();
                }
                if (clickable != null && clickable.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK)) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    SystemClock.sleep(250L);
                    return;
                }
            }
            SystemClock.sleep(75L);
        }
        throw new AssertionError("Could not activate visible Orbit control: " + text);
    }

    private void assertVisibleText(UiAutomation ui, String text, long timeoutMs) {
        assertNotNull("expected normal Orbit UI text: " + text, findExactText(ui, text, timeoutMs));
    }

    private AccessibilityNodeInfo findExactText(UiAutomation ui, String text, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            if (root != null) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
                for (AccessibilityNodeInfo node : nodes) {
                    CharSequence label = node.getText();
                    if (label != null && text.contentEquals(label.toString().trim())) return node;
                    CharSequence description = node.getContentDescription();
                    if (description != null
                            && text.contentEquals(description.toString().trim())) return node;
                }
            }
            SystemClock.sleep(50L);
        }
        return null;
    }

    private void awaitFirstActiveRowFocus(OrbitShellActivity activity, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<Object> focusedTag = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                View focused = activity.getCurrentFocus();
                focusedTag.set(focused == null ? null : focused.getTag());
            });
            if ("download_active_row_first".equals(focusedTag.get())) return;
            SystemClock.sleep(50L);
        }
        throw new AssertionError("TV queue must focus the first Active row from the normal navigation path");
    }

    private File captureNormalOrbitQueue(UiAutomation ui, boolean television) throws IOException {
        Bitmap bitmap = ui.takeScreenshot();
        assertNotNull("UiAutomation must capture the normal Orbit queue screen", bitmap);
        File directory = screenshotDirectory();
        assertTrue("test-owned screenshot directory must exist",
                directory.isDirectory() || directory.mkdirs());
        String name = television ? "queue-render-only-tv.png" : "queue-render-only-phone.png";
        File output = new File(directory, name).getAbsoluteFile();
        assertEquals("fixture screenshot output must not resolve through a file symlink",
                output, output.getCanonicalFile());
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue("test-owned screenshot must be written as PNG",
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            stream.flush();
        } finally {
            bitmap.recycle();
        }
        assertTrue("test-owned screenshot must be non-empty", output.isFile() && output.length() > 0L);
        testPreferences().edit().putString(SCREENSHOT_NAME_KEY, name).commit();
        return output;
    }

    private File screenshotDirectory() throws IOException {
        File filesRoot = target.getFilesDir().getCanonicalFile();
        File expected = new File(filesRoot, "queue-render-only").getAbsoluteFile();
        File candidate = expected.getCanonicalFile();
        assertEquals("fixture screenshot directory must stay beneath render-only app-private files",
                filesRoot, candidate.getParentFile());
        assertEquals("fixture screenshot directory must not resolve through a symlink",
                expected, candidate);
        return candidate;
    }

    private void persistFixtureManifest() {
        SharedPreferences prefs = testPreferences();
        JSONArray ids = new JSONArray(seededIds);
        JSONArray stableIds = new JSONArray(seededStableIds);
        assertTrue("test-only manifest must be durable before touching the queue database",
                prefs.edit()
                        .remove("profile_prefs_snapshot")
                        .remove("download_prefs_snapshot")
                        .remove("folder_uri_value")
                        .remove("folder_uri_present")
                        .remove("folder_uri_before")
                        .putBoolean(PENDING_KEY, true)
                        .putString(IDS_KEY, ids.toString())
                        .putString(STABLE_IDS_KEY, stableIds.toString())
                        .putString(PROFILE_ID_KEY, activeProfileId)
                        .putString(PROFILE_PREFS_DIGEST_KEY,
                                sha256Text(preexistingProfileSnapshot))
                        .putString(DOWNLOAD_PREFS_DIGEST_KEY,
                                sha256Text(preexistingDownloadSnapshot))
                        .commit());
    }

    private void assertUserPreferencesUnchanged() {
        SharedPreferences profilePreferences = target.getSharedPreferences(
                PROFILE_PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences downloadPreferences = target.getSharedPreferences(
                DOWNLOAD_PREFS_NAME, Context.MODE_PRIVATE);
        assertEquals("Orbit profile preferences must be preserved exactly",
                preexistingProfileSnapshot, canonicalPreferences(profilePreferences.getAll()));
        assertEquals("Orbit download preferences must be preserved exactly",
                preexistingDownloadSnapshot, canonicalPreferences(downloadPreferences.getAll()));
        Profile active = new LocalProfileStore(target).getActiveProfile();
        assertNotNull("the active Orbit profile must remain available", active);
        assertEquals("the active Orbit profile must remain unchanged", activeProfileId, active.id);
    }

    private void assertUserPreferencesMatchManifest(SharedPreferences prefs) {
        assertEquals("profile preferences must match their pre-fixture digest",
                prefs.getString(PROFILE_PREFS_DIGEST_KEY, null),
                sha256Text(canonicalPreferences(target.getSharedPreferences(
                        PROFILE_PREFS_NAME, Context.MODE_PRIVATE).getAll())));
        assertEquals("download preferences must match their pre-fixture digest",
                prefs.getString(DOWNLOAD_PREFS_DIGEST_KEY, null),
                sha256Text(canonicalPreferences(target.getSharedPreferences(
                        DOWNLOAD_PREFS_NAME, Context.MODE_PRIVATE).getAll())));
    }

    private void deleteOnlySeededRows(List<String> recordIds, List<String> stableIds,
            String expectedProfileId) {
        assertEquals("cleanup requires paired, exact record/stable IDs",
                recordIds.size(), stableIds.size());
        for (int index = 0; index < recordIds.size(); index++) {
            String id = recordIds.get(index);
            String stableId = stableIds.get(index);
            assertTrue("cleanup row ID must use the fixture namespace",
                    id.startsWith(TEST_RECORD_PREFIX));
            assertTrue("cleanup stable ID must use the fixture namespace",
                    stableId.startsWith(TEST_STABLE_PREFIX));
            String recordSuffix = id.substring(TEST_RECORD_PREFIX.length());
            String stableSuffix = stableId.substring(TEST_STABLE_PREFIX.length());
            assertEquals("cleanup must pair the record and stable IDs from one exact seed run",
                    recordSuffix, stableSuffix);
            assertTrue("cleanup IDs must retain their deterministic fixture row index",
                    recordSuffix.endsWith("-" + index));
            DownloadRecord row = store.get(id);
            if (row == null) continue;
            assertEquals("cleanup may delete only rows from the recorded active profile",
                    expectedProfileId, row.profileId);
            assertEquals("cleanup must match the exact stable item ID", stableId, row.stableItemId);
            assertEquals("cleanup must match the dedicated render-only source ID",
                    TEST_SOURCE_ID, row.sourceId);
            assertEquals("cleanup must match the mockup title", FIXTURE_ROWS[index].title, row.title);
            assertTrue("cleanup must not target a real or provider-owned file",
                    row.fileUri == null || row.fileUri.isEmpty());
            DownloadQueueStore.RetainedDocuments retained = store.getRetainedDocuments(id);
            assertNotNull("render fixture row must retain its metadata record", retained);
            assertTrue("render fixture row must not reserve any real file",
                    retained.partialUri.isEmpty() && retained.partialName.isEmpty()
                            && retained.fileUri.isEmpty());
            assertTrue("cleanup may remove only the fixture's active/waiting/recovered row",
                    DownloadRecord.DOWNLOADING.equals(row.status)
                            || DownloadRecord.QUEUED.equals(row.status)
                            || DownloadRecord.PAUSED.equals(row.status));
            store.delete(id);
            assertNull("exact render fixture row must be deleted", store.get(id));
        }
    }

    private void clearFixtureManifest() {
        assertTrue("test-only cleanup marker must be removed after exact row cleanup",
                testPreferences().edit()
                        .remove(PENDING_KEY)
                        .remove(IDS_KEY)
                        .remove(STABLE_IDS_KEY)
                        .remove(PROFILE_ID_KEY)
                        .remove(PROFILE_PREFS_DIGEST_KEY)
                        .remove(DOWNLOAD_PREFS_DIGEST_KEY)
                        .remove(SCREENSHOT_NAME_KEY)
                        .remove("profile_prefs_snapshot")
                        .remove("download_prefs_snapshot")
                        .remove("folder_uri_value")
                        .remove("folder_uri_present")
                        .remove("folder_uri_before")
                        .commit());
    }

    private SharedPreferences testPreferences() {
        return target.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE);
    }

    private static List<String> readJsonArray(String value) {
        assertNotNull("retained fixture manifest must include its exact row IDs", value);
        try {
            JSONArray json = new JSONArray(value);
            ArrayList<String> items = new ArrayList<>();
            for (int index = 0; index < json.length(); index++) items.add(json.getString(index));
            return items;
        } catch (org.json.JSONException error) {
            throw new AssertionError("retained fixture manifest is malformed", error);
        }
    }

    private static String canonicalPreferences(Map<String, ?> values) {
        StringBuilder snapshot = new StringBuilder();
        for (Map.Entry<String, ?> entry : new TreeMap<>(values).entrySet()) {
            String key = Base64.encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
            Object value = entry.getValue();
            String rendered = value == null ? "<null>"
                    : value.getClass().getName() + ":" + value;
            String encoded = Base64.encodeToString(rendered.getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
            snapshot.append(key).append('=').append(encoded).append('\n');
        }
        return snapshot.toString();
    }

    private boolean isTelevisionDevice() {
        int type = target.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK;
        return type == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private static boolean containsRecord(List<DownloadRecord> rows, String id) {
        for (DownloadRecord row : rows) if (id.equals(row.id)) return true;
        return false;
    }

    private static long gigabytes(double gb) {
        return Math.round(gb * 1_000_000_000d);
    }

    private static long megabytes(long mb) {
        return mb * 1_000_000L;
    }

    private static long minimumBytesForProgress(long totalBytes, int progressPercent) {
        if (totalBytes <= 0L || progressPercent < 0 || progressPercent > 100) {
            throw new IllegalArgumentException("Progress requires positive bytes and 0..100 percent");
        }
        if (progressPercent == 0) return 0L;

        // Split before multiplying to avoid overflow. The remainder term is at most 99 * 100.
        long wholeHundreds = totalBytes / 100L;
        long remainderBytes = totalBytes % 100L;
        long wholeProgressBytes = wholeHundreds * progressPercent;
        long roundedRemainderBytes = (remainderBytes * progressPercent + 99L) / 100L;
        return wholeProgressBytes + roundedRemainderBytes;
    }

    private static String sha256Text(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by Android", impossible);
        }
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[8192];
            try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                int count;
                while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
            }
            StringBuilder value = new StringBuilder();
            for (byte b : digest.digest()) value.append(String.format("%02x", b & 0xff));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by Android", impossible);
        }
    }

    private static final class FixtureRow {
        final String title;
        final String seasonLabel;
        final String episodeLabel;
        final String status;
        final long totalBytes;
        final int progressPercent;
        final long etaSeconds;

        FixtureRow(String title, String seasonLabel, String episodeLabel, String status,
                long totalBytes, int progressPercent, long etaSeconds) {
            this.title = title;
            this.seasonLabel = seasonLabel;
            this.episodeLabel = episodeLabel;
            this.status = status;
            this.totalBytes = totalBytes;
            this.progressPercent = progressPercent;
            this.etaSeconds = etaSeconds;
        }
    }
}
