package com.auras.orbit.ui;

import android.app.Instrumentation;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.BuildConfig;
import com.auras.orbit.OrbitApplication;
import com.auras.orbit.navigation.ShellRoute;
import com.auras.orbit.navigation.ShellState;
import com.auras.orbit.profile.LocalProfileStore;
import com.auras.orbit.profile.LocalProfileStore.Profile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Covers system Back routing, nested details, and focus return across the Orbit shell. */
@RunWith(AndroidJUnit4.class)
public final class OrbitShellActivityBackNavigationInstrumentationTest {
    private static final long UI_TIMEOUT_MS = 10_000L;

    private Instrumentation instrumentation;
    private Context target;
    private OrbitShellActivity activity;
    private SharedPreferences profilePreferences;
    private Map<String, Object> profilePreferenceSnapshot;
    private String initialRuntimeProfileId;
    private boolean disposableProfileCreated;

    @Before public void attachToOrbitHomeWithoutClearingTask() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        target = instrumentation.getTargetContext();
        prepareDisposableQueueRenderProfile();
        Intent launch = new Intent(target, OrbitShellActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (OrbitShellActivity) instrumentation.startActivitySync(launch);
        assertNotNull("OrbitShellActivity must launch", activity);
        continueProfileIfNeeded();
        navigateToHomeIfNeeded();
        enterTelevisionRemoteFocusMode();
    }

    @After public void restoreHomeAndKeepOrbitRunning() throws Throwable {
        Throwable cleanupFailure = null;
        try {
            if (activity != null) {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    Intent launch = new Intent(target, OrbitShellActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity = (OrbitShellActivity) instrumentation.startActivitySync(launch);
                }
                continueProfileIfNeeded();
                navigateToHomeIfNeeded();
                assertFalse("test cleanup must leave Orbit running", activity.isFinishing());
            }
        } catch (Throwable error) {
            cleanupFailure = error;
        } finally {
            if (disposableProfileCreated && activity != null) {
                try {
                    OrbitShellActivity seededActivity = activity;
                    if (!seededActivity.isDestroyed()) {
                        if (!seededActivity.isFinishing()) {
                            instrumentation.runOnMainSync(seededActivity::finish);
                        }
                        awaitUi("the disposable-profile shell must finish before preference restoration",
                                seededActivity::isDestroyed);
                        instrumentation.waitForIdleSync();
                    }
                } catch (Throwable error) {
                    cleanupFailure = retainFirstFailure(cleanupFailure, error);
                }
            }
            try {
                restoreProfilePreferences();
            } catch (Throwable error) {
                cleanupFailure = retainFirstFailure(cleanupFailure, error);
            }
            if (disposableProfileCreated) {
                try {
                    OrbitApplication application = (OrbitApplication) target.getApplicationContext();
                    assertTrue("the runtime must return to its pre-test profile",
                            application.getCS3PluginRuntime().reconcileProfile(
                                    initialRuntimeProfileId, application.getCloudStreamExtensionManager()).isEmpty());
                    Intent launch = new Intent(target, OrbitShellActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity = (OrbitShellActivity) instrumentation.startActivitySync(launch);
                    awaitUi("the original empty profile picker must be restored",
                            () -> findTag("profile_create") != null);
                } catch (Throwable error) {
                    cleanupFailure = retainFirstFailure(cleanupFailure, error);
                }
            }
        }
        if (cleanupFailure != null) throw cleanupFailure;
    }

    private static Throwable retainFirstFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    @Test public void backFromSearchReturnsToHomeWithoutFinishingActivity() {
        openRoute(ShellRoute.SEARCH);
        awaitUi("Search route", () -> currentRoute() == ShellRoute.SEARCH);

        sendSystemBackAndAwait("Home route", () -> currentRoute() == ShellRoute.HOME);

        assertFalse("Back from Search must return to Home without finishing Orbit",
                activity.isFinishing());
        assertEquals("Back from Search must select the Home route", ShellRoute.HOME, currentRoute());
        assertNotNull("Home navigation must be present after Back", findTag("nav_HOME"));
        assertTvFocusReturnsTo("home_provider_selector");
    }

    @Test public void systemBackClosesSeededHomeQuickSearchAndReturnsTvFocus() {
        setBooleanField("homeSearchOpen", true);
        invokeRender();

        String emptyState = "Choose a Home source with quick search to continue.";
        awaitUi("Home quick-search empty overlay", () -> findText(emptyState) != null);
        assertTrue("the seeded Home quick-search overlay must be open",
                readBooleanField("homeSearchOpen"));

        sendSystemBackAndAwait("Home quick-search overlay after Back",
                () -> !readBooleanField("homeSearchOpen") && findText(emptyState) == null);

        assertEquals("Back from Home quick search must remain on Home",
                ShellRoute.HOME, currentRoute());
        assertFalse("Back must close the seeded quick-search overlay",
                readBooleanField("homeSearchOpen"));
        assertNull("the quick-search empty-state message must be removed",
                findText(emptyState));
        assertTvFocusReturnsTo("home_header_search");
    }

    @Test public void systemBackClosesSettingsDetailAndReturnsTvFocus() {
        openRoute(ShellRoute.SETTINGS);
        String categoryTag = "settings_category_Appearance & language";
        View category = findTag(categoryTag);
        assertNotNull("Settings must expose the Appearance category", category);
        click(category);
        awaitUi("Appearance settings detail", () -> findText("Theme and layout") != null);
        assertTrue("category selection must open the nested Settings detail",
                readBooleanField("settingsDetailOpen"));

        sendSystemBackAndAwait("Settings category after Back",
                () -> !readBooleanField("settingsDetailOpen"));

        assertEquals("Back from Settings detail must remain in Settings",
                ShellRoute.SETTINGS, currentRoute());
        assertFalse("Back must close the nested Settings detail",
                readBooleanField("settingsDetailOpen"));
        assertNotNull("the selected Settings category must remain available",
                findTag(categoryTag));
        assertTvFocusReturnsTo(categoryTag);
    }

    @Test public void systemBackClosesDownloadQueueAndReturnsTvFocus() {
        openRoute(ShellRoute.DOWNLOADS);
        View queueEntry = findTag("downloads_queue_entry");
        assertNotNull("Downloads must expose the queue entry", queueEntry);
        click(queueEntry);
        awaitUi("Download Queue detail", () -> findTag("downloads_queue_back") != null);

        sendSystemBackAndAwait("Downloads queue entry after Back",
                () -> findTag("downloads_queue_back") == null
                        && findTag("downloads_queue_entry") != null);

        assertEquals("Back from Download Queue must remain in Downloads",
                ShellRoute.DOWNLOADS, currentRoute());
        assertNotNull("the queue entry must return to Downloads", findTag("downloads_queue_entry"));
        assertTvFocusReturnsTo("downloads_queue_entry");
    }

    @Test public void systemBackClosesLibraryListDetailAndReturnsTvFocus() {
        openRoute(ShellRoute.LIBRARY);
        View favorites = findTag("library_status_Favorites");
        assertNotNull("Library must expose the Favorites list", favorites);
        click(favorites);
        awaitUi("Favorites list detail", () -> findTag("library_list_back") != null);

        sendSystemBackAndAwait("Library overview after Back",
                () -> findTag("library_list_back") == null
                        && findTag("library_status_Favorites") != null);

        assertEquals("Back from a Library list detail must remain in Library",
                ShellRoute.LIBRARY, currentRoute());
        assertNotNull("Favorites must return to the Library overview",
                findTag("library_status_Favorites"));
        assertTvFocusReturnsTo("library_status_Favorites");
    }

    @Test public void activityRecreationRestoresNestedSettingsAndBackBehavior() throws Exception {
        openRoute(ShellRoute.SETTINGS);
        String appearanceTag = "settings_category_Appearance & language";
        View appearanceCategory = findTag(appearanceTag);
        assertNotNull("Settings must expose its Appearance category before recreation", appearanceCategory);
        click(appearanceCategory);
        awaitUi("Appearance settings detail before recreation",
                () -> findText("Theme and layout") != null
                        && readBooleanField("settingsDetailOpen"));

        String profileId = ((OrbitApplication) target.getApplicationContext())
                .getLocalProfileStore().getActiveProfileId();
        OrbitShellActivity oldActivity = activity;
        recreateActivityAndAwaitSavedState(oldActivity);

        awaitUi("the recreated Activity must restore nested Settings", () ->
                activity != oldActivity && !activity.isDestroyed()
                        && currentRoute() == ShellRoute.SETTINGS
                        && readBooleanField("settingsDetailOpen")
                        && findText("Theme and layout") != null);
        assertTrue("Activity.recreate must destroy the saved instance", oldActivity.isDestroyed());
        assertEquals("route restoration remains scoped to the active profile", profileId,
                ((OrbitApplication) target.getApplicationContext())
                        .getLocalProfileStore().getActiveProfileId());

        sendSystemBackAndAwait("restored Settings hub after Back",
                () -> !readBooleanField("settingsDetailOpen") && findTag(appearanceTag) != null);
        assertEquals("Back after recreation must remain in Settings",
                ShellRoute.SETTINGS, currentRoute());
        assertTvFocusReturnsTo(appearanceTag);
    }

    @Test public void continueWatchingEmptyStateHasNoCardBackground() {
        String copy = "Nothing to continue yet. Partially watched titles will appear here after a supported player records progress.";
        View message = findText(copy);
        assertNotNull("Home must retain the Continue Watching empty-state copy", message);
        View container = (View) message.getParent();
        assertTrue("Continue Watching empty state must have no visible card fill or outline",
                container.getBackground() == null
                        || (container.getBackground() instanceof ColorDrawable
                        && ((ColorDrawable) container.getBackground()).getColor() == Color.TRANSPARENT));

        int inset = Math.round((isTelevision() ? 8f : 14f)
                * target.getResources().getDisplayMetrics().density);
        assertEquals("Continue Watching copy keeps its left placement", inset,
                container.getPaddingLeft());
        assertEquals("Continue Watching copy keeps its top placement", inset,
                container.getPaddingTop());
    }

    private void continueProfileIfNeeded() {
        awaitUi("Orbit shell or profile picker", () -> findTag("nav_SEARCH") != null
                || findText("Continue") != null);
        View continueButton = findText("Continue");
        if (continueButton != null) click(continueButton);
        awaitUi("confirmed Orbit shell", () -> findTag("nav_SEARCH") != null);
    }

    private void recreateActivityAndAwaitSavedState(OrbitShellActivity oldActivity) throws Exception {
        Application application = (Application) oldActivity.getApplication();
        CountDownLatch recreated = new CountDownLatch(1);
        AtomicReference<Boolean> receivedSavedState = new AtomicReference<>(false);
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity created, Bundle savedInstanceState) {
                if (created instanceof OrbitShellActivity && created != oldActivity) {
                    activity = (OrbitShellActivity) created;
                    receivedSavedState.set(savedInstanceState != null);
                    recreated.countDown();
                }
            }
            @Override public void onActivityStarted(Activity created) { }
            @Override public void onActivityResumed(Activity created) { }
            @Override public void onActivityPaused(Activity created) { }
            @Override public void onActivityStopped(Activity created) { }
            @Override public void onActivitySaveInstanceState(Activity created, Bundle state) { }
            @Override public void onActivityDestroyed(Activity created) { }
        };
        application.registerActivityLifecycleCallbacks(callbacks);
        try {
            instrumentation.runOnMainSync(oldActivity::recreate);
            assertTrue("the replacement Orbit Activity must be created",
                    recreated.await(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertTrue("the replacement Activity must receive the saved presentation bundle",
                    receivedSavedState.get());
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks);
        }
    }

    private void prepareDisposableQueueRenderProfile() {
        assertEquals("back-navigation coverage must target the disposable QueueRender package",
                "com.auras.orbit.queuerender", target.getPackageName());
        assertFalse("the CS3 canary gate must remain disabled", BuildConfig.CS3_CANARY_BUILD);
        assertTrue("the QueueRender target must expose its OrbitApplication instance",
                target.getApplicationContext() instanceof OrbitApplication);

        OrbitApplication application = (OrbitApplication) target.getApplicationContext();
        LocalProfileStore profileStore = application.getLocalProfileStore();
        initialRuntimeProfileId = application.getCS3PluginRuntime().getActiveProfileId();
        profilePreferences = readLocalProfilePreferences(profileStore);
        profilePreferenceSnapshot = snapshotPreferences(profilePreferences);
        Profile activeProfile = profileStore.getActiveProfile();
        if (activeProfile == null) {
            String unresolvedActiveId = profilePreferences.getString("active_profile_id", "");
            String rawProfilesJson = profilePreferences.getString("profiles_json", null);
            assertTrue("an unresolved persisted active ID must be preserved",
                    unresolvedActiveId == null || unresolvedActiveId.isEmpty());
            assertTrue("only an absent or literal empty profile list may be seeded",
                    rawProfilesJson == null || "[]".equals(rawProfilesJson.trim()));
            assertTrue("a missing active profile is safe to seed only in an empty QueueRender package",
                    profileStore.getProfiles().isEmpty());
            Profile seedProfile = profileStore.createProfile("REV005 QueueRender Back Navigation");
            assertNotNull("the isolated test package must accept its disposable profile", seedProfile);
            disposableProfileCreated = true;
            profileStore.selectProfile(seedProfile.id);
            activeProfile = profileStore.getActiveProfile();
        }
        assertNotNull("the QueueRender package must retain its active profile", activeProfile);
        assertFalse("the active profile ID must be nonempty", activeProfile.id.trim().isEmpty());
        assertEquals("the captured profile must be the persisted active profile",
                activeProfile.id, profileStore.getActiveProfileId());
    }

    private Map<String, Object> snapshotPreferences(SharedPreferences preferences) {
        Map<String, Object> snapshot = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set<?>) value = new HashSet<>((Set<?>) value);
            snapshot.put(entry.getKey(), value);
        }
        return snapshot;
    }

    private void restoreProfilePreferences() {
        if (profilePreferences == null || profilePreferenceSnapshot == null) return;
        SharedPreferences.Editor editor = profilePreferences.edit().clear();
        for (Map.Entry<String, Object> entry : profilePreferenceSnapshot.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set<?>) {
                Set<String> strings = new HashSet<>();
                for (Object item : (Set<?>) value) {
                    if (item instanceof String) strings.add((String) item);
                }
                editor.putStringSet(key, strings);
            } else {
                throw new AssertionError("Unsupported profile preference type for key " + key);
            }
        }
        assertTrue("QueueRender profile preferences must return to their pre-test values", editor.commit());
    }

    private SharedPreferences readLocalProfilePreferences(LocalProfileStore profileStore) {
        try {
            Field field = LocalProfileStore.class.getDeclaredField("preferences");
            field.setAccessible(true);
            return (SharedPreferences) field.get(profileStore);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not verify raw local profile state before test setup", error);
        }
    }

    private void navigateToHomeIfNeeded() {
        if (currentRoute() == ShellRoute.HOME) return;
        View homeNavigation = findTag("nav_HOME");
        assertNotNull("Orbit must expose Home navigation", homeNavigation);
        click(homeNavigation);
        awaitUi("Orbit Home", () -> currentRoute() == ShellRoute.HOME
                && findTag("nav_SEARCH") != null);
    }

    private void enterTelevisionRemoteFocusMode() {
        if (!isTelevision()) return;
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        awaitUi("TV remote focus mode", () -> !isInTouchMode());
        assertFalse("TV Back and focus coverage must run outside touch mode", isInTouchMode());
    }

    private void openRoute(ShellRoute route) {
        View navigation = findTag("nav_" + route.name());
        assertNotNull("Orbit must expose navigation for " + route.name(), navigation);
        click(navigation);
        awaitUi(route + " route", () -> currentRoute() == route);
    }

    private void sendSystemBackAndAwait(String resultDescription, UiCondition result) {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitUi(resultDescription, () -> activity.isFinishing() || result.isMet());
        assertFalse("system Back must not finish Orbit", activity.isFinishing());
    }

    private void assertTvFocusReturnsTo(String expectedTag) {
        if (!isTelevision()) return;
        long deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            if (taggedViewHasFocus(expectedTag)) break;
            SystemClock.sleep(50L);
        }
        assertTrue("TV Back must restore focus to the originating control; current focus: "
                        + currentFocusDescription() + "; target: " + taggedViewDescription(expectedTag),
                taggedViewHasFocus(expectedTag));
    }

    private boolean isTelevision() {
        return target.getPackageManager().hasSystemFeature("android.software.leanback");
    }

    private boolean isInTouchMode() {
        AtomicReference<Boolean> inTouchMode = new AtomicReference<>(true);
        instrumentation.runOnMainSync(() -> inTouchMode.set(
                activity.getWindow().getDecorView().isInTouchMode()));
        return inTouchMode.get();
    }

    private boolean readBooleanField(String name) {
        try {
            Field field = OrbitShellActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(activity);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not inspect Orbit state " + name, error);
        }
    }

    private void setBooleanField(String name, boolean value) {
        instrumentation.runOnMainSync(() -> {
            try {
                Field field = OrbitShellActivity.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setBoolean(activity, value);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Could not seed Orbit state " + name, error);
            }
        });
    }

    private void invokeRender() {
        instrumentation.runOnMainSync(() -> {
            try {
                Method render = OrbitShellActivity.class.getDeclaredMethod("render");
                render.setAccessible(true);
                render.invoke(activity);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Could not render the seeded Home quick-search overlay", error);
            }
        });
    }

    private boolean taggedViewHasFocus(String expectedTag) {
        View targetView = findTag(expectedTag);
        if (targetView == null) return false;
        AtomicReference<Boolean> focused = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> focused.set(targetView.hasFocus()));
        return focused.get();
    }

    private String currentFocusDescription() {
        AtomicReference<String> description = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            View focused = activity.getCurrentFocus();
            if (focused == null) {
                description.set("none");
                return;
            }
            Object tag = focused.getTag();
            CharSequence contentDescription = focused.getContentDescription();
            String text = focused instanceof TextView
                    ? ((TextView) focused).getText().toString() : null;
            description.set(focused.getClass().getSimpleName()
                    + " tag=" + tag
                    + " contentDescription=" + contentDescription
                    + " text=" + text);
        });
        return description.get();
    }

    private String taggedViewDescription(String expectedTag) {
        View targetView = findTag(expectedTag);
        if (targetView == null) return "missing tag " + expectedTag;
        AtomicReference<String> description = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> description.set(
                targetView.getClass().getSimpleName()
                        + " focusable=" + targetView.isFocusable()
                        + " focused=" + targetView.isFocused()
                        + " hasFocus=" + targetView.hasFocus()
                        + " shown=" + targetView.isShown()
                        + " visibility=" + targetView.getVisibility()
                        + " windowFocus=" + targetView.hasWindowFocus()
                        + " focusableInTouchMode=" + targetView.isFocusableInTouchMode()
                        + " touchMode=" + activity.getWindow().getDecorView().isInTouchMode()));
        return description.get();
    }

    private ShellRoute currentRoute() {
        AtomicReference<ShellRoute> route = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                Field field = OrbitShellActivity.class.getDeclaredField("shellState");
                field.setAccessible(true);
                route.set(((ShellState) field.get(activity)).getRoute());
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Could not inspect Orbit shellState", error);
            }
        });
        return route.get();
    }

    private View findText(String expected) {
        AtomicReference<View> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(
                findText(activity.getWindow().getDecorView(), expected)));
        return result.get();
    }

    private static View findText(View view, String expected) {
        if (view instanceof TextView
                && expected.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View result = findText(group.getChildAt(index), expected);
                if (result != null) return result;
            }
        }
        return null;
    }

    private View findTag(String expected) {
        AtomicReference<View> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(
                findTag(activity.getWindow().getDecorView(), expected)));
        return result.get();
    }

    private static View findTag(View view, String expected) {
        if (expected.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View result = findTag(group.getChildAt(index), expected);
                if (result != null) return result;
            }
        }
        return null;
    }

    private void click(View view) {
        assertNotNull("Expected Orbit control must be visible", view);
        AtomicReference<Boolean> clicked = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> clicked.set(view.performClick()));
        assertTrue("Orbit control must accept a click", clicked.get());
    }

    private void awaitUi(String description, UiCondition condition) {
        long deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS;
        do {
            instrumentation.waitForIdleSync();
            if (condition.isMet()) return;
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("Timed out waiting for " + description, condition.isMet());
    }

    private interface UiCondition {
        boolean isMet();
    }
}
