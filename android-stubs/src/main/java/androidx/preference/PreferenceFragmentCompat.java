package androidx.preference;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import android.content.Context;
import com.lagradost.common.storage.PluginSettingsSchemaRegistry;
@android.annotation.Implemented
public abstract class PreferenceFragmentCompat extends Fragment {
    private PreferenceManager preferenceManager;
    private PreferenceScreen preferenceScreen;
    
    public abstract void onCreatePreferences(Bundle savedInstanceState, String rootKey);
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context ctx = getContext();
        if (ctx == null) ctx = android.content.DesktopContextProvider.INSTANCE.getContext();
        preferenceManager = new PreferenceManager(ctx);
        // By invoking this, the plugin will populate the preferences
        try {
            onCreatePreferences(savedInstanceState, null);
        } catch(Exception e) {}
    }
    
    public PreferenceManager getPreferenceManager() { return preferenceManager; }
    public PreferenceScreen getPreferenceScreen() { return preferenceScreen; }
    public void setPreferenceScreen(PreferenceScreen preferenceScreen) {
        this.preferenceScreen = preferenceScreen;
        String prefName = PluginSettingsSchemaRegistry.INSTANCE.activePluginPrefName();
        if (prefName == null || prefName.isEmpty()) {
            prefName = this.getClass().getSimpleName().replace("SettingsFragment", "").replace("Settings", "") + "_";
        }
        preferenceScreen.setDesktopPrefName(prefName);
    }
    public void addPreferencesFromResource(int preferencesResId) {
        setPreferencesFromResource(preferencesResId, null);
    }
    
    public void setPreferencesFromResource(int preferencesResId, String key) {
        try {
            Class<?> extLoader = Class.forName("com.lagradost.runtime.loader.ExtensionLoader");
            java.lang.reflect.Method method = extLoader.getMethod("parsePluginPreferences", Object.class, int.class);
            method.invoke(null, this, preferencesResId);
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
}
