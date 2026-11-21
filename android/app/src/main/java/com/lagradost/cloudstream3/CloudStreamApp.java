package com.lagradost.cloudstream3;

import com.auras.orbit.extensions.OrbitCs3HostServices;
import android.content.Context;
import org.json.JSONObject;

import kotlin.jvm.internal.Intrinsics;

/** Orbit-owned application-context and namespaced preference boundary. */
public class CloudStreamApp extends android.app.Application {
    private CloudStreamApp() { super(); }
    public static final com.lagradost.cloudstream3.CloudStreamApp.Companion Companion = new com.lagradost.cloudstream3.CloudStreamApp.Companion();

    public static class Companion {
        private Companion() { super(); }
        public Context getContext() {
            return OrbitCs3HostServices.get().getApplicationContext();
        }

        /** CloudStream-compatible scalar preference storage for CS3 plugins. */
        public void setKey(String path, Object value) {
            Intrinsics.checkNotNullParameter(path, "path");
            setScalarPreference(path, value);
        }

        public void setKey(java.lang.String p0, java.lang.String p1, java.lang.Object p2) {
            String key = (p0 == null ? "" : p0) + "/" + (p1 == null ? "" : p1);
            setScalarPreference(key, p2);
        }

        private void setScalarPreference(String key, Object value) {
            Context context = getContext();
            android.content.SharedPreferences.Editor editor =
                    com.lagradost.cloudstream3.utils.DataStore.INSTANCE
                            .getSharedPrefs(context).edit();
            if (value == null) editor.remove(key);
            else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
                editor.putString(key, value.toString());
            } else if (value instanceof Float) {
                float number = (Float) value;
                if (!Float.isFinite(number)) {
                    throw new IllegalArgumentException("CloudStream preferences require finite numbers.");
                }
                editor.putString(key, Float.toString(number));
            } else if (value instanceof String) {
                editor.putString(key, JSONObject.quote((String) value));
            }
            else throw new IllegalArgumentException("Orbit plugin preferences support scalar values only.");
            editor.apply();
        }
    }
}
