package androidx.preference;

import android.content.Context;
import android.util.AttributeSet;
import com.lagradost.common.storage.PluginSettingAction;
import com.lagradost.common.storage.PluginSettingsSchemaRegistry;
import java.util.Map;

@android.annotation.Implemented
public class Preference {
    private String key;
    private CharSequence title;
    private CharSequence summary;
    private Object defaultValue;
    private int order;
    private boolean enabled = true;
    private boolean visible = true;
    private OnPreferenceClickListener clickListener;

    public interface OnPreferenceClickListener {
        boolean onPreferenceClick(Preference preference);
    }

    public Preference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {}
    public Preference(Context context, AttributeSet attrs, int defStyleAttr) {}
    public Preference(Context context, AttributeSet attrs) {}
    public Preference(Context context) {}

    public void setKey(String key) { this.key = key; }
    public String getKey() { return key; }

    public void setTitle(CharSequence title) { this.title = title; }
    public void setTitle(int titleResId) {}
    public CharSequence getTitle() { return title; }

    public void setSummary(CharSequence summary) { this.summary = summary; }
    public void setSummary(int summaryResId) {}
    public CharSequence getSummary() { return summary; }

    public void setDefaultValue(Object defaultValue) { this.defaultValue = defaultValue; }
    public Object getDefaultValue() { return defaultValue; }
    public void setOrder(int order) { this.order = order; }
    public int getOrder() { return order; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void setVisible(boolean visible) { this.visible = visible; }
    public boolean isVisible() { return visible; }
    public void setOnPreferenceClickListener(OnPreferenceClickListener listener) { this.clickListener = listener; }
    public boolean hasOnPreferenceClickListener() { return clickListener != null; }
    public boolean performClick() { return clickListener != null && clickListener.onPreferenceClick(this); }
    
    // Desktop integration: when a preference is added, we can register its type
    protected void registerWithDesktop(String prefName, String type, String category, Map<String, String> options, PluginSettingAction action) {
        if (key != null) {
            PluginSettingsSchemaRegistry.INSTANCE.register(
                prefName, key, type, defaultValue, false, options,
                title == null ? null : title.toString(),
                summary == null ? null : summary.toString(),
                category,
                order,
                getClass().getSimpleName(),
                action
            );
        }
    }
}
