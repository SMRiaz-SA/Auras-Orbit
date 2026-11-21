package com.auras.orbit.metadata.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.auras.orbit.metadata.IdentityProvenance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Durable user-authored display and identity corrections, physically separate from disposable
 * metadata. Never changes provider media/action identities, Library, or watch progress. User
 * action must call put/clear or an explicit correction method; automatic matching is forbidden.
 */
public final class OrbitMetadataUserDecisions extends SQLiteOpenHelper {
    public static final String DATABASE_NAME = "orbit_metadata_user_decisions.db";
    public static final int VERSION = 4;
    public enum Field { TITLE, SYNOPSIS, GENRE, CAST, BACKDROP }
    public enum IdentityCorrectionOperation { MERGE, SPLIT, LINK, UNLINK, UNDO }

    public OrbitMetadataUserDecisions(Context context) {
        super(context, DATABASE_NAME, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE user_display_override ("
                + "profile_scope TEXT NOT NULL CHECK(length(profile_scope)=64),"
                + "source_key TEXT NOT NULL CHECK(length(source_key)=64),"
                + "field TEXT NOT NULL CHECK(field IN ('TITLE','SYNOPSIS','GENRE','CAST','BACKDROP')) ,"
                + "display_value TEXT NOT NULL CHECK(length(display_value) BETWEEN 1 AND 24000),"
                + "revision INTEGER NOT NULL CHECK(revision>0),"
                + "modified_ms INTEGER NOT NULL CHECK(modified_ms>=0),"
                + "PRIMARY KEY(profile_scope,source_key,field))");
        createCatalogLinkTable(db);
        createHistoryTable(db);
        createIdentityCorrectionTables(db);
    }

    /** An explicit, reversible link is NOT an automatic cross-provider match. */
    private static void createCatalogLinkTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS user_catalog_link ("
                + "profile_scope TEXT NOT NULL CHECK(length(profile_scope)=64),"
                + "source_key TEXT NOT NULL CHECK(length(source_key)=64),"
                + "catalog TEXT NOT NULL CHECK(catalog='TMDB'),"
                + "media_kind TEXT NOT NULL CHECK(media_kind IN ('MOVIE','SERIES')) ,"
                + "catalog_id INTEGER NOT NULL CHECK(catalog_id BETWEEN 1 AND 2147483647),"
                + "decision TEXT NOT NULL CHECK(decision IN ('LINK','REJECT')) ,"
                + "revision INTEGER NOT NULL CHECK(revision>0),"
                + "modified_ms INTEGER NOT NULL CHECK(modified_ms>=0),"
                + "PRIMARY KEY(profile_scope,source_key,catalog,media_kind))");
    }

    /** Immutable user decision history; no playback payloads or provider URLs are stored. */
    private static void createHistoryTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS user_decision_history ("
                + "event_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "profile_scope TEXT NOT NULL CHECK(length(profile_scope)=64),"
                + "source_key TEXT NOT NULL CHECK(length(source_key)=64),"
                + "field TEXT NOT NULL CHECK(field IN ('TITLE','SYNOPSIS','GENRE','CAST',"
                + "'BACKDROP','TMDB_MOVIE','TMDB_SERIES')) ,"
                + "previous_value TEXT NOT NULL CHECK(length(previous_value)<=24000),"
                + "next_value TEXT NOT NULL CHECK(length(next_value)<=24000),"
                + "operation TEXT NOT NULL CHECK(operation IN ('SET','CLEAR','UNDO')) ,"
                + "modified_ms INTEGER NOT NULL CHECK(modified_ms>=0))");
        db.execSQL("CREATE INDEX IF NOT EXISTS decision_history_by_source "
                + "ON user_decision_history(profile_scope,source_key,field,event_id DESC)");
    }

    /** Current explicit identity groupings and their append-only correction audit. */
    private static void createIdentityCorrectionTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS user_identity_assignment ("
                + "profile_scope TEXT NOT NULL CHECK(length(profile_scope)=64),"
                + "source_key TEXT NOT NULL CHECK(length(source_key)=64),"
                + "media_kind TEXT NOT NULL CHECK(media_kind IN ('MOVIE','SERIES')) ,"
                + "identity_id TEXT NOT NULL CHECK(length(identity_id) BETWEEN 1 AND 64),"
                + "revision INTEGER NOT NULL CHECK(revision>0),"
                + "modified_ms INTEGER NOT NULL CHECK(modified_ms>=0),"
                + "PRIMARY KEY(profile_scope,source_key))");
        db.execSQL("CREATE INDEX IF NOT EXISTS identity_assignment_group "
                + "ON user_identity_assignment(profile_scope,media_kind,identity_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS identity_correction_history ("
                + "event_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "correction_id TEXT NOT NULL CHECK(length(correction_id)=36),"
                + "profile_scope TEXT NOT NULL CHECK(length(profile_scope)=64),"
                + "source_key TEXT NOT NULL CHECK(length(source_key)=64),"
                + "media_kind TEXT NOT NULL CHECK(media_kind IN ('MOVIE','SERIES')) ,"
                + "previous_identity_id TEXT NOT NULL CHECK(length(previous_identity_id)<=64),"
                + "next_identity_id TEXT NOT NULL CHECK(length(next_identity_id)<=64),"
                + "operation TEXT NOT NULL CHECK(operation IN ('MERGE','SPLIT','LINK','UNLINK','UNDO')) ,"
                + "modified_ms INTEGER NOT NULL CHECK(modified_ms>=0))");
        db.execSQL("CREATE INDEX IF NOT EXISTS identity_history_by_source "
                + "ON identity_correction_history(profile_scope,source_key,event_id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS identity_history_by_correction "
                + "ON identity_correction_history(profile_scope,correction_id,event_id)");
    }

    private static void appendHistory(SQLiteDatabase db, String scope, String source,
            String field, String before, String after, String operation, long nowMs) {
        ContentValues values = new ContentValues();
        values.put("profile_scope", scope);
        values.put("source_key", source);
        values.put("field", field);
        values.put("previous_value", before == null ? "" : before);
        values.put("next_value", after == null ? "" : after);
        values.put("operation", operation);
        values.put("modified_ms", Math.max(0L, nowMs));
        if (db.insertOrThrow("user_decision_history", null, values) < 0L) {
            throw new IllegalStateException("Could not record user decision");
        }
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        int current = oldV;
        if (current == 1 && newV >= 2) {
            createCatalogLinkTable(db); // Additive, preserves all explicit corrections.
            current = 2;
        }
        if (current == 2 && newV >= 3) {
            createHistoryTable(db); // Historical changes start at this migration.
            current = 3;
        }
        if (current == 3 && newV >= 4) {
            createIdentityCorrectionTables(db); // Additive; preserves existing links and history.
            current = 4;
        }
        if (current != newV) {
            throw new IllegalStateException("Unsupported durable user-decision migration "
                    + oldV + " to " + newV);
        }
    }

    public static final class TmdbLink {
        public final int id;
        public final String kind;
        public final boolean rejected;
        public final long revision;
        public TmdbLink(int id, String kind, boolean rejected, long revision) {
            this.id = id; this.kind = kind; this.rejected = rejected; this.revision = revision;
        }
    }

    public synchronized void putTmdbLink(String profileId, String providerId,
            String stableId, String detailUrl, String mediaKind, int tmdbId,
            boolean rejected, long nowMs) {
        if (profileId == null || providerId == null || stableId == null || tmdbId <= 0
                || !("MOVIE".equals(mediaKind) || "SERIES".equals(mediaKind))) {
            throw new IllegalArgumentException("Explicit typed TMDB ID required");
        }
        String profileScope = OrbitMetadataCacheDatabase.sourceKey(profileId, "", "", "");
        String source = OrbitMetadataCacheDatabase.sourceKey(profileId, providerId, stableId, detailUrl);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long revision = 1L;
            String previous = "";
            try (Cursor prior = db.rawQuery("SELECT revision,catalog_id,decision FROM user_catalog_link "
                    + "WHERE profile_scope=? AND source_key=? AND catalog='TMDB' AND media_kind=?",
                    new String[]{profileScope, source, mediaKind})) {
                if (prior.moveToFirst()) {
                    revision = prior.getLong(0) + 1L;
                    previous = prior.getInt(1) + ":" + prior.getString(2);
                }
            }
            ContentValues row = new ContentValues();
            row.put("profile_scope", profileScope);
            row.put("source_key", source);
            row.put("catalog", "TMDB");
            row.put("media_kind", mediaKind);
            row.put("catalog_id", tmdbId);
            row.put("decision", rejected ? "REJECT" : "LINK");
            row.put("revision", revision);
            row.put("modified_ms", Math.max(0L, nowMs));
            if (db.insertWithOnConflict("user_catalog_link", null, row,
                    SQLiteDatabase.CONFLICT_REPLACE) < 0L)
                throw new IllegalStateException("Could not save typed catalog decision");
            appendHistory(db, profileScope, source, "TMDB_" + mediaKind, previous,
                    tmdbId + ":" + (rejected ? "REJECT" : "LINK"), "SET", nowMs);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public TmdbLink readTmdbLink(String profileId, String providerId,
            String stableId, String detailUrl, String mediaKind) {
        if (profileId == null || providerId == null || stableId == null
                || !("MOVIE".equals(mediaKind) || "SERIES".equals(mediaKind))) return null;
        String scope = OrbitMetadataCacheDatabase.sourceKey(profileId, "", "", "");
        String source = OrbitMetadataCacheDatabase.sourceKey(profileId, providerId, stableId, detailUrl);
        try (Cursor c = getReadableDatabase().query("user_catalog_link",
                new String[]{"catalog_id","decision","revision"},
                "profile_scope=? AND source_key=? AND catalog='TMDB' AND media_kind=?",
                new String[]{scope, source, mediaKind}, null, null, null, "1")) {
            return c.moveToFirst() ? new TmdbLink(c.getInt(0), mediaKind,
                    "REJECT".equals(c.getString(1)), c.getLong(2)) : null;
        }
    }

    public synchronized void clearTmdbLink(String profileId, String providerId,
            String stableId, String detailUrl, String mediaKind) {
        if (profileId == null || providerId == null || stableId == null
                || !("MOVIE".equals(mediaKind) || "SERIES".equals(mediaKind))) return;
        String scope = OrbitMetadataCacheDatabase.sourceKey(profileId, "", "", "");
        String source = OrbitMetadataCacheDatabase.sourceKey(profileId, providerId, stableId, detailUrl);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String previous = null;
            try (Cursor prior = db.rawQuery("SELECT catalog_id,decision FROM user_catalog_link "
                    + "WHERE profile_scope=? AND source_key=? AND catalog='TMDB' AND media_kind=?",
                    new String[]{scope, source, mediaKind})) {
                if (prior.moveToFirst()) previous = prior.getInt(0) + ":" + prior.getString(1);
            }
            if (previous != null) {
                db.delete("user_catalog_link",
                        "profile_scope=? AND source_key=? AND catalog='TMDB' AND media_kind=?",
                        new String[]{scope, source, mediaKind});
                appendHistory(db, scope, source, "TMDB_" + mediaKind, previous, "", "CLEAR",
                        System.currentTimeMillis());
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Reverts the latest explicit TMDB link or unlink while retaining an UNDO history event. */
    public synchronized boolean undoLastTmdbLinkChange(String profileId, String providerId,
            String stableId, String detailUrl, String mediaKind, long nowMs) {
        if (profileId == null || providerId == null || stableId == null
                || !("MOVIE".equals(mediaKind) || "SERIES".equals(mediaKind))) return false;
        String scope = OrbitMetadataCacheDatabase.sourceKey(profileId, "", "", "");
        String source = OrbitMetadataCacheDatabase.sourceKey(profileId, providerId, stableId, detailUrl);
        String field = "TMDB_" + mediaKind;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String previous, next;
            try (Cursor last = db.rawQuery("SELECT previous_value,next_value FROM user_decision_history "
                    + "WHERE profile_scope=? AND source_key=? AND field=? ORDER BY event_id DESC LIMIT 1",
                    new String[]{scope, source, field})) {
                if (!last.moveToFirst()) return false;
                previous = last.getString(0);
                next = last.getString(1);
            }
            String current = "";
            long revision = 1L;
            try (Cursor row = db.rawQuery("SELECT catalog_id,decision,revision FROM user_catalog_link "
                    + "WHERE profile_scope=? AND source_key=? AND catalog='TMDB' AND media_kind=?",
                    new String[]{scope, source, mediaKind})) {
                if (row.moveToFirst()) {
                    current = row.getInt(0) + ":" + row.getString(1);
                    revision = row.getLong(2) + 1L;
                }
            }
            if (!current.equals(next) || current.equals(previous)) return false;
            if (previous.isEmpty()) {
                db.delete("user_catalog_link", "profile_scope=? AND source_key=? "
                                + "AND catalog='TMDB' AND media_kind=?",
                        new String[]{scope, source, mediaKind});
            } else {
                int separator = previous.indexOf(':');
                if (separator <= 0 || separator != previous.lastIndexOf(':')) return false;
                int id;
                try { id = Integer.parseInt(previous.substring(0, separator)); }
                catch (NumberFormatException invalid) { return false; }
                String decision = previous.substring(separator + 1);
                if (id <= 0 || !("LINK".equals(decision) || "REJECT".equals(decision))) return false;
                ContentValues row = new ContentValues();
                row.put("profile_scope", scope);
                row.put("source_key", source);
                row.put("catalog", "TMDB");
                row.put("media_kind", mediaKind);
                row.put("catalog_id", id);
                row.put("decision", decision);
                row.put("revision", revision);
                row.put("modified_ms", Math.max(0L, nowMs));
                if (db.insertWithOnConflict("user_catalog_link", null, row,
                        SQLiteDatabase.CONFLICT_REPLACE) < 0L)
                    throw new IllegalStateException("Could not undo typed catalog decision");
            }
            appendHistory(db, scope, source, field, current, previous, "UNDO", nowMs);
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    /** A local provider item address; the raw values are hashed before storage. */
    public static final class IdentitySource {
        public final String providerId;
        public final String stableId;
        public final String detailUrl;
        public final IdentityProvenance.Kind kind;

        public IdentitySource(String providerId, String stableId, String detailUrl,
                IdentityProvenance.Kind kind) {
            this.providerId = boundedIdentityPart(providerId, "providerId", 256);
            this.stableId = boundedIdentityPart(stableId, "stableId", 2048);
            this.detailUrl = detailUrl == null || detailUrl.trim().isEmpty()
                    ? "" : boundedIdentityPart(detailUrl, "detailUrl", 4096);
            this.kind = Objects.requireNonNull(kind, "kind");
        }
    }

    public static final class IdentityAssignment {
        /** Local correction group UUID; null means no manual grouping was applied. */
        public final String identityId;
        public final IdentityProvenance.Kind kind;
        public final long revision;

        IdentityAssignment(String identityId, IdentityProvenance.Kind kind, long revision) {
            this.identityId = identityId;
            this.kind = kind;
            this.revision = revision;
        }
    }

    public static final class IdentityCorrectionEvent {
        public final long eventId;
        public final String correctionId;
        /** SHA-256 source address; raw provider IDs and detail URLs are never written here. */
        private final String sourceKey;
        public final IdentityProvenance.Kind kind;
        public final String previousIdentityId;
        public final String nextIdentityId;
        public final IdentityCorrectionOperation operation;
        public final long modifiedMs;

        IdentityCorrectionEvent(long eventId, String correctionId, String sourceKey,
                IdentityProvenance.Kind kind, String previousIdentityId, String nextIdentityId,
                IdentityCorrectionOperation operation, long modifiedMs) {
            this.eventId = eventId;
            this.correctionId = correctionId;
            this.sourceKey = sourceKey;
            this.kind = kind;
            this.previousIdentityId = previousIdentityId;
            this.nextIdentityId = nextIdentityId;
            this.operation = operation;
            this.modifiedMs = modifiedMs;
        }
    }

    /**
     * Manually joins two or more provider items under a new local identity. Their provider IDs,
     * Library rows and playback locators are never rewritten.
     */
    public synchronized String mergeIdentitySources(String profileId,
            List<IdentitySource> sources, long nowMs) {
        return assignIdentityGroup(profileId, sources, IdentityCorrectionOperation.MERGE, nowMs);
    }

    /** Explicit two-source link; otherwise equivalent to the reversible merge operation. */
    public synchronized String linkIdentitySources(String profileId,
            IdentitySource first, IdentitySource second, long nowMs) {
        List<IdentitySource> sources = new ArrayList<>();
        sources.add(first);
        sources.add(second);
        return assignIdentityGroup(profileId, sources, IdentityCorrectionOperation.LINK, nowMs);
    }

    /** Splits one member away from its current manual group into a new local identity. */
    public synchronized boolean splitIdentitySource(String profileId,
            IdentitySource source, long nowMs) {
        return separateIdentitySource(profileId, source, IdentityCorrectionOperation.SPLIT, nowMs);
    }

    /** Removes a manual link while preserving a standalone identity for the source. */
    public synchronized boolean unlinkIdentitySource(String profileId,
            IdentitySource source, long nowMs) {
        return separateIdentitySource(profileId, source, IdentityCorrectionOperation.UNLINK, nowMs);
    }

    public IdentityAssignment readIdentityAssignment(String profileId, IdentitySource source) {
        if (!hasIdentityProfile(profileId) || source == null) return null;
        String scope = identityProfileScope(profileId);
        String key = identitySourceKey(profileId, source);
        try (Cursor cursor = getReadableDatabase().query("user_identity_assignment",
                new String[]{"identity_id", "media_kind", "revision"},
                "profile_scope=? AND source_key=?", new String[]{scope, key}, null, null, null, "1")) {
            return cursor.moveToFirst() ? new IdentityAssignment(cursor.getString(0),
                    IdentityProvenance.Kind.valueOf(cursor.getString(1)), cursor.getLong(2)) : null;
        }
    }

    /** Returns only hashed source references and correction provenance, never provider payloads. */
    public List<IdentityCorrectionEvent> readIdentityCorrectionHistory(String profileId,
            IdentitySource source) {
        if (!hasIdentityProfile(profileId) || source == null) return Collections.emptyList();
        String scope = identityProfileScope(profileId);
        String key = identitySourceKey(profileId, source);
        List<IdentityCorrectionEvent> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("identity_correction_history",
                new String[]{"event_id", "correction_id", "source_key", "media_kind", "previous_identity_id",
                        "next_identity_id", "operation", "modified_ms"},
                "profile_scope=? AND source_key=?", new String[]{scope, key}, null, null,
                "event_id DESC")) {
            while (cursor.moveToNext()) result.add(new IdentityCorrectionEvent(cursor.getLong(0),
                    cursor.getString(1), cursor.getString(2),
                    IdentityProvenance.Kind.valueOf(cursor.getString(3)), cursor.getString(4),
                    cursor.getString(5), IdentityCorrectionOperation.valueOf(cursor.getString(6)),
                    cursor.getLong(7)));
        }
        return Collections.unmodifiableList(result);
    }

    /** Reverts the most recent complete manual correction touching this source, if still current. */
    public synchronized boolean undoLastIdentityCorrection(String profileId,
            IdentitySource source, long nowMs) {
        if (!hasIdentityProfile(profileId) || source == null) return false;
        String scope = identityProfileScope(profileId);
        String key = identitySourceKey(profileId, source);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String correctionId;
            try (Cursor last = db.rawQuery("SELECT correction_id FROM identity_correction_history "
                    + "WHERE profile_scope=? AND source_key=? ORDER BY event_id DESC LIMIT 1",
                    new String[]{scope, key})) {
                if (!last.moveToFirst()) return false;
                correctionId = last.getString(0);
            }

            List<IdentityCorrectionEvent> group = new ArrayList<>();
            try (Cursor rows = db.query("identity_correction_history",
                    new String[]{"event_id", "correction_id", "source_key", "media_kind", "previous_identity_id",
                            "next_identity_id", "operation", "modified_ms"},
                    "profile_scope=? AND correction_id=?", new String[]{scope, correctionId}, null, null,
                    "event_id")) {
                while (rows.moveToNext()) group.add(new IdentityCorrectionEvent(rows.getLong(0),
                        rows.getString(1), rows.getString(2),
                        IdentityProvenance.Kind.valueOf(rows.getString(3)), rows.getString(4),
                        rows.getString(5), IdentityCorrectionOperation.valueOf(rows.getString(6)),
                        rows.getLong(7)));
            }
            if (group.isEmpty()) return false;
            for (IdentityCorrectionEvent event : group) {
                IdentityAssignment current = currentIdentity(db, scope, event.sourceKey);
                String currentId = current == null ? "" : current.identityId;
                if (!event.nextIdentityId.equals(currentId)
                        || (current != null && event.kind != current.kind)) return false;
            }

            String undoId = UUID.randomUUID().toString();
            for (IdentityCorrectionEvent event : group) {
                String previous = event.nextIdentityId;
                String next = event.previousIdentityId;
                applyIdentityAssignment(db, scope, event.sourceKey, event.kind,
                        next, nowMs);
                appendIdentityHistory(db, undoId, scope, event.sourceKey, event.kind,
                        previous, next, IdentityCorrectionOperation.UNDO, nowMs);
            }
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    private String assignIdentityGroup(String profileId, List<IdentitySource> sources,
            IdentityCorrectionOperation operation, long nowMs) {
        if (!hasIdentityProfile(profileId) || sources == null || sources.size() < 2 || sources.size() > 100)
            throw new IllegalArgumentException("At least two bounded identity sources are required");
        String scope = identityProfileScope(profileId);
        Map<String, IdentityProvenance.Kind> kindsByKey = new LinkedHashMap<>();
        IdentityProvenance.Kind commonKind = null;
        for (IdentitySource source : sources) {
            if (source == null) throw new IllegalArgumentException("Identity source is required");
            String key = identitySourceKey(profileId, source);
            IdentityProvenance.Kind kind = source.kind;
            IdentityProvenance.Kind prior = kindsByKey.put(key, kind);
            if (prior != null) throw new IllegalArgumentException("Identity sources must be unique");
            if (commonKind != null && commonKind != kind)
                throw new IllegalArgumentException("Cannot group different media kinds");
            commonKind = kind;
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Map<String, String> previousByKey = new LinkedHashMap<>();
            Set<String> existingGroups = new LinkedHashSet<>();
            boolean hasUnassigned = false;
            for (Map.Entry<String, IdentityProvenance.Kind> entry : kindsByKey.entrySet()) {
                IdentityAssignment current = currentIdentity(db, scope, entry.getKey());
                if (current != null && entry.getValue() != current.kind)
                    throw new IllegalArgumentException("Source media kind changed since its correction");
                String identityId = current == null ? null : current.identityId;
                previousByKey.put(entry.getKey(), identityId == null ? "" : identityId);
                if (identityId == null) hasUnassigned = true;
                else existingGroups.add(identityId);
            }
            if (!hasUnassigned && existingGroups.size() == 1) {
                db.setTransactionSuccessful();
                return existingGroups.iterator().next();
            }
            String identityId = UUID.randomUUID().toString();
            String correctionId = UUID.randomUUID().toString();
            for (Map.Entry<String, IdentityProvenance.Kind> entry : kindsByKey.entrySet()) {
                applyIdentityAssignment(db, scope, entry.getKey(), entry.getValue(), identityId, nowMs);
                appendIdentityHistory(db, correctionId, scope, entry.getKey(), entry.getValue(),
                        previousByKey.get(entry.getKey()), identityId, operation, nowMs);
            }
            db.setTransactionSuccessful();
            return identityId;
        } finally { db.endTransaction(); }
    }

    private boolean separateIdentitySource(String profileId, IdentitySource source,
            IdentityCorrectionOperation operation, long nowMs) {
        if (!hasIdentityProfile(profileId) || source == null) return false;
        String scope = identityProfileScope(profileId);
        String key = identitySourceKey(profileId, source);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            IdentityAssignment current = currentIdentity(db, scope, key);
            if (current == null) return false;
            IdentityProvenance.Kind kind = source.kind;
            if (kind != current.kind)
                throw new IllegalArgumentException("Source media kind changed since its correction");
            String previous = current.identityId;
            try (Cursor peers = db.rawQuery("SELECT COUNT(*) FROM user_identity_assignment "
                    + "WHERE profile_scope=? AND media_kind=? AND identity_id=? AND source_key<>?",
                    new String[]{scope, kind.name(), previous, key})) {
                if (!peers.moveToFirst() || peers.getLong(0) == 0L) return false;
            }
            String next = UUID.randomUUID().toString();
            String correctionId = UUID.randomUUID().toString();
            applyIdentityAssignment(db, scope, key, kind, next, nowMs);
            appendIdentityHistory(db, correctionId, scope, key, kind,
                    previous, next, operation, nowMs);
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    private static String identitySourceKey(String profileId, IdentitySource source) {
        return OrbitMetadataCacheDatabase.sourceKey(profileId, source.providerId,
                source.stableId, source.detailUrl);
    }

    private static String identityProfileScope(String profileId) {
        String valid = boundedIdentityPart(profileId, "profileId", 256);
        return OrbitMetadataCacheDatabase.sourceKey(valid, "", "", "");
    }

    private static boolean hasIdentityProfile(String profileId) {
        return profileId != null && !profileId.trim().isEmpty();
    }

    private static IdentityAssignment currentIdentity(SQLiteDatabase db, String scope, String sourceKey) {
        try (Cursor cursor = db.rawQuery("SELECT identity_id,media_kind,revision FROM user_identity_assignment "
                + "WHERE profile_scope=? AND source_key=?", new String[]{scope, sourceKey})) {
            return cursor.moveToFirst() ? new IdentityAssignment(cursor.getString(0),
                    IdentityProvenance.Kind.valueOf(cursor.getString(1)), cursor.getLong(2)) : null;
        }
    }

    private static void applyIdentityAssignment(SQLiteDatabase db, String scope, String sourceKey,
            IdentityProvenance.Kind kind, String identityId, long nowMs) {
        if (identityId == null || identityId.isEmpty()) {
            db.delete("user_identity_assignment", "profile_scope=? AND source_key=?",
                    new String[]{scope, sourceKey});
            return;
        }
        long revision = 1L;
        try (Cursor prior = db.rawQuery("SELECT revision FROM user_identity_assignment "
                + "WHERE profile_scope=? AND source_key=?", new String[]{scope, sourceKey})) {
            if (prior.moveToFirst()) revision = prior.getLong(0) + 1L;
        }
        ContentValues row = new ContentValues();
        row.put("profile_scope", scope);
        row.put("source_key", sourceKey);
        row.put("media_kind", kind.name());
        row.put("identity_id", identityId);
        row.put("revision", revision);
        row.put("modified_ms", Math.max(0L, nowMs));
        long inserted = db.insertWithOnConflict("user_identity_assignment", null, row,
                SQLiteDatabase.CONFLICT_REPLACE);
        if (inserted < 0L) {
            throw new IllegalStateException("Could not save identity correction assignment");
        }
        try (Cursor saved = db.rawQuery("SELECT identity_id,media_kind,revision "
                + "FROM user_identity_assignment WHERE profile_scope=? AND source_key=?",
                new String[]{scope, sourceKey})) {
            if (!saved.moveToFirst() || !identityId.equals(saved.getString(0))
                    || !kind.name().equals(saved.getString(1)) || revision != saved.getLong(2)) {
                throw new IllegalStateException("Identity assignment insert did not persist the requested row");
            }
        }
    }

    private static void appendIdentityHistory(SQLiteDatabase db, String correctionId,
            String scope, String sourceKey, IdentityProvenance.Kind kind,
            String previous, String next, IdentityCorrectionOperation operation, long nowMs) {
        ContentValues row = new ContentValues();
        row.put("correction_id", correctionId);
        row.put("profile_scope", scope);
        row.put("source_key", sourceKey);
        row.put("media_kind", kind.name());
        row.put("previous_identity_id", previous == null ? "" : previous);
        row.put("next_identity_id", next == null ? "" : next);
        row.put("operation", operation.name());
        row.put("modified_ms", Math.max(0L, nowMs));
        db.insertOrThrow("identity_correction_history", null, row);
    }

    private static String boundedIdentityPart(String value, String label, int max) {
        if (value == null) throw new IllegalArgumentException(label);
        if (value.trim().isEmpty() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label);
        return value;
    }

    public synchronized void put(String profileId, String providerId, String stableId,
                                 String detailUrl, Field field, String display, long nowMs) {
        if (profileId == null || providerId == null || stableId == null || field == null || display == null)
            throw new IllegalArgumentException("User override requires a scope, field and value");
        String trimmed = display.trim();
        if (trimmed.isEmpty() || trimmed.length() > 24000)
            throw new IllegalArgumentException("Override must be nonblank and within the text budget");
        SQLiteDatabase db = getWritableDatabase();
        String profileScope = OrbitMetadataCacheDatabase.sourceKey(profileId, "", "", "");
        String sourceKey = OrbitMetadataCacheDatabase.sourceKey(profileId, providerId, stableId, detailUrl);
        db.beginTransaction();
        try {
            long revision = 1L;
            String previous = "";
            try (Cursor prior = db.rawQuery("SELECT revision,display_value FROM user_display_override "
                    + "WHERE profile_scope=? AND source_key=? AND field=?",
                    new String[]{profileScope, sourceKey, field.name()})) {
                if (prior.moveToFirst()) {
                    revision = prior.getLong(0) + 1L;
                    previous = prior.getString(1);
                }
            }
            ContentValues values = new ContentValues();
            values.put("profile_scope", profileScope);
            values.put("source_key", sourceKey);
            values.put("field", field.name());
            values.put("display_value", trimmed);
            values.put("revision", revision);
            values.put("modified_ms", Math.max(0, nowMs));
            if (db.insertWithOnConflict("user_display_override", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) < 0L)
                throw new IllegalStateException("Could not save display correction");
            appendHistory(db, profileScope, sourceKey, field.name(), previous, trimmed,
                    "SET", nowMs);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public String read(String profileId, String providerId, String stableId,
                       String detailUrl, Field field) {
        if (profileId == null || providerId == null || stableId == null || field == null) return null;
        String scope = OrbitMetadataCacheDatabase.sourceKey(profileId, "", "", "");
        String source = OrbitMetadataCacheDatabase.sourceKey(profileId, providerId, stableId, detailUrl);
        try (Cursor c = getReadableDatabase().query("user_display_override",
                new String[]{"display_value"}, "profile_scope=? AND source_key=? AND field=?",
                new String[]{scope, source, field.name()}, null, null, null, "1")) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    public synchronized void clear(String profileId, String providerId, String stableId,
                      String detailUrl, Field field) {
        if (profileId == null || providerId == null || stableId == null || field == null) return;
        String scope = OrbitMetadataCacheDatabase.sourceKey(profileId, "", "", "");
        String source = OrbitMetadataCacheDatabase.sourceKey(profileId, providerId, stableId, detailUrl);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String previous = null;
            try (Cursor prior = db.rawQuery("SELECT display_value FROM user_display_override "
                    + "WHERE profile_scope=? AND source_key=? AND field=?",
                    new String[]{scope, source, field.name()})) {
                if (prior.moveToFirst()) previous = prior.getString(0);
            }
            if (previous != null) {
                db.delete("user_display_override", "profile_scope=? AND source_key=? AND field=?",
                        new String[]{scope, source, field.name()});
                appendHistory(db, scope, source, field.name(), previous, "", "CLEAR",
                        System.currentTimeMillis());
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Undo one explicit field change only when its recorded result is still current. */
    public synchronized boolean undoLastDisplayChange(String profileId, String providerId,
            String stableId, String detailUrl, Field field, long nowMs) {
        if (profileId == null || providerId == null || stableId == null || field == null) return false;
        String scope = OrbitMetadataCacheDatabase.sourceKey(profileId, "", "", "");
        String source = OrbitMetadataCacheDatabase.sourceKey(profileId, providerId, stableId, detailUrl);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String before, after;
            try (Cursor last = db.rawQuery("SELECT previous_value,next_value FROM user_decision_history "
                    + "WHERE profile_scope=? AND source_key=? AND field=? "
                    + "ORDER BY event_id DESC LIMIT 1", new String[]{scope, source, field.name()})) {
                if (!last.moveToFirst()) return false;
                before = last.getString(0);
                after = last.getString(1);
            }
            String current = "";
            long revision = 1L;
            try (Cursor prior = db.rawQuery("SELECT display_value,revision FROM user_display_override "
                    + "WHERE profile_scope=? AND source_key=? AND field=?",
                    new String[]{scope, source, field.name()})) {
                if (prior.moveToFirst()) {
                    current = prior.getString(0);
                    revision = prior.getLong(1) + 1L;
                }
            }
            if (!current.equals(after)) return false; // No lost updates.
            if (before.equals(current)) return false; // No pointless replay.
            if (before.isEmpty()) {
                db.delete("user_display_override", "profile_scope=? AND source_key=? AND field=?",
                        new String[]{scope, source, field.name()});
            } else {
                ContentValues row = new ContentValues();
                row.put("profile_scope", scope);
                row.put("source_key", source);
                row.put("field", field.name());
                row.put("display_value", before);
                row.put("revision", revision);
                row.put("modified_ms", Math.max(0L, nowMs));
                if (db.insertWithOnConflict("user_display_override", null, row,
                        SQLiteDatabase.CONFLICT_REPLACE) < 0L)
                    throw new IllegalStateException("Could not undo display correction");
            }
            appendHistory(db, scope, source, field.name(), current, before, "UNDO", nowMs);
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

}
