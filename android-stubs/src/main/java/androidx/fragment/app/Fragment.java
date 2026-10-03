package androidx.fragment.app;
import android.content.Context;
@android.annotation.Implemented
public class Fragment {
    public Context getContext() { return android.content.DesktopContextProvider.INSTANCE.getContext(); }
    public void onCreate(android.os.Bundle savedInstanceState) {}

    /** Runs the declaration phase needed to expose AndroidX preference screens on desktop. */
    public void initializeForDesktopSettings() {
        if (this instanceof androidx.preference.PreferenceFragmentCompat) {
            try {
                onCreate(new android.os.Bundle());
            } catch (Throwable error) {
                java.util.logging.Logger.getLogger("CloudStream.PluginSettings")
                    .warning("Could not read plugin preference screen: " + error.getMessage());
            }
        }
    }
}
