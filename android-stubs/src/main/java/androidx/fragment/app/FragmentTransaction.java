package androidx.fragment.app;

import java.util.ArrayList;
import java.util.List;

@android.annotation.Stub
public class FragmentTransaction {
    private final List<Fragment> addedFragments = new ArrayList<>();

    public FragmentTransaction add(int containerViewId, androidx.fragment.app.Fragment fragment, String tag) {
        if (fragment != null) addedFragments.add(fragment);
        return this;
    }

    public FragmentTransaction add(int containerViewId, androidx.fragment.app.Fragment fragment) {
        if (fragment != null) addedFragments.add(fragment);
        return this;
    }

    public FragmentTransaction add(androidx.fragment.app.Fragment fragment, String tag) {
        if (fragment != null) addedFragments.add(fragment);
        return this;
    }

    public FragmentTransaction replace(int containerViewId, androidx.fragment.app.Fragment fragment, String tag) {
        if (fragment != null) addedFragments.add(fragment);
        return this;
    }

    public FragmentTransaction replace(int containerViewId, androidx.fragment.app.Fragment fragment) {
        if (fragment != null) addedFragments.add(fragment);
        return this;
    }

    public FragmentTransaction remove(androidx.fragment.app.Fragment fragment) {
        return this;
    }

    public FragmentTransaction addToBackStack(String name) {
        return this;
    }

    public int commit() {
        initializePreferenceScreens();
        return 0;
    }

    public int commitAllowingStateLoss() {
        initializePreferenceScreens();
        return 0;
    }

    public void commitNow() {
        initializePreferenceScreens();
    }

    public void commitNowAllowingStateLoss() {
        initializePreferenceScreens();
    }

    private void initializePreferenceScreens() {
        for (Fragment fragment : addedFragments) {
            fragment.initializeForDesktopSettings();
        }
    }
}
