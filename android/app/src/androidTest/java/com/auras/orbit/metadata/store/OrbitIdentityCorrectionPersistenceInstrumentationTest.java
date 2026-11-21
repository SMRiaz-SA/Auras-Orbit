package com.auras.orbit.metadata.store;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.metadata.IdentityProvenance;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** P6 persistence and additive v3→v4 migration qualification. */
@RunWith(AndroidJUnit4.class)
public final class OrbitIdentityCorrectionPersistenceInstrumentationTest {
    private static final String PROFILE = "p6-profile";
    private static final String PROVIDER_A = "provider-a";
    private static final String PROVIDER_B = "provider-b";
    private static final String PROVIDER_C = "provider-c";

    private final List<SQLiteOpenHelper> opened = new ArrayList<>();
    private Context target;
    private File root;

    @Before public void setUp() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        root = new File(target.getCacheDir(), "p6-identity-" + UUID.randomUUID());
        assertTrue("Could not create the P6 fixture directory", root.mkdirs());
    }

    @After public void tearDown() {
        for (int index = opened.size() - 1; index >= 0; index--) {
            try { opened.get(index).close(); } catch (RuntimeException ignored) { }
        }
        opened.clear();
        if (root != null && root.exists()) assertTrue("P6 fixture cleanup failed", deleteRecursively(root));
    }

    @Test public void v3MigrationPreservesExistingOverridesLinksAndHistory() {
        IsolatedContext context = isolatedContext("v3-upgrade");
        String profileScope = OrbitMetadataCacheDatabase.sourceKey(PROFILE, "", "", "");
        String sourceKey = OrbitMetadataCacheDatabase.sourceKey(PROFILE,
                PROVIDER_A, "v3-title", "https://provider.invalid/v3-title");
        File fixture = context.getDatabasePath(OrbitMetadataUserDecisions.DATABASE_NAME);
        SQLiteDatabase v3 = SQLiteDatabase.openOrCreateDatabase(fixture, null);
        v3.execSQL("CREATE TABLE user_display_override (profile_scope TEXT NOT NULL, source_key TEXT NOT NULL, "
                + "field TEXT NOT NULL, display_value TEXT NOT NULL, revision INTEGER NOT NULL, "
                + "modified_ms INTEGER NOT NULL, PRIMARY KEY(profile_scope,source_key,field))");
        v3.execSQL("CREATE TABLE user_catalog_link (profile_scope TEXT NOT NULL, source_key TEXT NOT NULL, "
                + "catalog TEXT NOT NULL, media_kind TEXT NOT NULL, catalog_id INTEGER NOT NULL, "
                + "decision TEXT NOT NULL, revision INTEGER NOT NULL, modified_ms INTEGER NOT NULL, "
                + "PRIMARY KEY(profile_scope,source_key,catalog,media_kind))");
        v3.execSQL("CREATE TABLE user_decision_history (event_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "profile_scope TEXT NOT NULL, source_key TEXT NOT NULL, field TEXT NOT NULL, "
                + "previous_value TEXT NOT NULL, next_value TEXT NOT NULL, operation TEXT NOT NULL, "
                + "modified_ms INTEGER NOT NULL)");
        ContentValues override = new ContentValues();
        override.put("profile_scope", profileScope);
        override.put("source_key", sourceKey);
        override.put("field", "TITLE");
        override.put("display_value", "v3 user title");
        override.put("revision", 4L);
        override.put("modified_ms", 800L);
        v3.insertOrThrow("user_display_override", null, override);
        ContentValues link = new ContentValues();
        link.put("profile_scope", profileScope);
        link.put("source_key", sourceKey);
        link.put("catalog", "TMDB");
        link.put("media_kind", "MOVIE");
        link.put("catalog_id", 603);
        link.put("decision", "LINK");
        link.put("revision", 2L);
        link.put("modified_ms", 900L);
        v3.insertOrThrow("user_catalog_link", null, link);
        ContentValues history = new ContentValues();
        history.put("profile_scope", profileScope);
        history.put("source_key", sourceKey);
        history.put("field", "TMDB_MOVIE");
        history.put("previous_value", "");
        history.put("next_value", "603:LINK");
        history.put("operation", "SET");
        history.put("modified_ms", 900L);
        v3.insertOrThrow("user_decision_history", null, history);
        v3.setVersion(3);
        v3.close();

        OrbitMetadataUserDecisions decisions = newDecisions(context);
        SQLiteDatabase upgraded = decisions.getWritableDatabase();
        assertEquals(4, upgraded.getVersion());
        assertEquals("v3 user title", decisions.read(PROFILE, PROVIDER_A, "v3-title",
                "https://provider.invalid/v3-title", OrbitMetadataUserDecisions.Field.TITLE));
        OrbitMetadataUserDecisions.TmdbLink read = decisions.readTmdbLink(PROFILE, PROVIDER_A,
                "v3-title", "https://provider.invalid/v3-title", "MOVIE");
        assertNotNull(read);
        assertEquals(603, read.id);
        assertEquals(1L, scalarLong(upgraded,
                "SELECT COUNT(*) FROM user_decision_history WHERE profile_scope='" + profileScope + "'"));
        assertTableExists(upgraded, "user_identity_assignment");
        assertTableExists(upgraded, "identity_correction_history");
    }

    @Test public void linkMergeSplitUnlinkUndoAndAuditSurviveHelperReopen() {
        IsolatedContext context = isolatedContext("correction-reopen");
        OrbitMetadataUserDecisions decisions = newDecisions(context);
        OrbitMetadataUserDecisions.IdentitySource a = source(PROVIDER_A, "movie-a", IdentityProvenance.Kind.MOVIE);
        OrbitMetadataUserDecisions.IdentitySource b = source(PROVIDER_B, "movie-b", IdentityProvenance.Kind.MOVIE);
        OrbitMetadataUserDecisions.IdentitySource c = source(PROVIDER_C, "movie-c", IdentityProvenance.Kind.MOVIE);

        String linked = decisions.linkIdentitySources(PROFILE, a, b, 1_000L);
        assertEquals(linked, decisions.readIdentityAssignment(PROFILE, a).identityId);
        assertEquals(linked, decisions.readIdentityAssignment(PROFILE, b).identityId);
        decisions.close();
        opened.remove(decisions);

        decisions = newDecisions(context);
        assertEquals(linked, decisions.readIdentityAssignment(PROFILE, a).identityId);
        assertEquals(OrbitMetadataUserDecisions.IdentityCorrectionOperation.LINK,
                decisions.readIdentityCorrectionHistory(PROFILE, a).get(0).operation);

        String merged = decisions.mergeIdentitySources(PROFILE, Arrays.asList(a, b, c), 2_000L);
        assertEquals(merged, decisions.readIdentityAssignment(PROFILE, a).identityId);
        assertEquals(merged, decisions.readIdentityAssignment(PROFILE, b).identityId);
        assertEquals(merged, decisions.readIdentityAssignment(PROFILE, c).identityId);
        assertTrue(decisions.splitIdentitySource(PROFILE, c, 3_000L));
        String cSplit = decisions.readIdentityAssignment(PROFILE, c).identityId;
        assertFalse(merged.equals(cSplit));
        assertEquals(OrbitMetadataUserDecisions.IdentityCorrectionOperation.SPLIT,
                decisions.readIdentityCorrectionHistory(PROFILE, c).get(0).operation);

        assertTrue(decisions.unlinkIdentitySource(PROFILE, b, 4_000L));
        String bUnlinked = decisions.readIdentityAssignment(PROFILE, b).identityId;
        assertFalse(merged.equals(bUnlinked));
        assertEquals(OrbitMetadataUserDecisions.IdentityCorrectionOperation.UNLINK,
                decisions.readIdentityCorrectionHistory(PROFILE, b).get(0).operation);
        assertTrue(decisions.undoLastIdentityCorrection(PROFILE, b, 5_000L));
        assertEquals(merged, decisions.readIdentityAssignment(PROFILE, b).identityId);
        assertEquals(OrbitMetadataUserDecisions.IdentityCorrectionOperation.UNDO,
                decisions.readIdentityCorrectionHistory(PROFILE, b).get(0).operation);

        decisions.putTmdbLink(PROFILE, PROVIDER_A, "movie-a", "", "MOVIE", 603, false, 6_000L);
        decisions.clearTmdbLink(PROFILE, PROVIDER_A, "movie-a", "", "MOVIE");
        assertNull(decisions.readTmdbLink(PROFILE, PROVIDER_A, "movie-a", "", "MOVIE"));
        assertTrue(decisions.undoLastTmdbLinkChange(PROFILE, PROVIDER_A,
                "movie-a", "", "MOVIE", 7_000L));
        assertEquals(603, decisions.readTmdbLink(PROFILE, PROVIDER_A,
                "movie-a", "", "MOVIE").id);

        decisions.close();
        opened.remove(decisions);
        decisions = newDecisions(context);
        assertEquals(merged, decisions.readIdentityAssignment(PROFILE, a).identityId);
        assertEquals(merged, decisions.readIdentityAssignment(PROFILE, b).identityId);
        assertEquals(cSplit, decisions.readIdentityAssignment(PROFILE, c).identityId);
        assertEquals(603, decisions.readTmdbLink(PROFILE, PROVIDER_A,
                "movie-a", "", "MOVIE").id);
        String profileScope = OrbitMetadataCacheDatabase.sourceKey(PROFILE, "", "", "");
        String tmdbSourceKey = OrbitMetadataCacheDatabase.sourceKey(PROFILE,
                PROVIDER_A, "movie-a", "");
        try (Cursor row = decisions.getReadableDatabase().rawQuery(
                "SELECT operation FROM user_decision_history WHERE profile_scope=? "
                        + "AND source_key=? AND field='TMDB_MOVIE' ORDER BY event_id DESC LIMIT 1",
                new String[]{profileScope, tmdbSourceKey})) {
            assertTrue(row.moveToFirst());
            assertEquals("UNDO", row.getString(0));
        }
        List<OrbitMetadataUserDecisions.IdentityCorrectionEvent> history =
                decisions.readIdentityCorrectionHistory(PROFILE, b);
        assertEquals(OrbitMetadataUserDecisions.IdentityCorrectionOperation.UNDO, history.get(0).operation);
        assertEquals(OrbitMetadataUserDecisions.IdentityCorrectionOperation.UNLINK, history.get(1).operation);
        assertEquals(OrbitMetadataUserDecisions.IdentityCorrectionOperation.MERGE, history.get(2).operation);
        assertEquals(OrbitMetadataUserDecisions.IdentityCorrectionOperation.LINK, history.get(3).operation);
        assertTrue(history.get(0).eventId > history.get(1).eventId);
        String bSourceKey = OrbitMetadataCacheDatabase.sourceKey(PROFILE, PROVIDER_B,
                "movie-b", "https://provider.invalid/movie-b");
        try (Cursor row = decisions.getReadableDatabase().rawQuery(
                "SELECT source_key FROM identity_correction_history "
                        + "WHERE profile_scope=? AND source_key=? LIMIT 1",
                new String[]{profileScope, bSourceKey})) {
            assertTrue(row.moveToFirst());
            assertEquals(64, row.getString(0).length());
            assertNotEquals(PROVIDER_B, row.getString(0));
            assertNotEquals("movie-b", row.getString(0));
        }
    }

    @Test public void ignoredAssignmentInsertRollsBackEarlierRowsAndCorrectionHistory() {
        IsolatedContext context = isolatedContext("ignored-insert-rollback");
        OrbitMetadataUserDecisions decisions = newDecisions(context);
        SQLiteDatabase db = decisions.getWritableDatabase();
        OrbitMetadataUserDecisions.IdentitySource a = source(
                PROVIDER_A, "movie-a", IdentityProvenance.Kind.MOVIE);
        OrbitMetadataUserDecisions.IdentitySource b = source(
                PROVIDER_B, "movie-b", IdentityProvenance.Kind.MOVIE);
        String profileScope = OrbitMetadataCacheDatabase.sourceKey(PROFILE, "", "", "");
        String bSourceKey = OrbitMetadataCacheDatabase.sourceKey(PROFILE, PROVIDER_B,
                "movie-b", "https://provider.invalid/movie-b");
        db.execSQL("CREATE TRIGGER reject_p6_second_assignment BEFORE INSERT ON user_identity_assignment "
                + "WHEN NEW.source_key='" + bSourceKey + "' BEGIN SELECT RAISE(IGNORE); END");

        try {
            assertThrows(IllegalStateException.class, () -> decisions.mergeIdentitySources(
                    PROFILE, Arrays.asList(a, b), 8_000L));
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS reject_p6_second_assignment");
        }

        assertNull(decisions.readIdentityAssignment(PROFILE, a));
        assertNull(decisions.readIdentityAssignment(PROFILE, b));
        assertEquals(0L, scalarLong(db,
                "SELECT COUNT(*) FROM user_identity_assignment WHERE profile_scope='" + profileScope + "'"));
        assertEquals(0L, scalarLong(db,
                "SELECT COUNT(*) FROM identity_correction_history WHERE profile_scope='" + profileScope + "'"));
    }

    private OrbitMetadataUserDecisions.IdentitySource source(String provider, String stable,
            IdentityProvenance.Kind kind) {
        return new OrbitMetadataUserDecisions.IdentitySource(provider, stable,
                "https://provider.invalid/" + stable, kind);
    }

    private IsolatedContext isolatedContext(String name) {
        File directory = new File(root, name);
        assertTrue(directory.mkdirs());
        return new IsolatedContext(target, directory);
    }

    private OrbitMetadataUserDecisions newDecisions(Context context) {
        OrbitMetadataUserDecisions helper = new OrbitMetadataUserDecisions(context);
        opened.add(helper);
        return helper;
    }

    private static long scalarLong(SQLiteDatabase db, String query) {
        try (Cursor cursor = db.rawQuery(query, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private static void assertTableExists(SQLiteDatabase db, String name) {
        try (Cursor cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{name})) {
            assertTrue(cursor.moveToFirst());
        }
    }

    private static boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) if (!deleteRecursively(child)) return false;
        return file.delete() || !file.exists();
    }

    private static final class IsolatedContext extends ContextWrapper {
        private final File directory;
        IsolatedContext(Context base, File directory) {
            super(base);
            this.directory = directory;
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getDatabasePath(String name) { return new File(directory, name); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory, android.database.DatabaseErrorHandler handler) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getAbsolutePath(), factory, handler);
        }
        @Override public boolean deleteDatabase(String name) {
            File file = getDatabasePath(name);
            return !file.exists() || deleteRecursively(file);
        }
    }
}
