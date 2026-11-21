package com.auras.orbit.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.library.LocalLibraryStore;
import com.auras.orbit.profile.LocalProfileStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashSet;

/**
 * Durable phase fixtures for OrbitApplication.onCreate archive-recovery acceptance.
 * Run each stage filter alone, stop the app process, and launch OrbitShellActivity normally.
 * The host inspects the credential-protected stores after launch; these methods never recover.
 */
@RunWith(AndroidJUnit4.class)
public final class OrbitApplicationRecoveryInstrumentationTest {
    private static final String TAG = "OrbitAppRecoveryFixture";
    private static final String LOCAL_PROFILE_ID = "4b8f0123-4567-4abc-8def-0123456789ab";
    private static final String LOCAL_PROFILE_NAME = "Orbit Transfer Source Fixture";
    private static final String LOCAL_TITLE_ID = "orbitlib:acceptance:source-title:v1";
    private static final String LOCAL_TITLE_NAME = "Orbit Transfer Source Fixture Title";

    private static final String PRECOMMIT_PROFILE_ID = "51000000-0000-4000-8000-000000000001";
    private static final String PRECOMMIT_OPERATION_ID = "52000000-0000-4000-8000-000000000001";
    private static final String POSTCOMMIT_PROFILE_ID = "51000000-0000-4000-8000-000000000002";
    private static final String POSTCOMMIT_COLLECTION_ID = "53000000-0000-4000-8000-000000000001";
    private static final String POSTCOMMIT_OPERATION_ID = "52000000-0000-4000-8000-000000000002";
    private static final String POSTCOMMIT_TITLE_ID = "orbitlib:recovery:committed-title:v1";

    @Test
    public void stagePreCommitForNormalApplicationStartup() throws Exception {
        Context target = requireTargetContext();
        assertPlainRunnerApplication(target);
        LocalProfileStore profiles = new LocalProfileStore(target);
        LocalLibraryStore library = new LocalLibraryStore(target);
        try {
            assertCleanRecoveryBoundary(profiles, library);
            requireLocalFixture(profiles, library);
            assertFalse("pre-commit imported profile must be absent before staging",
                    hasProfile(profiles, PRECOMMIT_PROFILE_ID));

            String previousActiveId = profiles.getActiveProfileId();
            assertEquals("the fixture starts from the active source profile", LOCAL_PROFILE_ID,
                    previousActiveId);

            assertFalse("pre-commit imported profile ID must not already exist",
                    hasProfile(profiles, PRECOMMIT_PROFILE_ID));
            JSONObject imported = importedProfile(PRECOMMIT_PROFILE_ID,
                    "Recovery Imported Before Commit");
            assertTrue("pre-commit journal must be durable before profile mutation",
                    profiles.writeArchiveImportJournal(PRECOMMIT_OPERATION_ID,
                            Collections.singleton(PRECOMMIT_PROFILE_ID), previousActiveId));
            assertTrue("imported profile must merge before the SQLite commit phase",
                    profiles.mergeArchiveProfiles(imported.getJSONArray("profiles"), PRECOMMIT_PROFILE_ID));

            LocalProfileStore.ArchiveImportJournal journal = profiles.readArchiveImportJournal();
            assertNotNull("pre-commit state must leave a journal for app-startup recovery", journal);
            assertEquals(PRECOMMIT_OPERATION_ID, journal.operationId);
            assertEquals(Collections.singleton(PRECOMMIT_PROFILE_ID), journal.newlyIntroducedProfileIds);
            assertEquals(LOCAL_PROFILE_ID, journal.previousActiveProfileId);
            assertFalse("pre-commit state must not have a SQLite commit marker",
                    library.hasArchiveImportCommit(PRECOMMIT_OPERATION_ID));
            assertEquals("the source profile remains active while the import is staged", LOCAL_PROFILE_ID,
                    profiles.getActiveProfileId());
            assertProfilePresent(profiles, PRECOMMIT_PROFILE_ID, "Recovery Imported Before Commit");
            assertLocalFixture(profiles, library);
            Log.i(TAG, "PRECOMMIT_STAGED operationId=" + PRECOMMIT_OPERATION_ID
                    + " activeProfileId=" + profiles.getActiveProfileId()
                    + " importedProfileId=" + PRECOMMIT_PROFILE_ID + " commitMarker=false");
        } finally {
            library.close();
        }
    }

    @Test
    public void stagePostCommitForNormalApplicationStartup() throws Exception {
        Context target = requireTargetContext();
        assertPlainRunnerApplication(target);
        LocalProfileStore profiles = new LocalProfileStore(target);
        LocalLibraryStore library = new LocalLibraryStore(target);
        try {
            assertCleanRecoveryBoundary(profiles, library);
            requireLocalFixture(profiles, library);
            assertFalse("post-commit imported profile ID must not already exist",
                    hasProfile(profiles, POSTCOMMIT_PROFILE_ID));

            JSONObject imported = postCommitFixture();
            assertTrue("post-commit journal must be durable before profile mutation",
                    profiles.writeArchiveImportJournal(POSTCOMMIT_OPERATION_ID,
                            Collections.singleton(POSTCOMMIT_PROFILE_ID), LOCAL_PROFILE_ID));
            assertTrue("committed profile must be present before its SQLite transaction",
                    profiles.mergeArchiveProfiles(imported.getJSONArray("profiles"), POSTCOMMIT_PROFILE_ID));
            HashSet<String> importedProfileIds = new HashSet<>();
            importedProfileIds.add(LOCAL_PROFILE_ID);
            importedProfileIds.add(POSTCOMMIT_PROFILE_ID);
            library.mergeArchiveState(imported.getJSONObject("library"), importedProfileIds,
                    POSTCOMMIT_OPERATION_ID);

            LocalProfileStore.ArchiveImportJournal journal = profiles.readArchiveImportJournal();
            assertNotNull("post-commit state must leave a journal for app-startup recovery", journal);
            assertEquals(POSTCOMMIT_OPERATION_ID, journal.operationId);
            assertEquals(Collections.singleton(POSTCOMMIT_PROFILE_ID), journal.newlyIntroducedProfileIds);
            assertEquals(LOCAL_PROFILE_ID, journal.previousActiveProfileId);
            assertTrue("post-commit state must include the atomic SQLite marker",
                    library.hasArchiveImportCommit(POSTCOMMIT_OPERATION_ID));
            assertEquals("the valid local active profile remains selected", LOCAL_PROFILE_ID,
                    profiles.getActiveProfileId());
            assertProfilePresent(profiles, POSTCOMMIT_PROFILE_ID, "Recovery Imported After Commit");
            assertLocalFixture(profiles, library);
            assertImportedFixture(library);
            Log.i(TAG, "POSTCOMMIT_STAGED operationId=" + POSTCOMMIT_OPERATION_ID
                    + " activeProfileId=" + profiles.getActiveProfileId()
                    + " importedProfileId=" + POSTCOMMIT_PROFILE_ID + " commitMarker=true");
        } finally {
            library.close();
        }
    }

    private static Context requireTargetContext() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("fixtures must use Orbit's normal credential-protected stores",
                "com.auras.orbit", target.getPackageName());
        return target;
    }

    private static void assertPlainRunnerApplication(Context target) {
        assertEquals("staging must not run OrbitApplication.onCreate before the host restart",
                Application.class.getName(), target.getApplicationContext().getClass().getName());
    }

    private static void assertCleanRecoveryBoundary(LocalProfileStore profiles,
            LocalLibraryStore library) {
        assertFalse("archive recovery must not already be blocked",
                LocalProfileStore.isArchiveRecoveryBlocked());
        assertNull("the setup must start with no pending archive journal",
                profiles.readArchiveImportJournal());
        assertEquals("the setup must start with no stale commit markers", 0, markerCount(library));
    }

    private static void requireLocalFixture(LocalProfileStore profiles, LocalLibraryStore library) {
        assertProfilePresent(profiles, LOCAL_PROFILE_ID, LOCAL_PROFILE_NAME);
        assertEquals("local source fixture is active before either stage", LOCAL_PROFILE_ID,
                profiles.getActiveProfileId());
        LocalLibraryStore.SavedTitle local = library.getSavedTitle(LOCAL_PROFILE_ID, LOCAL_TITLE_ID);
        assertNotNull("the existing local fixture title must be preserved", local);
        assertEquals(LOCAL_TITLE_NAME, local.title);
        assertTrue("the local playback row must be retained",
                rowsForProfile(library.exportArchiveState().optJSONArray("playbackProgress"),
                        LOCAL_PROFILE_ID) > 0);
        assertTrue("the local collection row must be retained",
                rowsForProfile(library.exportArchiveState().optJSONArray("collections"),
                        LOCAL_PROFILE_ID) > 0);
        assertTrue("the local collection membership must be retained",
                rowsForProfile(library.exportArchiveState().optJSONArray("collectionMemberships"),
                        LOCAL_PROFILE_ID) > 0);
    }

    private static void assertLocalFixture(LocalProfileStore profiles, LocalLibraryStore library) {
        assertProfilePresent(profiles, LOCAL_PROFILE_ID, LOCAL_PROFILE_NAME);
        LocalLibraryStore.SavedTitle local = library.getSavedTitle(LOCAL_PROFILE_ID, LOCAL_TITLE_ID);
        assertNotNull("the local fixture title must coexist with the import outcome", local);
        assertEquals(LOCAL_TITLE_NAME, local.title);
        JSONObject state = library.exportArchiveState();
        assertTrue("local playback data must coexist with the import outcome",
                rowsForProfile(state.optJSONArray("playbackProgress"), LOCAL_PROFILE_ID) > 0);
        assertTrue("local collections must coexist with the import outcome",
                rowsForProfile(state.optJSONArray("collections"), LOCAL_PROFILE_ID) > 0);
        assertTrue("local collection membership must coexist with the import outcome",
                rowsForProfile(state.optJSONArray("collectionMemberships"), LOCAL_PROFILE_ID) > 0);
    }

    private static JSONObject importedProfile(String profileId, String profileName)
            throws JSONException {
        return new JSONObject().put("profiles", new JSONArray().put(new JSONObject()
                .put("id", profileId).put("name", profileName)));
    }

    private static JSONObject postCommitFixture() throws JSONException {
        JSONObject profile = new JSONObject().put("id", POSTCOMMIT_PROFILE_ID)
                .put("name", "Recovery Imported After Commit");
        JSONObject savedTitle = titleRow(POSTCOMMIT_PROFILE_ID, POSTCOMMIT_TITLE_ID,
                "Recovery committed fixture title", 100L, 200L);
        JSONObject playback = titleRow(POSTCOMMIT_PROFILE_ID, POSTCOMMIT_TITLE_ID,
                "Recovery committed fixture title", 300L, 400L)
                .put("progressMs", 17_000L)
                .put("durationMs", 99_000L)
                .put("resumeEpisodeId", "recovery-episode-v1")
                .put("resumeEpisodeTitle", "Synthetic recovery episode")
                .put("resumeSeason", 1)
                .put("resumeEpisodeNumber", 2);
        JSONObject collection = new JSONObject()
                .put("profileId", POSTCOMMIT_PROFILE_ID)
                .put("collectionId", POSTCOMMIT_COLLECTION_ID)
                .put("name", "Recovery committed fixture list")
                .put("createdAt", 100L)
                .put("updatedAt", 200L);
        JSONObject membership = new JSONObject()
                .put("profileId", POSTCOMMIT_PROFILE_ID)
                .put("collectionId", POSTCOMMIT_COLLECTION_ID)
                .put("stableId", POSTCOMMIT_TITLE_ID)
                .put("addedAt", 150L);
        JSONObject library = new JSONObject()
                .put("savedTitles", new JSONArray().put(savedTitle))
                .put("playbackProgress", new JSONArray().put(playback))
                .put("collections", new JSONArray().put(collection))
                .put("collectionMemberships", new JSONArray().put(membership));
        return new JSONObject()
                .put("profiles", new JSONArray().put(profile))
                .put("library", library);
    }

    private static JSONObject titleRow(String profileId, String stableId, String title,
            long savedAt, long updatedAt) throws JSONException {
        return new JSONObject()
                .put("profileId", profileId)
                .put("stableId", stableId)
                .put("sourceId", "orbit-recovery-fixture")
                .put("title", title)
                .put("mediaType", "MOVIE")
                .put("detailUrl", "https://recovery-fixture.invalid/title/v1")
                .put("posterUrl", "https://recovery-fixture.invalid/poster/v1")
                .put("year", "2026")
                .put("synopsis", "Synthetic .invalid archive recovery fixture.")
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

    private static void assertImportedFixture(LocalLibraryStore library) {
        LocalLibraryStore.SavedTitle imported = library.getSavedTitle(
                POSTCOMMIT_PROFILE_ID, POSTCOMMIT_TITLE_ID);
        assertNotNull("committed imported title must survive app startup", imported);
        assertEquals("Recovery committed fixture title", imported.title);
        assertTrue("committed collection membership must survive app startup",
                library.isInCollection(POSTCOMMIT_PROFILE_ID,
                        POSTCOMMIT_COLLECTION_ID, POSTCOMMIT_TITLE_ID));
        JSONObject state = library.exportArchiveState();
        assertTrue("committed playback row must survive app startup",
                rowsForProfile(state.optJSONArray("playbackProgress"), POSTCOMMIT_PROFILE_ID) > 0);
    }

    private static int rowsForProfile(JSONArray rows, String profileId) {
        if (rows == null) return 0;
        int count = 0;
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row != null && profileId.equals(row.optString("profileId"))) count++;
        }
        return count;
    }

    private static boolean hasProfile(LocalProfileStore store, String id) {
        for (LocalProfileStore.Profile profile : store.getProfiles()) {
            if (id.equals(profile.id)) return true;
        }
        return false;
    }

    private static void assertProfilePresent(LocalProfileStore store, String id, String name) {
        for (LocalProfileStore.Profile profile : store.getProfiles()) {
            if (id.equals(profile.id) && name.equals(profile.name)) return;
        }
        fail("missing profile " + name + " (" + id + ")");
    }

    private static int markerCount(LocalLibraryStore library) {
        try (Cursor cursor = library.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM archive_import_commits", null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }
}
