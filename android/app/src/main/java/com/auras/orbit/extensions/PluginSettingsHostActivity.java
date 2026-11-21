package com.auras.orbit.extensions;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.auras.orbit.OrbitApplication;

import java.util.ArrayList;
import java.util.List;

/** Private AppCompat context for optional CS3 Configure callbacks and their DialogFragments. */
public final class PluginSettingsHostActivity extends AppCompatActivity {
    public static final String EXTRA_SESSION_TOKEN = "com.auras.orbit.extensions.SETTINGS_SESSION";
    private static final String FRAGMENT_STATE_KEY = "android:support:fragments";
    private static final long EMPTY_HOST_CLOSE_DELAY_MS = 180L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable emptyHostCheck = this::closeIfNoPluginDialog;
    private String sessionToken;
    private boolean callbackAttempted;
    private boolean closeStarted;
    private boolean observedPluginWindowFocusLoss;

    private final FragmentManager.FragmentLifecycleCallbacks fragmentLifecycleCallbacks =
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentDetached(@NonNull FragmentManager fragmentManager,
                                               @NonNull Fragment fragment) {
                    if (fragment instanceof DialogFragment && !closeStarted) scheduleEmptyHostCheck();
                }
            };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        sessionToken = getIntent() == null ? null
                : getIntent().getStringExtra(EXTRA_SESSION_TOKEN);
        OrbitCs3HostServices services = getHostServices();

        // FragmentActivity would restore plugin DialogFragments through its default factory.
        // They may have no public no-arg constructor, so any Activity recreation fails closed.
        if (savedInstanceState != null) {
            if (services != null) services.failClosedPluginSettingsSession(sessionToken);
            super.onCreate(null);
            finish();
            return;
        }
        if (services == null || !services.hasPluginSettingsSession(sessionToken)) {
            super.onCreate(null);
            finish();
            return;
        }

        super.onCreate(null);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        applyTransparentSystemBars(getWindow());
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, true);
        if (!services.attachPluginSettingsHost(sessionToken, this)) {
            closeStarted = true;
            finish();
        }
    }

    private static void applyTransparentSystemBars(Window window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        // For target 36, Android 15+ enforces edge-to-edge and transparent system bars.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            applyLegacyTransparentSystemBarColors(window);
        }
    }

    /** Preserve transparent dialog-host bars on Android 6 through Android 14. */
    @SuppressWarnings("deprecation")
    private static void applyLegacyTransparentSystemBarColors(Window window) {
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (closeStarted || callbackAttempted) return;
        callbackAttempted = true;
        OrbitCs3HostServices services = getHostServices();
        if (services == null || !services.invokePluginSettingsSession(sessionToken, this)) {
            closePluginSettingsNow();
            return;
        }
        try { getSupportFragmentManager().executePendingTransactions(); }
        catch (RuntimeException ignored) { }
        scheduleEmptyHostCheck();
    }

    private void scheduleEmptyHostCheck() {
        mainHandler.removeCallbacks(emptyHostCheck);
        mainHandler.postDelayed(emptyHostCheck, EMPTY_HOST_CLOSE_DELAY_MS);
    }

    private void closeIfNoPluginDialog() {
        if (closeStarted || hasVisiblePluginDialog(getSupportFragmentManager())) return;
        // CS3 plugins may show a regular platform/AppCompat Dialog or another Activity instead of
        // a DialogFragment. Those windows temporarily take focus from this transparent host; keep
        // the host alive until the plugin window closes and focus returns.
        if (!getWindow().getDecorView().hasWindowFocus()) {
            observedPluginWindowFocusLoss = true;
            mainHandler.removeCallbacks(emptyHostCheck);
            mainHandler.postDelayed(emptyHostCheck, 1_000L);
            return;
        }
        observedPluginWindowFocusLoss = false;
        closePluginSettingsNow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!callbackAttempted || closeStarted) return;
        if (!hasFocus) {
            observedPluginWindowFocusLoss = true;
        } else if (observedPluginWindowFocusLoss) {
            observedPluginWindowFocusLoss = false;
            scheduleEmptyHostCheck();
        }
    }

    private boolean hasVisiblePluginDialog(FragmentManager manager) {
        for (Fragment fragment : manager.getFragments()) {
            if (fragment instanceof DialogFragment) {
                DialogFragment dialog = (DialogFragment) fragment;
                if (dialog.isAdded() && dialog.getDialog() != null && dialog.getDialog().isShowing()) {
                    return true;
                }
            }
            if (fragment.isAdded() && hasVisiblePluginDialog(fragment.getChildFragmentManager())) return true;
        }
        return false;
    }

    /** Removes and detaches every plugin DialogFragment synchronously before runtime cleanup proceeds. */
    public boolean closePluginSettingsNow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Plugin settings host closure must run on the main thread.");
        }
        FragmentManager manager = getSupportFragmentManager();
        if (closeStarted && !containsAddedDialog(manager)) {
            OrbitCs3HostServices services = getHostServices();
            if (services != null) services.onPluginSettingsHostClosed(sessionToken, this);
            if (!isFinishing()) finish();
            return true;
        }
        closeStarted = true;
        mainHandler.removeCallbacksAndMessages(null);
        try { manager.executePendingTransactions(); }
        catch (RuntimeException ignored) { }
        for (int pass = 0; pass < 8; pass++) {
            boolean removedDialog = detachDialogFragments(manager);
            if (!containsAddedDialog(manager) || !removedDialog) break;
        }
        if (containsAddedDialog(manager)) {
            // FragmentActivity will detach remaining fragments during destruction. Runtime
            // cleanup remains blocked until onDestroy confirms that detachment completed.
            if (!isFinishing()) finish();
            return false;
        }
        OrbitCs3HostServices services = getHostServices();
        if (services != null) services.onPluginSettingsHostClosed(sessionToken, this);
        if (!isFinishing()) finish();
        return true;
    }

    private boolean containsAddedDialog(FragmentManager manager) {
        for (Fragment fragment : manager.getFragments()) {
            if (fragment instanceof DialogFragment && fragment.isAdded()) return true;
            if (fragment.isAdded() && containsAddedDialog(fragment.getChildFragmentManager())) return true;
        }
        return false;
    }

    private boolean detachDialogFragments(FragmentManager manager) {
        boolean detached = false;
        for (Fragment fragment : new ArrayList<>(manager.getFragments())) {
            if (fragment.isAdded()) detached |= detachDialogFragments(fragment.getChildFragmentManager());
            if (!(fragment instanceof DialogFragment) || !fragment.isAdded()) continue;
            DialogFragment dialog = (DialogFragment) fragment;
            detached = true;
            try {
                if (!manager.isStateSaved()) dialog.dismissNow();
                else manager.beginTransaction().remove(dialog).commitNowAllowingStateLoss();
            } catch (RuntimeException dismissalFailure) {
                try {
                    FragmentTransaction removal = manager.beginTransaction().remove(dialog);
                    removal.commitNowAllowingStateLoss();
                } catch (RuntimeException ignored) { }
            }
        }
        try { manager.executePendingTransactions(); }
        catch (RuntimeException ignored) { }
        return detached;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // The host never persists a plugin fragment, callback, or plugin instance.
        outState.remove(FRAGMENT_STATE_KEY);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(fragmentLifecycleCallbacks);
        boolean removingForConfiguration = isChangingConfigurations();
        super.onDestroy();
        if (!removingForConfiguration && !containsAddedDialog(getSupportFragmentManager())) {
            OrbitCs3HostServices services = getHostServices();
            if (services != null) services.onPluginSettingsHostClosed(sessionToken, this);
        }
    }

    @Nullable
    private OrbitCs3HostServices getHostServices() {
        if (!(getApplication() instanceof OrbitApplication)) return null;
        try { return OrbitCs3HostServices.get(); }
        catch (IllegalStateException notInstalled) { return null; }
    }
}
