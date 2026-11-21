package com.auras.orbit.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.auras.orbit.BuildConfig;
import com.auras.orbit.OrbitApplication;
import com.auras.orbit.navigation.ShellRoute;
import com.auras.orbit.navigation.ShellState;
import com.auras.orbit.profile.LocalProfileStore;
import com.auras.orbit.profile.LocalProfileStore.Profile;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises prolonged directional input and visible route navigation on the live Orbit shell. */
@RunWith(AndroidJUnit4.class)
public final class OrbitShellEnduranceInstrumentationTest {
    private static final long UI_TIMEOUT_MS = 15_000L;
    private static final int DPAD_KEY_EVENT_COUNT = 500;
    private static final int DPAD_KEY_EVENT_BATCH_SIZE = 50;
    private static final int ROUTE_CYCLE_COUNT = 200;
    private static final ShellRoute[] ROUTE_CYCLE = {
            ShellRoute.SEARCH,
            ShellRoute.LIBRARY,
            ShellRoute.DOWNLOADS,
            ShellRoute.SETTINGS,
            ShellRoute.HOME
    };
    private static final int[] DPAD_DIRECTIONS = {
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT
    };

    private Instrumentation instrumentation;
    private Context target;
    private OrbitApplication application;
    private OrbitShellActivity activity;
    private Field shellStateField;
    private Field selectedProfileIdField;
    private LocalProfileStore profileStore;
    private String activeProfileId;

    @Before public void launchLiveOrbitShell() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        target = instrumentation.getTargetContext();
        assertEquals("endurance coverage must target the disposable queue-render package",
                "com.auras.orbit.queuerender", target.getPackageName());
        assertFalse("the CS3 canary gate must remain disabled", BuildConfig.CS3_CANARY_BUILD);

        // Keep any existing active profile unchanged. A fresh QueueRender test package has its
        // own empty profile store, so seed one disposable profile before opening the shell.
        assertTrue("the QueueRender target must expose its OrbitApplication instance",
                target.getApplicationContext() instanceof OrbitApplication);
        application = (OrbitApplication) target.getApplicationContext();
        profileStore = application.getLocalProfileStore();
        Profile activeProfile = profileStore.getActiveProfile();
        if (activeProfile == null) {
            SharedPreferences profilePreferences = readLocalProfilePreferences();
            String unresolvedActiveId = profilePreferences.getString("active_profile_id", "");
            String rawProfilesJson = profilePreferences.getString("profiles_json", null);
            assertTrue("an unresolved persisted active ID must be preserved",
                    unresolvedActiveId == null || unresolvedActiveId.isEmpty());
            assertTrue("only an absent or literal empty profile list may be seeded",
                    rawProfilesJson == null || "[]".equals(rawProfilesJson.trim()));
            assertTrue("a missing active profile is safe to seed only in an empty QueueRender package",
                    profileStore.getProfiles().isEmpty());
            Profile seedProfile = profileStore.createProfile("REV005 QueueRender Endurance");
            assertNotNull("the isolated test package must accept its disposable profile", seedProfile);
            profileStore.selectProfile(seedProfile.id);
            activeProfile = profileStore.getActiveProfile();
        }
        assertNotNull("the QueueRender package must retain its active profile", activeProfile);
        assertFalse("the active profile ID must be nonempty", activeProfile.id.trim().isEmpty());
        activeProfileId = activeProfile.id;
        assertEquals("the captured profile must be the persisted active profile",
                activeProfileId, profileStore.getActiveProfileId());

        String launchAction = "com.auras.orbit.test.ENDURANCE_SETUP." + System.nanoTime();
        Intent launch = new Intent(target, OrbitShellActivity.class)
                .setAction(launchAction)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = launchOrReuseShell(launch);
        assertNotNull("the actual OrbitShellActivity must launch", activity);
        try {
            shellStateField = OrbitShellActivity.class.getDeclaredField("shellState");
            shellStateField.setAccessible(true);
            selectedProfileIdField = OrbitShellActivity.class.getDeclaredField("selectedProfileId");
            selectedProfileIdField.setAccessible(true);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not access the live Orbit shell route state", error);
        }

        prepareConfirmedHomeShell();
        assertEquals("launching the shell must not change the active profile",
                activeProfileId, profileStore.getActiveProfileId());
        assertTrue("the already-active profile must open the live shell on Home",
                routeIsVisibleAndSelected(ShellRoute.HOME));
    }

    private void prepareConfirmedHomeShell() {
        boolean shellOrProfilePickerReady = waitForUi(
                () -> shellNavigationIsVisible() || profilePickerIsVisible());
        assertTrue("the live shell or its profile picker must be visible",
                shellOrProfilePickerReady);
        if (profilePickerIsVisible()) {
            // A newly created Activity asks the user to confirm a profile even when the local
            // store already has one active. Select that exact persisted profile in the real UI,
            // then continue only after verifying the target ID matches the captured active ID.
            View activeProfileRow = findTag("profile_select_" + activeProfileId);
            assertNotNull("the profile picker must show the already-active QueueRender profile",
                    activeProfileRow);
            instrumentation.runOnMainSync(() -> assertTrue(
                    "the existing active profile row must be selectable",
                    activeProfileRow.performClick()));
            assertEquals("the UI selection must remain the already-active profile",
                    activeProfileId, selectedProfileIdOnMain());
            assertEquals("selecting the same profile must not alter the persisted active ID",
                    activeProfileId, profileStore.getActiveProfileId());
            View continueButton = findTag("profile_continue");
            assertNotNull("the selected active profile must expose the Continue action", continueButton);
            instrumentation.runOnMainSync(() -> assertTrue(
                    "Continue must confirm the same active profile into Orbit",
                    continueButton.performClick()));
        } else if (currentRoute() != ShellRoute.HOME) {
            // OrbitShellActivity is singleTask: a second launch can return its existing
            // Search/Library/Downloads/Settings route through onNewIntent. Normalize the
            // confirmed live shell using its visible Home control before starting endurance.
            clickNavigation(ShellRoute.HOME);
        }
        boolean confirmedHomeShellReady = waitForUi(() -> routeIsVisibleAndSelected(ShellRoute.HOME)
                && findTag("nav_SEARCH") != null);
        assertTrue("the already-active profile must open the live shell on Home",
                confirmedHomeShellReady);
    }

    @Test public void dpadInputAndTwoHundredLiveRouteCyclesRemainResponsive() {
        UiAutomation uiAutomation = instrumentation.getUiAutomation();
        focusVisibleHomeNavigation();
        assertFocusAndForeground();

        int injectedEvents = injectDpadKeyEvents(uiAutomation);
        assertEquals("the stress session injects exactly 500 DPAD KeyEvent objects",
                DPAD_KEY_EVENT_COUNT, injectedEvents);
        assertFocusAndForeground();

        int completedTransitions = 0;
        for (int cycle = 1; cycle <= ROUTE_CYCLE_COUNT; cycle++) {
            for (ShellRoute route : ROUTE_CYCLE) {
                clickNavigation(route);
                assertEquals("cycle " + cycle + " reaches " + route,
                        route, currentRoute());
                completedTransitions++;
            }
            if (cycle % 25 == 0) assertApplicationForeground();
        }

        assertEquals("each cycle visits all five top-level routes",
                ROUTE_CYCLE_COUNT * ROUTE_CYCLE.length, completedTransitions);
        assertEquals("the final route cycle returns the live shell to Home",
                ShellRoute.HOME, currentRoute());
        assertTrue("Home remains selected and visible after the full route session",
                routeIsVisibleAndSelected(ShellRoute.HOME));
        assertApplicationForeground();
        assertEquals("the stress session must preserve the pre-provisioned active profile",
                activeProfileId, profileStore.getActiveProfileId());
    }

    @Test public void setupReturnsAnExistingSearchShellToHome() {
        clickNavigation(ShellRoute.SEARCH);
        assertEquals("the regression fixture must start from Search", ShellRoute.SEARCH, currentRoute());
        OrbitShellActivity existingShell = activity;
        String relaunchAction = "com.auras.orbit.test.ENDURANCE_RELAUNCH." + System.nanoTime();
        Intent relaunch = new Intent(target, OrbitShellActivity.class)
                .setAction(relaunchAction)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = launchOrReuseShell(relaunch);
        assertTrue("singleTask launch must reuse the existing Orbit shell Activity",
                activity == existingShell);
        assertTrue("singleTask onNewIntent must receive the new request while retaining Search",
                waitForUi(() -> activityIntentActionIs(activity, relaunchAction)
                        && currentRoute() == ShellRoute.SEARCH));

        prepareConfirmedHomeShell();

        assertEquals("test setup must normalize the reused shell to Home",
                ShellRoute.HOME, currentRoute());
        assertEquals("test setup must preserve the active profile", activeProfileId,
                profileStore.getActiveProfileId());
    }

    private int injectDpadKeyEvents(UiAutomation uiAutomation) {
        int injectedEvents = 0;
        int keyPressCount = DPAD_KEY_EVENT_COUNT / 2;
        for (int press = 0; press < keyPressCount; press++) {
            int keyCode = DPAD_DIRECTIONS[press % DPAD_DIRECTIONS.length];
            long downTime = SystemClock.uptimeMillis();
            injectDpadEvent(uiAutomation, downTime, downTime,
                    KeyEvent.ACTION_DOWN, keyCode, "down", press);
            injectedEvents++;

            long upTime = SystemClock.uptimeMillis();
            injectDpadEvent(uiAutomation, downTime, upTime,
                    KeyEvent.ACTION_UP, keyCode, "up", press);
            injectedEvents++;

            if (injectedEvents % DPAD_KEY_EVENT_BATCH_SIZE == 0) {
                instrumentation.waitForIdleSync();
                assertFocusAndForeground();
            }
        }
        return injectedEvents;
    }

    private OrbitShellActivity launchOrReuseShell(Intent launch) {
        OrbitShellActivity existing = findLiveShellActivity();
        if (existing == null) {
            return (OrbitShellActivity) instrumentation.startActivitySync(launch);
        }

        target.startActivity(launch);
        AtomicReference<OrbitShellActivity> resumed = new AtomicReference<>();
        assertTrue("the existing singleTask Orbit Activity must receive the new intent and resume",
                waitForUi(() -> {
                    OrbitShellActivity current = resumedShellActivity();
                    if (current == null || !activityIntentActionIs(current, launch.getAction())) return false;
                    resumed.set(current);
                    return true;
                }));
        assertTrue("the live singleTask Activity identity must be preserved", resumed.get() == existing);
        return resumed.get();
    }

    private OrbitShellActivity findLiveShellActivity() {
        AtomicReference<OrbitShellActivity> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            for (Stage stage : Stage.values()) {
                if ("DESTROYED".equals(stage.name())) continue;
                for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(stage)) {
                    if (candidate instanceof OrbitShellActivity
                            && !candidate.isFinishing() && !candidate.isDestroyed()) {
                        result.set((OrbitShellActivity) candidate);
                        return;
                    }
                }
            }
        });
        return result.get();
    }

    private OrbitShellActivity resumedShellActivity() {
        AtomicReference<OrbitShellActivity> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (candidate instanceof OrbitShellActivity
                        && !candidate.isFinishing() && !candidate.isDestroyed()) {
                    result.set((OrbitShellActivity) candidate);
                    return;
                }
            }
        });
        return result.get();
    }

    private void injectDpadEvent(UiAutomation uiAutomation, long downTime, long eventTime,
                                 int action, int keyCode, String actionName, int press) {
        KeyEvent event = new KeyEvent(downTime, eventTime, action, keyCode, 0);
        assertTrue("UiAutomation must inject DPAD " + actionName + " event " + (press + 1),
                uiAutomation.injectInputEvent(event, true));
    }

    private void focusVisibleHomeNavigation() {
        AtomicReference<Boolean> focused = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            View home = findTag(activity.getWindow().getDecorView(), "nav_HOME");
            if (home == null || !home.isShown() || !home.isEnabled()) return;
            focused.set(home.requestFocus() || home.requestFocusFromTouch());
        });
        assertTrue("the live Home navigation control must establish a valid initial focus",
                focused.get());
    }

    private void assertFocusAndForeground() {
        AtomicReference<Boolean> validFocus = new AtomicReference<>(false);
        AtomicReference<String> focusDescription = new AtomicReference<>("none");
        instrumentation.runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView();
            View focused = activity.getCurrentFocus();
            if (focused == null) return;
            focusDescription.set(focused.getClass().getSimpleName() + " tag=" + focused.getTag()
                    + " shown=" + focused.isShown() + " focusable=" + focused.isFocusable());
            validFocus.set(!activity.isFinishing() && !activity.isDestroyed()
                    && activity.hasWindowFocus() && focused.hasFocus() && focused.isFocusable()
                    && focused.isShown() && focused.getRootView() == decor);
        });
        assertTrue("focus must remain valid in the foreground Orbit window: "
                + focusDescription.get(), validFocus.get());
        assertApplicationForeground();
    }

    private void assertApplicationForeground() {
        assertFalse("OrbitShellActivity must remain open", activity.isFinishing());
        assertFalse("OrbitShellActivity must remain alive", activity.isDestroyed());
        assertTrue("OrbitShellActivity must remain resumed", resumedShellActivity() == activity);
        assertTrue("OrbitShellActivity must retain window focus", activity.hasWindowFocus());
        assertEquals("the foreground Activity must belong to the QueueRender package",
                target.getPackageName(), activity.getPackageName());
    }

    private void clickNavigation(ShellRoute route) {
        String tag = "nav_" + route.name();
        AtomicReference<Boolean> clicked = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            View navigation = findTag(activity.getWindow().getDecorView(), tag);
            if (navigation == null || !navigation.isShown()
                    || navigation.getVisibility() != View.VISIBLE || !navigation.isEnabled()) return;
            clicked.set(navigation.performClick());
        });
        assertTrue("the visible top-level " + route + " navigation button must accept a click", clicked.get());
        awaitUi("rendered " + route + " route", () -> routeIsVisibleAndSelected(route));
    }

    private boolean shellNavigationIsVisible() {
        AtomicReference<Boolean> visible = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            View home = findTag(activity.getWindow().getDecorView(), "nav_HOME");
            View search = findTag(activity.getWindow().getDecorView(), "nav_SEARCH");
            visible.set(home != null && home.isShown() && home.isEnabled()
                    && search != null && search.isShown() && search.isEnabled());
        });
        return visible.get();
    }

    private boolean profilePickerIsVisible() {
        View continueButton = findTag("profile_continue");
        View activeProfileRow = findTag("profile_select_" + activeProfileId);
        return (continueButton != null && continueButton.isShown())
                || (activeProfileRow != null && activeProfileRow.isShown());
    }

    private boolean activityIntentActionIs(OrbitShellActivity shell, String expectedAction) {
        AtomicReference<Boolean> matches = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            Intent currentIntent = shell.getIntent();
            matches.set(currentIntent != null && expectedAction.equals(currentIntent.getAction()));
        });
        return matches.get();
    }

    private boolean routeIsVisibleAndSelected(ShellRoute expectedRoute) {
        AtomicReference<Boolean> reached = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            View navigation = findTag(activity.getWindow().getDecorView(), "nav_" + expectedRoute.name());
            ShellState state = readShellStateOnMain();
            reached.set(state != null && state.getRoute() == expectedRoute
                    && navigation != null && navigation.isShown()
                    && navigation.getVisibility() == View.VISIBLE && navigation.isSelected());
        });
        return reached.get();
    }

    private ShellRoute currentRoute() {
        AtomicReference<ShellRoute> route = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            ShellState state = readShellStateOnMain();
            if (state != null) route.set(state.getRoute());
        });
        return route.get();
    }

    private ShellState readShellStateOnMain() {
        try {
            return (ShellState) shellStateField.get(activity);
        } catch (IllegalAccessException error) {
            throw new AssertionError("Could not inspect the live Orbit shell route", error);
        }
    }

    private View findTag(String expectedTag) {
        AtomicReference<View> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(
                findTag(activity.getWindow().getDecorView(), expectedTag)));
        return result.get();
    }

    private String selectedProfileIdOnMain() {
        AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                result.set((String) selectedProfileIdField.get(activity));
            } catch (IllegalAccessException error) {
                throw new AssertionError("Could not inspect the UI-selected profile", error);
            }
        });
        return result.get();
    }

    private SharedPreferences readLocalProfilePreferences() {
        try {
            Field field = LocalProfileStore.class.getDeclaredField("preferences");
            field.setAccessible(true);
            return (SharedPreferences) field.get(profileStore);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not verify raw local profile state before test setup", error);
        }
    }

    private static View findTag(View view, String expectedTag) {
        if (expectedTag.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View result = findTag(group.getChildAt(index), expectedTag);
                if (result != null) return result;
            }
        }
        return null;
    }

    private void awaitUi(String description, UiCondition condition) {
        assertTrue("Timed out waiting for " + description, waitForUi(condition));
    }

    private boolean waitForUi(UiCondition condition) {
        long deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS;
        do {
            if (condition.isMet()) return true;
            SystemClock.sleep(25L);
        } while (SystemClock.uptimeMillis() < deadline);
        return condition.isMet();
    }

    private interface UiCondition {
        boolean isMet();
    }
}
