package com.auras.orbit.extensions;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.lifecycle.LifecycleOwner;

import com.auras.orbit.OrbitApplication;
import com.auras.orbit.BuildConfig;
import com.auras.orbit.R;
import com.auras.orbit.navigation.ShellRoute;
import com.auras.orbit.navigation.ShellState;
import com.auras.orbit.ui.OrbitShellActivity;
import com.lagradost.cloudstream3.plugins.Plugin;
import com.lagradost.cloudstream3.utils.UiText;

import org.json.JSONArray;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Uses only an in-process fake Plugin and DialogFragment; no CS3 archive is loaded or executed. */
@RunWith(AndroidJUnit4.class)
public final class PluginSettingsHostInstrumentationTest {
    private static final long UI_TIMEOUT_MS = 10_000L;
    private static final String TAG = "OrbitPluginSettingsTest";
    private static final String EXTENSION_PREFS = "orbit_cloudstream_extensions_v1";
    private static final String PROFILE_PREFS = "orbit_shell_local_profiles_v1";
    private static final String INSTALLED_PLUGINS_KEY = "installed_plugins_json";
    private static boolean extensionPrefsClassStartCaptured;
    private static boolean extensionPrefsFileExistedAtClassStart;

    private Instrumentation instrumentation;
    private Context target;
    private OrbitApplication application;
    private OrbitCs3HostServices services;
    private CS3PluginRuntime runtime;
    private CloudStreamExtensionManager extensionManager;
    private OrbitShellActivity shell;
    private PluginSettingsHostActivity diagnosticHost;
    private ShellRoute originalRoute;
    private String originalRuntimeProfileId;
    private File profilePreferencesFile;
    private String originalProfilePreferencesSha256;
    private boolean profilePreferencesExistedAtClassStart;
    private boolean temporaryProfileCreated;
    private SharedPreferences extensionPreferences;
    private boolean hadInstalledPluginsValue;
    private String originalInstalledPluginsValue;
    private File extensionPreferencesFile;
    private Fixture fixture;
    private final List<Fixture> fixtures = new ArrayList<>();
    private AlertDialog platformSettingsDialog;

    @Before
    public void attachToExistingOrbitShellAndSnapshotExtensionMetadata() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        target = instrumentation.getTargetContext();
        assertFalse("normal debug acceptance must not enable the CS3 canary build", BuildConfig.CS3_CANARY_BUILD);
        profilePreferencesFile = new File(target.getApplicationInfo().dataDir,
                "shared_prefs/" + PROFILE_PREFS + ".xml");
        profilePreferencesExistedAtClassStart = profilePreferencesFile.isFile();
        application = (OrbitApplication) target.getApplicationContext();
        if (target.getPackageName().endsWith(".queuerender")
                && !profilePreferencesExistedAtClassStart
                && application.getLocalProfileStore().getActiveProfile() == null) {
            com.auras.orbit.profile.LocalProfileStore.Profile profile =
                    application.getLocalProfileStore().createProfile("Temporary settings fixture");
            assertNotNull("the disposable queueRender target must create its temporary profile", profile);
            application.getLocalProfileStore().selectProfile(profile.id);
            temporaryProfileCreated = true;
        }
        Intent launch = new Intent(target, OrbitShellActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity launched = instrumentation.startActivitySync(launch);
        assertTrue("the existing Orbit shell must be the target Activity",
                launched instanceof OrbitShellActivity);
        shell = (OrbitShellActivity) launched;
        assertTrue("the target application must already be OrbitApplication",
                shell.getApplication() instanceof OrbitApplication);
        application = (OrbitApplication) shell.getApplication();
        services = OrbitCs3HostServices.get();
        runtime = application.getCS3PluginRuntime();
        extensionManager = application.getCloudStreamExtensionManager();
        originalRoute = shellState(shell).getRoute();
        originalRuntimeProfileId = runtime.getActiveProfileId();
        assertTrue("the accepted target crosswalk remains enabled during fake settings acceptance",
                runtime.isTargetPluginCrosswalkValidated());
        if (originalRuntimeProfileId == null) {
            com.auras.orbit.profile.LocalProfileStore.Profile active =
                    application.getLocalProfileStore().getActiveProfile();
            assertNotNull("the existing shell must have an active profile", active);
            assertTrue("profile runtime should reconcile without activating CS3 packages",
                    runtime.reconcileProfile(active.id, extensionManager).isEmpty());
            originalRuntimeProfileId = runtime.getActiveProfileId();
        }
        assertNotNull("fake settings host acceptance needs the currently reconciled profile",
                originalRuntimeProfileId);

        originalProfilePreferencesSha256 = sha256(profilePreferencesFile);
        continueWithExistingProfile();
        assertEquals("continuing with the already active profile must not change profile data",
                originalProfilePreferencesSha256, sha256(profilePreferencesFile));

        extensionPreferences = extensionManagerPreferences();
        extensionPreferencesFile = new File(target.getApplicationInfo().dataDir,
                "shared_prefs/" + EXTENSION_PREFS + ".xml");
        if (!extensionPrefsClassStartCaptured) {
            extensionPrefsFileExistedAtClassStart = extensionPreferencesFile.isFile();
            extensionPrefsClassStartCaptured = true;
        }
        hadInstalledPluginsValue = extensionPreferences.contains(INSTALLED_PLUGINS_KEY);
        originalInstalledPluginsValue = extensionPreferences.getString(INSTALLED_PLUGINS_KEY, "[]");
    }

    @After
    public void removeOnlyFakeSettingsFixtureAndRestoreShellRoute() throws Exception {
        if (platformSettingsDialog != null && platformSettingsDialog.isShowing()) {
            instrumentation.runOnMainSync(platformSettingsDialog::dismiss);
        }
        for (Fixture item : fixtures) {
            try { services.invalidatePluginSettingsForPlugin(item.pluginId); }
            catch (Throwable ignored) { }
            try { runtime.deactivateIfClean(item.pluginId); }
            catch (Throwable ignored) { }
            try { services.unloadPlugin(item.pluginId); }
            catch (Throwable ignored) { }
            removeFakeRuntimeHandle(item.pluginId);
            if (item.assets != null) {
                try { item.assets.close(); }
                catch (RuntimeException ignored) { }
            }
            if (item.packageFile != null) item.packageFile.delete();
        }
        if (extensionPreferences != null) {
            SharedPreferences.Editor editor = extensionPreferences.edit();
            if (hadInstalledPluginsValue) editor.putString(INSTALLED_PLUGINS_KEY, originalInstalledPluginsValue);
            else editor.remove(INSTALLED_PLUGINS_KEY);
            assertTrue("fake installed metadata must be restored before leaving the test",
                    editor.commit());
        }
        if (temporaryProfileCreated) {
            assertTrue("the temporary queueRender-only profile data must be removed",
                    target.deleteSharedPreferences(PROFILE_PREFS));
            assertFalse("the originally absent profile preference file must remain absent",
                    profilePreferencesFile.exists());
        } else if (profilePreferencesFile != null) {
            assertEquals("the test must leave the existing profile preferences byte-for-byte unchanged",
                    originalProfilePreferencesSha256, sha256(profilePreferencesFile));
        }
        if (runtime != null && originalRuntimeProfileId != null
                && !originalRuntimeProfileId.equals(runtime.getActiveProfileId())) {
            runtime.reconcileProfile(originalRuntimeProfileId, extensionManager);
        }
        OrbitShellActivity shellToRestore = resumedShellActivity();
        if (shellToRestore != null && !shellToRestore.isFinishing() && !shellToRestore.isDestroyed()) {
            instrumentation.runOnMainSync(() -> {
                shellState(shellToRestore).navigateTo(originalRoute);
                setField(shellToRestore, "settingsDetailOpen", false);
                setField(shellToRestore, "extensionPage", "overview");
                invokeRender(shellToRestore);
            });
        }
    }

    @AfterClass
    public static void deleteOnlyOriginallyAbsentExtensionPreferencesFile() {
        if (!extensionPrefsClassStartCaptured || extensionPrefsFileExistedAtClassStart) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteSharedPreferences(EXTENSION_PREFS);
    }

    @Test
    public void configureShowsFakeDialogOnceReturnsToSettingsAndRestoresTvFocus() throws Exception {
        fixture = stageFakePlugin("configure", true);
        openSettingsExtensions();
        String configureTag = "extension_configure_fake_settings_host";
        awaitUi("fake Configure button", () -> findTaggedView(shell, configureTag) != null);
        View configure = findTaggedView(shell, configureTag);
        assertNotNull("the fake plugin row must expose its conditional Configure action", configure);

        AtomicBoolean legacyDispatchReturned = new AtomicBoolean(false);
        instrumentation.runOnMainSync(() -> {
            Activity shellActivity = shell;
            assertFalse("the existing shell must remain a platform Activity",
                    shellActivity instanceof AppCompatActivity);
            legacyDispatchReturned.set(fixture.plugin.dispatchOpenSettings(shellActivity));
        });
        assertTrue("the legacy direct-Activity callback is present", legacyDispatchReturned.get());
        assertEquals("legacy dispatch reaches the fake callback", 1, fixture.callbackCount.get());
        assertEquals("legacy Activity context does not pass the fake AppCompat guard", 0,
                fixture.appCompatCallbackCount.get());
        assertEquals("legacy direct-Activity behavior cannot attach the fake DialogFragment", 0,
                fixture.dialogCreated.get());
        fixture.callbackCount.set(0);
        fixture.appCompatCallbackCount.set(0);
        fixture.receivedAppCompatContext.set(false);

        Instrumentation.ActivityMonitor monitor = addHostMonitor();
        if (target.getPackageManager().hasSystemFeature("android.software.leanback")) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            awaitUi("TV remote navigation mode", () -> {
                OrbitShellActivity active = resumedShellActivity();
                return active != null && !active.getWindow().getDecorView().isInTouchMode();
            });
            instrumentation.runOnMainSync(() -> {
                assertTrue("Configure must receive TV focus before remote activation", configure.requestFocus());
                assertTrue("Configure must be the focused TV target before remote activation", configure.isFocused());
            });
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        } else {
            instrumentation.runOnMainSync(() ->
                    assertTrue("Configure click must launch the host", configure.performClick()));
        }
        PluginSettingsHostActivity host = awaitHost(monitor);
        awaitUi("fake DialogFragment presentation", () -> fixture.dialogCreated.get() == 1
                && findDialog(host, fixture.dialogTag) != null);

        assertEquals("the settings callback must be invoked once", 1, fixture.callbackCount.get());
        assertEquals("the AppCompat path must pass the fake host guard once", 1,
                fixture.appCompatCallbackCount.get());
        assertEquals("the fake DialogFragment must be created once", 1, fixture.dialogCreated.get());
        assertTrue("the callback context must be AppCompatActivity", fixture.receivedAppCompatContext.get());
        android.graphics.drawable.Drawable hostBackground = host.getWindow().getDecorView().getBackground();
        assertTrue("the host window background must be transparent behind the plugin dialog",
                hostBackground == null || (hostBackground instanceof ColorDrawable
                        && ((ColorDrawable) hostBackground).getColor() == Color.TRANSPARENT));
        String liveToken = settingsTokenFor(fixture.pluginId);
        assertNotNull("the Configure route must place only an opaque live token in the session map", liveToken);

        exerciseHostRotationOrConfigurationChange(host, liveToken);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitUi("host closure and return to resumed Settings shell", () -> host.isFinishing()
                && resumedShellHasWindowFocus());
        OrbitShellActivity resumedShell = resumedShellActivity();
        assertNotNull("the current resumed shell must be present after host close", resumedShell);
        assertEquals("the shell route must remain Settings", ShellRoute.SETTINGS,
                shellState(resumedShell).getRoute());
        assertEquals("normal close must detach the fake plugin dialog", 1, fixture.dialogDestroyed.get());
        assertFalse("normal host close must remove the in-memory session", services.hasPluginSettingsSession(liveToken));
        if (target.getPackageManager().hasSystemFeature("android.software.leanback")) {
            awaitUi("TV focus returning to Configure", () -> resumedShellHasViewFocus(configureTag));
            assertTrue("TV focus must return to Configure after the host closes",
                    resumedShellHasViewFocus(configureTag));
        }
        instrumentation.removeMonitor(monitor);
    }

    @Test
    public void configureKeepsHostOpenForPlatformAlertDialog() throws Exception {
        fixture = stageFakePlugin("platform-dialog", false);
        fixture.plugin.setOpenSettings(new Function1<Context, Unit>() {
            @Override public Unit invoke(Context context) {
                fixture.callbackCount.incrementAndGet();
                platformSettingsDialog = new AlertDialog.Builder(context)
                        .setTitle("Platform settings")
                        .setMessage("This dialog is not a DialogFragment.")
                        .setPositiveButton("Close", null)
                        .create();
                platformSettingsDialog.show();
                return Unit.INSTANCE;
            }
        });
        openSettingsExtensions();
        View configure = findTaggedView(shell, fixture.configureTag);
        assertNotNull("the fake plugin row must expose Configure", configure);

        Instrumentation.ActivityMonitor monitor = addHostMonitor();
        instrumentation.runOnMainSync(() -> assertTrue(
                "Configure click must launch the host", configure.performClick()));
        PluginSettingsHostActivity host = awaitHost(monitor);
        awaitUi("platform AlertDialog presentation", () -> platformSettingsDialog != null
                && platformSettingsDialog.isShowing());

        long holdUntil = SystemClock.uptimeMillis() + 600L;
        while (SystemClock.uptimeMillis() < holdUntil) {
            instrumentation.waitForIdleSync();
            assertFalse("the settings host must stay open while a platform dialog owns focus",
                    host.isFinishing());
            assertTrue("the plugin's platform dialog must stay visible", platformSettingsDialog.isShowing());
            SystemClock.sleep(40L);
        }

        instrumentation.runOnMainSync(platformSettingsDialog::dismiss);
        awaitUi("empty settings host closure after platform dialog dismissal", host::isFinishing);
        instrumentation.removeMonitor(monitor);
    }

    @Test
    public void resourceBearingFixtureResolvesUiTextAndKeepsConfigureLifecycle() throws Exception {
        assertTrue("the resource fixture must run against the disposable queueRender target",
                target.getPackageName().endsWith(".queuerender"));
        File resourcePackage = new File(target.getApplicationInfo().sourceDir);
        assertTrue("the installed queueRender target APK must be available for resource attachment",
                resourcePackage.isFile());
        fixture = stageFakePlugin("resources", true, resourcePackage);

        Resources pluginResources = fixture.plugin.getResources();
        assertNotNull("the fake Plugin must receive Resources from the queueRender APK", pluginResources);
        assertEquals("the attached resource table must belong to the disposable app package",
                target.getPackageName(), pluginResources.getResourcePackageName(R.color.orbit_base));
        assertEquals("the attached table must resolve an Orbit-owned color entry", "orbit_base",
                pluginResources.getResourceEntryName(R.color.orbit_base));
        assertTrue("the attached Orbit color must resolve to a visible color",
                Color.alpha(pluginResources.getColor(R.color.orbit_base)) > 0);
        assertNotNull("the attached Orbit drawable must resolve", pluginResources.getDrawable(
                R.drawable.auras_orbit_mark, target.getTheme()));

        runtime.refreshPluginResources();
        pluginResources = fixture.plugin.getResources();
        assertNotNull("refresh must keep the resource-bearing fake Plugin attached", pluginResources);
        assertEquals("refresh must retain the Orbit resource table", "orbit_base",
                pluginResources.getResourceEntryName(R.color.orbit_base));

        fixture.plugin.setOpenSettings(new Function1<Context, Unit>() {
            @Override public Unit invoke(Context context) {
                fixture.callbackCount.incrementAndGet();
                fixture.receivedAppCompatContext.set(context instanceof AppCompatActivity);
                if (context instanceof AppCompatActivity) fixture.appCompatCallbackCount.incrementAndGet();
                Resources resources = fixture.plugin.getResources();
                assertNotNull("resources must still be attached when Configure dispatches", resources);
                Context resourceContext = withResources(context, resources);
                // Orbit currently has no app-owned string entry; this exercises UiText's resource path
                // through the attached plugin Resources while app-owned color/drawable lookups are
                // asserted above.
                fixture.resolvedUiText = new UiText.StringResource(android.R.string.ok,
                        Collections.emptyList()).asString(resourceContext);
                fixture.dialogTitle = fixture.resolvedUiText;
                fixture.dialogMessage = "Orbit resource: "
                        + resources.getResourceEntryName(R.color.orbit_base);
                FakeSettingsDialogFragment.newInstance(fixture.dialogTag)
                        .show(((AppCompatActivity) context).getSupportFragmentManager(), fixture.dialogTag);
                return Unit.INSTANCE;
            }
        });

        openSettingsExtensions();
        String configureTag = fixture.configureTag;
        awaitUi("resource-bearing fake Configure button", () -> findTaggedView(shell, configureTag) != null);
        View configure = findTaggedView(shell, configureTag);
        assertNotNull("the resource-bearing fake plugin row must expose Configure", configure);

        Instrumentation.ActivityMonitor monitor = addHostMonitor();
        if (target.getPackageManager().hasSystemFeature("android.software.leanback")) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            awaitUi("TV remote navigation mode", () -> {
                OrbitShellActivity active = resumedShellActivity();
                return active != null && !active.getWindow().getDecorView().isInTouchMode();
            });
            instrumentation.runOnMainSync(() -> {
                assertTrue("Configure must receive TV focus before remote activation", configure.requestFocus());
                assertTrue("Configure must be the focused TV target before remote activation", configure.isFocused());
            });
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        } else {
            instrumentation.runOnMainSync(() ->
                    assertTrue("Configure click must launch the resource settings host", configure.performClick()));
        }
        PluginSettingsHostActivity host = awaitHost(monitor);
        awaitUi("resource-backed UiText dialog", () -> fixture.dialogCreated.get() == 1
                && findDialog(host, fixture.dialogTag) != null
                && fixture.resolvedUiText != null);

        assertEquals("Configure must dispatch the resource-bearing fake callback once", 1,
                fixture.callbackCount.get());
        assertEquals("the callback must still receive the AppCompat settings host once", 1,
                fixture.appCompatCallbackCount.get());
        assertTrue("the callback context must be AppCompatActivity", fixture.receivedAppCompatContext.get());
        assertEquals("UiText must resolve through the attached resource context",
                target.getString(android.R.string.ok), fixture.resolvedUiText);
        DialogFragment dialogFragment = findDialog(host, fixture.dialogTag);
        assertNotNull("the resource dialog must be attached to the settings host", dialogFragment);
        assertNotNull("the resolved UiText must be rendered in the plugin settings dialog",
                findTextView(dialogFragment.getDialog().getWindow().getDecorView(), fixture.resolvedUiText));
        assertNotNull("the dialog must report the attached Orbit resource", findTextView(
                dialogFragment.getDialog().getWindow().getDecorView(), "Orbit resource: orbit_base"));

        String liveToken = settingsTokenFor(fixture.pluginId);
        assertNotNull("Configure must create only an opaque live settings token", liveToken);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitUi("resource host closure and return to the Settings shell", () -> host.isFinishing()
                && resumedShellHasWindowFocus());
        OrbitShellActivity resumedShell = resumedShellActivity();
        assertNotNull("the current resumed shell must be present after resource host close", resumedShell);
        assertEquals("closing the resource-backed settings host must preserve Settings route",
                ShellRoute.SETTINGS, shellState(resumedShell).getRoute());
        assertEquals("normal close must detach the fake resource dialog", 1, fixture.dialogDestroyed.get());
        assertFalse("normal close must consume the in-memory settings session",
                services.hasPluginSettingsSession(liveToken));
        if (target.getPackageManager().hasSystemFeature("android.software.leanback")) {
            awaitUi("TV focus returning to Configure", () -> resumedShellHasViewFocus(configureTag));
            assertTrue("TV focus must return to Configure after the host closes",
                    resumedShellHasViewFocus(configureTag));
        }
        instrumentation.removeMonitor(monitor);
    }

    @Test
    public void runtimeCleanupDetachesDialogBeforeProviderCleanupContinues() throws Exception {
        fixture = stageFakePlugin("cleanup", true);
        openSettingsExtensions();
        Instrumentation.ActivityMonitor monitor = addHostMonitor();
        instrumentation.runOnMainSync(() -> {
            View configure = findTaggedView(shell, fixture.configureTag);
            assertNotNull("the cleanup fixture must expose Configure", configure);
            assertTrue(configure.performClick());
        });
        PluginSettingsHostActivity host = awaitHost(monitor);
        awaitUi("cleanup fixture dialog", () -> fixture.dialogCreated.get() == 1
                && findDialog(host, fixture.dialogTag) != null);

        Field barrierField = CS3PluginRuntime.class.getDeclaredField("pluginCleanupBarrier");
        barrierField.setAccessible(true);
        Object originalBarrier = barrierField.get(runtime);
        AtomicBoolean cleanupObservedDetachedDialog = new AtomicBoolean(false);
        runtime.setPluginCleanupBarrier(new CS3PluginRuntime.PluginCleanupBarrier() {
            @Override public void blockPluginCalls(String pluginId) { }
            @Override public boolean scheduleCleanupRetry(String pluginId, Runnable cleanup) { return false; }
            @Override public boolean pauseAndAwaitPluginCalls(String pluginId, long timeoutMs) {
                cleanupObservedDetachedDialog.set(fixture.dialogDestroyed.get() == 1
                        && findDialog(host, fixture.dialogTag) == null);
                return cleanupObservedDetachedDialog.get();
            }
            @Override public void pluginCleanupSucceeded(String pluginId) { }
        });

        FutureTask<Boolean> cleanup = new FutureTask<>(() -> runtime.deactivateIfClean(fixture.pluginId));
        Thread worker = new Thread(cleanup, "orbit-fake-plugin-settings-cleanup-test");
        worker.start();
        try {
            assertTrue("fake plugin cleanup should finish after settings fragments detach",
                    cleanup.get(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertTrue("provider cleanup must not continue until the dialog has detached",
                    cleanupObservedDetachedDialog.get());
        } finally {
            runtime.setPluginCleanupBarrier((CS3PluginRuntime.PluginCleanupBarrier) originalBarrier);
        }
        awaitUi("host closure after runtime cleanup", host::isFinishing);
        instrumentation.removeMonitor(monitor);
    }

    @Test
    public void staleTransactionAndProfileTokensFailClosedBeforeCallback() throws Exception {
        fixture = stageFakePlugin("stale", true);
        String staleLoadToken = services.createPluginSettingsSession(fixture.pluginId);
        assertNotNull("the committed fake load should mint a token", staleLoadToken);
        services.unloadPlugin(fixture.pluginId);
        assertHostRejects(staleLoadToken);
        assertEquals("stale load token must not invoke callback", 0, fixture.callbackCount.get());

        fixture = stageFakePlugin("profile-mismatch", true);
        String staleProfileToken = services.createPluginSettingsSession(fixture.pluginId);
        assertNotNull("the active fake load should mint a profile-bound token", staleProfileToken);
        setField(runtime, "activeProfileId", originalRuntimeProfileId + "-stale");
        setField(runtime, "activeProfileFullyReconciled", true);
        try {
            assertHostRejects(staleProfileToken);
            assertEquals("profile mismatch must not invoke callback", 0, fixture.callbackCount.get());
        } finally {
            setField(runtime, "activeProfileId", originalRuntimeProfileId);
            setField(runtime, "activeProfileFullyReconciled", true);
        }
        assertNull("unknown plugin IDs must not create settings sessions",
                services.createPluginSettingsSession("missing-" + UUID.randomUUID()));
        String unknownToken = UUID.randomUUID().toString();
        PluginSettingsHostActivity unknownHost = launchHost(unknownToken);
        awaitUi("unknown token fails closed", unknownHost::isFinishing);
        assertEquals("unknown session must not execute a callback", 0, fixture.callbackCount.get());
    }

    @Test
    public void callbackFailureEmptyCallbackAndDismissedDialogCloseHost() throws Exception {
        fixture = stageFakePlugin("throwing", false);
        fixture.plugin.setOpenSettings(context -> {
            fixture.callbackCount.incrementAndGet();
            throw new IllegalStateException("fake callback failure");
        });
        String token = services.createPluginSettingsSession(fixture.pluginId);
        assertNotNull(token);
        PluginSettingsHostActivity host = launchHost(token);
        awaitUi("throwing callback host closure", host::isFinishing);
        assertEquals("failing callback runs once", 1, fixture.callbackCount.get());
        assertFalse("callback failure must not leave an empty host window", services.hasPluginSettingsSession(token));

        fixture = stageFakePlugin("empty", false);
        fixture.plugin.setOpenSettings(null);
        assertNull("empty callbacks must not mint a host session",
                services.createPluginSettingsSession(fixture.pluginId));

        fixture = stageFakePlugin("no-dialog", false);
        fixture.plugin.setOpenSettings(context -> {
            fixture.callbackCount.incrementAndGet();
            fixture.receivedAppCompatContext.set(context instanceof AppCompatActivity);
            if (context instanceof AppCompatActivity) fixture.appCompatCallbackCount.incrementAndGet();
            return Unit.INSTANCE;
        });
        String emptyHostToken = services.createPluginSettingsSession(fixture.pluginId);
        assertNotNull("a present callback that adds no dialog may still create a temporary host", emptyHostToken);
        PluginSettingsHostActivity emptyHost = launchHost(emptyHostToken);
        awaitUi("normal callback with no DialogFragment closes its empty host", emptyHost::isFinishing);
        assertEquals("a normal no-dialog callback must be invoked once", 1, fixture.callbackCount.get());
        assertEquals("the no-dialog callback must receive an AppCompatActivity", 1,
                fixture.appCompatCallbackCount.get());
        assertFalse("the delayed empty-host close must remove its session",
                services.hasPluginSettingsSession(emptyHostToken));
        assertNull("the empty host must not retain a plugin DialogFragment",
                findDialog(emptyHost, fixture.dialogTag));

        fixture = stageFakePlugin("dismiss", true);
        String dismissToken = services.createPluginSettingsSession(fixture.pluginId);
        host = launchHost(dismissToken);
        final PluginSettingsHostActivity dismissHost = host;
        awaitUi("dismissible fake dialog", () -> fixture.dialogCreated.get() == 1
                && findDialog(dismissHost, fixture.dialogTag) != null);
        instrumentation.runOnMainSync(() -> findDialog(dismissHost, fixture.dialogTag).getDialog().cancel());
        awaitUi("outside-tap cancellation closes the host", dismissHost::isFinishing);
        assertEquals("dismissal callback is only invoked once", 1, fixture.callbackCount.get());
    }

    @Test
    public void recreatedHostFailsClosedBeforePrivateDialogFragmentRestore() throws Exception {
        fixture = stageFakePlugin("recreate", true);
        String token = services.createPluginSettingsSession(fixture.pluginId);
        PluginSettingsHostActivity host = launchHost(token);
        awaitUi("private-constructor fake dialog", () -> fixture.dialogCreated.get() == 1
                && findDialog(host, fixture.dialogTag) != null);

        Instrumentation.ActivityMonitor monitor = addHostMonitor();
        instrumentation.runOnMainSync(host::recreate);
        Activity recreated = monitor.waitForActivityWithTimeout(UI_TIMEOUT_MS);
        instrumentation.removeMonitor(monitor);
        assertTrue("Activity recreation must create a replacement host", recreated instanceof PluginSettingsHostActivity);
        PluginSettingsHostActivity replacement = (PluginSettingsHostActivity) recreated;
        awaitUi("recreated host fails closed without fragment restoration", replacement::isFinishing);
        assertEquals("recreation must not redispatch the callback", 1, fixture.callbackCount.get());
        assertTrue("the private DialogFragment must be detached before recreation finishes",
                fixture.dialogDestroyed.get() >= 1);
    }

    private Fixture stageFakePlugin(String label, boolean showDialog) throws Exception {
        return stageFakePlugin(label, showDialog, null);
    }

    private Fixture stageFakePlugin(String label, boolean showDialog, File resourcePackage) throws Exception {
        String pluginId = "fake-settings-" + label + "-" + UUID.randomUUID();
        File packageFile = File.createTempFile("orbit-fake-settings-", ".cs3", target.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(packageFile)) {
            output.write("fake instrumentation only".getBytes(StandardCharsets.UTF_8));
        }
        FakePlugin plugin = new FakePlugin();
        plugin.setFilename(packageFile.getAbsolutePath());
        Fixture next = new Fixture(pluginId, plugin, packageFile,
                "fake_settings_host_" + label + "_" + pluginId.substring(pluginId.length() - 8));
        fixtures.add(next);
        if (showDialog) {
            plugin.setOpenSettings(new Function1<Context, Unit>() {
                @Override public Unit invoke(Context context) {
                    next.callbackCount.incrementAndGet();
                    next.receivedAppCompatContext.set(context instanceof AppCompatActivity);
                    if (context instanceof AppCompatActivity) next.appCompatCallbackCount.incrementAndGet();
                    if (context instanceof AppCompatActivity) {
                        FakeSettingsDialogFragment.newInstance(next.dialogTag)
                                .show(((AppCompatActivity) context).getSupportFragmentManager(), next.dialogTag);
                    }
                    return Unit.INSTANCE;
                }
            });
        }
        OrbitCs3HostServices.LoadTransaction transaction = services.beginPluginLoad(
                pluginId, packageFile.getAbsolutePath(), plugin);
        AssetManager attachedResources = resourcePackage == null ? null
                : attachPluginResources(plugin, resourcePackage);
        transaction.commit();
        next.transaction = transaction;
        next.assets = attachedResources;

        CloudStreamExtensionManager.InstalledPlugin metadata = new CloudStreamExtensionManager.InstalledPlugin(
                pluginId, "https://example.invalid/fake-repository.json", "Fake settings fixture " + label,
                "Fake Settings Host", null, 1L, repeat('0', 64), false,
                FakePlugin.class.getName(), resourcePackage != null,
                packageFile.getAbsolutePath(), null, System.currentTimeMillis());
        JSONArray installed = new JSONArray(extensionPreferences.getString(INSTALLED_PLUGINS_KEY, "[]"));
        installed.put(metadata.toJson());
        assertTrue("fake extension metadata must be staged for this test and restored afterward",
                extensionPreferences.edit().putString(INSTALLED_PLUGINS_KEY, installed.toString()).commit());
        addFakeRuntimeHandle(plugin, packageFile, transaction, pluginId, attachedResources);
        next.configureTag = "extension_configure_fake_settings_host";
        currentFixture = next;
        return next;
    }

    private void openSettingsExtensions() throws Exception {
        instrumentation.runOnMainSync(() -> {
            shellState(shell).navigateTo(ShellRoute.SETTINGS);
            setField(shell, "settingsDetailOpen", !target.getPackageManager()
                    .hasSystemFeature("android.software.leanback"));
            setField(shell, "selectedSettingsCategory", "Extensions");
            setField(shell, "extensionPage", "overview");
            invokeRender(shell);
        });
        awaitUi("Installed plugins entry", () ->
                findTaggedView(shell, "settings_extension_installed_plugins") != null);
        instrumentation.runOnMainSync(() -> {
            View installedPlugins = findTaggedView(shell, "settings_extension_installed_plugins");
            assertNotNull("Extensions overview must expose Installed plugins", installedPlugins);
            assertTrue("Installed plugins entry must open the plugin list", installedPlugins.performClick());
        });
        awaitUi("Installed CloudStream plugins page", () ->
                findTaggedView(shell, fixture.configureTag) != null);
    }

    private void continueWithExistingProfile() throws Exception {
        awaitUi("existing profile picker or confirmed shell", () ->
                findTaggedView(shell, "nav_SETTINGS") != null || findText(shell, "Continue") != null);
        View continueButton = findText(shell, "Continue");
        if (continueButton != null) {
            assertNotNull("Continue must use the already active profile", application.getLocalProfileStore().getActiveProfile());
            instrumentation.runOnMainSync(() -> assertTrue("the active-profile Continue action must succeed",
                    continueButton.performClick()));
        }
        awaitUi("confirmed shell using the existing profile", () ->
                findTaggedView(shell, "nav_SETTINGS") != null);
    }

    private Instrumentation.ActivityMonitor addHostMonitor() {
        return instrumentation.addMonitor(PluginSettingsHostActivity.class.getName(), null, false);
    }

    private PluginSettingsHostActivity awaitHost(Instrumentation.ActivityMonitor monitor) throws Exception {
        Activity activity = monitor.waitForActivityWithTimeout(UI_TIMEOUT_MS);
        instrumentation.removeMonitor(monitor);
        assertTrue("the explicit plugin settings host must launch", activity instanceof PluginSettingsHostActivity);
        diagnosticHost = (PluginSettingsHostActivity) activity;
        return diagnosticHost;
    }

    private PluginSettingsHostActivity launchHost(String token) throws Exception {
        Intent intent = new Intent(target, PluginSettingsHostActivity.class)
                .putExtra(PluginSettingsHostActivity.EXTRA_SESSION_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        assertTrue("the test token must launch the private host", activity instanceof PluginSettingsHostActivity);
        diagnosticHost = (PluginSettingsHostActivity) activity;
        return diagnosticHost;
    }

    private void assertHostRejects(String token) throws Exception {
        PluginSettingsHostActivity host = launchHost(token);
        awaitUi("stale settings host rejection", host::isFinishing);
        assertFalse("stale token must be consumed and removed", services.hasPluginSettingsSession(token));
    }

    private void exerciseHostRotationOrConfigurationChange(PluginSettingsHostActivity host,
            String liveToken) throws Exception {
        int previousRotation = host.getWindowManager().getDefaultDisplay().getRotation();
        int requestedRotation = previousRotation == android.view.Surface.ROTATION_90
                ? android.view.Surface.ROTATION_270 : android.view.Surface.ROTATION_90;
        Instrumentation.ActivityMonitor monitor = addHostMonitor();
        String unavailableReason = null;
        boolean rotationApplied = false;
        try {
            try { instrumentation.getUiAutomation().setRotation(requestedRotation); }
            catch (RuntimeException error) {
                unavailableReason = error.getClass().getSimpleName() + ": " + error.getMessage();
            }
            long deadline = SystemClock.uptimeMillis() + 3000L;
            while (unavailableReason == null && SystemClock.uptimeMillis() < deadline) {
                if (host.getWindowManager().getDefaultDisplay().getRotation() == requestedRotation) {
                    rotationApplied = true;
                    break;
                }
                instrumentation.waitForIdleSync();
                SystemClock.sleep(50L);
            }
            if (!rotationApplied && unavailableReason == null) {
                unavailableReason = "device did not apply the requested display rotation";
            }

            String deviceType = target.getPackageManager().hasSystemFeature("android.software.leanback")
                    ? "tv" : "phone";
            if (rotationApplied) {
                Log.i(TAG, "HOST_ROTATION_APPLIED device=" + deviceType + " previous=" + previousRotation
                        + " requested=" + requestedRotation);
            } else {
                Configuration changed = new Configuration(host.getResources().getConfiguration());
                changed.orientation = changed.orientation == Configuration.ORIENTATION_LANDSCAPE
                        ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
                final Configuration deliveredConfiguration = changed;
                instrumentation.runOnMainSync(() -> host.onConfigurationChanged(deliveredConfiguration));
                Log.w(TAG, "HOST_ROTATION_UNSUPPORTED device=" + deviceType + " reason=" + unavailableReason
                        + " configurationChange=manually-delivered");
            }

            awaitUi("host and fake dialog survive rotation/configuration change", () -> !host.isFinishing()
                    && fixture.callbackCount.get() == 1
                    && findDialog(host, fixture.dialogTag) != null
                    && services.hasPluginSettingsSession(liveToken));
            assertEquals("rotation/configuration change must not recreate the host", 0, monitor.getHits());
            assertEquals("rotation/configuration change must not redispatch the callback", 1,
                    fixture.callbackCount.get());
        } finally {
            instrumentation.removeMonitor(monitor);
            try { instrumentation.getUiAutomation().setRotation(previousRotation); }
            catch (RuntimeException ignored) { }
            if (rotationApplied) {
                long restoreDeadline = SystemClock.uptimeMillis() + 3000L;
                while (SystemClock.uptimeMillis() < restoreDeadline
                        && host.getWindowManager().getDefaultDisplay().getRotation() != previousRotation) {
                    instrumentation.waitForIdleSync();
                    SystemClock.sleep(50L);
                }
                assertEquals("device rotation must return to its original value", previousRotation,
                        host.getWindowManager().getDefaultDisplay().getRotation());
            }
        }
    }

    private DialogFragment findDialog(PluginSettingsHostActivity host, String tag) {
        AtomicReference<DialogFragment> result = new AtomicReference<>();
        Runnable lookup = () -> {
            FragmentManager manager = host.getSupportFragmentManager();
            for (androidx.fragment.app.Fragment fragment : manager.getFragments()) {
                if (fragment instanceof DialogFragment && tag.equals(fragment.getTag())) {
                    result.set((DialogFragment) fragment);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) lookup.run();
        else instrumentation.runOnMainSync(lookup);
        return result.get();
    }

    private View findTaggedView(OrbitShellActivity activity, String tag) {
        AtomicReference<View> result = new AtomicReference<>();
        Runnable lookup = () -> result.set(findView(activity.getWindow().getDecorView(), tag));
        if (Looper.myLooper() == Looper.getMainLooper()) lookup.run();
        else instrumentation.runOnMainSync(lookup);
        return result.get();
    }

    private View findText(OrbitShellActivity activity, String expected) {
        AtomicReference<View> result = new AtomicReference<>();
        Runnable lookup = () -> result.set(findTextView(activity.getWindow().getDecorView(), expected));
        if (Looper.myLooper() == Looper.getMainLooper()) lookup.run();
        else instrumentation.runOnMainSync(lookup);
        return result.get();
    }

    private View findTextView(View root, String expected) {
        if (root instanceof TextView && expected.contentEquals(((TextView) root).getText())) return root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int index = 0; index < group.getChildCount(); index++) {
            View found = findTextView(group.getChildAt(index), expected);
            if (found != null) return found;
        }
        return null;
    }

    private View findView(View root, String tag) {
        if (tag.equals(root.getTag())) return root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int index = 0; index < group.getChildCount(); index++) {
            View found = findView(group.getChildAt(index), tag);
            if (found != null) return found;
        }
        return null;
    }

    private void awaitUi(String description, UiCondition condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            if (condition.isMet()) return;
            SystemClock.sleep(50L);
        }
        String diagnostic = shellStateSnapshot();
        Log.e(TAG, "timeout=" + description + " " + diagnostic);
        assertTrue("timed out waiting for " + description + " " + diagnostic, condition.isMet());
    }

    private String shellStateSnapshot() {
        if (shell == null || application == null) return "shell=unavailable";
        AtomicReference<String> snapshot = new AtomicReference<>();
        Runnable collect = () -> {
            OrbitShellActivity activeShell = resumedShellActivityOnMain();
            OrbitShellActivity stateShell = activeShell == null ? shell : activeShell;
            StringBuilder output = new StringBuilder();
            ShellRoute route = shellRoute(stateShell);
            output.append("route=").append(route == null ? "unavailable" : route)
                    .append(" profileConfirmed=").append(readFieldValue(shell, "profileConfirmed"))
                    .append(" settingsDetailOpen=").append(readFieldValue(shell, "settingsDetailOpen"))
                    .append(" selectedSettingsCategory=").append(readFieldValue(shell, "selectedSettingsCategory"))
                    .append(" extensionPage=").append(readFieldValue(shell, "extensionPage"))
                    .append(" shellRef=").append(activityStateOnMain(shell))
                    .append(" shellRefHierarchy=[");
            appendHierarchy(shell.getWindow().getDecorView(), output, 0);
            output.append("] resumedShell=").append(activityStateOnMain(activeShell));
            if (activeShell != null && activeShell != shell) {
                output.append(" resumedShellHierarchy=[");
                appendHierarchy(activeShell.getWindow().getDecorView(), output, 0);
                output.append(']');
            }
            if (activeShell != null) {
                View configure = findView(activeShell.getWindow().getDecorView(),
                        "extension_configure_fake_settings_host");
                View currentFocus = activeShell.getCurrentFocus();
                Object currentFocusTag = currentFocus == null ? null : currentFocus.getTag();
                output.append(" shellTelevision=").append(readFieldValue(activeShell, "isTelevision"))
                        .append(" pendingFocusTag=").append(readFieldValue(activeShell, "pendingFocusTag"))
                        .append(" pluginSettingsReturnFocusTag=")
                        .append(readFieldValue(activeShell, "pluginSettingsReturnFocusTag"))
                        .append(" pluginSettingsHostTookWindowFocus=")
                        .append(readFieldValue(activeShell, "pluginSettingsHostTookWindowFocus"))
                        .append(" inTouchMode=")
                        .append(activeShell.getWindow().getDecorView().isInTouchMode())
                        .append(" currentFocusTag=").append(currentFocusTag)
                        .append(" configureState=").append(viewState(configure));
            }
            output.append(" host=").append(activityStateOnMain(diagnosticHost))
                    .append(" hostTokenPresent=").append(diagnosticHost != null
                            && services.hasPluginSettingsSession(diagnosticHost.getIntent()
                                    .getStringExtra(PluginSettingsHostActivity.EXTRA_SESSION_TOKEN)))
                    .append(" hostDialogs=").append(hostDialogSnapshotOnMain());
            snapshot.set(output.toString());
        };
        if (Looper.myLooper() == Looper.getMainLooper()) collect.run();
        else instrumentation.runOnMainSync(collect);
        return snapshot.get() + " topResumed=" + topResumedComponentSnapshot();
    }

    private OrbitShellActivity resumedShellActivity() {
        AtomicReference<OrbitShellActivity> result = new AtomicReference<>();
        Runnable collect = () -> result.set(resumedShellActivityOnMain());
        if (Looper.myLooper() == Looper.getMainLooper()) collect.run();
        else instrumentation.runOnMainSync(collect);
        return result.get();
    }

    private OrbitShellActivity resumedShellActivityOnMain() {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)) {
            if (activity instanceof OrbitShellActivity && !activity.isFinishing() && !activity.isDestroyed()) {
                return (OrbitShellActivity) activity;
            }
        }
        return null;
    }

    private static ShellState shellState(OrbitShellActivity activity) {
        if (activity == null) return null;
        Object state = readFieldValue(activity, "shellState");
        if (state instanceof ShellState) return (ShellState) state;
        throw new AssertionError("Could not inspect Orbit shellState on "
                + activity.getClass().getSimpleName() + ": " + state);
    }

    private static ShellRoute shellRoute(OrbitShellActivity activity) {
        ShellState state = shellState(activity);
        return state == null ? null : state.getRoute();
    }

    private boolean resumedShellHasWindowFocus() {
        AtomicBoolean hasFocus = new AtomicBoolean(false);
        instrumentation.runOnMainSync(() -> {
            OrbitShellActivity active = resumedShellActivityOnMain();
            if (active != null) hasFocus.set(active.getWindow().getDecorView().hasWindowFocus());
        });
        return hasFocus.get();
    }

    private boolean resumedShellHasViewFocus(String tag) {
        AtomicBoolean hasFocus = new AtomicBoolean(false);
        instrumentation.runOnMainSync(() -> {
            OrbitShellActivity active = resumedShellActivityOnMain();
            View view = active == null ? null : findView(active.getWindow().getDecorView(), tag);
            hasFocus.set(view != null && view.hasFocus());
        });
        return hasFocus.get();
    }

    private String activityStateOnMain(Activity activity) {
        if (activity == null) return "null";
        String lifecycle = activity instanceof LifecycleOwner
                ? ((LifecycleOwner) activity).getLifecycle().getCurrentState().name() : "unavailable";
        boolean hasWindowFocus = false;
        try { hasWindowFocus = activity.getWindow().getDecorView().hasWindowFocus(); }
        catch (RuntimeException ignored) { }
        return activity.getClass().getSimpleName()
                + "{finishing=" + activity.isFinishing()
                + ",destroyed=" + activity.isDestroyed()
                + ",changingConfigurations=" + activity.isChangingConfigurations()
                + ",windowFocus=" + hasWindowFocus
                + ",lifecycle=" + lifecycle + "}";
    }

    private String viewState(View view) {
        if (view == null) return "null";
        return view.getClass().getSimpleName()
                + "{focusable=" + view.isFocusable()
                + ",focusableInTouchMode=" + view.isFocusableInTouchMode()
                + ",enabled=" + view.isEnabled()
                + ",isFocused=" + view.isFocused()
                + ",focused=" + view.hasFocus()
                + ",shown=" + view.isShown()
                + ",attached=" + view.isAttachedToWindow()
                + ",windowToken=" + (view.getWindowToken() != null) + "}";
    }

    private String hostDialogSnapshotOnMain() {
        if (diagnosticHost == null) return "none";
        StringBuilder output = new StringBuilder("[");
        try {
            List<androidx.fragment.app.Fragment> fragments = diagnosticHost
                    .getSupportFragmentManager().getFragments();
            for (androidx.fragment.app.Fragment fragment : fragments) {
                if (!(fragment instanceof DialogFragment)) continue;
                DialogFragment dialogFragment = (DialogFragment) fragment;
                if (output.length() > 1) output.append(',');
                output.append(dialogFragment.getClass().getName())
                        .append("#").append(dialogFragment.getTag())
                        .append("{added=").append(dialogFragment.isAdded())
                        .append(",visible=").append(dialogFragment.isVisible())
                        .append(",showing=").append(dialogFragment.getDialog() != null
                                && dialogFragment.getDialog().isShowing()).append('}');
            }
        } catch (RuntimeException error) {
            output.append("error=").append(error.getClass().getSimpleName());
        }
        return output.append(']').toString();
    }

    private String topResumedComponentSnapshot() {
        ParcelFileDescriptor descriptor = null;
        try {
            descriptor = instrumentation.getUiAutomation()
                    .executeShellCommand("dumpsys activity activities");
            StringBuilder output = new StringBuilder("[");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ParcelFileDescriptor.AutoCloseInputStream(descriptor)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("topResumedActivity") || line.contains("mResumedActivity")) {
                        if (output.length() > 1) output.append(" | ");
                        output.append(line.trim());
                        if (output.length() > 4000) break;
                    }
                }
            }
            return output.append(']').toString();
        } catch (Exception error) {
            if (descriptor != null) {
                try { descriptor.close(); } catch (Exception ignored) { }
            }
            return "unavailable:" + error.getClass().getSimpleName();
        }
    }

    private static void appendHierarchy(View view, StringBuilder output, int depth) {
        if (depth > 12) return;
        if (output.length() > 8000) return;
        Object tag = view.getTag();
        output.append(view.getClass().getSimpleName());
        if (tag instanceof String) output.append("#").append(tag);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            output.append('(');
            for (int index = 0; index < group.getChildCount(); index++) {
                if (index > 0) output.append(',');
                appendHierarchy(group.getChildAt(index), output, depth + 1);
                if (output.length() > 8000) break;
            }
            output.append(')');
        }
    }

    private static Object readFieldValue(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception error) {
            return "unavailable:" + name;
        }
    }

    private void addFakeRuntimeHandle(Plugin plugin, File file,
            OrbitCs3HostServices.LoadTransaction transaction, String pluginId) throws Exception {
        addFakeRuntimeHandle(plugin, file, transaction, pluginId, null);
    }

    private void addFakeRuntimeHandle(Plugin plugin, File file,
            OrbitCs3HostServices.LoadTransaction transaction, String pluginId, AssetManager assets)
            throws Exception {
        Class<?> loadedType = Class.forName("com.auras.orbit.extensions.CS3PluginRuntime$LoadedPlugin");
        Constructor<?> constructor = loadedType.getDeclaredConstructor(Object.class,
                dalvik.system.PathClassLoader.class, File.class,
                OrbitCs3HostServices.LoadTransaction.class, AssetManager.class, String.class);
        constructor.setAccessible(true);
        Object loaded = constructor.newInstance(plugin, null, file, transaction, assets, pluginId);
        Field mapField = CS3PluginRuntime.class.getDeclaredField("loadedPlugins");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) mapField.get(runtime);
        map.put(pluginId, loaded);
    }

    private SharedPreferences extensionManagerPreferences() throws Exception {
        Field preferencesField = CloudStreamExtensionManager.class.getDeclaredField("preferences");
        preferencesField.setAccessible(true);
        return (SharedPreferences) preferencesField.get(extensionManager);
    }

    private void removeFakeRuntimeHandle(String pluginId) {
        if (runtime == null) return;
        try {
            Field field = CS3PluginRuntime.class.getDeclaredField("loadedPlugins");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) field.get(runtime);
            map.remove(pluginId);
        } catch (Throwable ignored) { }
    }

    private AssetManager attachPluginResources(Plugin plugin, File resourcePackage) throws Exception {
        Method attach = CS3PluginRuntime.class.getDeclaredMethod("attachResources",
                Object.class, File.class, ClassLoader.class);
        attach.setAccessible(true);
        return (AssetManager) attach.invoke(runtime, plugin, resourcePackage, plugin.getClass().getClassLoader());
    }

    private static Context withResources(Context base, Resources resources) {
        return new ContextWrapper(base) {
            @Override public Resources getResources() { return resources; }
        };
    }

    private String settingsTokenFor(String pluginId) throws Exception {
        Field tokenField = OrbitCs3HostServices.class.getDeclaredField("pluginSettingsTokenByPluginId");
        tokenField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, String> tokens =
                (Map<String, String>) tokenField.get(services);
        return tokens.get(pluginId);
    }

    private static void invokeRender(OrbitShellActivity activity) {
        try {
            Method render = OrbitShellActivity.class.getDeclaredMethod("render");
            render.setAccessible(true);
            render.invoke(activity);
        } catch (Exception error) {
            throw new IllegalStateException("Could not render the current test route", error);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception error) {
            throw new IllegalStateException("Could not set test field " + name, error);
        }
    }

    private static String repeat(char character, int count) {
        StringBuilder value = new StringBuilder(count);
        for (int index = 0; index < count; index++) value.append(character);
        return value.toString();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file.toPath());
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private static volatile Fixture currentFixture;

    private static final class Fixture {
        final String pluginId;
        final FakePlugin plugin;
        final File packageFile;
        final String dialogTag;
        final AtomicInteger callbackCount = new AtomicInteger();
        final AtomicInteger appCompatCallbackCount = new AtomicInteger();
        final AtomicInteger dialogCreated = new AtomicInteger();
        final AtomicInteger dialogDestroyed = new AtomicInteger();
        final AtomicBoolean receivedAppCompatContext = new AtomicBoolean(false);
        OrbitCs3HostServices.LoadTransaction transaction;
        String configureTag;
        AssetManager assets;
        String resolvedUiText;
        String dialogTitle;
        String dialogMessage;

        Fixture(String pluginId, FakePlugin plugin, File packageFile, String dialogTag) {
            this.pluginId = pluginId;
            this.plugin = plugin;
            this.packageFile = packageFile;
            this.dialogTag = dialogTag;
        }
    }

    private static final class FakePlugin extends Plugin { }

    public static final class FakeSettingsDialogFragment extends DialogFragment {
        private String fixtureDialogTag;

        private FakeSettingsDialogFragment() { }

        static FakeSettingsDialogFragment newInstance(String tag) {
            FakeSettingsDialogFragment fragment = new FakeSettingsDialogFragment();
            fragment.fixtureDialogTag = tag;
            return fragment;
        }

        @NonNull
        @Override
        public android.app.Dialog onCreateDialog(android.os.Bundle savedInstanceState) {
            Fixture fixture = currentFixture;
            if (fixture != null) fixture.dialogCreated.incrementAndGet();
            String title = fixture != null && fixture.dialogTitle != null
                    ? fixture.dialogTitle : "Fake plugin settings";
            String message = fixture != null && fixture.dialogMessage != null
                    ? fixture.dialogMessage : "This dialog belongs to the instrumentation fake only.";
            return new AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("Close", (dialog, which) -> dismiss())
                    .create();
        }

        @Override
        public void onDestroy() {
            Fixture fixture = currentFixture;
            if (fixture != null) fixture.dialogDestroyed.incrementAndGet();
            super.onDestroy();
        }
    }

    private interface UiCondition { boolean isMet(); }
}
