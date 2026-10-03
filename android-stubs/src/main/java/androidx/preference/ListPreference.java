package androidx.preference;
import android.content.Context;
import android.util.AttributeSet;
@android.annotation.Implemented
public class ListPreference extends Preference {
    private CharSequence[] entries;
    private CharSequence[] entryValues;
    public ListPreference(Context c, AttributeSet a) { super(c,a); }
    public ListPreference(Context c) { super(c); }
    public void setEntries(CharSequence[] entries) { this.entries = entries; }
    public CharSequence[] getEntries() { return entries; }
    public void setEntryValues(CharSequence[] entryValues) { this.entryValues = entryValues; }
    public CharSequence[] getEntryValues() { return entryValues; }
    java.util.Map<String, String> getDesktopOptions() {
        if (entries == null || entryValues == null || entries.length != entryValues.length) return null;
        java.util.Map<String, String> options = new java.util.LinkedHashMap<>();
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] != null && entryValues[i] != null) options.put(entries[i].toString(), entryValues[i].toString());
        }
        return options;
    }
}
