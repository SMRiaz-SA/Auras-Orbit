package com.auras.orbit.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.library.LocalLibraryStore;
import com.auras.orbit.profile.LocalProfileStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Archive acceptance tests that write only to isolated device-protected storage namespaces.
 *
 * For real process-death checks, invoke each stage method in its own instrumentation run,
 * force-stop the instrumented target process after the stage run exits, and then invoke its
 * matching recovery method in a fresh run. The recovery methods assert that the process ID
 * changed. A method call by itself tests store reconstruction, not process death.
 */
@RunWith(AndroidJUnit4.class)
public final class OrbitLibraryArchiveRecoveryInstrumentationTest {
    private static final String PREFS_NAME = "orbit_shell_local_profiles_v1";
    private static final String DATABASE_NAME = "orbit_local_library.db";
    private static final String TEST_NAMESPACE_PREFIX = "orbitlib_instrumentation_v1_";
    private static final String STAGED_PID_KEY = "orbitlib_instrumentation_staged_pid";
    private static final String PASSWORD = "orbitlib-test-password-2026";
    private static final String WRONG_PASSWORD = "different-test-password-2026";

    private static final String INCOMING_PROFILE_ID = "10000000-0000-4000-8000-000000000001";
    private static final String INCOMING_COLLECTION_ID = "20000000-0000-4000-8000-000000000001";
    private static final String ROLLBACK_OPERATION_ID = "30000000-0000-4000-8000-000000000001";
    private static final String FINALIZE_OPERATION_ID = "30000000-0000-4000-8000-000000000002";
    private static final String INCOMING_TITLE_ID = "incoming-title-001";

    @Test
    public void archiveExportPreviewAndMergePreserveExistingData() throws Exception {
        Context context = isolatedStorage("merge");
        clearNamespace(context);
        Stores stores = new Stores(context);
        boolean completed = false;
        try {
            LocalProfileStore.Profile local = stores.profiles.createProfile("Local profile");
            assertNotNull(local);
            stores.profiles.selectProfile(local.id);

            LocalLibraryStore.SavedTitle localTitle = title("local-title-001", "Local title");
            stores.library.saveTitle(local.id, localTitle);
            LocalLibraryStore.Collection localCollection = stores.library.createCollection(local.id, "Local list");
            assertTrue(stores.library.addToCollection(local.id, localCollection.id, localTitle.stableId));
            assertTrue(stores.library.recordPlaybackProgress(local.id, localTitle,
                    12_000L, 90_000L, "local-episode-1", "Local episode", 1, 2));

            ByteArrayOutputStream exported = new ByteArrayOutputStream();
            stores.archive.exportEncrypted(exported, PASSWORD.toCharArray());
            byte[] archive = exported.toByteArray();
            assertTrue("encrypted export should be non-empty", archive.length > 60);
            assertEquals("AURORBLB", new String(archive, 0, 8, StandardCharsets.US_ASCII));
            assertFalse("the title text must not appear in ciphertext",
                    contains(archive, "Local title".getBytes(StandardCharsets.UTF_8)));

            OrbitLibraryArchiveService.PreparedImport preview = stores.archive.prepareImport(
                    new ByteArrayInputStream(archive), PASSWORD.toCharArray());
            assertEquals(1, preview.profileCount);
            assertEquals(1, preview.savedTitleCount);
            assertEquals(1, preview.playbackCount);
            assertEquals(1, preview.collectionCount);
            assertEquals(1, preview.membershipCount);

            String beforeRejectedPreviews = snapshot(stores.profiles, stores.library);
            expectArchiveFailure(() -> stores.archive.prepareImport(
                    new ByteArrayInputStream(archive), WRONG_PASSWORD.toCharArray()));
            expectArchiveFailure(() -> stores.archive.prepareImport(
                    new ByteArrayInputStream(encrypt(new JSONObject().put("format", "invalid"))),
                    PASSWORD.toCharArray()));
            assertEquals("rejected preview attempts must not mutate local data",
                    beforeRejectedPreviews, snapshot(stores.profiles, stores.library));

            JSONObject importedPayload = fixturePayload(INCOMING_PROFILE_ID, "Imported profile",
                    INCOMING_TITLE_ID, INCOMING_COLLECTION_ID, "Imported title");
            OrbitLibraryArchiveService.PreparedImport incoming = stores.archive.prepareImport(
                    new ByteArrayInputStream(encrypt(importedPayload)), PASSWORD.toCharArray());
            assertEquals(1, incoming.profileCount);
            assertEquals(1, incoming.savedTitleCount);
            stores.archive.applyImport(incoming);

            assertEquals(2, stores.profiles.getProfiles().size());
            assertProfilePresent(stores.profiles, local.id, "Local profile");
            assertProfilePresent(stores.profiles, INCOMING_PROFILE_ID, "Imported profile");
            assertEquals("an existing active profile remains selected", local.id,
                    stores.profiles.getActiveProfileId());
            assertTitlePresent(stores.library, local.id, "local-title-001", "Local title");
            assertTitlePresent(stores.library, INCOMING_PROFILE_ID, INCOMING_TITLE_ID, "Imported title");
            assertTrue(stores.library.isInCollection(local.id, localCollection.id, "local-title-001"));
            assertTrue(stores.library.isInCollection(INCOMING_PROFILE_ID,
                    INCOMING_COLLECTION_ID, INCOMING_TITLE_ID));
            JSONObject merged = stores.library.exportArchiveState();
            assertEquals(2, merged.getJSONArray("savedTitles").length());
            assertEquals(2, merged.getJSONArray("playbackProgress").length());
            assertEquals(2, merged.getJSONArray("collections").length());
            assertEquals(2, merged.getJSONArray("collectionMemberships").length());
            assertNull(stores.profiles.readArchiveImportJournal());
            assertEquals(0, markerCount(stores.library));
            completed = true;
        } finally {
            stores.close();
            if (completed) clearNamespace(context);
        }
    }

    /** Stage this test, stop the process, then run recoverPreCommitRollbackAfterProcessRestart. */
    @Test
    public void stagePreCommitRollbackForProcessRestart() throws Exception {
        Context context = isolatedStorage("rollback");
        clearNamespace(context);
        Stores stores = new Stores(context);
        try {
            LocalProfileStore.Profile local = stores.profiles.createProfile("Rollback local");
            assertNotNull(local);
            stores.library.saveTitle(local.id, title("rollback-local-title", "Rollback local title"));

            JSONObject fixture = fixturePayload(INCOMING_PROFILE_ID, "Rollback imported",
                    INCOMING_TITLE_ID, INCOMING_COLLECTION_ID, "Rollback imported title");
            Set<String> introduced = Collections.singleton(INCOMING_PROFILE_ID);
            assertTrue(stores.profiles.writeArchiveImportJournal(
                    ROLLBACK_OPERATION_ID, introduced, ""));
            assertTrue(stores.profiles.mergeArchiveProfiles(
                    fixture.getJSONArray("profiles"), INCOMING_PROFILE_ID));

            assertEquals("the imported active profile is durable before the SQLite commit",
                    INCOMING_PROFILE_ID, stores.profiles.getActiveProfileId());
            assertNotNull(stores.profiles.readArchiveImportJournal());
            assertEquals(0, markerCount(stores.library));
            assertTitlePresent(stores.library, local.id, "rollback-local-title", "Rollback local title");
            assertNull(stores.library.getSavedTitle(INCOMING_PROFILE_ID, INCOMING_TITLE_ID));
            recordStageProcess(context);
        } finally {
            stores.close();
        }
    }

    /** Run in a fresh process after staging; verifies pre-commit rollback and active-ID restoration. */
    @Test
    public void recoverPreCommitRollbackAfterProcessRestart() throws Exception {
        Context context = isolatedStorage("rollback");
        assertDifferentProcess(context);
        Stores stores = new Stores(context);
        boolean completed = false;
        try {
            LocalProfileStore.ArchiveImportJournal staged = stores.profiles.readArchiveImportJournal();
            assertNotNull("pre-commit recovery requires a durable journal", staged);
            assertEquals(ROLLBACK_OPERATION_ID, staged.operationId);
            assertFalse("pre-commit recovery must have no SQLite commit marker",
                    stores.library.hasArchiveImportCommit(ROLLBACK_OPERATION_ID));
            assertProfilePresent(stores.profiles, INCOMING_PROFILE_ID, "Rollback imported");

            stores.archive.recoverInterruptedImport();

            assertEquals(1, stores.profiles.getProfiles().size());
            assertProfileNamed(stores.profiles, "Rollback local");
            assertEquals("the previously empty active selection is restored", "",
                    stores.profiles.getActiveProfileId());
            assertNull(stores.profiles.getActiveProfile());
            assertTitleNamed(stores.library, "rollback-local-title", "Rollback local title");
            assertNull(stores.library.getSavedTitle(INCOMING_PROFILE_ID, INCOMING_TITLE_ID));
            assertNull(stores.profiles.readArchiveImportJournal());
            assertEquals(0, markerCount(stores.library));
            completed = true;
        } finally {
            stores.close();
            if (completed) {
                consumeStageProcess(context);
                clearNamespace(context);
            }
        }
    }

    /** Stage this test, stop the process, then run recoverPostCommitFinalizeAfterProcessRestart. */
    @Test
    public void stagePostCommitFinalizeForProcessRestart() throws Exception {
        Context context = isolatedStorage("finalize");
        clearNamespace(context);
        Stores stores = new Stores(context);
        try {
            LocalProfileStore.Profile local = stores.profiles.createProfile("Finalize local");
            assertNotNull(local);
            stores.profiles.selectProfile(local.id);
            stores.library.saveTitle(local.id, title("finalize-local-title", "Finalize local title"));
            flushPreferences(context);

            JSONObject fixture = fixturePayload(INCOMING_PROFILE_ID, "Finalize imported",
                    INCOMING_TITLE_ID, INCOMING_COLLECTION_ID, "Finalize imported title");
            Set<String> introduced = Collections.singleton(INCOMING_PROFILE_ID);
            assertTrue(stores.profiles.writeArchiveImportJournal(
                    FINALIZE_OPERATION_ID, introduced, local.id));
            assertTrue(stores.profiles.mergeArchiveProfiles(
                    fixture.getJSONArray("profiles"), INCOMING_PROFILE_ID));
            stores.library.mergeArchiveState(fixture.getJSONObject("library"),
                    new HashSet<>(Arrays.asList(local.id, INCOMING_PROFILE_ID)), FINALIZE_OPERATION_ID);

            LocalProfileStore.ArchiveImportJournal journal = stores.profiles.readArchiveImportJournal();
            assertNotNull(journal);
            assertEquals(FINALIZE_OPERATION_ID, journal.operationId);
            assertTrue(stores.library.hasArchiveImportCommit(FINALIZE_OPERATION_ID));
            assertEquals("a valid local selection is retained after the database commit",
                    local.id, stores.profiles.getActiveProfileId());
            assertTitlePresent(stores.library, local.id, "finalize-local-title", "Finalize local title");
            assertTitlePresent(stores.library, INCOMING_PROFILE_ID, INCOMING_TITLE_ID,
                    "Finalize imported title");
            recordStageProcess(context);
        } finally {
            stores.close();
        }
    }

    /** Run in a fresh process after staging; verifies committed data is kept and finalized. */
    @Test
    public void recoverPostCommitFinalizeAfterProcessRestart() throws Exception {
        Context context = isolatedStorage("finalize");
        assertDifferentProcess(context);
        Stores stores = new Stores(context);
        boolean completed = false;
        try {
            LocalProfileStore.ArchiveImportJournal staged = stores.profiles.readArchiveImportJournal();
            assertNotNull("post-commit recovery requires a durable journal", staged);
            assertEquals(FINALIZE_OPERATION_ID, staged.operationId);
            assertTrue("post-commit recovery requires its SQLite commit marker",
                    stores.library.hasArchiveImportCommit(FINALIZE_OPERATION_ID));
            assertProfilePresent(stores.profiles, INCOMING_PROFILE_ID, "Finalize imported");
            assertTitlePresent(stores.library, INCOMING_PROFILE_ID, INCOMING_TITLE_ID,
                    "Finalize imported title");

            stores.archive.recoverInterruptedImport();

            assertEquals(2, stores.profiles.getProfiles().size());
            assertProfileNamed(stores.profiles, "Finalize local");
            assertProfileNamed(stores.profiles, "Finalize imported");
            assertEquals("the committed import retains the prior local active profile",
                    profileIdNamed(stores.profiles, "Finalize local"),
                    stores.profiles.getActiveProfileId());
            assertTitleNamed(stores.library, "finalize-local-title", "Finalize local title");
            assertTitlePresent(stores.library, INCOMING_PROFILE_ID, INCOMING_TITLE_ID,
                    "Finalize imported title");
            assertTrue(stores.library.isInCollection(INCOMING_PROFILE_ID,
                    INCOMING_COLLECTION_ID, INCOMING_TITLE_ID));
            assertNull(stores.profiles.readArchiveImportJournal());
            assertEquals(0, markerCount(stores.library));
            completed = true;
        } finally {
            stores.close();
            if (completed) {
                consumeStageProcess(context);
                clearNamespace(context);
            }
        }
    }

    private static Context isolatedStorage(String namespace) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        Application application = (Application) target.getApplicationContext();
        assertEquals("the isolated runner must suppress OrbitApplication startup",
                Application.class.getName(), application.getClass().getName());
        assertTrue("isolated test storage requires device-protected storage support",
                Build.VERSION.SDK_INT >= 24);
        Context deviceProtected = target.createDeviceProtectedStorageContext();
        assertTrue(deviceProtected.isDeviceProtectedStorage());
        assertEquals("com.auras.orbit", target.getPackageName());
        assertEquals("com.auras.orbit", deviceProtected.getPackageName());

        NamespacedDeviceProtectedContext isolated =
                new NamespacedDeviceProtectedContext(deviceProtected, namespace);
        File normalPreferences = sharedPreferencesFile(target, PREFS_NAME);
        File isolatedPreferences = sharedPreferencesFile(deviceProtected,
                TEST_NAMESPACE_PREFIX + namespace + "_" + PREFS_NAME);
        assertFalse("namespaced preferences must differ from Orbit's normal preference file",
                normalPreferences.getAbsoluteFile().equals(isolatedPreferences.getAbsoluteFile()));
        assertFalse("namespaced database must differ from Orbit's normal database file",
                target.getDatabasePath(DATABASE_NAME).getAbsoluteFile().equals(
                        isolated.getDatabasePath(DATABASE_NAME).getAbsoluteFile()));
        return isolated;
    }

    private static File sharedPreferencesFile(Context context, String name) {
        return new File(new File(context.getDataDir(), "shared_prefs"), name + ".xml");
    }

    private static void clearNamespace(Context context) {
        LocalProfileStore.setArchiveRecoveryBlocked(false);
        assertTrue(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit());
        context.deleteDatabase(DATABASE_NAME);
    }

    private static void flushPreferences(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        assertTrue(preferences.edit().commit());
    }

    private static void recordStageProcess(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        assertTrue("stage process marker must be durable",
                preferences.edit().putInt(STAGED_PID_KEY, Process.myPid()).commit());
    }

    private static void consumeStageProcess(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        assertTrue("stage process marker must be consumed after recovery",
                preferences.edit().remove(STAGED_PID_KEY).commit());
    }

    private static void assertDifferentProcess(Context context) {
        int stagedPid = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(STAGED_PID_KEY, -1);
        assertTrue("run the recovery phase after a new process starts", stagedPid > 0
                && stagedPid != Process.myPid());
    }

    private static JSONObject fixturePayload(String profileId, String profileName,
            String titleId, String collectionId, String title) throws JSONException {
        JSONObject profile = new JSONObject().put("id", profileId).put("name", profileName);
        JSONArray profiles = new JSONArray().put(profile);
        JSONObject savedTitle = titleRow(profileId, titleId, title, 100L, 200L);
        JSONObject playback = titleRow(profileId, titleId, title, 300L, 400L)
                .put("progressMs", 25_000L)
                .put("durationMs", 100_000L)
                .put("resumeEpisodeId", "incoming-episode-1")
                .put("resumeEpisodeTitle", "Incoming episode")
                .put("resumeSeason", 1)
                .put("resumeEpisodeNumber", 1);
        JSONObject collection = new JSONObject()
                .put("profileId", profileId)
                .put("collectionId", collectionId)
                .put("name", profileName + " list")
                .put("createdAt", 100L)
                .put("updatedAt", 200L);
        JSONObject membership = new JSONObject()
                .put("profileId", profileId)
                .put("collectionId", collectionId)
                .put("stableId", titleId)
                .put("addedAt", 150L);
        JSONObject library = new JSONObject()
                .put("savedTitles", new JSONArray().put(savedTitle))
                .put("playbackProgress", new JSONArray().put(playback))
                .put("collections", new JSONArray().put(collection))
                .put("collectionMemberships", new JSONArray().put(membership));
        return new JSONObject()
                .put("format", OrbitLibraryArchiveService.FORMAT)
                .put("version", OrbitLibraryArchiveService.VERSION)
                .put("createdAt", 1_800_000_000_000L)
                .put("profiles", profiles)
                .put("activeProfileId", profileId)
                .put("library", library);
    }

    private static JSONObject titleRow(String profileId, String titleId, String title, long savedAt,
            long updatedAt) throws JSONException {
        return new JSONObject()
                .put("profileId", profileId)
                .put("stableId", titleId)
                .put("sourceId", "instrumentation-fixture")
                .put("title", title)
                .put("mediaType", "MOVIE")
                .put("detailUrl", "https://example.invalid/title?token=omitted#fragment")
                .put("posterUrl", "https://example.invalid/poster?signature=omitted#fragment")
                .put("year", "2026")
                .put("synopsis", "Instrumentation fixture row")
                .put("savedAt", savedAt)
                .put("favorite", true)
                .put("watchStatus", LocalLibraryStore.STATUS_WATCHING)
                .put("progressMs", 0L)
                .put("durationMs", 0L)
                .put("resumeEpisodeId", "")
                .put("resumeEpisodeTitle", "")
                .put("resumeSeason", -1)
                .put("resumeEpisodeNumber", -1)
                .put("updatedAt", updatedAt);
    }

    private static LocalLibraryStore.SavedTitle title(String stableId, String name) {
        return new LocalLibraryStore.SavedTitle(stableId, "instrumentation-fixture", name,
                "MOVIE", "https://example.invalid/title?token=omitted#fragment",
                "https://example.invalid/poster?signature=omitted#fragment", "2026", "local fixture");
    }

    private static byte[] encrypt(JSONObject payload) throws IOException,
            OrbitLibraryArchiveCodec.ArchiveException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrbitLibraryArchiveCodec.write(payload, output, PASSWORD.toCharArray());
        return output.toByteArray();
    }

    private static String snapshot(LocalProfileStore profiles, LocalLibraryStore library) {
        StringBuilder result = new StringBuilder(profiles.getActiveProfileId()).append('\n');
        for (LocalProfileStore.Profile profile : profiles.getProfiles()) {
            result.append(profile.id).append(':').append(profile.name).append('\n');
        }
        result.append(library.exportArchiveState());
        return result.toString();
    }

    private static int markerCount(LocalLibraryStore library) {
        try (Cursor cursor = library.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM archive_import_commits", null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }

    private static void assertProfilePresent(LocalProfileStore store, String id, String name) {
        for (LocalProfileStore.Profile profile : store.getProfiles()) {
            if (id.equals(profile.id) && name.equals(profile.name)) return;
        }
        fail("Missing profile " + name + " (" + id + ")");
    }

    private static void assertProfileNamed(LocalProfileStore store, String name) {
        assertTrue("missing profile " + name, profileIdNamedOrNull(store, name) != null);
    }

    private static String profileIdNamed(LocalProfileStore store, String name) {
        String id = profileIdNamedOrNull(store, name);
        assertNotNull("missing profile " + name, id);
        return id;
    }

    private static String profileIdNamedOrNull(LocalProfileStore store, String name) {
        for (LocalProfileStore.Profile profile : store.getProfiles()) {
            if (name.equals(profile.name)) return profile.id;
        }
        return null;
    }

    private static void assertTitlePresent(LocalLibraryStore library, String profileId,
            String stableId, String title) {
        LocalLibraryStore.SavedTitle row = library.getSavedTitle(profileId, stableId);
        assertNotNull("missing title " + stableId, row);
        assertEquals(title, row.title);
    }

    private static void assertTitleNamed(LocalLibraryStore library, String stableId, String title) {
        JSONObject state = library.exportArchiveState();
        JSONArray rows = state.optJSONArray("savedTitles");
        assertNotNull(rows);
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row != null && stableId.equals(row.optString("stableId"))) {
                assertEquals(title, row.optString("title"));
                return;
            }
        }
        fail("Missing title " + stableId);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) return false;
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            boolean match = true;
            for (int index = 0; index < needle.length; index++) {
                if (haystack[start + index] != needle[index]) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    private static void expectArchiveFailure(ArchiveAction action) throws Exception {
        try {
            action.run();
        } catch (OrbitLibraryArchiveCodec.ArchiveException expected) {
            return;
        }
        fail("Expected invalid archive preview to be rejected");
    }

    private interface ArchiveAction {
        void run() throws Exception;
    }

    private static final class Stores {
        final LocalProfileStore profiles;
        final LocalLibraryStore library;
        final OrbitLibraryArchiveService archive;

        Stores(Context context) {
            profiles = new LocalProfileStore(context);
            library = new LocalLibraryStore(context);
            archive = new OrbitLibraryArchiveService(profiles, library);
        }

        void close() {
            library.close();
        }
    }

    private static final class NamespacedDeviceProtectedContext extends ContextWrapper {
        private final String namespace;

        NamespacedDeviceProtectedContext(Context base, String namespace) {
            super(base);
            if (namespace == null || !namespace.matches("[a-z_]+")) {
                throw new IllegalArgumentException("Invalid Orbit archive instrumentation namespace.");
            }
            this.namespace = namespace;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return getBaseContext().getSharedPreferences(
                    TEST_NAMESPACE_PREFIX + namespace + "_" + name, mode);
        }

        @Override
        public File getDatabasePath(String name) {
            File baseDatabase = getBaseContext().getDatabasePath(name);
            File parent = baseDatabase.getParentFile();
            File directory = new File(parent, TEST_NAMESPACE_PREFIX + namespace);
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IllegalStateException("Could not create isolated Orbit test database directory.");
            }
            return new File(directory, name);
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(), factory, errorHandler);
        }

        @Override
        public boolean deleteDatabase(String name) {
            return SQLiteDatabase.deleteDatabase(getDatabasePath(name));
        }

        @Override
        public boolean isDeviceProtectedStorage() {
            return true;
        }
    }
}
