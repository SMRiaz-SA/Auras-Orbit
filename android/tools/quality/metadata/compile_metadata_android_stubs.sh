#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/src/android/content" "$TMP/src/android/database/sqlite" "$TMP/src/android/database" "$TMP/src/android/net" "$TMP/src/com/auras/orbit/providers" "$TMP/src/com/auras/orbit/metadata" "$TMP/out"
cat > "$TMP/src/android/content/Context.java" <<'EOF'
package android.content;
public class Context { public boolean deleteDatabase(String name) { return true; } }
EOF
cat > "$TMP/src/android/content/ContentValues.java" <<'EOF'
package android.content;
public class ContentValues {
    public void put(String key, String value) {}
    public void put(String key, Integer value) {}
    public void put(String key, Long value) {}
}
EOF
cat > "$TMP/src/android/database/Cursor.java" <<'EOF'
package android.database;
public interface Cursor extends AutoCloseable {
    boolean moveToFirst(); boolean moveToNext(); long getLong(int index);
    int getInt(int index); String getString(int index); void close();
}
EOF
cat > "$TMP/src/android/database/sqlite/SQLiteDatabase.java" <<'EOF'
package android.database.sqlite;
import android.content.ContentValues;
import android.database.Cursor;
public class SQLiteDatabase {
    public static final int CONFLICT_REPLACE = 5;
    public void setForeignKeyConstraintsEnabled(boolean value) {}
    public void execSQL(String statement) {}
    public Cursor rawQuery(String sql, String[] selectionArgs) { return null; }
    public Cursor query(String table, String[] columns, String where, String[] args,
                        String group, String having, String order, String limit) { return null; }
    public Cursor query(String table, String[] columns, String where, String[] args,
                        String group, String having, String order) { return null; }
    public void beginTransaction() {}
    public void setTransactionSuccessful() {}
    public void endTransaction() {}
    public long insertWithOnConflict(String table, String nullColumn, ContentValues values, int conflict) { return 1; }
    public long insertOrThrow(String table, String nullColumn, ContentValues values) { return 1; }
    public int delete(String table, String where, String[] selectionArgs) { return 0; }
}
EOF
cat > "$TMP/src/android/database/sqlite/SQLiteOpenHelper.java" <<'EOF'
package android.database.sqlite;
import android.content.Context;
public abstract class SQLiteOpenHelper {
    public SQLiteOpenHelper(Context context, String name, Object factory, int version) {}
    public void setWriteAheadLoggingEnabled(boolean value) {}
    public void close() {}
    public SQLiteDatabase getWritableDatabase() { return null; }
    public SQLiteDatabase getReadableDatabase() { return null; }
    public void onConfigure(SQLiteDatabase db) {}
    public abstract void onCreate(SQLiteDatabase db);
    public abstract void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion);
}
EOF
cat > "$TMP/src/android/net/Uri.java" <<'EOF'
package android.net;
public class Uri {
    public static Uri parse(String value) { return new Uri(); }
    public String getScheme() { return null; }
    public String getUserInfo() { return null; }
    public String getEncodedQuery() { return null; }
    public String getEncodedFragment() { return null; }
    public String getHost() { return null; }
}
EOF
cat > "$TMP/src/com/auras/orbit/providers/MediaItem.java" <<'EOF'
package com.auras.orbit.providers;
public class MediaItem {
    public enum Kind { MOVIE, SERIES, LIVE, UNKNOWN }
    public String stableId, detailUrl, title, overview, year, posterUrl;
    public Kind kind;
}
EOF
cat > "$TMP/src/com/auras/orbit/metadata/TitleCatalogRecord.java" <<'EOF'
package com.auras.orbit.metadata;
public class TitleCatalogRecord {
    public int catalogId;
    public String kind, title, synopsis, year, posterUrl, backdropUrl;
    public TitleCatalogRecord(int id, String k, String name, String plot, String y, String poster, String back) {}
}
EOF
find "$TMP/src" -name '*.java' -print > "$TMP/sources"
printf '%s\n' \
    "$ROOT/app/src/main/java/com/auras/orbit/metadata/store/OrbitMetadataCacheDatabase.java" \
    "$ROOT/app/src/main/java/com/auras/orbit/metadata/store/OrbitMetadataUserDecisions.java" >> "$TMP/sources"
javac --release 17 -encoding UTF-8 -d "$TMP/out" @"$TMP/sources"
echo "ANDROID_METADATA_STORE_STUB_TYPECHECK_PASS (does not replace Android SDK compilation)"
