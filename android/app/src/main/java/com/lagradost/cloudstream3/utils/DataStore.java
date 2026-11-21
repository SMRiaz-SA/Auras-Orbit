package com.lagradost.cloudstream3.utils;

/** Orbit-owned DataStore compatibility subset. */
public class DataStore {
    public static final String PREFERENCES_NAME = "orbit_cs3_datastore_v1";
    private DataStore() { super(); }
    public static final com.lagradost.cloudstream3.utils.DataStore INSTANCE = new com.lagradost.cloudstream3.utils.DataStore();
    public java.lang.String getFolderName(java.lang.String p0, java.lang.String p1) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(p0, "folder");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(p1, "path");
        return p0 + "/" + p1;
    }
    public android.content.SharedPreferences getSharedPrefs(android.content.Context p0) {
        // The old app stores arbitrary user, account, plugin, and watch-state data
        // in rebuild_preference. The replacement shares the package ID for in-place
        // updates, so using that legacy name here would silently cross the migration
        // boundary. Keep new CS3-compatible values in Orbit's own store.
        return p0.getSharedPreferences(PREFERENCES_NAME, android.content.Context.MODE_PRIVATE);
    }
}
