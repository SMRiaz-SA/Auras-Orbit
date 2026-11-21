package com.auras.orbit.metadata.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;

import com.auras.orbit.providers.MediaItem;
import com.auras.orbit.metadata.TitleCatalogRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Disposable, metadata-only, Android-owned cache. This database is intentionally separate
 * from the durable Library and from explicitly accepted user identity/correction records.
 * Never save plugin callbacks, episode.data, URLs to playback, cookies, headers or credentials.
 * Run writes/maintenance on a worker, never Android's UI thread.
 */
public final class OrbitMetadataCacheDatabase extends SQLiteOpenHelper {
    public static final String DATABASE_NAME = "orbit_metadata_cache.db";
    public static final int SCHEMA_VERSION = 1;
    private static final long FRESH_MS = 6L * 60 * 60_000L;
    private static final long STALE_MS = 7L * 24 * 60 * 60_000L;
    private static final long RETAIN_MS = 30L * 24 * 60 * 60_000L;
    private static final int MAX_SNAPSHOTS = 2500;
    private static final long MAX_BYTES = 8L * 1024L * 1024L;

    public static final class Display {
        public final String title;
        public final String synopsis;
        public final String year;
        public final String posterUrl;
        public final boolean fresh;
        public Display(String title, String synopsis, String year, String posterUrl, boolean fresh) {
            this.title = title; this.synopsis = synopsis; this.year = year;
            this.posterUrl = posterUrl; this.fresh = fresh;
        }
    }

    public OrbitMetadataCacheDatabase(Context context) {
        super(context, DATABASE_NAME, null, SCHEMA_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    /** Reset ONLY disposable catalog/display cache after a confirmed SQLite corruption. */
    public synchronized boolean recoverCorruptCache(Context context) {
        if (context == null) return false;
        try {
            close(); // Also closes the WAL connection before deleting the cache database.
            context.deleteDatabase(DATABASE_NAME);
            getWritableDatabase(); // Recreates the schema; no Library or user decisions touched.
            return true;
        } catch (RuntimeException cannotRebuild) {
            return false;
        }
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE source_display ("
                + "source_key TEXT PRIMARY KEY NOT NULL CHECK(length(source_key)=64),"
                + "profile_scope TEXT NOT NULL CHECK(length(profile_scope)=64),"
                + "provider_scope TEXT NOT NULL CHECK(length(provider_scope)=64),"
                + "kind TEXT NOT NULL CHECK(kind IN ('MOVIE','SERIES','LIVE','UNKNOWN')) ,"
                + "title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 1024),"
                + "synopsis TEXT NOT NULL DEFAULT '' CHECK(length(synopsis)<=24000),"
                + "year TEXT NOT NULL DEFAULT '' CHECK(length(year)<=16),"
                + "poster_url_public TEXT NOT NULL DEFAULT '' CHECK(length(poster_url_public)<=2048),"
                + "fetched_ms INTEGER NOT NULL, fresh_until_ms INTEGER NOT NULL,"
                + "stale_until_ms INTEGER NOT NULL, retain_until_ms INTEGER NOT NULL,"
                + "measured_bytes INTEGER NOT NULL CHECK(measured_bytes>0),"
                + "CHECK(fetched_ms<=fresh_until_ms AND fresh_until_ms<=stale_until_ms "
                + "AND stale_until_ms<=retain_until_ms))");
        db.execSQL("CREATE INDEX source_display_profile ON source_display(profile_scope,provider_scope)");
        db.execSQL("CREATE INDEX source_display_retention ON source_display(retain_until_ms)");
        db.execSQL("CREATE TABLE catalog_field ("
                + "catalog_scope TEXT NOT NULL CHECK(length(catalog_scope)=64),"
                + "field TEXT NOT NULL CHECK(field IN ('title','synopsis','year','genre','cast',"
                + "'backdrop','poster','rating','release_date','language','original_title')) ,"
                + "origin TEXT NOT NULL CHECK(origin IN ('TMDB','JELLYFIN_PUBLIC','OTHER_CATALOG')) ,"
                + "locale TEXT NOT NULL DEFAULT '' CHECK(length(locale)<=32),"
                + "value TEXT NOT NULL CHECK(length(value)<=24000),"
                + "source_ref TEXT NOT NULL CHECK(length(source_ref) BETWEEN 1 AND 256),"
                + "verified_at_ms INTEGER NOT NULL, expires_at_ms INTEGER NOT NULL,"
                + "PRIMARY KEY(catalog_scope,field,origin,locale,source_ref))");
        db.execSQL("CREATE INDEX catalog_expiry ON catalog_field(expires_at_ms)");
    }

    /** The cache is disposable; no schema upgrade may touch the Library or corrections DB. */
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion >= newVersion) return;
        db.execSQL("DROP TABLE IF EXISTS catalog_field");
        db.execSQL("DROP TABLE IF EXISTS source_display");
        onCreate(db);
    }

    /** An untrusted provider's content is never persisted beyond a safe display projection. */
    public void saveProviderDisplay(String profileId, String providerId, MediaItem seed,
            MediaItem details, long nowMs) {
        if (seed == null || details == null || profileId == null || providerId == null
                || profileId.isEmpty() || providerId.isEmpty() || !seed.stableId.equals(details.stableId)) return;
        String title = safe(details.title, 1024);
        if (title.isEmpty()) return;
        String synopsis = safe(details.overview, 24000);
        String year = safe(details.year, 16);
        String poster = verifiedPublicArtwork(details.posterUrl);
        long measured = Math.max(1L, 2L * (title.length() + synopsis.length()
                + year.length() + poster.length()) + 512L);
        ContentValues row = new ContentValues();
        row.put("source_key", sourceKey(profileId, providerId, seed.stableId, seed.detailUrl));
        row.put("profile_scope", digest(profileId));
        row.put("provider_scope", digest(providerId));
        row.put("kind", details.kind.name());
        row.put("title", title);
        row.put("synopsis", synopsis);
        row.put("year", year);
        row.put("poster_url_public", poster);
        row.put("fetched_ms", nowMs);
        row.put("fresh_until_ms", nowMs + FRESH_MS);
        row.put("stale_until_ms", nowMs + STALE_MS);
        row.put("retain_until_ms", nowMs + RETAIN_MS);
        row.put("measured_bytes", measured);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Throwable primaryFailure = null;
        try {
            db.insertWithOnConflict("source_display", null, row, SQLiteDatabase.CONFLICT_REPLACE);
            pruneLocked(db, nowMs);
            db.setTransactionSuccessful();
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally { endTransactionPreservingFailure(db, primaryFailure); }
    }

    /** Display-only stale reads; never used to authorize Play or provider availability. */
    public Display readProviderDisplay(String profileId, String providerId, MediaItem seed, long nowMs) {
        if (seed == null || profileId == null || providerId == null) return null;
        String key = sourceKey(profileId, providerId, seed.stableId, seed.detailUrl);
        try (Cursor cursor = getReadableDatabase().query("source_display",
                new String[]{"title","synopsis","year","poster_url_public","fresh_until_ms"},
                "source_key=? AND retain_until_ms>=?", new String[]{key, Long.toString(nowMs)},
                null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;
            return new Display(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), nowMs <= cursor.getLong(4));
        }
    }

    /** Public, disposable catalog display fields; scoped by typed TMDB ID, not plugin URL. */
    public void saveCatalogTitle(TitleCatalogRecord title, long nowMs) {
        if (title == null || title.title.isEmpty()) return;
        String scope = digest("TMDB:" + title.kind + ":" + title.catalogId);
        String ref = "tmdb:" + title.kind.toLowerCase(Locale.ROOT) + ":" + title.catalogId;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Throwable primaryFailure = null;
        try {
            String[][] fields = {{"title", title.title}, {"synopsis", title.synopsis},
                    {"year", title.year}, {"poster", title.posterUrl},
                    {"backdrop", title.backdropUrl}};
            for (String[] field : fields) {
                if (field[1].isEmpty()) continue;
                ContentValues values = new ContentValues();
                values.put("catalog_scope", scope);
                values.put("field", field[0]);
                values.put("origin", "TMDB");
                values.put("locale", "en-US");
                values.put("value", field[1]);
                values.put("source_ref", ref);
                values.put("verified_at_ms", nowMs);
                values.put("expires_at_ms", nowMs + 7L * 24 * 60 * 60_000L);
                db.insertWithOnConflict("catalog_field", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.delete("catalog_field", "expires_at_ms<?", new String[]{Long.toString(nowMs)});
            db.setTransactionSuccessful();
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally { endTransactionPreservingFailure(db, primaryFailure); }
    }

    public TitleCatalogRecord readCatalogTitle(int catalogId, String mediaKind, long nowMs) {
        if (catalogId <= 0 || !("MOVIE".equals(mediaKind) || "SERIES".equals(mediaKind))) return null;
        String scope = digest("TMDB:" + mediaKind + ":" + catalogId);
        String ref = "tmdb:" + mediaKind.toLowerCase(Locale.ROOT) + ":" + catalogId;
        String title = "", synopsis = "", year = "", poster = "", backdrop = "";
        try (Cursor cursor = getReadableDatabase().query("catalog_field",
                new String[]{"field", "value"},
                "catalog_scope=? AND source_ref=? AND origin='TMDB' AND locale='en-US' AND expires_at_ms>=?",
                new String[]{scope, ref, Long.toString(nowMs)}, null, null, null)) {
            while (cursor.moveToNext()) {
                String field = cursor.getString(0), value = cursor.getString(1);
                if ("title".equals(field)) title = value;
                else if ("synopsis".equals(field)) synopsis = value;
                else if ("year".equals(field)) year = value;
                else if ("poster".equals(field)) poster = value;
                else if ("backdrop".equals(field)) backdrop = value;
            }
        }
        return title.isEmpty() ? null : new TitleCatalogRecord(catalogId, mediaKind,
                title, synopsis, year, poster, backdrop);
    }

    public void resetCacheOnly() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Throwable primaryFailure = null;
        try {
            db.delete("catalog_field", null, null);
            db.delete("source_display", null, null);
            db.setTransactionSuccessful();
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally { endTransactionPreservingFailure(db, primaryFailure); }
    }

    private static void endTransactionPreservingFailure(SQLiteDatabase db, Throwable primaryFailure) {
        try {
            db.endTransaction();
        } catch (RuntimeException | Error cleanupFailure) {
            if (primaryFailure == null) throw cleanupFailure;
            if (cleanupFailure != primaryFailure) primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    public void clearProfile(String profileId) {
        if (profileId == null) return;
        getWritableDatabase().delete("source_display", "profile_scope=?",
                new String[]{digest(profileId)});
    }

    private static void pruneLocked(SQLiteDatabase db, long now) {
        db.delete("source_display", "retain_until_ms < ?", new String[]{Long.toString(now)});
        db.delete("catalog_field", "expires_at_ms < ?", new String[]{Long.toString(now)});
        // Both quota decisions are against actual stored text estimates, independent of UI paging.
        try (Cursor stats = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(measured_bytes),0) FROM source_display", null)) {
            if (!stats.moveToFirst()) return;
            long count = stats.getLong(0), bytes = stats.getLong(1);
            if (count <= MAX_SNAPSHOTS && bytes <= MAX_BYTES) return;
            try (Cursor oldest = db.rawQuery("SELECT source_key,measured_bytes FROM source_display "
                    + "ORDER BY fetched_ms ASC", null)) {
                while ((count > MAX_SNAPSHOTS || bytes > MAX_BYTES) && oldest.moveToNext()) {
                    db.delete("source_display", "source_key=?", new String[]{oldest.getString(0)});
                    bytes -= oldest.getLong(1); count--;
                }
            }
        }
    }

    public static String sourceKey(String profile, String provider, String stableId, String detailUrl) {
        return digest(lengthDelimited(profile, provider, stableId, detailUrl));
    }

    private static String lengthDelimited(String... fields) {
        StringBuilder out = new StringBuilder();
        for (String f : fields) {
            String value = f == null ? "" : f;
            out.append(value.length()).append(':').append(value);
        }
        return out.toString();
    }

    private static String digest(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format(Locale.ROOT, "%02x", b & 255));
            return sb.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String safe(String s, int max) {
        if (s == null) return "";
        String normalized = s.trim();
        // Reject instead of silently truncating metadata facts (provider output is unaffected).
        return normalized.length() > max ? "" : normalized;
    }

    /** Unknown provider poster URLs could embed signed credentials even in the path. */
    private static String verifiedPublicArtwork(String raw) {
        if (raw == null || raw.length() > 2048) return "";
        try {
            Uri uri = Uri.parse(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getEncodedQuery() != null || uri.getEncodedFragment() != null) return "";
            String host = uri.getHost();
            if (host == null || !host.equalsIgnoreCase("image.tmdb.org")) return "";
            return raw;
        } catch (RuntimeException ignored) { return ""; }
    }
}
