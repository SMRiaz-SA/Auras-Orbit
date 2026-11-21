package com.auras.orbit.metadata.store;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.library.LocalLibraryStore;
import com.auras.orbit.metadata.TitleCatalogRecord;
import com.auras.orbit.providers.MediaItem;
import com.auras.orbit.providers.MediaKind;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Two host-selected phases for process-death WAL recovery; does not simulate physical ENOSPC. */
@RunWith(AndroidJUnit4.class)
public final class OrbitMetadataColdProcessRestartInstrumentationTest {
    public static final String FIXTURE_DIRECTORY_NAME = "p5-cold-process-restart";
    public static final String STATE_FILE_NAME = "fixture-state.properties";

    private static final String TAG = "P5ColdRestartTest";
    private static final String PROFILE_ID = "p5-cold-profile";
    private static final String PROVIDER_ID = "p5-cold-provider";
    private static final String STABLE_ID = "p5-cold-title";
    private static final String DETAIL_URL = "https://provider.invalid/title/p5-cold";
    private static final String LIBRARY_DATABASE_NAME = "orbit_local_library.db";
    private static final long CACHE_WRITE_MS = 6_000_000L;
    private static final long CACHE_READ_MS = CACHE_WRITE_MS + 1L;
    private static final long WAL_HEADER_BYTES = 32L;
    private static final long WAL_FRAME_HEADER_BYTES = 24L;
    private static final long MAX_SEED_HOLD_MS = 120_000L;

    // Keep the seed phase's open handles strongly reachable until the host kills this process.
    private static final List<SQLiteOpenHelper> HELD_OPEN_HELPERS = new ArrayList<>();

    private final List<SQLiteOpenHelper> openedHelpers = new ArrayList<>();
    private Context targetContext;
    private File fixtureRoot;
    private boolean preserveOpenHelpersForHostStop;
    private boolean deleteFixtureAfterSuccessfulVerification;

    @Before public void captureTargetContext() {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        fixtureRoot = new File(targetContext.getCacheDir(), FIXTURE_DIRECTORY_NAME);
    }

    @After public void closeOrRetainFixtureHandles() {
        if (preserveOpenHelpersForHostStop) {
            synchronized (HELD_OPEN_HELPERS) {
                HELD_OPEN_HELPERS.addAll(openedHelpers);
            }
            openedHelpers.clear();
            return;
        }

        for (int index = openedHelpers.size() - 1; index >= 0; index--) {
            try { openedHelpers.get(index).close(); } catch (RuntimeException ignored) { }
        }
        openedHelpers.clear();
        if (deleteFixtureAfterSuccessfulVerification && fixtureRoot.exists()) {
            assertTrue("The cold-restart fixture must be removable after verification",
                    deleteRecursively(fixtureRoot));
        }
    }

    /** Run only with p5_cold_phase=seed; the host must stop this live process after SEED_READY. */
    @Test public void seedDirtyWalAndLeaveDatabasesOpenUntilHostStopsProcess() throws Exception {
        requireHostPhase("seed");
        if (fixtureRoot.exists()) {
            assertTrue("A prior isolated P5 cold fixture must be removable before reseeding",
                    deleteRecursively(fixtureRoot));
        }
        assertTrue("Could not create the isolated cold-restart fixture", fixtureRoot.mkdirs());

        IsolatedDatabaseContext context = new IsolatedDatabaseContext(targetContext, fixtureRoot);
        OrbitMetadataCacheDatabase cache = newCache(context);
        OrbitMetadataUserDecisions decisions = newDecisions(context);
        LocalLibraryStore library = newLibrary(context);

        SQLiteDatabase cacheDb = cache.getWritableDatabase();
        SQLiteDatabase decisionsDb = decisions.getWritableDatabase();
        SQLiteDatabase libraryDb = library.getWritableDatabase();
        assertTrue("the metadata cache must use WAL", cacheDb.isWriteAheadLoggingEnabled());
        assertTrue("the durable override store must use WAL", decisionsDb.isWriteAheadLoggingEnabled());
        assertTrue("the personal Library must use WAL", libraryDb.isWriteAheadLoggingEnabled());
        disableAutomaticWalCheckpoint(cacheDb, "metadata cache");
        disableAutomaticWalCheckpoint(decisionsDb, "user decisions");
        disableAutomaticWalCheckpoint(libraryDb, "Library");

        String cacheDatabaseSha256 = sha256(
                context.getDatabasePath(OrbitMetadataCacheDatabase.DATABASE_NAME));
        String decisionsDatabaseSha256 = sha256(
                context.getDatabasePath(OrbitMetadataUserDecisions.DATABASE_NAME));
        String libraryDatabaseSha256 = sha256(context.getDatabasePath(LIBRARY_DATABASE_NAME));

        MediaItem seed = providerSeed();
        MediaItem details = new MediaItem(STABLE_ID, "Cold-restart provider title",
                MediaKind.MOVIE, DETAIL_URL, "", "2026", "Cold-restart safe synopsis");
        cache.saveProviderDisplay(PROFILE_ID, PROVIDER_ID, seed, details, CACHE_WRITE_MS);
        cache.saveCatalogTitle(new TitleCatalogRecord(456, "MOVIE", "Cold-restart catalog title",
                "Cold-restart catalog synopsis", "2025", "", ""), CACHE_WRITE_MS);

        decisions.put(PROFILE_ID, PROVIDER_ID, STABLE_ID, DETAIL_URL,
                OrbitMetadataUserDecisions.Field.TITLE, "Cold-restart manual correction", 300L);
        decisions.putTmdbLink(PROFILE_ID, PROVIDER_ID, STABLE_ID, DETAIL_URL,
                "MOVIE", 780, false, 300L);
        decisions.putTmdbLink(PROFILE_ID, PROVIDER_ID, STABLE_ID, DETAIL_URL,
                "MOVIE", 781, true, 400L);

        LocalLibraryStore.SavedTitle title = librarySeed();
        library.saveTitle(PROFILE_ID, title);
        assertTrue("cold-restart playback progress fixture must persist", library.recordPlaybackProgress(
                PROFILE_ID, title, 86_000L, 510_000L, "cold-episode-2", "Cold episode two", 1, 2));
        assertTrue("cold-restart favorite fixture must persist",
                library.setFavorite(PROFILE_ID, STABLE_ID, true));
        assertTrue("cold-restart watch status fixture must persist", library.setWatchStatus(
                PROFILE_ID, STABLE_ID, LocalLibraryStore.STATUS_WATCHING));
        LocalLibraryStore.Collection collection = library.createCollection(PROFILE_ID,
                "P5 cold-restart collection");
        assertTrue("cold-restart collection membership must persist", library.addToCollection(
                PROFILE_ID, collection.id, STABLE_ID));

        assertEquals("metadata-cache main database must stay unchanged until restart recovery",
                cacheDatabaseSha256,
                sha256(context.getDatabasePath(OrbitMetadataCacheDatabase.DATABASE_NAME)));
        assertEquals("user-decisions main database must stay unchanged until restart recovery",
                decisionsDatabaseSha256,
                sha256(context.getDatabasePath(OrbitMetadataUserDecisions.DATABASE_NAME)));
        assertEquals("Library main database must stay unchanged until restart recovery",
                libraryDatabaseSha256, sha256(context.getDatabasePath(LIBRARY_DATABASE_NAME)));

        long cacheWalBytes = assertUncheckpointedWal(context,
                OrbitMetadataCacheDatabase.DATABASE_NAME, cacheDb.getPageSize());
        long decisionsWalBytes = assertUncheckpointedWal(context,
                OrbitMetadataUserDecisions.DATABASE_NAME, decisionsDb.getPageSize());
        long libraryWalBytes = assertUncheckpointedWal(context,
                LIBRARY_DATABASE_NAME, libraryDb.getPageSize());
        String cacheWalSha256 = sha256(walFile(context,
                OrbitMetadataCacheDatabase.DATABASE_NAME));
        String decisionsWalSha256 = sha256(walFile(context,
                OrbitMetadataUserDecisions.DATABASE_NAME));
        String libraryWalSha256 = sha256(walFile(context, LIBRARY_DATABASE_NAME));
        writeFixtureState(collection.id, cacheDb.getPageSize(), decisionsDb.getPageSize(),
                libraryDb.getPageSize(), cacheWalBytes, decisionsWalBytes, libraryWalBytes,
                cacheDatabaseSha256, decisionsDatabaseSha256, libraryDatabaseSha256,
                cacheWalSha256, decisionsWalSha256, libraryWalSha256);

        synchronized (HELD_OPEN_HELPERS) {
            HELD_OPEN_HELPERS.addAll(openedHelpers);
        }
        openedHelpers.clear();
        preserveOpenHelpersForHostStop = true;
        Log.i(TAG, "SEED_READY openHelpers=3 mainDatabasesUnchanged=true cacheWalBytes=" + cacheWalBytes
                + " decisionsWalBytes=" + decisionsWalBytes + " libraryWalBytes=" + libraryWalBytes
                + " cacheWalSha256=" + cacheWalSha256 + " decisionsWalSha256=" + decisionsWalSha256
                + " libraryWalSha256=" + libraryWalSha256 + " collectionId=" + collection.id);
        awaitHostControlledProcessStop();
    }

    /** Run only with p5_cold_phase=verify after the host proves the old process PID is gone. */
    @Test public void reopensWALAndDurableDataAfterExternalProcessDeath() throws Exception {
        requireHostPhase("verify");
        assertTrue("The host-seeded cold-restart fixture must exist", fixtureRoot.isDirectory());
        Properties state = readFixtureState();
        String collectionId = state.getProperty("collectionId", "");
        assertTrue("The collection ID must be recorded before process death", !collectionId.isEmpty());

        // Inspect the uncheckpointed WAL files before opening any helper in the new process.
        assertWalSurvivedProcessStop(contextDatabasePath(
                OrbitMetadataCacheDatabase.DATABASE_NAME),
                Long.parseLong(state.getProperty("cacheWalMinimumBytes", "0")),
                state.getProperty("cacheDatabaseSha256", ""),
                state.getProperty("cacheWalSha256", ""));
        assertWalSurvivedProcessStop(contextDatabasePath(
                OrbitMetadataUserDecisions.DATABASE_NAME),
                Long.parseLong(state.getProperty("decisionsWalMinimumBytes", "0")),
                state.getProperty("decisionsDatabaseSha256", ""),
                state.getProperty("decisionsWalSha256", ""));
        assertWalSurvivedProcessStop(contextDatabasePath(LIBRARY_DATABASE_NAME),
                Long.parseLong(state.getProperty("libraryWalMinimumBytes", "0")),
                state.getProperty("libraryDatabaseSha256", ""),
                state.getProperty("libraryWalSha256", ""));

        IsolatedDatabaseContext context = new IsolatedDatabaseContext(targetContext, fixtureRoot);
        OrbitMetadataCacheDatabase cache = newCache(context);
        OrbitMetadataUserDecisions decisions = newDecisions(context);
        LocalLibraryStore library = newLibrary(context);
        assertTrue("the cache must remain WAL-backed after cold restart",
                cache.getWritableDatabase().isWriteAheadLoggingEnabled());
        assertTrue("the durable decisions must remain WAL-backed after cold restart",
                decisions.getWritableDatabase().isWriteAheadLoggingEnabled());
        assertTrue("the Library must remain WAL-backed after cold restart",
                library.getWritableDatabase().isWriteAheadLoggingEnabled());

        OrbitMetadataCacheDatabase.Display display = cache.readProviderDisplay(
                PROFILE_ID, PROVIDER_ID, providerSeed(), CACHE_READ_MS);
        assertNotNull("the provider display snapshot must rehydrate from its uncheckpointed WAL", display);
        assertEquals("Cold-restart provider title", display.title);
        assertEquals("Cold-restart safe synopsis", display.synopsis);
        TitleCatalogRecord catalog = cache.readCatalogTitle(456, "MOVIE", CACHE_READ_MS);
        assertNotNull("the catalog title must rehydrate from its uncheckpointed WAL", catalog);
        assertEquals("Cold-restart catalog title", catalog.title);
        assertEquals("Cold-restart catalog synopsis", catalog.synopsis);

        assertEquals("Cold-restart manual correction", decisions.read(PROFILE_ID, PROVIDER_ID,
                STABLE_ID, DETAIL_URL, OrbitMetadataUserDecisions.Field.TITLE));
        OrbitMetadataUserDecisions.TmdbLink link = decisions.readTmdbLink(PROFILE_ID, PROVIDER_ID,
                STABLE_ID, DETAIL_URL, "MOVIE");
        assertNotNull("the explicit typed link decision must survive process death", link);
        assertEquals(781, link.id);
        assertEquals("MOVIE", link.kind);
        assertTrue(link.rejected);
        assertEquals(2L, link.revision);

        LocalLibraryStore.SavedTitle saved = library.getSavedTitle(PROFILE_ID, STABLE_ID);
        assertNotNull("the saved title must rehydrate after process death", saved);
        assertEquals("Cold-restart Library title", saved.title);
        assertEquals(PROVIDER_ID, saved.sourceId);
        assertEquals("MOVIE", saved.mediaType);
        assertEquals(86_000L, saved.progressMs);
        assertEquals(510_000L, saved.durationMs);
        assertEquals("cold-episode-2", saved.resumeEpisodeId);
        assertEquals("Cold episode two", saved.resumeEpisodeTitle);
        assertEquals(1, saved.resumeSeason);
        assertEquals(2, saved.resumeEpisodeNumber);
        assertTrue(saved.favorite);
        assertEquals(LocalLibraryStore.STATUS_WATCHING, saved.watchStatus);
        assertTrue("the collection membership must survive process death", library.isInCollection(
                PROFILE_ID, collectionId, STABLE_ID));
        List<LocalLibraryStore.SavedTitle> collectionTitles = library.getCollectionTitles(
                PROFILE_ID, collectionId);
        assertEquals("one collection title must survive process death", 1, collectionTitles.size());
        assertEquals("the collection title must survive process death", "Cold-restart Library title",
                collectionTitles.get(0).title);
        assertEquals(STABLE_ID, library.getContinueWatching(PROFILE_ID, 10).get(0).stableId);

        Log.i(TAG, "VERIFY_PASSED cache=1 corrections=1 library=1 progress=1 collection=1 "
                + "mainDatabasesUnchangedBeforeRecovery=true walDigestsMatched=true");
        deleteFixtureAfterSuccessfulVerification = true;
    }

    private void requireHostPhase(String expected) {
        String actual = InstrumentationRegistry.getArguments().getString("p5_cold_phase", "");
        Assume.assumeTrue("The host script must select the explicit p5_cold_phase=" + expected
                + " instrumentation phase", expected.equals(actual));
    }

    private void awaitHostControlledProcessStop() throws InterruptedException {
        long deadline = System.currentTimeMillis() + MAX_SEED_HOLD_MS;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(250L);
        }
        throw new AssertionError("The host did not force-stop the live seed process after SEED_READY");
    }

    private void disableAutomaticWalCheckpoint(SQLiteDatabase database, String label) {
        try (Cursor setting = database.rawQuery("PRAGMA wal_autocheckpoint=0", null)) {
            assertTrue(label + " must apply its WAL autocheckpoint setting", setting.moveToFirst());
        }
        try (Cursor result = database.rawQuery("PRAGMA wal_autocheckpoint", null)) {
            assertTrue(label + " must report its WAL autocheckpoint setting", result.moveToFirst());
            assertEquals(label + " WAL autocheckpoint must be disabled for the host-stop fixture",
                    0, result.getInt(0));
        }
    }

    private long assertUncheckpointedWal(IsolatedDatabaseContext context, String databaseName,
            long pageSize) {
        File wal = walFile(context, databaseName);
        long minimumBytes = WAL_HEADER_BYTES + WAL_FRAME_HEADER_BYTES + pageSize;
        assertTrue("WAL file must exist for " + databaseName, wal.isFile());
        assertTrue("WAL must contain at least one full frame for " + databaseName,
                wal.length() >= minimumBytes);
        return wal.length();
    }

    private void assertWalSurvivedProcessStop(File database, long minimumBytes,
            String expectedDatabaseSha256, String expectedWalSha256) throws Exception {
        File wal = walFile(database);
        assertTrue("The database file must still exist after force-stop: " + database.getName(),
                database.isFile());
        assertTrue("The seed phase must record the main-database digest", !expectedDatabaseSha256.isEmpty());
        assertEquals("The main database must remain unchanged before phase-two WAL recovery",
                expectedDatabaseSha256, sha256(database));
        assertTrue("The uncheckpointed WAL must survive force-stop: " + wal.getName(), wal.isFile());
        assertTrue("The WAL must retain its recorded frame size after force-stop: " + wal.getName(),
                wal.length() >= minimumBytes);
        assertTrue("The seed phase must record the WAL digest", !expectedWalSha256.isEmpty());
        assertEquals("The exact uncheckpointed WAL bytes must survive force-stop: " + wal.getName(),
                expectedWalSha256, sha256(wal));
    }

    private File walFile(IsolatedDatabaseContext context, String databaseName) {
        return walFile(context.getDatabasePath(databaseName));
    }

    private File walFile(File database) {
        return new File(database.getPath() + "-wal");
    }

    private String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError("SHA-256 must be available on Android", unavailable);
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        char[] hex = "0123456789abcdef".toCharArray();
        byte[] hash = digest.digest();
        StringBuilder encoded = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            int unsigned = value & 0xff;
            encoded.append(hex[unsigned >>> 4]).append(hex[unsigned & 0x0f]);
        }
        return encoded.toString();
    }

    private File contextDatabasePath(String databaseName) {
        return new File(fixtureRoot, databaseName);
    }

    private void writeFixtureState(String collectionId, long cachePageSize, long decisionsPageSize,
            long libraryPageSize, long cacheWalBytes, long decisionsWalBytes, long libraryWalBytes,
            String cacheDatabaseSha256, String decisionsDatabaseSha256, String libraryDatabaseSha256,
            String cacheWalSha256, String decisionsWalSha256, String libraryWalSha256) throws IOException {
        Properties state = new Properties();
        state.setProperty("collectionId", collectionId);
        state.setProperty("cachePageSize", Long.toString(cachePageSize));
        state.setProperty("decisionsPageSize", Long.toString(decisionsPageSize));
        state.setProperty("libraryPageSize", Long.toString(libraryPageSize));
        state.setProperty("cacheWalMinimumBytes", Long.toString(cacheWalBytes));
        state.setProperty("decisionsWalMinimumBytes", Long.toString(decisionsWalBytes));
        state.setProperty("libraryWalMinimumBytes", Long.toString(libraryWalBytes));
        state.setProperty("cacheDatabaseSha256", cacheDatabaseSha256);
        state.setProperty("decisionsDatabaseSha256", decisionsDatabaseSha256);
        state.setProperty("libraryDatabaseSha256", libraryDatabaseSha256);
        state.setProperty("cacheWalSha256", cacheWalSha256);
        state.setProperty("decisionsWalSha256", decisionsWalSha256);
        state.setProperty("libraryWalSha256", libraryWalSha256);
        File file = new File(fixtureRoot, STATE_FILE_NAME);
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            state.store(output, "P5 cold-process WAL fixture; test-only isolated cache directory");
            output.flush();
            output.getFD().sync();
        }
    }

    private Properties readFixtureState() throws IOException {
        File file = new File(fixtureRoot, STATE_FILE_NAME);
        assertTrue("The seed phase must write a durable fixture marker", file.isFile());
        Properties state = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            state.load(input);
        }
        return state;
    }

    private MediaItem providerSeed() {
        return new MediaItem(STABLE_ID, "Provider seed title", MediaKind.MOVIE,
                DETAIL_URL, "", "2026", "Seed synopsis");
    }

    private LocalLibraryStore.SavedTitle librarySeed() {
        return new LocalLibraryStore.SavedTitle(STABLE_ID, PROVIDER_ID,
                "Cold-restart Library title", "MOVIE", DETAIL_URL,
                "https://provider.invalid/poster/cold.jpg", "2026", "Cold-restart Library synopsis",
                "Cold-restart creator");
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

    private static boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) if (!deleteRecursively(child)) return false;
        }
        return file.delete() || !file.exists();
    }

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
                SQLiteDatabase.CursorFactory factory) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
        }

        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory,
                android.database.DatabaseErrorHandler errorHandler) {
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
