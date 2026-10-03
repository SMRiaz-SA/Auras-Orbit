package androidx.preference;
import android.content.Context;
import android.util.AttributeSet;
import com.lagradost.common.storage.PluginSettingAction;
import java.util.ArrayList;
import java.util.List;

@android.annotation.Implemented
public class PreferenceGroup extends Preference {
    private final List<Preference> children = new ArrayList<>();
    private String desktopPrefName;
    private String inheritedCategory;

    public PreferenceGroup(Context c, AttributeSet a, int d, int d2) { super(c,a,d,d2); }
    public PreferenceGroup(Context c, AttributeSet a, int d) { super(c,a,d); }
    public PreferenceGroup(Context c, AttributeSet a) { super(c,a); }
    public PreferenceGroup(Context c) { super(c); }
    
    public boolean addPreference(Preference preference) {
        if (preference == null) return false;
        children.add(preference);
        if (preference instanceof PreferenceGroup) {
            PreferenceGroup group = (PreferenceGroup) preference;
            group.setDesktopContext(getDesktopPrefName(), getCategoryForChildren());
        } else {
            registerPreference(preference);
        }
        return true;
    }

    public int getPreferenceCount() { return children.size(); }
    public Preference getPreference(int index) { return children.get(index); }

    public void setDesktopPrefName(String name) {
        setDesktopContext(name, inheritedCategory);
    }

    private void setDesktopContext(String name, String category) {
        this.desktopPrefName = name;
        this.inheritedCategory = category;
        for (Preference child : children) {
            if (child instanceof PreferenceGroup) {
                ((PreferenceGroup) child).setDesktopContext(name, getCategoryForChildren());
            } else {
                registerPreference(child);
            }
        }
    }

    private String getCategoryForChildren() {
        if (this instanceof PreferenceCategory && getTitle() != null) return getTitle().toString();
        return inheritedCategory;
    }

    private void registerPreference(Preference preference) {
        if (desktopPrefName == null) return;
        String category = getCategoryForChildren();
        if (preference instanceof SwitchPreferenceCompat || preference instanceof CheckBoxPreference) {
            preference.registerWithDesktop(desktopPrefName, "Boolean", category, null, actionFor(preference));
        } else if (preference instanceof EditTextPreference || preference instanceof ListPreference || preference instanceof DropDownPreference) {
            MapHolder.registerString(this, preference, desktopPrefName, category);
        } else if (preference instanceof MultiSelectListPreference) {
            preference.registerWithDesktop(desktopPrefName, "StringSet", category, ((MultiSelectListPreference) preference).getDesktopOptions(), actionFor(preference));
        } else if (preference instanceof SeekBarPreference) {
            preference.registerWithDesktop(desktopPrefName, "Int", category, null, actionFor(preference));
        } else if (preference.getKey() != null && preference.hasOnPreferenceClickListener()) {
            preference.registerWithDesktop(desktopPrefName, "Action", category, null, actionFor(preference));
        }
    }

    private static PluginSettingAction actionFor(Preference preference) {
        if (!preference.hasOnPreferenceClickListener()) return null;
        return () -> preference.performClick();
    }

    protected String getDesktopPrefName() { return desktopPrefName; }

    private static final class MapHolder {
        private static void registerString(PreferenceGroup parent, Preference preference, String prefName, String category) {
            java.util.Map<String, String> options = preference instanceof ListPreference
                ? ((ListPreference) preference).getDesktopOptions()
                : null;
            preference.registerWithDesktop(prefName, "String", category, options, actionFor(preference));
        }
    }
}
