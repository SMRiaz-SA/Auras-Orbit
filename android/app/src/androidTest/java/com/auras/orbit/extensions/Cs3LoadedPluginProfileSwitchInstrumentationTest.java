package com.auras.orbit.extensions;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.BuildConfig;
import com.auras.orbit.OrbitApplication;
import com.auras.orbit.profile.LocalProfileStore;
import com.auras.orbit.providers.MediaProvider;
import com.auras.orbit.providers.ProviderBatch;
import com.auras.orbit.providers.ProviderOutcome;
import com.auras.orbit.providers.SearchRequest;
import com.auras.orbit.providers.SearchType;
import com.lagradost.cloudstream3.APIHolder;
import com.lagradost.cloudstream3.MainAPI;
import com.lagradost.cloudstream3.SearchResponse;
import com.lagradost.cloudstream3.plugins.BasePlugin;
import com.lagradost.cloudstream3.utils.ExtractorApi;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.coroutines.Continuation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exercises the real profile cleanup path with an in-memory provider and a non-package marker. */
@RunWith(AndroidJUnit4.class)
public final class Cs3LoadedPluginProfileSwitchInstrumentationTest {
    private static final long CALLBACK_TIMEOUT_SECONDS = 25L;
    private static final String EXTENSION_PREFERENCES = "orbit_cloudstream_extensions_v1";
    private static final String PROFILE_PREFERENCES = "orbit_shell_local_profiles_v1";
    private static final String PROVIDER_PREFERENCES = "orbit_provider_selection_v1";
    private static final String INSTALLED_PLUGINS_KEY = "installed_plugins_json";
    private static final String ENABLED_PLUGINS_KEY = "profile_enabled_plugins_json";
    private static final String FAILURE_SECRET =
            "PROFILE_SWITCH_SECRET_7A31 https://private.fixture.invalid/path?token=must-not-escape";
    private static final String SAFE_PROVIDER_FAILURE = "Provider request failed";

    private Context target;
    private OrbitApplication application;
    private CS3PluginRuntime runtime;
    private CloudStreamExtensionManager extensionManager;
    private OrbitCs3HostServices hostServices;
    private LocalProfileStore profileStore;
    private SharedPreferences extensionPreferences;
    private SharedPreferences profilePreferences;
    private SharedPreferences providerPreferences;
    private PreferenceSnapshot extensionSnapshot;
    private PreferenceSnapshot profileSnapshot;
    private PreferenceSnapshot providerSnapshot;
    private String originalRuntimeProfileId;
    private String originalSelectedProfileId;
    private LocalProfileStore.Profile oldProfile;
    private LocalProfileStore.Profile targetProfile;
    private String pluginId;
    private File markerDirectory;
    private File markerFile;
    private FixturePlugin fakePlugin;
    private BlockingSearchMainApi fakeProvider;
    private ThrowingSearchMainApi fakeFailingProvider;
    private ExtractorApi fakeExtractor;
    private OrbitCs3HostServices.LoadTransaction transaction;
    private ProviderBatch<?> searchBatch;
    private CountDownLatch profileTransitionFinished;
    private AtomicReference<Boolean> profileTransitionSucceeded;
    private AtomicReference<List<String>> profileTransitionFailures;
    private CS3PluginRuntime.PluginCleanupBarrier originalCleanupBarrier;
    private TrackingCleanupBarrier trackingCleanupBarrier;

    @Before
    public void prepareIsolatedFixture() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("the profile-switch fixture runs only in the isolated queueRender package",
                "com.auras.orbit.queuerender", target.getPackageName());
        assertFalse("the ordinary fixture build keeps its canary gate closed", BuildConfig.CS3_CANARY_BUILD);

        application = (OrbitApplication) target.getApplicationContext();
        runtime = application.getCS3PluginRuntime();
        extensionManager = application.getCloudStreamExtensionManager();
        hostServices = OrbitCs3HostServices.get();
        profileStore = application.getLocalProfileStore();
        assertTrue("the accepted CS3 target crosswalk is enabled",
                runtime.isTargetPluginCrosswalkValidated());

        extensionPreferences = preferencesOf(extensionManager);
        profilePreferences = preferencesOf(profileStore);
        providerPreferences = preferencesOf(application.getProviderSelectionStore());
        extensionSnapshot = snapshotPreferences(EXTENSION_PREFERENCES, extensionPreferences);
        profileSnapshot = snapshotPreferences(PROFILE_PREFERENCES, profilePreferences);
        providerSnapshot = snapshotPreferences(PROVIDER_PREFERENCES, providerPreferences);
        originalRuntimeProfileId = runtime.getActiveProfileId();
        originalSelectedProfileId = profileStore.getActiveProfileId();

        assertTrue("the fixture must not disturb another loaded plugin handle",
                loadedPluginIds().isEmpty());
        assertTrue("the fixture must start without another outstanding host transaction",
                hostServices.getOutstandingPluginIds().isEmpty());

        oldProfile = findBaselineProfile();
        if (oldProfile == null) {
            oldProfile = profileStore.createProfile("Temporary profile-switch fixture");
            assertNotNull("the fixture must create a disposable baseline profile", oldProfile);
        }
        profileStore.selectProfile(oldProfile.id);
        reconcileAndAwait(oldProfile.id);
        assertEquals("the baseline profile is live before staging the fake provider",
                oldProfile.id, runtime.getActiveProfileId());

        targetProfile = profileStore.createProfile("Profile-switch target fixture");
        assertNotNull("the fixture must create a disposable target profile", targetProfile);
        pluginId = "orbit_profile_switch_fixture_" + UUID.randomUUID();
        markerDirectory = new File(target.getCacheDir(), "profile-switch-fixture-" + UUID.randomUUID());
        assertTrue("the fixture marker directory must be app-private",
                markerDirectory.mkdirs() || markerDirectory.isDirectory());
        markerFile = new File(markerDirectory, "in-memory-plugin.marker");
        try (FileOutputStream output = new FileOutputStream(markerFile)) {
            output.write("fixture marker; not an archive".getBytes(StandardCharsets.UTF_8));
        }

        fakePlugin = new FixturePlugin();
        fakeProvider = new BlockingSearchMainApi();
        fakeFailingProvider = new ThrowingSearchMainApi();
        fakeExtractor = new ExtractorApi() { };
        stageInstalledMetadataAndGrant();

        transaction = hostServices.beginPluginLoad(pluginId, markerFile.getAbsolutePath(), fakePlugin);
        fakePlugin.registerMainAPI(fakeProvider);
        fakePlugin.registerMainAPI(fakeFailingProvider);
        fakePlugin.registerExtractorAPI(fakeExtractor);
        transaction.commit();
        seedLoadedPluginHandle(fakePlugin, markerFile, transaction, pluginId);

        Field barrierField = CS3PluginRuntime.class.getDeclaredField("pluginCleanupBarrier");
        barrierField.setAccessible(true);
        originalCleanupBarrier = (CS3PluginRuntime.PluginCleanupBarrier) barrierField.get(runtime);
        assertNotNull("the real application cleanup barrier must be installed", originalCleanupBarrier);
        trackingCleanupBarrier = new TrackingCleanupBarrier(originalCleanupBarrier);
        runtime.setPluginCleanupBarrier(trackingCleanupBarrier);

        List<MediaProvider> oldProviders = application.getMediaProvidersForProfile(oldProfile.id);
        assertEquals("the old profile exposes both committed fake registrations", 2, oldProviders.size());
        assertEquals("both fake MainAPI instances belong to the committed host transaction", 2,
                transaction.getProviderCount());
        assertEquals("the extractor belongs to the committed host transaction", 1,
                transaction.getExtractorCount());
        assertTrue("the committed host transaction is visible", hostServices.hasCommittedRegistrations(pluginId));
    }

    @Test
    public void profileSwitchCancelsCallThenUnloadsAndRollsBackFakeRegistration() throws Exception {
        String blockingProviderId = providerId(pluginId, 0);
        String failingProviderId = providerId(pluginId, 1);
        List<MediaProvider> oldProviders = application.getMediaProvidersForProfile(oldProfile.id);
        Set<String> availableIds = new HashSet<>();
        for (MediaProvider provider : oldProviders) availableIds.add(provider.descriptor().id);
        assertEquals("the old-profile provider IDs identify both fixture registrations",
                new HashSet<>(java.util.Arrays.asList(blockingProviderId, failingProviderId)), availableIds);
        for (MediaProvider provider : oldProviders) {
            assertTrue("the retained adapter proves its old-profile registration is current",
                    provider.isRegistrationCurrent());
        }

        CountDownLatch failingProviderFailed = new CountDownLatch(1);
        CountDownLatch blockingProviderLoading = new CountDownLatch(1);
        CountDownLatch blockingProviderCancelled = new CountDownLatch(1);
        searchBatch = application.getProviderOperationCoordinator().search(
                oldProviders, new SearchRequest("fixture", SearchType.ALL, 1), 60_000L, outcomes -> {
                    for (ProviderOutcome<?> outcome : outcomes) {
                        String id = outcome.provider.id;
                        if (failingProviderId.equals(id)
                                && outcome.status == ProviderOutcome.Status.FAILURE) {
                            failingProviderFailed.countDown();
                        }
                        if (blockingProviderId.equals(id)) {
                            if (outcome.status == ProviderOutcome.Status.LOADING) {
                                blockingProviderLoading.countDown();
                            } else if (outcome.status == ProviderOutcome.Status.CANCELLED) {
                                blockingProviderCancelled.countDown();
                            }
                        }
                    }
                });
        assertTrue("the in-memory MainAPI search must start on a provider worker",
                fakeProvider.entered.await(5L, TimeUnit.SECONDS));
        assertTrue("the throwing MainAPI must reach a terminal failure before the profile switch",
                failingProviderFailed.await(5L, TimeUnit.SECONDS));
        assertTrue("the blocking MainAPI remains loading while the other provider fails",
                blockingProviderLoading.await(2L, TimeUnit.SECONDS));
        ProviderOutcome<?> failedBeforeSwitch = findOutcome(searchBatch.snapshot(), failingProviderId);
        ProviderOutcome<?> blockedBeforeSwitch = findOutcome(searchBatch.snapshot(), blockingProviderId);
        assertEquals("the throwing provider reports failure", ProviderOutcome.Status.FAILURE,
                failedBeforeSwitch.status);
        assertEquals("the blocking provider is still in flight before reconciliation",
                ProviderOutcome.Status.LOADING, blockedBeforeSwitch.status);
        assertEquals("provider exceptions expose only the fixed sanitized diagnostic",
                SAFE_PROVIDER_FAILURE, failedBeforeSwitch.message);
        assertFalse("provider diagnostics omit the fake plugin secret", failedBeforeSwitch.message.contains(FAILURE_SECRET));
        assertFalse("provider diagnostics omit the fake URL host",
                failedBeforeSwitch.message.contains("private.fixture.invalid"));
        assertEquals("the throwing fake callback runs once", 1,
                fakeFailingProvider.invocations.get());
        assertFalse("the fake provider call must still be in flight before switching profiles",
                application.getProviderOperationCoordinator().awaitProviderCallsQuiesced(100L));

        profileTransitionFinished = new CountDownLatch(1);
        profileTransitionSucceeded = new AtomicReference<>();
        profileTransitionFailures = new AtomicReference<>(Collections.emptyList());
        application.reconcilePluginsForProfile(targetProfile.id, (succeeded, failures) -> {
            profileTransitionSucceeded.set(succeeded);
            profileTransitionFailures.set(failures);
            profileTransitionFinished.countDown();
        });

        assertTrue("Orbit must finish the real target-profile reconciliation",
                profileTransitionFinished.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("the profile reconciliation callback must report success",
                Boolean.TRUE, profileTransitionSucceeded.get());
        assertTrue("the target profile must have no reconciliation failures",
                profileTransitionFailures.get().isEmpty());
        assertTrue("the blocking provider outcome is cancelled by the profile transition",
                blockingProviderCancelled.await(2L, TimeUnit.SECONDS));
        ProviderOutcome<?> failedAfterSwitch = findOutcome(searchBatch.snapshot(), failingProviderId);
        ProviderOutcome<?> cancelledAfterSwitch = findOutcome(searchBatch.snapshot(), blockingProviderId);
        assertEquals("the already failed provider remains failed", ProviderOutcome.Status.FAILURE,
                failedAfterSwitch.status);
        assertEquals("the blocking provider is cancelled by the profile switch",
                ProviderOutcome.Status.CANCELLED, cancelledAfterSwitch.status);
        assertEquals("the failure diagnostic stays sanitized after the profile switch",
                SAFE_PROVIDER_FAILURE, failedAfterSwitch.message);
        assertFalse("the failure diagnostic still omits the fake secret",
                failedAfterSwitch.message.contains(FAILURE_SECRET));
        assertEquals("the fake callback must observe cooperative interruption", 0L,
                fakeProvider.interrupted.getCount());
        assertEquals("the fake callback must leave its call boundary", 0L,
                fakeProvider.exited.getCount());
        assertTrue("all provider calls must be quiesced after reconciliation",
                application.getProviderOperationCoordinator().awaitProviderCallsQuiesced(5_000L));

        assertEquals("beforeUnload is invoked exactly once", 1, fakePlugin.beforeUnloadCount.get());
        assertEquals("runtime cleanup waits at the plugin call barrier exactly once", 1,
                trackingCleanupBarrier.pauseCount.get());
        assertTrue("the plugin barrier observed completed provider-call quiescence",
                trackingCleanupBarrier.pauseSucceeded.get());
        assertEquals("successful cleanup reopens the plugin barrier exactly once", 1,
                trackingCleanupBarrier.cleanupSucceededCount.get());

        assertEquals("the runtime now reports the target profile", targetProfile.id,
                runtime.getActiveProfileId());
        profileStore.selectProfile(targetProfile.id);
        assertEquals("the persisted active profile follows the completed transition",
                targetProfile.id, profileStore.getActiveProfileId());
        assertTrue("the accepted CS3 compatibility gate remains enabled after profile cleanup",
                runtime.isTargetPluginCrosswalkValidated());
        assertFalse("the canary build gate remains closed after profile cleanup",
                BuildConfig.CS3_CANARY_BUILD);
        assertFalse("the profile barrier is reopened after successful reconciliation",
                application.getCs3ProviderRegistry().isProfileReconcileInProgress());

        assertTrue("the old profile no longer exposes a provider after its runtime handle is removed",
                application.getMediaProvidersForProfile(oldProfile.id).isEmpty());
        for (MediaProvider provider : oldProviders) {
            assertFalse("the retained old-profile adapter rejects its stale registration",
                    provider.isRegistrationCurrent());
        }
        assertFalse("the fake registration transaction was rolled back",
                hostServices.hasOutstandingRegistrations(pluginId));
        assertFalse("the loaded-plugin handle was removed", runtime.isLoaded(pluginId));
        assertFalse("the exact fake MainAPI identity was removed from APIHolder",
                containsIdentity(APIHolder.INSTANCE.getAllProviders(), fakeProvider));
        assertFalse("the exact throwing MainAPI identity was removed from APIHolder",
                containsIdentity(APIHolder.INSTANCE.getAllProviders(), fakeFailingProvider));
        assertFalse("the exact fake ExtractorApi identity was removed from its host registry",
                containsIdentity(hostServices.getExtractorSnapshot(), fakeExtractor));
        assertFalse("no committed provider registration remains for the fake plugin",
                hasCommittedProvider(pluginId));
    }

    @After
    public void restoreFixtureState() throws Exception {
        if (searchBatch != null) searchBatch.close();
        if (fakeProvider != null) fakeProvider.release.countDown();
        if (profileTransitionFinished != null) {
            profileTransitionFinished.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        if (fakeProvider != null && fakeProvider.entered.getCount() == 0L) {
            fakeProvider.exited.await(5L, TimeUnit.SECONDS);
        }

        if (pluginId != null && runtime != null && hostServices != null) {
            if (runtime.isLoaded(pluginId)) runtime.deactivateIfClean(pluginId);
            if (hostServices.hasOutstandingRegistrations(pluginId)) {
                try { hostServices.unloadPlugin(pluginId); }
                catch (Throwable ignored) { }
            }
            if (runtime.isLoaded(pluginId) && !hostServices.hasOutstandingRegistrations(pluginId)) {
                runtime.deactivateIfClean(pluginId);
            }
        } else if (transaction != null && transaction.isCommitted()) {
            try { transaction.rollback(); }
            catch (Throwable ignored) { }
        }

        if (runtime != null && originalCleanupBarrier != null) {
            runtime.setPluginCleanupBarrier(originalCleanupBarrier);
        }

        restorePreferences(extensionSnapshot, extensionPreferences);
        restorePreferences(profileSnapshot, profilePreferences);
        restorePreferences(providerSnapshot, providerPreferences);

        if (runtime != null && extensionManager != null && originalRuntimeProfileId != null
                && !originalRuntimeProfileId.isEmpty()) {
            if (!originalRuntimeProfileId.equals(runtime.getActiveProfileId())) {
                reconcileAndAwait(originalRuntimeProfileId);
            }
        } else if (runtime != null && extensionManager != null && originalRuntimeProfileId == null) {
            List<String> failures = runtime.reconcileProfile(null, extensionManager);
            assertTrue("the originally inactive runtime profile must be restored", failures.isEmpty());
        }

        if (markerFile != null && markerFile.exists()) {
            assertTrue("the non-package fixture marker must be removed", markerFile.delete());
        }
        if (markerDirectory != null && markerDirectory.exists()) {
            assertTrue("the empty fixture marker directory must be removed", markerDirectory.delete());
        }
        if (extensionSnapshot != null && !extensionSnapshot.fileExisted) {
            assertTrue("an originally absent extension preference file remains absent",
                    target.deleteSharedPreferences(EXTENSION_PREFERENCES));
        }
        if (profileSnapshot != null && !profileSnapshot.fileExisted) {
            assertTrue("an originally absent profile preference file remains absent",
                    target.deleteSharedPreferences(PROFILE_PREFERENCES));
        }
        if (providerSnapshot != null && !providerSnapshot.fileExisted) {
            assertTrue("an originally absent provider preference file remains absent",
                    target.deleteSharedPreferences(PROVIDER_PREFERENCES));
        }
        verifyPreferenceSnapshot(extensionSnapshot, extensionPreferences);
        verifyPreferenceSnapshot(profileSnapshot, profilePreferences);
        verifyPreferenceSnapshot(providerSnapshot, providerPreferences);
        if (pluginId != null) {
            assertFalse("fixture teardown leaves no outstanding host registrations",
                    hostServices.hasOutstandingRegistrations(pluginId));
            assertFalse("fixture teardown leaves no fake runtime handle", runtime.isLoaded(pluginId));
            assertFalse("fixture teardown removes the blocking MainAPI identity",
                    containsIdentity(APIHolder.INSTANCE.getAllProviders(), fakeProvider));
            assertFalse("fixture teardown removes the throwing MainAPI identity",
                    containsIdentity(APIHolder.INSTANCE.getAllProviders(), fakeFailingProvider));
        }
        if (originalSelectedProfileId != null && !originalSelectedProfileId.isEmpty()) {
            assertEquals("profile preferences restore the original active profile",
                    originalSelectedProfileId, profileStore.getActiveProfileId());
        }
        if (originalRuntimeProfileId != null) {
            assertEquals("runtime profile state returns to its original profile",
                    originalRuntimeProfileId, runtime.getActiveProfileId());
        } else if (runtime != null) {
            assertNull("the originally inactive runtime profile is restored", runtime.getActiveProfileId());
        }
    }

    private LocalProfileStore.Profile findBaselineProfile() {
        LocalProfileStore.Profile active = profileStore.getActiveProfile();
        if (active != null) return active;
        if (originalRuntimeProfileId != null) {
            for (LocalProfileStore.Profile profile : profileStore.getProfiles()) {
                if (originalRuntimeProfileId.equals(profile.id)) return profile;
            }
        }
        List<LocalProfileStore.Profile> profiles = profileStore.getProfiles();
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    private void stageInstalledMetadataAndGrant() throws Exception {
        JSONArray installed = new JSONArray(extensionPreferences.getString(INSTALLED_PLUGINS_KEY, "[]"));
        CloudStreamExtensionManager.InstalledPlugin metadata =
                new CloudStreamExtensionManager.InstalledPlugin(pluginId,
                        "https://example.invalid/profile-switch-fixture.json",
                        "In-memory profile switch fixture", "ProfileSwitchFixture", null, 1L,
                        repeat('0', 64), false, FixturePlugin.class.getName(), false,
                        markerFile.getAbsolutePath(), null, System.currentTimeMillis());
        installed.put(metadata.toJson());

        JSONObject enabled = new JSONObject(extensionPreferences.getString(ENABLED_PLUGINS_KEY, "{}"));
        JSONArray oldGrants = enabled.optJSONArray(oldProfile.id);
        JSONArray updatedGrants = new JSONArray();
        boolean alreadyEnabled = false;
        if (oldGrants != null) {
            for (int index = 0; index < oldGrants.length(); index++) {
                String id = oldGrants.optString(index, "");
                updatedGrants.put(id);
                if (pluginId.equals(id)) alreadyEnabled = true;
            }
        }
        if (!alreadyEnabled) updatedGrants.put(pluginId);
        enabled.put(oldProfile.id, updatedGrants);
        assertTrue("the synthetic metadata and old-profile grant must be staged atomically",
                extensionPreferences.edit()
                        .putString(INSTALLED_PLUGINS_KEY, installed.toString())
                        .putString(ENABLED_PLUGINS_KEY, enabled.toString())
                        .commit());
        assertNotNull("the registry must see the fake installed metadata",
                extensionManager.getInstalledPlugin(pluginId));
    }

    private SharedPreferences preferencesOf(Object owner) throws Exception {
        Field field = owner.getClass().getDeclaredField("preferences");
        field.setAccessible(true);
        return (SharedPreferences) field.get(owner);
    }

    private void reconcileAndAwait(String profileId) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Boolean> succeeded = new AtomicReference<>();
        AtomicReference<List<String>> failures = new AtomicReference<>(Collections.emptyList());
        application.reconcilePluginsForProfile(profileId, (result, messages) -> {
            succeeded.set(result);
            failures.set(messages);
            finished.countDown();
        });
        assertTrue("the application profile reconciliation callback must complete",
                finished.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("the profile runtime transition must succeed: " + failures.get(),
                Boolean.TRUE, succeeded.get());
    }

    private void seedLoadedPluginHandle(BasePlugin plugin, File file,
                                        OrbitCs3HostServices.LoadTransaction loadTransaction,
                                        String id) throws Exception {
        Class<?> loadedType = Class.forName("com.auras.orbit.extensions.CS3PluginRuntime$LoadedPlugin");
        Constructor<?> constructor = loadedType.getDeclaredConstructor(Object.class,
                dalvik.system.PathClassLoader.class, File.class,
                OrbitCs3HostServices.LoadTransaction.class, android.content.res.AssetManager.class,
                String.class);
        constructor.setAccessible(true);
        // A null PathClassLoader is sufficient for this in-memory handle and owns no closeable DEX resources.
        Object loaded = constructor.newInstance(plugin, null, file, loadTransaction, null, id);
        Field loadedPluginsField = CS3PluginRuntime.class.getDeclaredField("loadedPlugins");
        loadedPluginsField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> loadedPlugins =
                (Map<String, Object>) loadedPluginsField.get(runtime);
        loadedPlugins.put(id, loaded);
    }

    private Set<String> loadedPluginIds() throws Exception {
        Field loadedPluginsField = CS3PluginRuntime.class.getDeclaredField("loadedPlugins");
        loadedPluginsField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> loadedPlugins =
                (Map<String, Object>) loadedPluginsField.get(runtime);
        return new HashSet<>(loadedPlugins.keySet());
    }

    private boolean hasCommittedProvider(String id) {
        for (OrbitCs3HostServices.ProviderRegistration registration
                : hostServices.getCommittedProviderRegistrations()) {
            if (id.equals(registration.pluginId)) return true;
        }
        return false;
    }

    private static String providerId(String id, int registrationIndex) {
        return "cs3:" + id + ":provider-" + registrationIndex;
    }

    private static ProviderOutcome<?> findOutcome(
            List<? extends ProviderOutcome<?>> outcomes, String providerId) {
        for (ProviderOutcome<?> outcome : outcomes) {
            if (providerId.equals(outcome.provider.id)) return outcome;
        }
        throw new AssertionError("No provider outcome exists for " + providerId);
    }

    private static boolean containsIdentity(Iterable<?> values, Object wanted) {
        for (Object value : values) if (value == wanted) return true;
        return false;
    }

    private PreferenceSnapshot snapshotPreferences(String name, SharedPreferences preferences) {
        File file = preferenceFile(name);
        return new PreferenceSnapshot(name, file.isFile(), copyPreferenceValues(preferences.getAll()));
    }

    private File preferenceFile(String name) {
        return new File(target.getApplicationInfo().dataDir, "shared_prefs/" + name + ".xml");
    }

    private void verifyPreferenceSnapshot(PreferenceSnapshot snapshot, SharedPreferences preferences) {
        if (snapshot == null || preferences == null) return;
        assertEquals("the original " + snapshot.name + " preference values are restored",
                snapshot.values, copyPreferenceValues(preferences.getAll()));
        assertEquals("the original " + snapshot.name + " preference-file presence is restored",
                snapshot.fileExisted, preferenceFile(snapshot.name).isFile());
    }

    private static Map<String, Object> copyPreferenceValues(Map<String, ?> values) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> item : values.entrySet()) {
            Object value = item.getValue();
            if (value instanceof Set) value = new HashSet<>((Set<?>) value);
            copy.put(item.getKey(), value);
        }
        return copy;
    }

    private void restorePreferences(PreferenceSnapshot snapshot, SharedPreferences preferences) {
        if (snapshot == null || preferences == null) return;
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, Object> item : snapshot.values.entrySet()) {
            Object value = item.getValue();
            if (value instanceof String) editor.putString(item.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(item.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(item.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(item.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(item.getKey(), (Float) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                editor.putStringSet(item.getKey(), new HashSet<>(strings));
            } else throw new IllegalStateException("Unsupported SharedPreferences value type: "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        assertTrue("the original " + snapshot.name + " preferences must be restored", editor.commit());
    }

    private static String repeat(char value, int count) {
        char[] output = new char[count];
        java.util.Arrays.fill(output, value);
        return new String(output);
    }

    private static final class PreferenceSnapshot {
        final String name;
        final boolean fileExisted;
        final Map<String, Object> values;

        PreferenceSnapshot(String name, boolean fileExisted, Map<String, Object> values) {
            this.name = name;
            this.fileExisted = fileExisted;
            this.values = values;
        }
    }

    private static final class FixturePlugin extends BasePlugin {
        final AtomicInteger beforeUnloadCount = new AtomicInteger();

        @Override public void beforeUnload() { beforeUnloadCount.incrementAndGet(); }
    }

    private static final class BlockingSearchMainApi extends MainAPI {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        BlockingSearchMainApi() { setName("In-memory profile switch provider"); }

        @Override
        public Object search(String query,
                Continuation<? super List<SearchResponse>> continuation) {
            entered.countDown();
            try {
                release.await();
                return Collections.emptyList();
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("fixture provider call cancelled");
            } finally {
                exited.countDown();
            }
        }
    }

    private static final class ThrowingSearchMainApi extends MainAPI {
        final AtomicInteger invocations = new AtomicInteger();

        ThrowingSearchMainApi() { setName("In-memory failing profile switch provider"); }

        @Override
        public Object search(String query,
                Continuation<? super List<SearchResponse>> continuation) {
            invocations.incrementAndGet();
            throw new IllegalStateException("Provider fixture failure: " + FAILURE_SECRET);
        }
    }

    private static final class TrackingCleanupBarrier implements CS3PluginRuntime.PluginCleanupBarrier {
        final CS3PluginRuntime.PluginCleanupBarrier delegate;
        final AtomicInteger pauseCount = new AtomicInteger();
        final AtomicReference<Boolean> pauseSucceeded = new AtomicReference<>(false);
        final AtomicInteger cleanupSucceededCount = new AtomicInteger();

        TrackingCleanupBarrier(CS3PluginRuntime.PluginCleanupBarrier delegate) {
            this.delegate = delegate;
        }

        @Override public void blockPluginCalls(String id) { delegate.blockPluginCalls(id); }

        @Override public boolean scheduleCleanupRetry(String id, Runnable cleanup) {
            return delegate.scheduleCleanupRetry(id, cleanup);
        }

        @Override public boolean pauseAndAwaitPluginCalls(String id, long timeoutMs)
                throws InterruptedException {
            pauseCount.incrementAndGet();
            boolean succeeded = delegate.pauseAndAwaitPluginCalls(id, timeoutMs);
            pauseSucceeded.set(succeeded);
            return succeeded;
        }

        @Override public void pluginCleanupSucceeded(String id) {
            delegate.pluginCleanupSucceeded(id);
            cleanupSucceededCount.incrementAndGet();
        }
    }
}
