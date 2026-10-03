package android.content;

import com.lagradost.common.storage.DesktopDataStore;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@android.annotation.Implemented
public interface SharedPreferences {
    Map<String, ?> getAll();
    String getString(String key, String defValue);
    int getInt(String key, int defValue);
    long getLong(String key, long defValue);
    float getFloat(String key, float defValue);
    boolean getBoolean(String key, boolean defValue);
    Set<String> getStringSet(String key, Set<String> defValue);
    boolean contains(String key);
    Editor edit();
    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);

    interface Editor {
        Editor putString(String key, String value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor putBoolean(String key, boolean value);
        Editor putStringSet(String key, Set<String> values);
        Editor remove(String key);
        Editor clear();
        boolean commit();
        void apply();
    }

    interface OnSharedPreferenceChangeListener {
        void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key);
    }
}

class DesktopSharedPreferences implements SharedPreferences {
    private final String prefName;

    public DesktopSharedPreferences(String name) {
        this.prefName = name + "_";
        com.lagradost.common.storage.PluginSettingsSchemaRegistry.INSTANCE.registerPrefName(this.prefName);
    }

    private String getActualPref() {
        String callingPlugin = null;
        try {
            Class<?> clazz = Class.forName("com.lagradost.runtime.loader.ExtensionLoader");
            java.lang.reflect.Field field = clazz.getField("INSTANCE");
            Object instance = field.get(null);
            java.lang.reflect.Method method = clazz.getMethod("getCallingPluginName");
            callingPlugin = (String) method.invoke(instance);
        } catch (Exception e) {
            // Ignored
        }
        if (callingPlugin == null) {
            String scopedPrefName = com.lagradost.common.storage.PluginSettingsSchemaRegistry.INSTANCE.activePluginPrefName();
            if (scopedPrefName != null && !scopedPrefName.isEmpty()) {
                callingPlugin = scopedPrefName.substring(0, scopedPrefName.length() - 1);
            }
        }
        if (callingPlugin != null) {
            String cleanPreferenceName = prefName.endsWith("_")
                ? prefName.substring(0, prefName.length() - 1)
                : prefName;
            if (cleanPreferenceName.equals("com.lagradost.cloudstream3") ||
                cleanPreferenceName.equals("rebuild_preference") ||
                cleanPreferenceName.equals(callingPlugin)) {
                return callingPlugin + "_";
            }
            if (cleanPreferenceName.startsWith(callingPlugin + "_")) {
                return cleanPreferenceName + "_";
            }
            String scopedPreferenceName = callingPlugin + "_" + cleanPreferenceName + "_";
            migrateLegacyPreferenceNamespace(prefName, scopedPreferenceName, callingPlugin, cleanPreferenceName);
            return scopedPreferenceName;
        }
        return prefName;
    }

    private void migrateLegacyPreferenceNamespace(String legacyPrefix, String scopedPrefix, String pluginName, String preferenceName) {
        if (legacyPrefix.equals(scopedPrefix)) return;
        String markerInput = pluginName.length() + ":" + pluginName + preferenceName;
        String marker = "__plugin_pref_namespace_v1_" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(markerInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Boolean migrated = DesktopDataStore.INSTANCE.getKey(marker, Boolean.class);
        if (Boolean.TRUE.equals(migrated)) return;

        for (String legacyKey : DesktopDataStore.INSTANCE.getAllKeysWithPrefix(legacyPrefix)) {
            String scopedKey = scopedPrefix + legacyKey.substring(legacyPrefix.length());
            if (DesktopDataStore.INSTANCE.containsKey(scopedKey)) continue;
            Object value = DesktopDataStore.INSTANCE.getKey(legacyKey, Object.class);
            if (value != null) DesktopDataStore.INSTANCE.setKey(scopedKey, value);
        }
        DesktopDataStore.INSTANCE.setKey(marker, true);
    }

    private String getFullKey(String key) {
        return getActualPref() + key;
    }

    @Override
    public Map<String, ?> getAll() {
        String actualPref = getActualPref();
        Map<String, Object> decodedValues = new HashMap<>();
        for (String fullKey : DesktopDataStore.INSTANCE.getAllKeysWithPrefix(actualPref)) {
            String key = fullKey.substring(actualPref.length());
            java.util.concurrent.ConcurrentHashMap<String, com.lagradost.common.storage.PluginSettingSchema> schemas =
                com.lagradost.common.storage.PluginSettingsSchemaRegistry.INSTANCE.getSchemas().get(actualPref);
            com.lagradost.common.storage.PluginSettingSchema schema = schemas == null ? null : schemas.get(key);
            Object value = null;
            if (schema != null) {
                switch (schema.getType()) {
                    case "String": value = getString(key, null); break;
                    case "Boolean": value = getBoolean(key, false); break;
                    case "Int": value = getInt(key, 0); break;
                    case "Long": value = getLong(key, 0L); break;
                    case "Float": value = getFloat(key, 0F); break;
                    case "StringSet": value = getStringSet(key, Collections.emptySet()); break;
                    default: value = DesktopDataStore.INSTANCE.getKey(fullKey, Object.class);
                }
            } else {
                value = DesktopDataStore.INSTANCE.getKey(fullKey, Object.class);
            }
            if (value != null) decodedValues.put(key, value);
        }
        return decodedValues;
    }

    @Override
    public String getString(String key, String defValue) {
        String val = DesktopDataStore.INSTANCE.getKey(getFullKey(key), String.class);
        return val != null ? val : defValue;
    }

    @Override
    public int getInt(String key, int defValue) {
        Integer val = DesktopDataStore.INSTANCE.getKey(getFullKey(key), Integer.class);
        return val != null ? val : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Long val = DesktopDataStore.INSTANCE.getKey(getFullKey(key), Long.class);
        return val != null ? val : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Float val = DesktopDataStore.INSTANCE.getKey(getFullKey(key), Float.class);
        return val != null ? val : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Boolean val = DesktopDataStore.INSTANCE.getKey(getFullKey(key), Boolean.class);
        return val != null ? val : defValue;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Set<String> getStringSet(String key, Set<String> defValue) {
        Set val = DesktopDataStore.INSTANCE.getKey(getFullKey(key), Set.class);
        return val != null ? (Set<String>) val : defValue;
    }

    @Override
    public boolean contains(String key) {
        return com.lagradost.common.storage.DesktopDataStore.INSTANCE.containsKey(getFullKey(key));
    }

    @Override
    public Editor edit() {
        return new DesktopEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    private class DesktopEditor implements Editor {
        private final Map<String, Object> pending = new HashMap<>();

        @Override
        public Editor putString(String key, String value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            pending.put(key, values);
            return this;
        }

        @Override
        public Editor remove(String key) {
            pending.put(key, null);
            return this;
        }

        @Override
        public Editor clear() {
            // Mark all currently stored keys under this prefix for removal on apply()
            pending.put("\u0000__clear__", null);
            return this;
        }

        @Override
        public boolean commit() {
            apply();
            return true;
        }

        @Override
        public void apply() {
            // If clear() was called, remove all keys stored under this prefix first
            if (pending.containsKey("\u0000__clear__")) {
                pending.remove("\u0000__clear__");
                String prefix = getActualPref();
                // Collect all keys from datastore that belong to this preference namespace and remove them
                try {
                    java.util.List<String> toDelete = com.lagradost.common.storage.DesktopDataStore.INSTANCE.getAllKeysWithPrefix(prefix);
                    for (String k : toDelete) {
                        DesktopDataStore.INSTANCE.removeKey(k);
                    }
                } catch (Exception ignored) {}
            }
            for (Map.Entry<String, Object> entry : pending.entrySet()) {
                if (entry.getValue() == null) {
                    DesktopDataStore.INSTANCE.removeKey(getFullKey(entry.getKey()));
                } else {
                    DesktopDataStore.INSTANCE.setKey(getFullKey(entry.getKey()), entry.getValue());
                }
            }
        }
    }
}
