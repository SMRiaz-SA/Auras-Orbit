package com.auras.orbit.metadata.store;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteFullException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.library.LocalLibraryStore;
import com.auras.orbit.metadata.TitleCatalogRecord;
import com.auras.orbit.providers.MediaItem;
import com.auras.orbit.providers.MediaKind;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Android SQLite qualification for P5, using an isolated database directory per test. */
@RunWith(AndroidJUnit4.class)
public final class OrbitMetadataPersistenceQualificationInstrumentationTest {
    private static final String TAG = "P5MetadataStoreTest";
    private static final String PROFILE_ID = "p5-profile";
    private static final String PROVIDER_ID = "p5-provider";
    private static final String STABLE_ID = "p5-title";
    private static final String DETAIL_URL = "https://provider.invalid/title/p5";

    // Independently computed SHA-256 values for the existing length-delimited source-key format.
    private static final String PROFILE_SCOPE_SHA256 =
            "2279955fd7e2f672edb2b38f734f515d6814139a5aa6ba23f5c16ba05972d616";
    private static final String SOURCE_KEY_SHA256 =
            "d09683ffd6fd96330598e6cd9fd74754498f239234213fca4a08c67e09441ef9";

    private final List<SQLiteOpenHelper> openedHelpers = new ArrayList<>();
    private Context targetContext;
    private File isolatedRoot;
    private String libraryCollectionId;

    @Before public void setUpIsolatedDatabases() {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        isolatedRoot = new File(targetContext.getCacheDir(), "p5-" + UUID.randomUUID());
        assertTrue("Could not create the P5 fixture directory", isolatedRoot.mkdirs());
    }

    @After public void closeAndRemoveIsolatedDatabases() {
        for (int index = openedHelpers.size() - 1; index >= 0; index--) {
            try { openedHelpers.get(index).close(); } catch (RuntimeException ignored) { }
        }
        openedHelpers.clear();
        if (isolatedRoot != null && isolatedRoot.exists()) {
            assertTrue("P5 fixture databases were not fully closed and removed",
                    deleteRecursively(isolatedRoot));
        }
    }

    @Test public void versionOneUpgradePreservesDisplayCorrectionAndCreatesVersionFourTables()
            throws Exception {
        IsolatedDatabaseContext context = newIsolatedContext("v1");
        createVersionOneFixture(context);

        OrbitMetadataUserDecisions decisions = newDecisions(context);
        SQLiteDatabase migrated = decisions.getWritableDatabase();
        assertEquals("v1 must migrate additively through the v2/v3/v4 steps",
                4, migrated.getVersion());
        assertEquals("the existing display correction must survive v1-to-v4",
                "User correction from v1", decisions.read(PROFILE_ID, PROVIDER_ID,
                        STABLE_ID, DETAIL_URL, OrbitMetadataUserDecisions.Field.TITLE));
        assertNull("v1 did not contain a TMDB decision", decisions.readTmdbLink(PROFILE_ID,
                PROVIDER_ID, STABLE_ID, DETAIL_URL, "MOVIE"));
        assertTableExists(migrated, "user_catalog_link");
        assertTableExists(migrated, "user_decision_history");
        assertTableExists(migrated, "user_identity_assignment");
        assertTableExists(migrated, "identity_correction_history");
    }

    @Test public void versionTwoUpgradePreservesTypedLinkAndDisplayCorrection() throws Exception {
        IsolatedDatabaseContext context = newIsolatedContext("v2");
        createVersionTwoFixture(context);

        OrbitMetadataUserDecisions decisions = newDecisions(context);
        SQLiteDatabase migrated = decisions.getWritableDatabase();
        assertEquals("v2 must migrate additively to v4",
                4, migrated.getVersion());
        assertEquals("the existing display correction must survive v2-to-v4",
                "User correction from v2", decisions.read(PROFILE_ID, PROVIDER_ID,
                        STABLE_ID, DETAIL_URL, OrbitMetadataUserDecisions.Field.TITLE));
        OrbitMetadataUserDecisions.TmdbLink link = decisions.readTmdbLink(PROFILE_ID,
                PROVIDER_ID, STABLE_ID, DETAIL_URL, "SERIES");
        assertNotNull("the explicit typed link must survive v2-to-v4", link);
        assertEquals(7301, link.id);
        assertEquals("SERIES", link.kind);
        assertEquals(4L, link.revision);
        assertTrue("the test fixture's decision must remain a rejection", link.rejected);
        assertTableExists(migrated, "user_decision_history");
        assertTableExists(migrated, "user_identity_assignment");
        assertTableExists(migrated, "identity_correction_history");
    }

    @Test public void walCacheAndDurableOverridesSurviveHelperCloseAndReopen() {
        IsolatedDatabaseContext context = newIsolatedContext("wal-reopen");
        OrbitMetadataCacheDatabase cache = newCache(context);
        OrbitMetadataUserDecisions decisions = newDecisions(context);
        LocalLibraryStore library = newLibrary(context);
        saveDurableFixture(decisions, library);
        saveCacheFixture(cache, 1_000_000L);

        assertTrue("the disposable metadata cache must use WAL",
                cache.getWritableDatabase().isWriteAheadLoggingEnabled());
        assertTrue("the durable correction store must use WAL",
                decisions.getWritableDatabase().isWriteAheadLoggingEnabled());
        cache.close();
        decisions.close();
        library.close();

        OrbitMetadataCacheDatabase reopenedCache = newCache(context);
        OrbitMetadataUserDecisions reopenedDecisions = newDecisions(context);
        LocalLibraryStore reopenedLibrary = newLibrary(context);
        OrbitMetadataCacheDatabase.Display display = reopenedCache.readProviderDisplay(
                PROFILE_ID, PROVIDER_ID, providerItem("Seed title", "Small synopsis"), 1_000_001L);
        assertNotNull("the SQLite WAL cache must rehydrate the provider display after reopen", display);
        assertEquals("Provider display title", display.title);
        assertTrue(display.fresh);
        TitleCatalogRecord catalog = reopenedCache.readCatalogTitle(123, "MOVIE", 1_000_001L);
        assertNotNull("the catalog display must survive WAL reopen", catalog);
        assertEquals("Catalog title", catalog.title);
        assertDurableFixture(reopenedDecisions, reopenedLibrary);
    }

    @Test public void cacheCorruptionRecoveryRebuildsOnlyDisposableCache() throws Exception {
        IsolatedDatabaseContext context = newIsolatedContext("corrupt-recovery");
        OrbitMetadataCacheDatabase cache = newCache(context);
        OrbitMetadataUserDecisions decisions = newDecisions(context);
        LocalLibraryStore library = newLibrary(context);
        saveDurableFixture(decisions, library);
        saveCacheFixture(cache, 2_000_000L);
        cache.close();
        decisions.close();
        library.close();

        File cacheFile = context.getDatabasePath(OrbitMetadataCacheDatabase.DATABASE_NAME);
        try (FileOutputStream output = new FileOutputStream(cacheFile, false)) {
            output.write(new byte[]{0x4f, 0x52, 0x42, 0x49, 0x54, 0x00, 0x01});
            output.getFD().sync();
        }
        assertDatabaseCorrupt(cacheFile);

        assertTrue("confirmed metadata-cache corruption must recover by rebuilding only that cache",
                cache.recoverCorruptCache(context));
        cache.close();
        OrbitMetadataCacheDatabase reopenedCache = newCache(context);
        OrbitMetadataUserDecisions reopenedDecisions = newDecisions(context);
        LocalLibraryStore reopenedLibrary = newLibrary(context);
        assertNull("the rebuilt disposable cache must not retain corrupted cache rows",
                reopenedCache.readProviderDisplay(PROFILE_ID, PROVIDER_ID,
                        providerItem("Seed title", "Small synopsis"), 2_000_001L));
        assertNull("the rebuilt disposable cache must not retain corrupted catalog rows",
                reopenedCache.readCatalogTitle(123, "MOVIE", 2_000_001L));
        assertDurableFixture(reopenedDecisions, reopenedLibrary);
    }

    @Test public void sqliteFullCacheWriteRollsBackAndLeavesLibraryAndCorrectionsReadable() {
        IsolatedDatabaseContext context = newIsolatedContext("sqlite-full");
        OrbitMetadataCacheDatabase cache = newCache(context);
        OrbitMetadataUserDecisions decisions = newDecisions(context);
        LocalLibraryStore library = newLibrary(context);
        saveDurableFixture(decisions, library);
        saveCacheFixture(cache, 3_000_000L);

        SQLiteDatabase db = cache.getWritableDatabase();
        db.disableWriteAheadLogging(); // One test connection makes the page cap deterministic.
        assertFalse("the bounded-page fixture must use SQLite's single connection",
                db.isWriteAheadLoggingEnabled());
        long pageSize = db.getPageSize();
        long existingPages = scalarLong(db, "PRAGMA page_count");
        long freePages = scalarLong(db, "PRAGMA freelist_count");
        long maximumBytes = pageSize * existingPages;

        StringBuilder largeSynopsis = new StringBuilder(24_000);
        for (int index = 0; index < 24_000; index++) largeSynopsis.append('x');
        String fullWriteStableId = STABLE_ID + "-sqlite-full-candidate";
        MediaItem fullWriteSeed = new MediaItem(fullWriteStableId, "Seed for full write",
                MediaKind.MOVIE, DETAIL_URL + "/full", "", "2020", "Small synopsis");
        MediaItem enlargedDetails = new MediaItem(fullWriteStableId, "New title at page limit",
                MediaKind.MOVIE, DETAIL_URL, "", "", largeSynopsis.toString());
        boolean sawSqliteFull = false;
        long appliedMaximumBytes = db.setMaximumSize(maximumBytes);
        Log.i(TAG, "SQLITE_FULL fixture before write: pageSize=" + pageSize
                + " pageCount=" + existingPages + " freelistCount=" + freePages
                + " maxPageCount=" + scalarLong(db, "PRAGMA max_page_count")
                + " appliedMaxBytes=" + appliedMaximumBytes);
        assertTrue("the test must constrain SQLite to its existing page count",
                appliedMaximumBytes <= maximumBytes + pageSize);
        try {
            cache.saveProviderDisplay(PROFILE_ID, PROVIDER_ID,
                    fullWriteSeed, enlargedDetails, 3_000_001L);
        } catch (SQLiteFullException full) {
            sawSqliteFull = true;
        }
        Log.i(TAG, "SQLITE_FULL fixture after write: pageCount="
                + scalarLong(db, "PRAGMA page_count") + " freelistCount="
                + scalarLong(db, "PRAGMA freelist_count") + " sawSqliteFull=" + sawSqliteFull);
        assertTrue("the bounded SQLite page limit must exercise the SQLITE_FULL write path", sawSqliteFull);

        cache.close();
        decisions.close();
        library.close();
        OrbitMetadataCacheDatabase reopenedCache = newCache(context);
        OrbitMetadataUserDecisions reopenedDecisions = newDecisions(context);
        LocalLibraryStore reopenedLibrary = newLibrary(context);
        OrbitMetadataCacheDatabase.Display priorDisplay = reopenedCache.readProviderDisplay(
                PROFILE_ID, PROVIDER_ID, providerItem("Seed title", "Small synopsis"), 3_000_002L);
        assertNotNull("a failed write must leave the previous good cache snapshot readable", priorDisplay);
        assertEquals("Provider display title", priorDisplay.title);
        assertNull("the failed cache insert must roll back its candidate row",
                reopenedCache.readProviderDisplay(PROFILE_ID, PROVIDER_ID,
                        fullWriteSeed, 3_000_002L));
        assertDurableFixture(reopenedDecisions, reopenedLibrary);
    }

    @Test public void sqliteFullCatalogSynopsisRollsBackEarlierTitleField() {
        IsolatedDatabaseContext context = newIsolatedContext("sqlite-full-catalog");
        OrbitMetadataCacheDatabase cache = newCache(context);
        OrbitMetadataUserDecisions decisions = newDecisions(context);
        LocalLibraryStore library = newLibrary(context);
        saveDurableFixture(decisions, library);
        saveCacheFixture(cache, 5_000_000L);

        SQLiteDatabase db = cache.getWritableDatabase();
        db.disableWriteAheadLogging();
        assertFalse("the bounded-page fixture must use SQLite's single connection",
                db.isWriteAheadLoggingEnabled());
        long pageSize = db.getPageSize();
        long pageCount = scalarLong(db, "PRAGMA page_count");
        long freePages = scalarLong(db, "PRAGMA freelist_count");
        assertEquals("the catalog failure fixture must start without reusable free pages", 0L, freePages);

        AtomicReference<String> lastAttemptedField = new AtomicReference<>("");
        AtomicInteger successfulTitleFields = new AtomicInteger();
        AtomicInteger successfulSynopsisFields = new AtomicInteger();
        db.setCustomScalarFunction("p5_catalog_field_attempt", field -> {
            lastAttemptedField.set(field);
            return field;
        });
        db.setCustomScalarFunction("p5_catalog_field_success", field -> {
            if ("title".equals(field)) successfulTitleFields.incrementAndGet();
            if ("synopsis".equals(field)) successfulSynopsisFields.incrementAndGet();
            return field;
        });
        db.execSQL("CREATE TEMP TRIGGER p5_catalog_before_insert_probe "
                + "BEFORE INSERT ON main.catalog_field "
                + "WHEN NEW.source_ref='tmdb:movie:123' "
                + "BEGIN SELECT p5_catalog_field_attempt(NEW.field); END");
        db.execSQL("CREATE TEMP TRIGGER p5_catalog_after_insert_probe "
                + "AFTER INSERT ON main.catalog_field "
                + "WHEN NEW.source_ref='tmdb:movie:123' "
                + "BEGIN SELECT p5_catalog_field_success(NEW.field); END");

        long maximumBytes = (pageCount + 4L) * pageSize;
        long appliedMaximumBytes = db.setMaximumSize(maximumBytes);
        assertTrue("the page cap must allow title replacement but deny the large synopsis",
                appliedMaximumBytes <= maximumBytes + pageSize);
        assertEquals("the SQLite page cap must be the requested four-page growth window",
                pageCount + 4L, scalarLong(db, "PRAGMA max_page_count"));

        StringBuilder largeSynopsis = new StringBuilder(24_000);
        for (int index = 0; index < 24_000; index++) largeSynopsis.append('s');
        boolean sawSqliteFull = false;
        try {
            cache.saveCatalogTitle(new TitleCatalogRecord(123, "MOVIE", "Changed title",
                    largeSynopsis.toString(), "", "", ""), 5_000_001L);
        } catch (SQLiteFullException full) {
            sawSqliteFull = true;
        }
        Log.i(TAG, "Catalog SQLITE_FULL stage: attempted=" + lastAttemptedField.get()
                + " insertedTitle=" + successfulTitleFields.get()
                + " insertedSynopsis=" + successfulSynopsisFields.get()
                + " sawSqliteFull=" + sawSqliteFull);

        assertTrue("saveCatalogTitle must hit SQLITE_FULL on the synopsis field", sawSqliteFull);
        assertEquals("the title INSERT must succeed before the later synopsis INSERT is attempted",
                1, successfulTitleFields.get());
        assertEquals("the page-limit error must occur on the following synopsis field",
                "synopsis", lastAttemptedField.get());
        assertEquals("the oversized synopsis must not finish inserting",
                0, successfulSynopsisFields.get());

        cache.close();
        decisions.close();
        library.close();
        OrbitMetadataCacheDatabase reopenedCache = newCache(context);
        OrbitMetadataUserDecisions reopenedDecisions = newDecisions(context);
        LocalLibraryStore reopenedLibrary = newLibrary(context);
        TitleCatalogRecord priorCatalog = reopenedCache.readCatalogTitle(123, "MOVIE", 5_000_002L);
        assertNotNull("the original catalog record must remain after the failed transaction", priorCatalog);
        assertEquals("an earlier title replacement must roll back with the synopsis failure",
                "Catalog title", priorCatalog.title);
        assertEquals("the original synopsis must remain after rollback", "Catalog synopsis",
                priorCatalog.synopsis);
        assertDurableFixture(reopenedDecisions, reopenedLibrary);
    }

    @Test public void metadataResetClearsOnlyCacheAndPreservesLibraryAndOverrides() {
        IsolatedDatabaseContext context = newIsolatedContext("reset-cache-only");
        OrbitMetadataCacheDatabase cache = newCache(context);
        OrbitMetadataUserDecisions decisions = newDecisions(context);
        LocalLibraryStore library = newLibrary(context);
        saveDurableFixture(decisions, library);
        saveCacheFixture(cache, 4_000_000L);

        cache.resetCacheOnly();

        assertNull("metadata reset must clear cached provider details",
                cache.readProviderDisplay(PROFILE_ID, PROVIDER_ID,
                        providerItem("Seed title", "Small synopsis"), 4_000_001L));
        assertNull("metadata reset must clear cached catalog details",
                cache.readCatalogTitle(123, "MOVIE", 4_000_001L));
        cache.close();
        decisions.close();
        library.close();
        OrbitMetadataCacheDatabase reopenedCache = newCache(context);
        OrbitMetadataUserDecisions reopenedDecisions = newDecisions(context);
        LocalLibraryStore reopenedLibrary = newLibrary(context);
        assertNull("cache reset remains empty after helper close and reopen",
                reopenedCache.readProviderDisplay(PROFILE_ID, PROVIDER_ID,
                        providerItem("Seed title", "Small synopsis"), 4_000_002L));
        assertDurableFixture(reopenedDecisions, reopenedLibrary);
    }

    private void createVersionOneFixture(Context context) {
        SQLiteDatabase db = openFixtureDatabase(context);
        db.execSQL("CREATE TABLE user_display_override ("
                + "profile_scope TEXT NOT NULL CHECK(length(profile_scope)=64),"
                + "source_key TEXT NOT NULL CHECK(length(source_key)=64),"
                + "field TEXT NOT NULL CHECK(field IN ('TITLE','SYNOPSIS','GENRE','CAST','BACKDROP')) ,"
                + "display_value TEXT NOT NULL CHECK(length(display_value) BETWEEN 1 AND 24000),"
                + "revision INTEGER NOT NULL CHECK(revision>0),"
                + "modified_ms INTEGER NOT NULL CHECK(modified_ms>=0),"
                + "PRIMARY KEY(profile_scope,source_key,field))");
        insertOldDisplayOverride(db, "User correction from v1");
        db.setVersion(1);
        db.close();
    }

    private void createVersionTwoFixture(Context context) {
        SQLiteDatabase db = openFixtureDatabase(context);
        db.execSQL("CREATE TABLE user_display_override ("
                + "profile_scope TEXT NOT NULL CHECK(length(profile_scope)=64),"
                + "source_key TEXT NOT NULL CHECK(length(source_key)=64),"
                + "field TEXT NOT NULL CHECK(field IN ('TITLE','SYNOPSIS','GENRE','CAST','BACKDROP')) ,"
                + "display_value TEXT NOT NULL CHECK(length(display_value) BETWEEN 1 AND 24000),"
                + "revision INTEGER NOT NULL CHECK(revision>0),"
                + "modified_ms INTEGER NOT NULL CHECK(modified_ms>=0),"
                + "PRIMARY KEY(profile_scope,source_key,field))");
        db.execSQL("CREATE TABLE user_catalog_link ("
                + "profile_scope TEXT NOT NULL CHECK(length(profile_scope)=64),"
                + "source_key TEXT NOT NULL CHECK(length(source_key)=64),"
                + "catalog TEXT NOT NULL CHECK(catalog='TMDB'),"
                + "media_kind TEXT NOT NULL CHECK(media_kind IN ('MOVIE','SERIES')) ,"
                + "catalog_id INTEGER NOT NULL CHECK(catalog_id BETWEEN 1 AND 2147483647),"
                + "decision TEXT NOT NULL CHECK(decision IN ('LINK','REJECT')) ,"
                + "revision INTEGER NOT NULL CHECK(revision>0),"
                + "modified_ms INTEGER NOT NULL CHECK(modified_ms>=0),"
                + "PRIMARY KEY(profile_scope,source_key,catalog,media_kind))");
        insertOldDisplayOverride(db, "User correction from v2");
        ContentValues link = new ContentValues();
        link.put("profile_scope", PROFILE_SCOPE_SHA256);
        link.put("source_key", SOURCE_KEY_SHA256);
        link.put("catalog", "TMDB");
        link.put("media_kind", "SERIES");
        link.put("catalog_id", 7301);
        link.put("decision", "REJECT");
        link.put("revision", 4);
        link.put("modified_ms", 100L);
        db.insertOrThrow("user_catalog_link", null, link);
        db.setVersion(2);
        db.close();
    }

    private static SQLiteDatabase openFixtureDatabase(Context context) {
        File file = context.getDatabasePath(OrbitMetadataUserDecisions.DATABASE_NAME);
        return SQLiteDatabase.openOrCreateDatabase(file, null);
    }

    private static void insertOldDisplayOverride(SQLiteDatabase db, String title) {
        ContentValues row = new ContentValues();
        row.put("profile_scope", PROFILE_SCOPE_SHA256);
        row.put("source_key", SOURCE_KEY_SHA256);
        row.put("field", "TITLE");
        row.put("display_value", title);
        row.put("revision", 3);
        row.put("modified_ms", 100L);
        db.insertOrThrow("user_display_override", null, row);
    }

    private void saveCacheFixture(OrbitMetadataCacheDatabase cache, long nowMs) {
        MediaItem seed = providerItem("Seed title", "Small synopsis");
        MediaItem details = providerItem("Provider display title", "Provider details");
        cache.saveProviderDisplay(PROFILE_ID, PROVIDER_ID, seed, details, nowMs);
        cache.saveCatalogTitle(new TitleCatalogRecord(123, "MOVIE", "Catalog title",
                "Catalog synopsis", "2024", "https://image.tmdb.org/t/p/w500/poster.jpg",
                "https://image.tmdb.org/t/p/w500/backdrop.jpg"), nowMs);
    }

    private void saveDurableFixture(OrbitMetadataUserDecisions decisions,
                                    LocalLibraryStore library) {
        decisions.put(PROFILE_ID, PROVIDER_ID, STABLE_ID, DETAIL_URL,
                OrbitMetadataUserDecisions.Field.TITLE, "Manual display title", 100L);
        decisions.putTmdbLink(PROFILE_ID, PROVIDER_ID, STABLE_ID, DETAIL_URL,
                "MOVIE", 991, false, 100L);
        decisions.putTmdbLink(PROFILE_ID, PROVIDER_ID, STABLE_ID, DETAIL_URL,
                "MOVIE", 992, true, 200L);
        LocalLibraryStore.SavedTitle title = new LocalLibraryStore.SavedTitle(STABLE_ID,
                PROVIDER_ID, "Saved Library title", "MOVIE", DETAIL_URL,
                "https://provider.invalid/poster/p5.jpg", "2020", "Saved synopsis", "Saved creator");
        library.saveTitle(PROFILE_ID, title);
        assertTrue("playback progress fixture must persist", library.recordPlaybackProgress(
                PROFILE_ID, title, 125_000L, 600_000L, "episode-5", "Episode five", 2, 5));
        assertTrue("favorite fixture must persist", library.setFavorite(PROFILE_ID, STABLE_ID, true));
        assertTrue("watch status fixture must persist", library.setWatchStatus(
                PROFILE_ID, STABLE_ID, LocalLibraryStore.STATUS_WATCHING));
        LocalLibraryStore.Collection collection = library.createCollection(PROFILE_ID,
                "P5 durable fixture collection");
        libraryCollectionId = collection.id;
        assertTrue("collection membership fixture must persist", library.addToCollection(
                PROFILE_ID, libraryCollectionId, STABLE_ID));
    }

    private void assertDurableFixture(OrbitMetadataUserDecisions decisions,
                                      LocalLibraryStore library) {
        assertEquals("durable display correction", "Manual display title",
                decisions.read(PROFILE_ID, PROVIDER_ID, STABLE_ID, DETAIL_URL,
                        OrbitMetadataUserDecisions.Field.TITLE));
        OrbitMetadataUserDecisions.TmdbLink link = decisions.readTmdbLink(PROFILE_ID,
                PROVIDER_ID, STABLE_ID, DETAIL_URL, "MOVIE");
        assertNotNull("the typed user decision must remain present", link);
        assertEquals(992, link.id);
        assertEquals("MOVIE", link.kind);
        assertTrue("the latest explicit TMDB decision is a rejection", link.rejected);
        assertEquals("the accepted link followed by the rejection must advance its revision",
                2L, link.revision);
        LocalLibraryStore.SavedTitle saved = library.getSavedTitle(PROFILE_ID, STABLE_ID);
        assertNotNull("the saved Library row must remain present", saved);
        assertEquals("Saved Library title", saved.title);
        assertEquals("provider source ID must survive", PROVIDER_ID, saved.sourceId);
        assertEquals("media type must survive", "MOVIE", saved.mediaType);
        assertEquals("detail action URL must survive", DETAIL_URL, saved.detailUrl);
        assertEquals("poster URL must survive", "https://provider.invalid/poster/p5.jpg", saved.posterUrl);
        assertEquals("year must survive", "2020", saved.year);
        assertEquals("synopsis must survive", "Saved synopsis", saved.synopsis);
        assertEquals("creator must survive", "Saved creator", saved.creator);
        assertTrue("favorite state must survive", saved.favorite);
        assertEquals(LocalLibraryStore.STATUS_WATCHING, saved.watchStatus);
        assertEquals("saved title playback progress must survive", 125_000L, saved.progressMs);
        assertEquals("saved title duration must survive", 600_000L, saved.durationMs);
        assertEquals("resume episode ID must survive", "episode-5", saved.resumeEpisodeId);
        assertEquals("resume episode title must survive", "Episode five", saved.resumeEpisodeTitle);
        assertEquals(2, saved.resumeSeason);
        assertEquals(5, saved.resumeEpisodeNumber);
        assertNotNull("the one saved collection must survive", libraryCollectionId);
        assertTrue("collection membership must survive", library.isInCollection(
                PROFILE_ID, libraryCollectionId, STABLE_ID));
        List<LocalLibraryStore.SavedTitle> collectionTitles = library.getCollectionTitles(
                PROFILE_ID, libraryCollectionId);
        assertEquals("collection membership title count must survive", 1, collectionTitles.size());
        assertEquals("collection title must survive", "Saved Library title", collectionTitles.get(0).title);
        assertEquals("continue-watching state must survive", STABLE_ID,
                library.getContinueWatching(PROFILE_ID, 10).get(0).stableId);

        JSONObject archive = library.exportArchiveState();
        JSONArray savedTitles = archive.optJSONArray("savedTitles");
        JSONArray playback = archive.optJSONArray("playbackProgress");
        JSONArray collections = archive.optJSONArray("collections");
        JSONArray memberships = archive.optJSONArray("collectionMemberships");
        assertNotNull("backup payload must retain saved Library rows", savedTitles);
        assertNotNull("backup payload must retain playback rows", playback);
        assertNotNull("backup payload must retain collections", collections);
        assertNotNull("backup payload must retain collection memberships", memberships);
        assertEquals(1, savedTitles.length());
        assertEquals(1, playback.length());
        assertEquals(1, collections.length());
        assertEquals(1, memberships.length());
        JSONObject savedBackupRow = savedTitles.optJSONObject(0);
        JSONObject playbackBackupRow = playback.optJSONObject(0);
        assertNotNull(savedBackupRow);
        assertNotNull(playbackBackupRow);
        assertEquals(STABLE_ID, savedBackupRow.optString("stableId"));
        assertEquals(PROVIDER_ID, savedBackupRow.optString("sourceId"));
        assertEquals("MOVIE", savedBackupRow.optString("mediaType"));
        assertEquals(DETAIL_URL, savedBackupRow.optString("detailUrl"));
        assertTrue(savedBackupRow.optBoolean("favorite"));
        assertEquals(LocalLibraryStore.STATUS_WATCHING, savedBackupRow.optString("watchStatus"));
        assertEquals(125_000L, savedBackupRow.optLong("progressMs"));
        assertEquals("episode-5", playbackBackupRow.optString("resumeEpisodeId"));
        assertEquals(2, playbackBackupRow.optInt("resumeSeason"));
        assertEquals(5, playbackBackupRow.optInt("resumeEpisodeNumber"));
        JSONObject collectionBackupRow = collections.optJSONObject(0);
        JSONObject membershipBackupRow = memberships.optJSONObject(0);
        assertNotNull(collectionBackupRow);
        assertNotNull(membershipBackupRow);
        assertEquals(libraryCollectionId, collectionBackupRow.optString("collectionId"));
        assertEquals("P5 durable fixture collection", collectionBackupRow.optString("name"));
        assertEquals(libraryCollectionId, membershipBackupRow.optString("collectionId"));
        assertEquals(STABLE_ID, membershipBackupRow.optString("stableId"));
    }

    private static MediaItem providerItem(String title, String synopsis) {
        return new MediaItem(STABLE_ID, title, MediaKind.MOVIE, DETAIL_URL,
                "", "2020", synopsis);
    }

    private IsolatedDatabaseContext newIsolatedContext(String name) {
        File directory = new File(isolatedRoot, name);
        assertTrue("Could not create isolated database path " + name, directory.mkdirs());
        return new IsolatedDatabaseContext(targetContext, directory);
    }

    private OrbitMetadataCacheDatabase newCache(Context context) {
        OrbitMetadataCacheDatabase helper = new OrbitMetadataCacheDatabase(context);
        openedHelpers.add(helper);
        return helper;
    }

    private OrbitMetadataUserDecisions newDecisions(Context context) {
        OrbitMetadataUserDecisions helper = new OrbitMetadataUserDecisions(context);
        openedHelpers.add(helper);
        return helper;
    }

    private LocalLibraryStore newLibrary(Context context) {
        LocalLibraryStore helper = new LocalLibraryStore(context);
        openedHelpers.add(helper);
        return helper;
    }

    private static void assertTableExists(SQLiteDatabase db, String tableName) {
        try (Cursor cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{tableName})) {
            assertTrue("Expected migrated table " + tableName, cursor.moveToFirst());
        }
    }

    private static long scalarLong(SQLiteDatabase db, String query) {
        try (Cursor cursor = db.rawQuery(query, null)) {
            assertTrue("SQLite scalar query returned no row: " + query, cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private static void assertDatabaseCorrupt(File file) {
        SQLiteDatabase probe = null;
        boolean corruptionDetected = false;
        try {
            probe = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY, new DatabaseErrorHandler() {
                        @Override public void onCorruption(SQLiteDatabase database) {
                            // Keep the fixture intact; production recovery runs after this probe.
                        }
                    });
            try (Cursor ignored = probe.rawQuery("PRAGMA integrity_check", null)) {
                if (ignored.moveToFirst()) ignored.getString(0);
            }
        } catch (SQLiteDatabaseCorruptException expected) {
            corruptionDetected = true;
        } finally {
            if (probe != null) probe.close();
        }
        assertTrue("the corruption fixture must trigger SQLite's corruption classification",
                corruptionDetected);
    }

    private static boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) if (!deleteRecursively(child)) return false;
        }
        return file.delete() || !file.exists();
    }

    /** Redirects SQLiteOpenHelper to the test's private directory, never app databases. */
    private static final class IsolatedDatabaseContext extends ContextWrapper {
        private final File directory;

        IsolatedDatabaseContext(Context base, File directory) {
            super(base);
            this.directory = directory;
        }

        @Override public Context getApplicationContext() { return this; }

        @Override public File getDatabasePath(String name) {
            return new File(directory, name);
        }

        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                CursorFactory factory) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
        }

        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                CursorFactory factory, DatabaseErrorHandler errorHandler) {
            return SQLiteDatabase.openOrCreateDatabase(
                    getDatabasePath(name).getAbsolutePath(), factory, errorHandler);
        }

        @Override public boolean deleteDatabase(String name) {
            File database = getDatabasePath(name);
            String[] suffixes = {"", "-journal", "-shm", "-wal"};
            for (String suffix : suffixes) {
                File candidate = new File(database.getPath() + suffix);
                if (candidate.exists() && !candidate.delete()) return false;
            }
            return true;
        }
    }
}
