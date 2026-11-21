package com.auras.orbit.library;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import com.auras.orbit.profile.LocalProfileStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.net.URI;
import java.net.URISyntaxException;

/** Profile-scoped local lists, resume state, and collections for the Orbit shell. */
public final class LocalLibraryStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "orbit_local_library.db";
    private static final int DATABASE_VERSION = 6;

    private static final String TABLE_TITLES = "saved_titles";
    private static final String TABLE_PLAYBACK = "playback_progress";
    private static final String TABLE_COLLECTIONS = "collections";
    private static final String TABLE_MEMBERSHIPS = "collection_memberships";
    private static final String TABLE_EPISODE_WATCH_STATES = "episode_watch_states";
    private static final String TABLE_ARCHIVE_IMPORT_COMMITS = "archive_import_commits";
    private static final String PROVIDER_LIBRARY_ARTWORK_REFERENCE_PREFIX =
            "orbit-library-artwork:";
    private static final String[] ROW_METADATA_TIE_FIELDS = {
            "source_id", "title", "creator", "media_type", "detail_url", "poster_url", "year", "synopsis"
    };
    private static final String[] ROW_RESUME_TEXT_TIE_FIELDS = {
            "resume_episode_id", "resume_episode_title"
    };

    public static final String STATUS_NONE = "NONE";
    public static final String STATUS_WATCHING = "WATCHING";
    public static final String STATUS_PLAN_TO_WATCH = "PLAN_TO_WATCH";
    public static final String STATUS_ON_HOLD = "ON_HOLD";
    public static final String STATUS_DROPPED = "DROPPED";
    public static final String STATUS_COMPLETED = "COMPLETED";

    public LocalLibraryStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public synchronized SQLiteDatabase getReadableDatabase() {
        LocalProfileStore.requireArchiveRecoveryAvailable();
        return super.getReadableDatabase();
    }

    @Override
    public synchronized SQLiteDatabase getWritableDatabase() {
        LocalProfileStore.requireArchiveRecoveryAvailable();
        return super.getWritableDatabase();
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_TITLES + " ("
                + "profile_id TEXT NOT NULL,"
                + "stable_id TEXT NOT NULL,"
                + "source_id TEXT NOT NULL DEFAULT '',"
                + "title TEXT NOT NULL,"
                + "creator TEXT NOT NULL DEFAULT '',"
                + "media_type TEXT NOT NULL DEFAULT 'UNKNOWN',"
                + "detail_url TEXT NOT NULL DEFAULT '',"
                + "poster_url TEXT NOT NULL DEFAULT '',"
                + "year TEXT NOT NULL DEFAULT '',"
                + "synopsis TEXT NOT NULL DEFAULT '',"
                + "saved_at INTEGER NOT NULL,"
                + "favorite INTEGER NOT NULL DEFAULT 0,"
                + "watch_status TEXT NOT NULL DEFAULT 'NONE',"
                + "progress_ms INTEGER NOT NULL DEFAULT 0,"
                + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                + "resume_episode_id TEXT NOT NULL DEFAULT '',"
                + "resume_episode_title TEXT NOT NULL DEFAULT '',"
                + "resume_artwork_ref TEXT NOT NULL DEFAULT '',"
                + "resume_season INTEGER NOT NULL DEFAULT -1,"
                + "resume_episode_number INTEGER NOT NULL DEFAULT -1,"
                + "updated_at INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY(profile_id, stable_id))");
        db.execSQL("CREATE INDEX saved_titles_profile_saved ON " + TABLE_TITLES
                + " (profile_id, saved_at DESC)");
        db.execSQL("CREATE INDEX saved_titles_profile_resume ON " + TABLE_TITLES
                + " (profile_id, updated_at DESC)");
        createPlaybackTable(db);
        db.execSQL("CREATE TABLE " + TABLE_COLLECTIONS + " ("
                + "profile_id TEXT NOT NULL,"
                + "collection_id TEXT NOT NULL,"
                + "name TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "PRIMARY KEY(profile_id, collection_id))");
        db.execSQL("CREATE TABLE " + TABLE_MEMBERSHIPS + " ("
                + "profile_id TEXT NOT NULL,"
                + "collection_id TEXT NOT NULL,"
                + "stable_id TEXT NOT NULL,"
                + "added_at INTEGER NOT NULL,"
                + "PRIMARY KEY(profile_id, collection_id, stable_id),"
                + "FOREIGN KEY(profile_id, collection_id) REFERENCES " + TABLE_COLLECTIONS
                + " (profile_id, collection_id) ON DELETE CASCADE,"
                + "FOREIGN KEY(profile_id, stable_id) REFERENCES " + TABLE_TITLES
                + " (profile_id, stable_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX collection_membership_order ON " + TABLE_MEMBERSHIPS
                + " (profile_id, collection_id, added_at DESC)");
        createEpisodeWatchStateTable(db);
        createArchiveImportCommitTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createPlaybackTable(db);
            db.execSQL("INSERT OR IGNORE INTO " + TABLE_PLAYBACK + " (profile_id, stable_id, source_id, title,"
                    + " media_type, detail_url, poster_url, year, synopsis, saved_at, favorite, watch_status,"
                    + " progress_ms, duration_ms, resume_episode_id, resume_episode_title, resume_season,"
                    + " resume_episode_number, updated_at) SELECT profile_id, stable_id, source_id, title,"
                    + " media_type, detail_url, poster_url, year, synopsis, saved_at, favorite, watch_status,"
                    + " progress_ms, duration_ms, resume_episode_id, resume_episode_title, resume_season,"
                    + " resume_episode_number, updated_at FROM " + TABLE_TITLES + " WHERE progress_ms>0");
        }
        if (oldVersion < 3) createArchiveImportCommitTable(db);
        if (oldVersion < 4) {
            addTextColumnIfMissing(db, TABLE_TITLES, "creator");
            addTextColumnIfMissing(db, TABLE_PLAYBACK, "creator");
        }
        if (oldVersion < 5) {
            addTextColumnIfMissing(db, TABLE_TITLES, "resume_artwork_ref");
            addTextColumnIfMissing(db, TABLE_PLAYBACK, "resume_artwork_ref");
        }
        if (oldVersion < 6) createEpisodeWatchStateTable(db);
    }

    private static void createEpisodeWatchStateTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_EPISODE_WATCH_STATES + " ("
                + "profile_id TEXT NOT NULL,"
                + "title_stable_id TEXT NOT NULL,"
                + "episode_stable_id TEXT NOT NULL,"
                + "watched INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY(profile_id, title_stable_id, episode_stable_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS episode_watch_profile_title ON "
                + TABLE_EPISODE_WATCH_STATES
                + " (profile_id, title_stable_id, watched)");
    }

    private static void createArchiveImportCommitTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_ARCHIVE_IMPORT_COMMITS + " ("
                + "operation_id TEXT NOT NULL PRIMARY KEY)");
    }

    private static void addTextColumnIfMissing(SQLiteDatabase db, String table, String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(cursor.getColumnIndexOrThrow("name")))) return;
            }
        }
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " TEXT NOT NULL DEFAULT ''");
    }

    private static void createPlaybackTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PLAYBACK + " ("
                + "profile_id TEXT NOT NULL,"
                + "stable_id TEXT NOT NULL,"
                + "source_id TEXT NOT NULL DEFAULT '',"
                + "title TEXT NOT NULL,"
                + "creator TEXT NOT NULL DEFAULT '',"
                + "media_type TEXT NOT NULL DEFAULT 'UNKNOWN',"
                + "detail_url TEXT NOT NULL DEFAULT '',"
                + "poster_url TEXT NOT NULL DEFAULT '',"
                + "year TEXT NOT NULL DEFAULT '',"
                + "synopsis TEXT NOT NULL DEFAULT '',"
                + "saved_at INTEGER NOT NULL DEFAULT 0,"
                + "favorite INTEGER NOT NULL DEFAULT 0,"
                + "watch_status TEXT NOT NULL DEFAULT 'NONE',"
                + "progress_ms INTEGER NOT NULL DEFAULT 0,"
                + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                + "resume_episode_id TEXT NOT NULL DEFAULT '',"
                + "resume_episode_title TEXT NOT NULL DEFAULT '',"
                + "resume_artwork_ref TEXT NOT NULL DEFAULT '',"
                + "resume_season INTEGER NOT NULL DEFAULT -1,"
                + "resume_episode_number INTEGER NOT NULL DEFAULT -1,"
                + "updated_at INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY(profile_id, stable_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS playback_profile_updated ON " + TABLE_PLAYBACK
                + " (profile_id, updated_at DESC)");
    }

    /** Adds a title or refreshes its provider metadata without clearing user state. */
    public synchronized void saveTitle(String profileId, SavedTitle title) {
        requireId(profileId, "profile id");
        if (title == null) throw new IllegalArgumentException("title is required");
        requireId(title.stableId, "stable title id");
        String cleanTitle = clean(title.title);
        if (cleanTitle.isEmpty()) throw new IllegalArgumentException("title name is required");

        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues initial = new ContentValues();
            initial.put("profile_id", profileId);
            initial.put("stable_id", title.stableId);
            initial.put("source_id", clean(title.sourceId));
            initial.put("title", cleanTitle);
            String creator = cleanCreator(title.creator);
            if (!creator.isEmpty()) initial.put("creator", creator);
            initial.put("media_type", clean(title.mediaType).isEmpty() ? "UNKNOWN" : clean(title.mediaType));
            initial.put("detail_url", clean(title.detailUrl));
            initial.put("poster_url", clean(title.posterUrl));
            initial.put("year", clean(title.year));
            initial.put("synopsis", clean(title.synopsis));
            initial.put("saved_at", now);
            db.insertWithOnConflict(TABLE_TITLES, null, initial, SQLiteDatabase.CONFLICT_IGNORE);

            ContentValues metadata = new ContentValues();
            metadata.put("source_id", clean(title.sourceId));
            metadata.put("title", cleanTitle);
            if (!creator.isEmpty()) metadata.put("creator", creator);
            metadata.put("media_type", clean(title.mediaType).isEmpty() ? "UNKNOWN" : clean(title.mediaType));
            metadata.put("detail_url", clean(title.detailUrl));
            metadata.put("poster_url", clean(title.posterUrl));
            metadata.put("year", clean(title.year));
            metadata.put("synopsis", clean(title.synopsis));
            db.update(TABLE_TITLES, metadata, "profile_id=? AND stable_id=?",
                    new String[]{profileId, title.stableId});
            copyProgressToSavedTitle(db, profileId, title.stableId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<SavedTitle> getSavedTitles(String profileId) {
        return queryTitles(profileId, "", null, "saved_at DESC, title COLLATE NOCASE ASC", null);
    }

    /** Looks up one saved title without loading the profile's entire personal library. */
    public synchronized SavedTitle getSavedTitle(String profileId, String stableId) {
        if (isBlank(profileId) || isBlank(stableId)) return null;
        List<SavedTitle> titles = queryTitles(profileId, "stable_id=?", new String[]{stableId}, null, "1");
        return titles.isEmpty() ? null : titles.get(0);
    }

    public synchronized List<SavedTitle> getFavorites(String profileId) {
        return queryTitles(profileId, "favorite=1", null, "saved_at DESC, title COLLATE NOCASE ASC", null);
    }

    public synchronized List<SavedTitle> getTitlesForStatus(String profileId, String status) {
        String cleanStatus = normalizeStatus(status);
        if (STATUS_NONE.equals(cleanStatus)) return Collections.emptyList();
        return queryTitles(profileId, "watch_status=?", new String[]{cleanStatus},
                "updated_at DESC, saved_at DESC", null);
    }

    public synchronized List<SavedTitle> getRecentlySaved(String profileId, int limit) {
        if (limit <= 0) return Collections.emptyList();
        return queryTitles(profileId, "", null, "saved_at DESC", Integer.toString(limit));
    }

    public synchronized List<SavedTitle> getContinueWatching(String profileId, int limit) {
        if (limit <= 0) return Collections.emptyList();
        if (isBlank(profileId)) return Collections.emptyList();
        String sql = "SELECT p.* FROM " + TABLE_PLAYBACK + " p LEFT JOIN " + TABLE_TITLES
                + " s ON p.profile_id=s.profile_id AND p.stable_id=s.stable_id"
                + " WHERE p.profile_id=? AND p.progress_ms>0"
                + " AND (p.duration_ms<=0 OR p.progress_ms<p.duration_ms)"
                + " AND COALESCE(s.watch_status, p.watch_status)<>?"
                + " ORDER BY p.updated_at DESC LIMIT ?";
        ArrayList<SavedTitle> titles = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(sql,
                new String[]{profileId, STATUS_COMPLETED, Integer.toString(limit)})) {
            while (cursor.moveToNext()) titles.add(readTitle(cursor));
        }
        return Collections.unmodifiableList(titles);
    }

    /** Checks one title's Continue Watching state without scanning or limiting the profile rail. */
    public synchronized boolean hasContinueWatching(String profileId, String stableId) {
        if (isBlank(profileId) || isBlank(stableId)) return false;
        String sql = "SELECT 1 FROM " + TABLE_PLAYBACK + " p LEFT JOIN " + TABLE_TITLES
                + " s ON p.profile_id=s.profile_id AND p.stable_id=s.stable_id"
                + " WHERE p.profile_id=? AND p.stable_id=? AND p.progress_ms>0"
                + " AND (p.duration_ms<=0 OR p.progress_ms<p.duration_ms)"
                + " AND COALESCE(s.watch_status, p.watch_status)<>? LIMIT 1";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql,
                new String[]{profileId, stableId, STATUS_COMPLETED})) {
            return cursor.moveToFirst();
        }
    }

    public synchronized boolean setFavorite(String profileId, String stableId, boolean favorite) {
        return updateTitleState(profileId, stableId, "favorite", favorite ? 1 : 0);
    }

    /** Favorites are independent from the title's mutually exclusive watch status. */
    public synchronized boolean setWatchStatus(String profileId, String stableId, String status) {
        if (isBlank(profileId) || isBlank(stableId)) return false;
        String normalized = normalizeStatus(status);
        ContentValues values = new ContentValues();
        values.put("watch_status", normalized);
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().update(TABLE_TITLES, values, "profile_id=? AND stable_id=?",
                new String[]{profileId, stableId}) > 0;
    }

    /** Returns whether this episode is marked watched in the given profile and series. */
    public synchronized boolean isEpisodeWatched(String profileId, String titleStableId,
            String episodeStableId) {
        if (isBlank(profileId) || isBlank(titleStableId) || isBlank(episodeStableId)) return false;
        try (Cursor cursor = getReadableDatabase().query(TABLE_EPISODE_WATCH_STATES,
                new String[]{"watched"},
                "profile_id=? AND title_stable_id=? AND episode_stable_id=?",
                new String[]{profileId, titleStableId, episodeStableId}, null, null, null, "1")) {
            return cursor.moveToFirst() && cursor.getInt(0) != 0;
        }
    }

    /** Returns the watched episode IDs for one profile-scoped series. */
    public synchronized Set<String> getWatchedEpisodeIds(String profileId, String titleStableId) {
        if (isBlank(profileId) || isBlank(titleStableId)) return Collections.emptySet();
        HashSet<String> result = new HashSet<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE_EPISODE_WATCH_STATES,
                new String[]{"episode_stable_id"},
                "profile_id=? AND title_stable_id=? AND watched=1",
                new String[]{profileId, titleStableId}, null, null, null)) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return Collections.unmodifiableSet(result);
    }

    public synchronized void setEpisodeWatched(String profileId, String titleStableId,
            String episodeStableId, boolean watched) {
        setEpisodesWatched(profileId, titleStableId,
                Collections.singletonList(episodeStableId), watched);
    }

    /** Applies watched state to several episodes in one profile-scoped transaction. */
    public synchronized void setEpisodesWatched(String profileId, String titleStableId,
            List<String> episodeStableIds, boolean watched) {
        requireId(profileId, "profile id");
        requireId(titleStableId, "stable title id");
        if (episodeStableIds == null || episodeStableIds.isEmpty()) return;
        if (episodeStableIds.size() > 10_000) {
            throw new IllegalArgumentException("Too many episode watch states.");
        }
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            HashSet<String> unique = new HashSet<>();
            for (String episodeStableId : episodeStableIds) {
                if (isBlank(episodeStableId) || !unique.add(episodeStableId)) continue;
                long updatedAt = Math.max(now, episodeWatchUpdatedAt(db, profileId,
                        titleStableId, episodeStableId) + 1L);
                ContentValues values = new ContentValues();
                values.put("profile_id", profileId);
                values.put("title_stable_id", titleStableId);
                values.put("episode_stable_id", episodeStableId);
                values.put("watched", watched ? 1 : 0);
                values.put("updated_at", updatedAt);
                db.insertWithOnConflict(TABLE_EPISODE_WATCH_STATES, null, values,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static long episodeWatchUpdatedAt(SQLiteDatabase db, String profileId,
            String titleStableId, String episodeStableId) {
        try (Cursor cursor = db.query(TABLE_EPISODE_WATCH_STATES,
                new String[]{"updated_at"},
                "profile_id=? AND title_stable_id=? AND episode_stable_id=?",
                new String[]{profileId, titleStableId, episodeStableId}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    public synchronized boolean updateProgress(String profileId, String stableId, long progressMs,
            long durationMs, String episodeId, String episodeTitle, int season, int episodeNumber) {
        return updateProgress(profileId, stableId, progressMs, durationMs, episodeId, episodeTitle,
                season, episodeNumber, null);
    }

    public synchronized boolean updateProgress(String profileId, String stableId, long progressMs,
            long durationMs, String episodeId, String episodeTitle, int season, int episodeNumber,
            String resumeArtworkRef) {
        if (isBlank(profileId) || isBlank(stableId)) return false;
        SavedTitle saved = getSavedTitle(profileId, stableId);
        if (saved != null) {
            return recordPlaybackProgress(profileId, saved, progressMs, durationMs, episodeId,
                    episodeTitle, season, episodeNumber, resumeArtworkRef);
        }
        long boundedProgress = Math.max(0L, progressMs);
        long boundedDuration = Math.max(0L, durationMs);
        if (boundedDuration > 0) boundedProgress = Math.min(boundedProgress, boundedDuration);
        SQLiteDatabase db = getWritableDatabase();
        String normalizedArtworkRef = resumeArtworkRef == null
                ? existingResumeArtworkRef(db, profileId, stableId, episodeId, episodeTitle,
                        season, episodeNumber)
                : safeResumeArtworkReference(resumeArtworkRef);
        ContentValues values = new ContentValues();
        values.put("progress_ms", boundedProgress);
        values.put("duration_ms", boundedDuration);
        values.put("resume_episode_id", clean(episodeId));
        values.put("resume_episode_title", clean(episodeTitle));
        values.put("resume_artwork_ref", normalizedArtworkRef);
        values.put("resume_season", season);
        values.put("resume_episode_number", episodeNumber);
        values.put("updated_at", System.currentTimeMillis());
        return db.update(TABLE_PLAYBACK, values, "profile_id=? AND stable_id=?",
                new String[]{profileId, stableId}) > 0;
    }

    /** Records playback independently of whether the user saved the title to My Lists. */
    public synchronized boolean recordPlaybackProgress(String profileId, SavedTitle title, long progressMs,
            long durationMs, String episodeId, String episodeTitle, int season, int episodeNumber) {
        return recordPlaybackProgress(profileId, title, progressMs, durationMs, episodeId,
                episodeTitle, season, episodeNumber, null);
    }

    public synchronized boolean recordPlaybackProgress(String profileId, SavedTitle title, long progressMs,
            long durationMs, String episodeId, String episodeTitle, int season, int episodeNumber,
            String resumeArtworkRef) {
        requireId(profileId, "profile id");
        if (title == null) throw new IllegalArgumentException("title metadata is required");
        requireId(title.stableId, "stable title id");
        String cleanTitle = clean(title.title);
        if (cleanTitle.isEmpty()) throw new IllegalArgumentException("title name is required");

        long boundedProgress = Math.max(0L, progressMs);
        long boundedDuration = Math.max(0L, durationMs);
        if (boundedDuration > 0) boundedProgress = Math.min(boundedProgress, boundedDuration);
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("profile_id", profileId);
            values.put("stable_id", title.stableId);
            values.put("source_id", clean(title.sourceId));
            values.put("title", cleanTitle);
            String creator = cleanCreator(title.creator);
            if (creator.isEmpty()) {
                ContentValues previous = findRow(db, TABLE_PLAYBACK, "profile_id=? AND stable_id=?",
                        new String[]{profileId, title.stableId});
                creator = previous == null ? "" : clean(previous.getAsString("creator"));
                if (creator.isEmpty()) {
                    ContentValues saved = findRow(db, TABLE_TITLES, "profile_id=? AND stable_id=?",
                            new String[]{profileId, title.stableId});
                    creator = saved == null ? "" : clean(saved.getAsString("creator"));
                }
            }
            values.put("creator", creator);
            values.put("media_type", clean(title.mediaType).isEmpty() ? "UNKNOWN" : clean(title.mediaType));
            values.put("detail_url", clean(title.detailUrl));
            values.put("poster_url", clean(title.posterUrl));
            values.put("year", clean(title.year));
            values.put("synopsis", clean(title.synopsis));
            values.put("saved_at", 0L);
            values.put("favorite", 0);
            values.put("watch_status", STATUS_NONE);
            values.put("progress_ms", boundedProgress);
            values.put("duration_ms", boundedDuration);
            values.put("resume_episode_id", clean(episodeId));
            values.put("resume_episode_title", clean(episodeTitle));
            String normalizedArtworkRef = resumeArtworkRef == null
                    ? existingResumeArtworkRef(db, profileId, title.stableId, episodeId,
                            episodeTitle, season, episodeNumber)
                    : safeResumeArtworkReference(resumeArtworkRef);
            values.put("resume_artwork_ref", normalizedArtworkRef);
            values.put("resume_season", season);
            values.put("resume_episode_number", episodeNumber);
            values.put("updated_at", now);
            db.insertWithOnConflict(TABLE_PLAYBACK, null, values, SQLiteDatabase.CONFLICT_REPLACE);

            ContentValues savedProgress = new ContentValues();
            savedProgress.put("progress_ms", boundedProgress);
            savedProgress.put("duration_ms", boundedDuration);
            savedProgress.put("resume_episode_id", clean(episodeId));
            savedProgress.put("resume_episode_title", clean(episodeTitle));
            savedProgress.put("resume_artwork_ref", normalizedArtworkRef);
            savedProgress.put("resume_season", season);
            savedProgress.put("resume_episode_number", episodeNumber);
            savedProgress.put("updated_at", now);
            db.update(TABLE_TITLES, savedProgress, "profile_id=? AND stable_id=?",
                    new String[]{profileId, title.stableId});
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /** Updates the safe, profile-scoped cached artwork for an existing resume record. */
    public synchronized boolean updateResumeArtworkRef(String profileId, String stableId,
            String resumeArtworkRef) {
        if (isBlank(profileId) || isBlank(stableId)) return false;
        ContentValues values = new ContentValues();
        values.put("resume_artwork_ref", safeResumeArtworkReference(resumeArtworkRef));
        values.put("updated_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        boolean playbackUpdated = db.update(TABLE_PLAYBACK, values,
                "profile_id=? AND stable_id=?", new String[]{profileId, stableId}) > 0;
        boolean titleUpdated = db.update(TABLE_TITLES, values,
                "profile_id=? AND stable_id=?", new String[]{profileId, stableId}) > 0;
        return playbackUpdated || titleUpdated;
    }

    private static String existingResumeArtworkRef(SQLiteDatabase db, String profileId,
            String stableId, String episodeId, String episodeTitle, int season, int episodeNumber) {
        ContentValues previous = findRow(db, TABLE_PLAYBACK, "profile_id=? AND stable_id=?",
                new String[]{profileId, stableId});
        if (previous == null
                || !clean(episodeId).equals(clean(previous.getAsString("resume_episode_id")))
                || !clean(episodeTitle).equals(clean(previous.getAsString("resume_episode_title")))
                || season != valueInt(previous, "resume_season")
                || episodeNumber != valueInt(previous, "resume_episode_number")) return "";
        return safeResumeArtworkReference(previous.getAsString("resume_artwork_ref"));
    }

    private static String safeResumeArtworkReference(String value) {
        if (value == null) return "";
        String cleaned = value.trim();
        if (!cleaned.startsWith(PROVIDER_LIBRARY_ARTWORK_REFERENCE_PREFIX)) return "";
        String encoded = cleaned.substring(PROVIDER_LIBRARY_ARTWORK_REFERENCE_PREFIX.length());
        if (encoded.length() != 129 || encoded.charAt(64) != ':') return "";
        for (int index = 0; index < encoded.length(); index++) {
            if (index == 64) continue;
            char current = encoded.charAt(index);
            if (!((current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f'))) return "";
        }
        return cleaned;
    }

    public synchronized boolean removeSavedTitle(String profileId, String stableId) {
        if (isBlank(profileId) || isBlank(stableId)) return false;
        return getWritableDatabase().delete(TABLE_TITLES, "profile_id=? AND stable_id=?",
                new String[]{profileId, stableId}) > 0;
    }

    public synchronized List<Collection> getCollections(String profileId) {
        if (isBlank(profileId)) return Collections.emptyList();
        ArrayList<Collection> collections = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE_COLLECTIONS,
                new String[]{"collection_id", "name", "created_at", "updated_at"},
                "profile_id=?", new String[]{profileId}, null, null, "name COLLATE NOCASE ASC")) {
            while (cursor.moveToNext()) {
                collections.add(new Collection(profileId, cursor.getString(0), cursor.getString(1),
                        cursor.getLong(2), cursor.getLong(3)));
            }
        }
        return Collections.unmodifiableList(collections);
    }

    public synchronized Collection createCollection(String profileId, String name) {
        requireId(profileId, "profile id");
        String cleanName = clean(name);
        if (cleanName.isEmpty()) throw new IllegalArgumentException("collection name is required");
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        ContentValues values = new ContentValues();
        values.put("profile_id", profileId);
        values.put("collection_id", id);
        values.put("name", cleanName);
        values.put("created_at", now);
        values.put("updated_at", now);
        getWritableDatabase().insertOrThrow(TABLE_COLLECTIONS, null, values);
        return new Collection(profileId, id, cleanName, now, now);
    }

    public synchronized boolean renameCollection(String profileId, String collectionId, String name) {
        if (isBlank(profileId) || isBlank(collectionId)) return false;
        String cleanName = clean(name);
        if (cleanName.isEmpty()) return false;
        ContentValues values = new ContentValues();
        values.put("name", cleanName);
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().update(TABLE_COLLECTIONS, values, "profile_id=? AND collection_id=?",
                new String[]{profileId, collectionId}) > 0;
    }

    /** Deletes the collection and its memberships; the saved titles themselves remain. */
    public synchronized boolean deleteCollection(String profileId, String collectionId) {
        if (isBlank(profileId) || isBlank(collectionId)) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_MEMBERSHIPS, "profile_id=? AND collection_id=?",
                    new String[]{profileId, collectionId});
            boolean removed = db.delete(TABLE_COLLECTIONS, "profile_id=? AND collection_id=?",
                    new String[]{profileId, collectionId}) > 0;
            db.setTransactionSuccessful();
            return removed;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean addToCollection(String profileId, String collectionId, String stableId) {
        if (isBlank(profileId) || isBlank(collectionId) || isBlank(stableId)) return false;
        ContentValues values = new ContentValues();
        values.put("profile_id", profileId);
        values.put("collection_id", collectionId);
        values.put("stable_id", stableId);
        values.put("added_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(TABLE_MEMBERSHIPS, null, values,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public synchronized boolean removeFromCollection(String profileId, String collectionId, String stableId) {
        if (isBlank(profileId) || isBlank(collectionId) || isBlank(stableId)) return false;
        return getWritableDatabase().delete(TABLE_MEMBERSHIPS,
                "profile_id=? AND collection_id=? AND stable_id=?",
                new String[]{profileId, collectionId, stableId}) > 0;
    }

    /** Checks a title's membership in one collection for detail and multi-select UI. */
    public synchronized boolean isInCollection(String profileId, String collectionId, String stableId) {
        if (isBlank(profileId) || isBlank(collectionId) || isBlank(stableId)) return false;
        try (Cursor cursor = getReadableDatabase().query(TABLE_MEMBERSHIPS, new String[]{"stable_id"},
                "profile_id=? AND collection_id=? AND stable_id=?",
                new String[]{profileId, collectionId, stableId}, null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    public synchronized List<SavedTitle> getCollectionTitles(String profileId, String collectionId) {
        if (isBlank(profileId) || isBlank(collectionId)) return Collections.emptyList();
        SQLiteDatabase db = getReadableDatabase();
        ArrayList<SavedTitle> titles = new ArrayList<>();
        String sql = "SELECT t.* FROM " + TABLE_TITLES + " t INNER JOIN " + TABLE_MEMBERSHIPS
                + " m ON t.profile_id=m.profile_id AND t.stable_id=m.stable_id"
                + " WHERE m.profile_id=? AND m.collection_id=? ORDER BY m.added_at DESC";
        try (Cursor cursor = db.rawQuery(sql, new String[]{profileId, collectionId})) {
            while (cursor.moveToNext()) titles.add(readTitle(cursor));
        }
        return Collections.unmodifiableList(titles);
    }

    /** Exports the four personal-library tables used by the encrypted Orbit archive. */
    public synchronized JSONObject exportArchiveState() {
        LocalProfileStore.requireArchiveRecoveryAvailable();
        JSONObject state = new JSONObject();
        try {
            SQLiteDatabase db = getReadableDatabase();
            state.put("savedTitles", exportTitleTable(db, TABLE_TITLES));
            state.put("playbackProgress", exportTitleTable(db, TABLE_PLAYBACK));
            state.put("collections", exportCollections(db));
            state.put("collectionMemberships", exportMemberships(db));
            state.put("episodeWatchStates", exportEpisodeWatchStates(db));
            return state;
        } catch (JSONException error) {
            throw new IllegalStateException("Could not serialize Orbit library data.", error);
        }
    }

    /**
     * Merges previously validated archive rows in one SQLite transaction. The archive
     * service validates profiles and all cross-table references before calling this.
     */
    public synchronized void mergeArchiveState(JSONObject imported, Set<String> profileIds) {
        mergeArchiveState(imported, profileIds, UUID.randomUUID().toString());
    }

    public synchronized void mergeArchiveState(JSONObject imported, Set<String> profileIds,
            String operationId) {
        LocalProfileStore.requireArchiveRecoveryAvailable();
        if (imported == null || profileIds == null || !isCanonicalUuid(operationId)) {
            throw new IllegalArgumentException("Archive library data is missing.");
        }
        JSONArray savedTitles = requiredArray(imported, "savedTitles");
        JSONArray playback = requiredArray(imported, "playbackProgress");
        JSONArray collections = requiredArray(imported, "collections");
        JSONArray memberships = requiredArray(imported, "collectionMemberships");
        JSONArray episodeWatchStates = imported.optJSONArray("episodeWatchStates");
        if (episodeWatchStates == null) {
            if (imported.has("episodeWatchStates")) {
                throw new IllegalArgumentException("Orbit backup contains invalid episode watch data.");
            }
            episodeWatchStates = new JSONArray();
        }
        if (savedTitles.length() > 100_000 || playback.length() > 100_000
                || collections.length() > 100_000 || memberships.length() > 250_000
                || episodeWatchStates.length() > 500_000) {
            throw new IllegalArgumentException("Orbit backup contains too many records.");
        }

        try {
            HashSet<String> titleKeys = new HashSet<>();
            for (int index = 0; index < savedTitles.length(); index++) {
                JSONObject row = requiredObject(savedTitles, index);
                requireProfile(profileIds, row);
                titleKeys.add(key(row.getString("profileId"), row.getString("stableId")));
            }
            HashSet<String> collectionKeys = new HashSet<>();
            for (int index = 0; index < collections.length(); index++) {
                JSONObject row = requiredObject(collections, index);
                requireProfile(profileIds, row);
                collectionKeys.add(key(row.getString("profileId"), row.getString("collectionId")));
            }
            for (int index = 0; index < playback.length(); index++) {
                JSONObject row = requiredObject(playback, index);
                requireProfile(profileIds, row);
            }
            HashSet<String> episodeWatchKeys = new HashSet<>();
            for (int index = 0; index < episodeWatchStates.length(); index++) {
                JSONObject row = requiredObject(episodeWatchStates, index);
                requireProfile(profileIds, row);
                String profileId = row.getString("profileId");
                String titleStableId = row.getString("titleStableId");
                String episodeStableId = row.getString("episodeStableId");
                row.getBoolean("watched");
                if (titleStableId.trim().isEmpty() || titleStableId.length() > 8_192
                        || episodeStableId.trim().isEmpty() || episodeStableId.length() > 4_096
                        || row.getLong("updatedAt") < 0L
                        || !episodeWatchKeys.add(key(profileId, titleStableId)
                                + "\u0000" + episodeStableId)) {
                    throw new IllegalArgumentException("Orbit backup contains invalid episode watch data.");
                }
            }
            for (int index = 0; index < memberships.length(); index++) {
                JSONObject row = requiredObject(memberships, index);
                requireProfile(profileIds, row);
                String profileId = row.getString("profileId");
                if (!collectionKeys.contains(key(profileId, row.getString("collectionId")))
                        || !titleKeys.contains(key(profileId, row.getString("stableId")))) {
                    throw new IllegalArgumentException("Orbit backup contains an unlinked collection item.");
                }
            }
        } catch (JSONException error) {
            throw new IllegalArgumentException("Orbit backup contains an invalid library reference.", error);
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            mergeTitleRows(db, TABLE_TITLES, savedTitles, true);
            mergeTitleRows(db, TABLE_PLAYBACK, playback, false);
            mergeEpisodeWatchStates(db, episodeWatchStates);
            for (int index = 0; index < playback.length(); index++) {
                JSONObject row = requiredObject(playback, index);
                copyNewerProgressToSavedTitle(db, row.getString("profileId"), row.getString("stableId"));
            }
            mergeCollections(db, collections);
            mergeMemberships(db, memberships);
            ContentValues commitMarker = new ContentValues();
            commitMarker.put("operation_id", operationId);
            db.insertOrThrow(TABLE_ARCHIVE_IMPORT_COMMITS, null, commitMarker);
            db.setTransactionSuccessful();
        } catch (JSONException error) {
            throw new IllegalArgumentException("Orbit backup contains an invalid library row.", error);
        } finally {
            db.endTransaction();
        }
    }

    /** Returns whether this operation's rows and transaction marker committed atomically. */
    public synchronized boolean hasArchiveImportCommit(String operationId) {
        if (!isCanonicalUuid(operationId)) return false;
        try (Cursor cursor = getReadableDatabase().query(TABLE_ARCHIVE_IMPORT_COMMITS,
                new String[]{"operation_id"}, "operation_id=?", new String[]{operationId},
                null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    /** Removes one marker after its journal has been durably finalized. */
    public synchronized void removeArchiveImportCommit(String operationId) {
        if (!isCanonicalUuid(operationId)) throw new IllegalArgumentException("Invalid archive operation ID.");
        getWritableDatabase().delete(TABLE_ARCHIVE_IMPORT_COMMITS, "operation_id=?",
                new String[]{operationId});
    }

    /** Removes markers left after a completed import whose journal was already cleared. */
    public synchronized void clearArchiveImportCommitMarkers() {
        getWritableDatabase().delete(TABLE_ARCHIVE_IMPORT_COMMITS, null, null);
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null || value.length() > 64) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static JSONArray exportTitleTable(SQLiteDatabase db, String table) throws JSONException {
        JSONArray rows = new JSONArray();
        String order = "profile_id ASC, stable_id ASC";
        try (Cursor cursor = db.query(table, null, null, null, null, null, order)) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                row.put("profileId", cursor.getString(cursor.getColumnIndexOrThrow("profile_id")));
                row.put("stableId", cursor.getString(cursor.getColumnIndexOrThrow("stable_id")));
                row.put("sourceId", cursor.getString(cursor.getColumnIndexOrThrow("source_id")));
                row.put("title", cursor.getString(cursor.getColumnIndexOrThrow("title")));
                row.put("creator", cursor.getString(cursor.getColumnIndexOrThrow("creator")));
                row.put("mediaType", cursor.getString(cursor.getColumnIndexOrThrow("media_type")));
                row.put("detailUrl", safeArchiveUrl(cursor.getString(cursor.getColumnIndexOrThrow("detail_url"))));
                row.put("posterUrl", safeArchiveUrl(
                        cursor.getString(cursor.getColumnIndexOrThrow("poster_url"))));
                row.put("year", cursor.getString(cursor.getColumnIndexOrThrow("year")));
                row.put("synopsis", cursor.getString(cursor.getColumnIndexOrThrow("synopsis")));
                row.put("savedAt", cursor.getLong(cursor.getColumnIndexOrThrow("saved_at")));
                row.put("favorite", cursor.getInt(cursor.getColumnIndexOrThrow("favorite")) != 0);
                row.put("watchStatus", cursor.getString(cursor.getColumnIndexOrThrow("watch_status")));
                row.put("progressMs", cursor.getLong(cursor.getColumnIndexOrThrow("progress_ms")));
                row.put("durationMs", cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")));
                row.put("resumeEpisodeId", cursor.getString(cursor.getColumnIndexOrThrow("resume_episode_id")));
                row.put("resumeEpisodeTitle", cursor.getString(cursor.getColumnIndexOrThrow("resume_episode_title")));
                row.put("resumeSeason", cursor.getInt(cursor.getColumnIndexOrThrow("resume_season")));
                row.put("resumeEpisodeNumber", cursor.getInt(cursor.getColumnIndexOrThrow("resume_episode_number")));
                row.put("updatedAt", cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")));
                rows.put(row);
            }
        }
        return rows;
    }

    private static JSONArray exportCollections(SQLiteDatabase db) throws JSONException {
        JSONArray rows = new JSONArray();
        try (Cursor cursor = db.query(TABLE_COLLECTIONS, null, null, null, null, null,
                "profile_id ASC, collection_id ASC")) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                row.put("profileId", cursor.getString(cursor.getColumnIndexOrThrow("profile_id")));
                row.put("collectionId", cursor.getString(cursor.getColumnIndexOrThrow("collection_id")));
                row.put("name", cursor.getString(cursor.getColumnIndexOrThrow("name")));
                row.put("createdAt", cursor.getLong(cursor.getColumnIndexOrThrow("created_at")));
                row.put("updatedAt", cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")));
                rows.put(row);
            }
        }
        return rows;
    }

    private static JSONArray exportMemberships(SQLiteDatabase db) throws JSONException {
        JSONArray rows = new JSONArray();
        try (Cursor cursor = db.query(TABLE_MEMBERSHIPS, null, null, null, null, null,
                "profile_id ASC, collection_id ASC, stable_id ASC")) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                row.put("profileId", cursor.getString(cursor.getColumnIndexOrThrow("profile_id")));
                row.put("collectionId", cursor.getString(cursor.getColumnIndexOrThrow("collection_id")));
                row.put("stableId", cursor.getString(cursor.getColumnIndexOrThrow("stable_id")));
                row.put("addedAt", cursor.getLong(cursor.getColumnIndexOrThrow("added_at")));
                rows.put(row);
            }
        }
        return rows;
    }

    private static JSONArray exportEpisodeWatchStates(SQLiteDatabase db) throws JSONException {
        JSONArray rows = new JSONArray();
        try (Cursor cursor = db.query(TABLE_EPISODE_WATCH_STATES, null, null, null,
                null, null, "profile_id ASC, title_stable_id ASC, episode_stable_id ASC")) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                row.put("profileId", cursor.getString(cursor.getColumnIndexOrThrow("profile_id")));
                row.put("titleStableId", cursor.getString(cursor.getColumnIndexOrThrow("title_stable_id")));
                row.put("episodeStableId", cursor.getString(cursor.getColumnIndexOrThrow("episode_stable_id")));
                row.put("watched", cursor.getInt(cursor.getColumnIndexOrThrow("watched")) != 0);
                row.put("updatedAt", cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")));
                rows.put(row);
            }
        }
        return rows;
    }

    private static void mergeEpisodeWatchStates(SQLiteDatabase db, JSONArray rows)
            throws JSONException {
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = requiredObject(rows, index);
            String profileId = row.getString("profileId");
            String titleStableId = row.getString("titleStableId");
            String episodeStableId = row.getString("episodeStableId");
            long updatedAt = row.getLong("updatedAt");
            if (updatedAt < episodeWatchUpdatedAt(db, profileId, titleStableId, episodeStableId)) {
                continue;
            }
            ContentValues values = new ContentValues();
            values.put("profile_id", profileId);
            values.put("title_stable_id", titleStableId);
            values.put("episode_stable_id", episodeStableId);
            values.put("watched", row.getBoolean("watched") ? 1 : 0);
            values.put("updated_at", updatedAt);
            db.insertWithOnConflict(TABLE_EPISODE_WATCH_STATES, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private static void mergeTitleRows(SQLiteDatabase db, String table, JSONArray rows, boolean saved)
            throws JSONException {
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = requiredObject(rows, index);
            String profileId = row.getString("profileId");
            String stableId = row.getString("stableId");
            ContentValues incoming = titleValues(row);
            ContentValues current = findRow(db, table, "profile_id=? AND stable_id=?",
                    new String[]{profileId, stableId});
            if (current == null) {
                db.insertOrThrow(table, null, incoming);
                continue;
            }

            long incomingUpdated = valueLong(incoming, "updated_at");
            long currentUpdated = valueLong(current, "updated_at");
            ContentValues merged = new ContentValues();
            merged.put("saved_at", earliestPositive(valueLong(current, "saved_at"),
                    valueLong(incoming, "saved_at")));
            merged.put("favorite", (valueLong(current, "favorite") != 0L
                    || valueLong(incoming, "favorite") != 0L) ? 1 : 0);
            merged.put("updated_at", Math.max(currentUpdated, incomingUpdated));
            if (incomingUpdated > currentUpdated) {
                copy(incoming, merged, "source_id", "title", "media_type", "detail_url",
                        "year", "synopsis");
                copyNonEmpty(incoming, merged, "poster_url");
                copyNonEmpty(incoming, merged, "creator");
                copy(incoming, merged, "progress_ms", "duration_ms", "resume_episode_id",
                        "resume_episode_title", "resume_season", "resume_episode_number");
                mergeResumeArtworkReference(incoming, current, merged);
                if (saved) copy(incoming, merged, "watch_status");
            } else if (incomingUpdated == currentUpdated
                    && compareTitleRows(incoming, current, saved) > 0) {
                copy(incoming, merged, "source_id", "title", "media_type", "detail_url",
                        "year", "synopsis", "progress_ms", "duration_ms",
                        "resume_episode_id", "resume_episode_title", "resume_season",
                        "resume_episode_number");
                copyNonEmpty(incoming, merged, "poster_url");
                mergeResumeArtworkReference(incoming, current, merged);
                copyNonEmpty(incoming, merged, "creator");
                if (saved) copy(incoming, merged, "watch_status");
            }
            db.update(table, merged, "profile_id=? AND stable_id=?", new String[]{profileId, stableId});
        }
    }

    private static void mergeResumeArtworkReference(ContentValues incoming, ContentValues current,
            ContentValues merged) {
        if (sameResumeEpisodeIdentity(incoming, current)) {
            merged.put("resume_artwork_ref", safeResumeArtworkReference(
                    current.getAsString("resume_artwork_ref")));
        } else {
            // Archive rows carry no cache bytes, so changed episode identity invalidates local art.
            merged.put("resume_artwork_ref", "");
        }
    }

    private static boolean sameResumeEpisodeIdentity(ContentValues left, ContentValues right) {
        String leftId = normalizeResumeIdentity(left.getAsString("resume_episode_id"));
        String rightId = normalizeResumeIdentity(right.getAsString("resume_episode_id"));
        if (!leftId.isEmpty() && !rightId.isEmpty()) return leftId.equals(rightId);

        long leftSeason = valueLong(left, "resume_season");
        long rightSeason = valueLong(right, "resume_season");
        long leftNumber = valueLong(left, "resume_episode_number");
        long rightNumber = valueLong(right, "resume_episode_number");
        boolean leftNumbered = leftSeason >= 0L && leftNumber >= 0L;
        boolean rightNumbered = rightSeason >= 0L && rightNumber >= 0L;
        if (leftNumbered && rightNumbered) {
            return leftSeason == rightSeason && leftNumber == rightNumber;
        }

        String leftTitle = normalizeResumeIdentity(left.getAsString("resume_episode_title"));
        String rightTitle = normalizeResumeIdentity(right.getAsString("resume_episode_title"));
        if (leftId.isEmpty() && rightId.isEmpty()
                && !leftTitle.isEmpty() && !rightTitle.isEmpty()) {
            return leftTitle.equalsIgnoreCase(rightTitle);
        }

        // Movies and older rows without episode metadata have no episode tuple to invalidate.
        return leftId.isEmpty() && rightId.isEmpty()
                && !leftNumbered && !rightNumbered && leftTitle.isEmpty() && rightTitle.isEmpty();
    }

    private static String normalizeResumeIdentity(String value) {
        return value == null ? "" : value.trim();
    }

    /** Compares mutable row values in a fixed order; the lexicographically greater row wins ties. */
    private static int compareTitleRows(ContentValues left, ContentValues right, boolean saved) {
        int comparison;
        for (String column : ROW_METADATA_TIE_FIELDS) {
            comparison = compareNullableText(left.getAsString(column), right.getAsString(column));
            if (comparison != 0) return comparison;
        }
        if (saved) {
            comparison = compareNullableText(left.getAsString("watch_status"),
                    right.getAsString("watch_status"));
            if (comparison != 0) return comparison;
        }
        comparison = Long.compare(valueLong(left, "progress_ms"), valueLong(right, "progress_ms"));
        if (comparison != 0) return comparison;
        comparison = Long.compare(valueLong(left, "duration_ms"), valueLong(right, "duration_ms"));
        if (comparison != 0) return comparison;
        for (String column : ROW_RESUME_TEXT_TIE_FIELDS) {
            comparison = compareNullableText(left.getAsString(column), right.getAsString(column));
            if (comparison != 0) return comparison;
        }
        comparison = Long.compare(valueLong(left, "resume_season"), valueLong(right, "resume_season"));
        if (comparison != 0) return comparison;
        return Long.compare(valueLong(left, "resume_episode_number"),
                valueLong(right, "resume_episode_number"));
    }

    private static int compareNullableText(String left, String right) {
        if (left == null) return right == null ? 0 : -1;
        if (right == null) return 1;
        return left.compareTo(right);
    }

    private static void mergeCollections(SQLiteDatabase db, JSONArray rows) throws JSONException {
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = requiredObject(rows, index);
            String profileId = row.getString("profileId");
            String collectionId = row.getString("collectionId");
            ContentValues incoming = new ContentValues();
            incoming.put("profile_id", profileId);
            incoming.put("collection_id", collectionId);
            incoming.put("name", row.getString("name"));
            incoming.put("created_at", row.getLong("createdAt"));
            incoming.put("updated_at", row.getLong("updatedAt"));
            ContentValues current = findRow(db, TABLE_COLLECTIONS,
                    "profile_id=? AND collection_id=?", new String[]{profileId, collectionId});
            if (current == null) {
                db.insertOrThrow(TABLE_COLLECTIONS, null, incoming);
                continue;
            }
            long currentUpdated = valueLong(current, "updated_at");
            long incomingUpdated = valueLong(incoming, "updated_at");
            ContentValues merged = new ContentValues();
            merged.put("created_at", earliestPositive(valueLong(current, "created_at"),
                    valueLong(incoming, "created_at")));
            merged.put("updated_at", Math.max(currentUpdated, incomingUpdated));
            String incomingName = incoming.getAsString("name");
            String currentName = current.getAsString("name");
            if (incomingUpdated > currentUpdated
                    || (incomingUpdated == currentUpdated
                    && compareNullableText(incomingName, currentName) > 0)) {
                copy(incoming, merged, "name");
            }
            db.update(TABLE_COLLECTIONS, merged, "profile_id=? AND collection_id=?",
                    new String[]{profileId, collectionId});
        }
    }

    private static void mergeMemberships(SQLiteDatabase db, JSONArray rows) throws JSONException {
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = requiredObject(rows, index);
            String profileId = row.getString("profileId");
            String collectionId = row.getString("collectionId");
            String stableId = row.getString("stableId");
            ContentValues incoming = new ContentValues();
            incoming.put("profile_id", profileId);
            incoming.put("collection_id", collectionId);
            incoming.put("stable_id", stableId);
            incoming.put("added_at", row.getLong("addedAt"));
            ContentValues current = findRow(db, TABLE_MEMBERSHIPS,
                    "profile_id=? AND collection_id=? AND stable_id=?",
                    new String[]{profileId, collectionId, stableId});
            if (current == null) {
                db.insertOrThrow(TABLE_MEMBERSHIPS, null, incoming);
            } else {
                ContentValues merged = new ContentValues();
                merged.put("added_at", earliestPositive(valueLong(current, "added_at"),
                        valueLong(incoming, "added_at")));
                db.update(TABLE_MEMBERSHIPS, merged,
                        "profile_id=? AND collection_id=? AND stable_id=?",
                        new String[]{profileId, collectionId, stableId});
            }
        }
    }

    private static void copyNewerProgressToSavedTitle(SQLiteDatabase db, String profileId, String stableId) {
        ContentValues playback = findRow(db, TABLE_PLAYBACK, "profile_id=? AND stable_id=?",
                new String[]{profileId, stableId});
        ContentValues saved = findRow(db, TABLE_TITLES, "profile_id=? AND stable_id=?",
                new String[]{profileId, stableId});
        if (playback == null || saved == null
                || valueLong(playback, "updated_at") <= valueLong(saved, "updated_at")) return;
        copyProgressToSavedTitle(db, profileId, stableId);
    }

    private static ContentValues titleValues(JSONObject row) throws JSONException {
        ContentValues values = new ContentValues();
        values.put("profile_id", row.getString("profileId"));
        values.put("stable_id", row.getString("stableId"));
        values.put("source_id", row.getString("sourceId"));
        values.put("title", row.getString("title"));
        values.put("creator", cleanCreator(row.optString("creator", "")));
        values.put("media_type", row.getString("mediaType"));
        values.put("detail_url", row.getString("detailUrl"));
        values.put("poster_url", row.getString("posterUrl"));
        values.put("year", row.getString("year"));
        values.put("synopsis", row.getString("synopsis"));
        values.put("saved_at", row.getLong("savedAt"));
        values.put("favorite", row.getBoolean("favorite") ? 1 : 0);
        values.put("watch_status", row.getString("watchStatus"));
        values.put("progress_ms", row.getLong("progressMs"));
        values.put("duration_ms", row.getLong("durationMs"));
        values.put("resume_episode_id", row.getString("resumeEpisodeId"));
        values.put("resume_episode_title", row.getString("resumeEpisodeTitle"));
        // Artwork bytes are not part of the archive, so imports start without cache references.
        values.put("resume_artwork_ref", "");
        values.put("resume_season", row.getInt("resumeSeason"));
        values.put("resume_episode_number", row.getInt("resumeEpisodeNumber"));
        values.put("updated_at", row.getLong("updatedAt"));
        return values;
    }

    private static ContentValues findRow(SQLiteDatabase db, String table, String selection, String[] args) {
        try (Cursor cursor = db.query(table, null, selection, args, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;
            ContentValues values = new ContentValues();
            for (int index = 0; index < cursor.getColumnCount(); index++) {
                String column = cursor.getColumnName(index);
                switch (cursor.getType(index)) {
                    case Cursor.FIELD_TYPE_NULL: values.putNull(column); break;
                    case Cursor.FIELD_TYPE_INTEGER: values.put(column, cursor.getLong(index)); break;
                    case Cursor.FIELD_TYPE_FLOAT: values.put(column, cursor.getDouble(index)); break;
                    case Cursor.FIELD_TYPE_BLOB: values.put(column, cursor.getBlob(index)); break;
                    default: values.put(column, cursor.getString(index)); break;
                }
            }
            return values;
        }
    }

    private static void copy(ContentValues from, ContentValues to, String... columns) {
        for (String column : columns) {
            Object value = from.get(column);
            if (value instanceof String) to.put(column, (String) value);
            else if (value instanceof Integer) to.put(column, (Integer) value);
            else if (value instanceof Long) to.put(column, (Long) value);
            else if (value instanceof Boolean) to.put(column, (Boolean) value);
            else if (value instanceof byte[]) to.put(column, (byte[]) value);
            else if (value == null) to.putNull(column);
        }
    }

    private static void copyNonEmpty(ContentValues from, ContentValues to, String column) {
        String value = from.getAsString(column);
        if (!isBlank(value)) to.put(column, value);
    }

    private static long valueLong(ContentValues values, String column) {
        Long value = values.getAsLong(column);
        return value == null ? 0L : value;
    }

    private static int valueInt(ContentValues values, String column) {
        Integer value = values.getAsInteger(column);
        return value == null ? -1 : value;
    }

    private static long earliestPositive(long left, long right) {
        if (left <= 0L) return Math.max(0L, right);
        if (right <= 0L) return left;
        return Math.min(left, right);
    }

    private static JSONArray requiredArray(JSONObject object, String key) {
        JSONArray array = object.optJSONArray(key);
        if (array == null) throw new IllegalArgumentException("Orbit backup is missing " + key + ".");
        return array;
    }

    private static JSONObject requiredObject(JSONArray array, int index) {
        JSONObject object = array.optJSONObject(index);
        if (object == null) throw new IllegalArgumentException("Orbit backup contains an invalid record.");
        return object;
    }

    private static void requireProfile(Set<String> profileIds, JSONObject row) throws JSONException {
        String profileId = row.getString("profileId");
        if (!profileIds.contains(profileId)) throw new IllegalArgumentException("Orbit backup references an unknown profile.");
    }

    private static String key(String left, String right) {
        return left + '\u0000' + right;
    }

    /** Drops query/fragment and refuses userinfo or non-web URLs in exported data. */
    private static String safeArchiveUrl(String value) {
        if (isBlank(value)) return "";
        try {
            URI parsed = new URI(value.trim());
            String scheme = parsed.getScheme();
            if (scheme == null || parsed.getHost() == null || parsed.getUserInfo() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return "";
            return new URI(scheme.toLowerCase(java.util.Locale.ROOT) + "://"
                    + parsed.getRawAuthority() + parsed.getRawPath()).toASCIIString();
        } catch (URISyntaxException ignored) {
            return "";
        }
    }

    private boolean updateTitleState(String profileId, String stableId, String column, int value) {
        if (isBlank(profileId) || isBlank(stableId)) return false;
        ContentValues values = new ContentValues();
        values.put(column, value);
        return getWritableDatabase().update(TABLE_TITLES, values, "profile_id=? AND stable_id=?",
                new String[]{profileId, stableId}) > 0;
    }

    private static void copyProgressToSavedTitle(SQLiteDatabase db, String profileId, String stableId) {
        String[] columns = {"progress_ms", "duration_ms", "resume_episode_id", "resume_episode_title",
                "resume_artwork_ref", "resume_season", "resume_episode_number", "updated_at"};
        try (Cursor cursor = db.query(TABLE_PLAYBACK, columns, "profile_id=? AND stable_id=?",
                new String[]{profileId, stableId}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return;
            ContentValues progress = new ContentValues();
            progress.put("progress_ms", cursor.getLong(0));
            progress.put("duration_ms", cursor.getLong(1));
            progress.put("resume_episode_id", cursor.getString(2));
            progress.put("resume_episode_title", cursor.getString(3));
            progress.put("resume_artwork_ref", cursor.getString(4));
            progress.put("resume_season", cursor.getInt(5));
            progress.put("resume_episode_number", cursor.getInt(6));
            progress.put("updated_at", cursor.getLong(7));
            db.update(TABLE_TITLES, progress, "profile_id=? AND stable_id=?",
                    new String[]{profileId, stableId});
        }
    }

    private List<SavedTitle> queryTitles(String profileId, String selection, String[] selectionArgs,
            String orderBy, String limit) {
        if (isBlank(profileId)) return Collections.emptyList();
        String where = "profile_id=?" + (TextUtils.isEmpty(selection) ? "" : " AND (" + selection + ")");
        String[] args;
        if (selectionArgs == null || selectionArgs.length == 0) {
            args = new String[]{profileId};
        } else {
            args = new String[selectionArgs.length + 1];
            args[0] = profileId;
            System.arraycopy(selectionArgs, 0, args, 1, selectionArgs.length);
        }
        ArrayList<SavedTitle> titles = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE_TITLES, null, where, args,
                null, null, orderBy, limit)) {
            while (cursor.moveToNext()) titles.add(readTitle(cursor));
        }
        return Collections.unmodifiableList(titles);
    }

    private static SavedTitle readTitle(Cursor cursor) {
        return new SavedTitle(cursor.getString(cursor.getColumnIndexOrThrow("stable_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("source_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("title")),
                cursor.getString(cursor.getColumnIndexOrThrow("media_type")),
                cursor.getString(cursor.getColumnIndexOrThrow("detail_url")),
                cursor.getString(cursor.getColumnIndexOrThrow("poster_url")),
                cursor.getString(cursor.getColumnIndexOrThrow("year")),
                cursor.getString(cursor.getColumnIndexOrThrow("synopsis")),
                cursor.getString(cursor.getColumnIndexOrThrow("creator")),
                cursor.getLong(cursor.getColumnIndexOrThrow("saved_at")),
                cursor.getInt(cursor.getColumnIndexOrThrow("favorite")) != 0,
                cursor.getString(cursor.getColumnIndexOrThrow("watch_status")),
                cursor.getLong(cursor.getColumnIndexOrThrow("progress_ms")),
                cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")),
                cursor.getString(cursor.getColumnIndexOrThrow("resume_episode_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("resume_episode_title")),
                safeResumeArtworkReference(cursor.getString(
                        cursor.getColumnIndexOrThrow("resume_artwork_ref"))),
                cursor.getInt(cursor.getColumnIndexOrThrow("resume_season")),
                cursor.getInt(cursor.getColumnIndexOrThrow("resume_episode_number")),
                cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")));
    }

    private static String normalizeStatus(String status) {
        if (status == null) return STATUS_NONE;
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        switch (normalized) {
            case STATUS_WATCHING:
            case STATUS_PLAN_TO_WATCH:
            case STATUS_ON_HOLD:
            case STATUS_DROPPED:
            case STATUS_COMPLETED:
                return normalized;
            default:
                return STATUS_NONE;
        }
    }

    private static void requireId(String value, String label) {
        if (isBlank(value)) throw new IllegalArgumentException(label + " is required");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanCreator(String value) {
        String cleaned = clean(value);
        return cleaned.length() <= 512 ? cleaned : cleaned.substring(0, 512);
    }

    public static final class SavedTitle {
        public final String stableId;
        public final String sourceId;
        public final String title;
        public final String mediaType;
        public final String detailUrl;
        public final String posterUrl;
        public final String year;
        public final String synopsis;
        public final String creator;
        public final long savedAt;
        public final boolean favorite;
        public final String watchStatus;
        public final long progressMs;
        public final long durationMs;
        public final String resumeEpisodeId;
        public final String resumeEpisodeTitle;
        public final String resumeArtworkRef;
        public final int resumeSeason;
        public final int resumeEpisodeNumber;
        public final long updatedAt;

        public SavedTitle(String stableId, String sourceId, String title, String mediaType,
                String detailUrl, String posterUrl, String year, String synopsis) {
            this(stableId, sourceId, title, mediaType, detailUrl, posterUrl, year, synopsis, "");
        }

        public SavedTitle(String stableId, String sourceId, String title, String mediaType,
                String detailUrl, String posterUrl, String year, String synopsis, String creator) {
            this(stableId, sourceId, title, mediaType, detailUrl, posterUrl, year, synopsis, creator,
                    0L, false, STATUS_NONE, 0L, 0L, "", "", "", -1, -1, 0L);
        }

        private SavedTitle(String stableId, String sourceId, String title, String mediaType,
                String detailUrl, String posterUrl, String year, String synopsis, String creator, long savedAt,
                boolean favorite, String watchStatus, long progressMs, long durationMs,
                String resumeEpisodeId, String resumeEpisodeTitle, String resumeArtworkRef,
                int resumeSeason,
                int resumeEpisodeNumber, long updatedAt) {
            this.stableId = stableId;
            this.sourceId = sourceId;
            this.title = title;
            this.mediaType = mediaType;
            this.detailUrl = detailUrl;
            this.posterUrl = posterUrl;
            this.year = year;
            this.synopsis = synopsis;
            this.creator = cleanCreator(creator);
            this.savedAt = savedAt;
            this.favorite = favorite;
            this.watchStatus = watchStatus;
            this.progressMs = progressMs;
            this.durationMs = durationMs;
            this.resumeEpisodeId = resumeEpisodeId;
            this.resumeEpisodeTitle = resumeEpisodeTitle;
            this.resumeArtworkRef = safeResumeArtworkReference(resumeArtworkRef);
            this.resumeSeason = resumeSeason;
            this.resumeEpisodeNumber = resumeEpisodeNumber;
            this.updatedAt = updatedAt;
        }
    }

    public static final class Collection {
        public final String profileId;
        public final String id;
        public final String name;
        public final long createdAt;
        public final long updatedAt;

        private Collection(String profileId, String id, String name, long createdAt, long updatedAt) {
            this.profileId = profileId;
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }
}
