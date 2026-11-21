package com.auras.orbit.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.auras.orbit.library.LocalLibraryStore;
import com.auras.orbit.profile.LocalProfileStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Filtered fixture seeders for the manual two-device .orbitlib transfer acceptance.
 * The root runner restores the source snapshot and prepares the disposable receiver.
 */
@RunWith(AndroidJUnit4.class)
public final class OrbitLibraryTransferFixtureInstrumentationTest {
    private static final String TAG = "OrbitTransferFixture";
    private static final String PROFILE_PREFERENCES = "orbit_shell_local_profiles_v1";

    private static final String SOURCE_PROFILE_ID = "4b8f0123-4567-4abc-8def-0123456789ab";
    private static final String SOURCE_PROFILE_NAME = "Orbit Transfer Source Fixture";
    private static final String SOURCE_TITLE_ID = "orbitlib:acceptance:source-title:v1";
    private static final String SOURCE_TITLE_NAME = "Orbit Transfer Source Fixture Title";

    private static final String RECEIVER_TITLE_ID = "orbitlib:acceptance:receiver-local-only:v1";
    private static final String RECEIVER_TITLE_NAME = "Orbit Receiver Local Only Fixture";

    @Test
    public void seedSourceTransferFixture() throws Exception {
        Context target = requireInstrumentedOrbitContext();
        LocalProfileStore profiles = new LocalProfileStore(target);
        LocalLibraryStore library = new LocalLibraryStore(target);
        try {
            LocalProfileStore.Profile baseline = requireEmptyBaseline(profiles, library);
            assertNotEquals("source fixture profile ID must be unique", baseline.id, SOURCE_PROFILE_ID);

            JSONArray importedProfiles = new JSONArray().put(new JSONObject()
                    .put("id", SOURCE_PROFILE_ID)
                    .put("name", SOURCE_PROFILE_NAME));
            assertTrue("source fixture profile must merge", profiles.mergeArchiveProfiles(
                    importedProfiles, SOURCE_PROFILE_ID));
            profiles.selectProfile(SOURCE_PROFILE_ID);
            flushProfilePreferences(target);

            LocalLibraryStore.SavedTitle sourceTitle = new LocalLibraryStore.SavedTitle(
                    SOURCE_TITLE_ID, "orbit-transfer-fixture", SOURCE_TITLE_NAME, "MOVIE",
                    "https://source-fixture.invalid/title/source-v1?fixture=1#detail",
                    "https://source-fixture.invalid/poster/source-v1?fixture=1#image",
                    "2026", "Synthetic source-only transfer fixture.");
            library.saveTitle(SOURCE_PROFILE_ID, sourceTitle);
            assertTrue(library.setFavorite(SOURCE_PROFILE_ID, SOURCE_TITLE_ID, true));
            assertTrue(library.setWatchStatus(SOURCE_PROFILE_ID, SOURCE_TITLE_ID,
                    LocalLibraryStore.STATUS_WATCHING));
            assertTrue(library.recordPlaybackProgress(SOURCE_PROFILE_ID, sourceTitle,
                    31_000L, 120_000L, "source-episode-v1", "Fixture episode", 1, 1));
            LocalLibraryStore.Collection collection = library.createCollection(
                    SOURCE_PROFILE_ID, "Orbit Transfer Source Fixture List");
            assertTrue(library.addToCollection(SOURCE_PROFILE_ID, collection.id, SOURCE_TITLE_ID));

            assertEquals(SOURCE_PROFILE_ID, profiles.getActiveProfileId());
            assertProfilePresent(profiles, SOURCE_PROFILE_ID, SOURCE_PROFILE_NAME);
            LocalLibraryStore.SavedTitle saved = library.getSavedTitle(SOURCE_PROFILE_ID, SOURCE_TITLE_ID);
            assertNotNull(saved);
            assertEquals(SOURCE_TITLE_NAME, saved.title);
            assertEquals("https://source-fixture.invalid/title/source-v1?fixture=1#detail", saved.detailUrl);
            assertTrue(saved.favorite);
            assertEquals(LocalLibraryStore.STATUS_WATCHING, saved.watchStatus);
            assertTrue(library.isInCollection(SOURCE_PROFILE_ID, collection.id, SOURCE_TITLE_ID));

            JSONObject state = library.exportArchiveState();
            assertEquals(1, rowsForProfile(state.getJSONArray("savedTitles"), SOURCE_PROFILE_ID));
            assertEquals(1, rowsForProfile(state.getJSONArray("playbackProgress"), SOURCE_PROFILE_ID));
            assertEquals(1, rowsForProfile(state.getJSONArray("collections"), SOURCE_PROFILE_ID));
            assertEquals(1, rowsForProfile(state.getJSONArray("collectionMemberships"), SOURCE_PROFILE_ID));
            assertEquals("the baseline profile remains local and empty", 0,
                    rowsForProfile(state.getJSONArray("savedTitles"), baseline.id));

            Log.i(TAG, "SOURCE_SEEDED profileId=" + SOURCE_PROFILE_ID + " titleId=" + SOURCE_TITLE_ID
                    + " collectionId=" + collection.id + " activeProfileId=" + profiles.getActiveProfileId());
        } finally {
            library.close();
        }
    }

    @Test
    public void seedReceiverTransferFixture() throws Exception {
        Context target = requireInstrumentedOrbitContext();
        LocalProfileStore profiles = new LocalProfileStore(target);
        LocalLibraryStore library = new LocalLibraryStore(target);
        try {
            LocalProfileStore.Profile receiver = requireEmptyBaseline(profiles, library);
            assertNotEquals("receiver profile must differ from the imported source profile",
                    SOURCE_PROFILE_ID, receiver.id);

            LocalLibraryStore.SavedTitle localTitle = new LocalLibraryStore.SavedTitle(
                    RECEIVER_TITLE_ID, "orbit-transfer-fixture", RECEIVER_TITLE_NAME, "SERIES",
                    "https://receiver-fixture.invalid/title/local-only-v1",
                    "https://receiver-fixture.invalid/poster/local-only-v1",
                    "2025", "Synthetic receiver-only row that must survive import.");
            library.saveTitle(receiver.id, localTitle);
            assertTrue(library.setFavorite(receiver.id, RECEIVER_TITLE_ID, true));
            assertTrue(library.setWatchStatus(receiver.id, RECEIVER_TITLE_ID,
                    LocalLibraryStore.STATUS_COMPLETED));

            assertEquals(receiver.id, profiles.getActiveProfileId());
            assertProfilePresent(profiles, receiver.id, receiver.name);
            LocalLibraryStore.SavedTitle saved = library.getSavedTitle(receiver.id, RECEIVER_TITLE_ID);
            assertNotNull(saved);
            assertEquals(RECEIVER_TITLE_NAME, saved.title);
            assertTrue(saved.favorite);
            assertEquals(LocalLibraryStore.STATUS_COMPLETED, saved.watchStatus);
            assertNull("source fixture title must not exist on the receiver before import",
                    library.getSavedTitle(SOURCE_PROFILE_ID, SOURCE_TITLE_ID));

            JSONObject state = library.exportArchiveState();
            assertEquals(1, rowsForProfile(state.getJSONArray("savedTitles"), receiver.id));
            assertEquals(0, state.getJSONArray("playbackProgress").length());
            assertEquals(0, state.getJSONArray("collections").length());
            assertEquals(0, state.getJSONArray("collectionMemberships").length());
            assertEquals(0, rowsForProfile(state.getJSONArray("savedTitles"), SOURCE_PROFILE_ID));

            Log.i(TAG, "RECEIVER_SEEDED profileId=" + receiver.id + " titleId=" + RECEIVER_TITLE_ID
                    + " activeProfileId=" + profiles.getActiveProfileId());
        } finally {
            library.close();
        }
    }

    private static Context requireInstrumentedOrbitContext() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("the fixture must seed Orbit's credential-protected package", "com.auras.orbit",
                target.getPackageName());
        assertEquals("the isolated test runner must keep OrbitApplication.onCreate from running",
                Application.class.getName(), target.getApplicationContext().getClass().getName());
        return target;
    }

    private static LocalProfileStore.Profile requireEmptyBaseline(LocalProfileStore profiles,
            LocalLibraryStore library) throws Exception {
        assertFalse("archive recovery must not be blocked", LocalProfileStore.isArchiveRecoveryBlocked());
        assertNull("no interrupted import may be pending", profiles.readArchiveImportJournal());

        assertEquals("fixture seeders require the prepared one-profile baseline", 1,
                profiles.getProfiles().size());
        LocalProfileStore.Profile profile = profiles.getProfiles().get(0);
        assertEquals("fixture seeders require the prepared default profile", "My Profile", profile.name);
        assertEquals("the prepared default profile must be active", profile.id,
                profiles.getActiveProfileId());

        JSONObject state = library.exportArchiveState();
        assertEquals("receiver/source library must be empty before seeding", 0,
                state.getJSONArray("savedTitles").length());
        assertEquals("receiver/source playback must be empty before seeding", 0,
                state.getJSONArray("playbackProgress").length());
        assertEquals("receiver/source collections must be empty before seeding", 0,
                state.getJSONArray("collections").length());
        assertEquals("receiver/source memberships must be empty before seeding", 0,
                state.getJSONArray("collectionMemberships").length());
        assertEquals("no prior archive commit markers may remain", 0, markerCount(library));
        return profile;
    }

    private static void flushProfilePreferences(Context context) {
        assertTrue(context.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE).edit().commit());
    }

    private static int rowsForProfile(JSONArray rows, String profileId) throws JSONException {
        int count = 0;
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.getJSONObject(index);
            if (profileId.equals(row.optString("profileId"))) count++;
        }
        return count;
    }

    private static int markerCount(LocalLibraryStore library) {
        try (Cursor cursor = library.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM archive_import_commits", null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }

    private static void assertProfilePresent(LocalProfileStore profiles, String id, String name) {
        for (LocalProfileStore.Profile profile : profiles.getProfiles()) {
            if (id.equals(profile.id) && name.equals(profile.name)) return;
        }
        fail("expected profile " + name + " (" + id + ")");
    }
}
