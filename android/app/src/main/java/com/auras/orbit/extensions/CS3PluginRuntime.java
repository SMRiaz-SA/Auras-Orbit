package com.auras.orbit.extensions;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import com.auras.orbit.OrbitApplication;
import com.auras.orbit.BuildConfig;
import com.auras.orbit.profile.ProviderSelectionStore;
import com.auras.orbit.providers.AttributedSearchHit;
import com.auras.orbit.providers.Cs3MediaProviderAdapter;
import com.auras.orbit.providers.Cs3ProviderRegistry;
import com.auras.orbit.providers.MediaProvider;
import com.auras.orbit.providers.ProviderBatch;
import com.auras.orbit.providers.ProviderOutcome;
import com.auras.orbit.providers.ProviderOperationCoordinator;
import com.auras.orbit.providers.SearchRequest;
import com.auras.orbit.providers.SearchType;
import com.lagradost.cloudstream3.APIHolder;

import org.json.JSONException;
import org.json.JSONObject;

import dalvik.system.PathClassLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** CS3 runtime loader. Actual class loading and registration determine compatibility. */
public final class CS3PluginRuntime {
    // The CS3 compatibility crosswalk is accepted for product activation.
    private static final boolean TARGET_PLUGIN_CROSSWALK_VALIDATED = true;
    private static final String STREAMPLAY_CANARY_REPOSITORY_URL =
            "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json";
    public static final String STREAMPLAY_V694_CANARY_PLUGIN_ID =
            STREAMPLAY_CANARY_REPOSITORY_URL + "|streamplay";
    /** Per-authorization replay guard; the previous TV marker is deliberately left untouched. */
    public static final String STREAMPLAY_V694_CANARY_CONSUMED_MARKER =
            "orbit-streamplay-v694-canary-consumed-2026-11-03-third-run.sha256";
    public static final String STREAMPLAY_V694_CANARY_AUTHORIZATION_ID = "2026-11-03-third-run";
    public static final String STREAMPLAY_V694_CANARY_MARKER_CONTENT =
            "authorization=2026-11-03-third-run\n"
                    + "sha256=1e4c77492069b3b8da2c2825c4d40e813436c0c2feed73b193b264ba4e1736ea\n";
    /** Fresh user authorization must create exactly one private marker with a new unique ID. */
    public static final String STREAMPLAY_V694_CANARY_AUTHORIZATION_MARKER_PREFIX =
            "orbit-streamplay-v694-canary-authorization-";
    private static final String STREAMPLAY_V694_CANARY_AUTHORIZATION_MARKER_SUFFIX = ".sha256";
    private static final String STREAMPLAY_V694_CANARY_CONSUMED_AUTHORIZATION_PREFIX =
            "orbit-streamplay-v694-canary-consumed-authorization-";
    private static final int MAX_STREAMPLAY_CANARY_AUTHORIZATION_ID_CHARS = 64;
    private static final int MAX_STREAMPLAY_CANARY_AUTHORIZATION_MARKER_BYTES = 192;
    private static final String STREAMPLAY_CANARY_NAME = "StreamPlay";
    private static final String STREAMPLAY_CANARY_CLASS = "com.phisher98.StreamPlayPlugin";
    private static final String STREAMPLAY_CANARY_SHA256 =
            "1e4c77492069b3b8da2c2825c4d40e813436c0c2feed73b193b264ba4e1736ea";
    private static final long STREAMPLAY_CANARY_SIZE = 2_106_784L;
    private static final long STREAMPLAY_CANARY_VERSION = 694L;
    public static final int MAX_CANARY_FAILURE_CAUSE_DEPTH = 32;
    public static final int MAX_CANARY_FAILURE_DIAGNOSTIC_CHARS = 448;
    private static final int MAX_CANARY_DIAGNOSTIC_CLASS_CHARS = 96;
    private static final int MAX_CANARY_DIAGNOSTIC_METHOD_CHARS = 48;
    private static final int MAX_CANARY_DIAGNOSTIC_FRAMES = 3;
    private static final int MAX_CANARY_DIAGNOSTIC_FRAMES_INSPECTED = 16;
    private static final int MAX_CANARY_MANIFEST_BYTES = 1024 * 1024;
    private static final String BASE_PLUGIN = "com.lagradost.cloudstream3.plugins.BasePlugin";
    private static final String ANDROID_PLUGIN = "com.lagradost.cloudstream3.plugins.Plugin";
    private static final String MAIN_API = "com.lagradost.cloudstream3.MainAPI";
    private static final String API_HOLDER = "com.lagradost.cloudstream3.APIHolder";
    private static final String REPOSITORY_MANAGER = "com.lagradost.cloudstream3.plugins.RepositoryManager";
    private static final long PLUGIN_PROVIDER_QUIESCE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15L);
    public static final long CANARY_PROVIDER_SEARCH_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15L);
    public static final long CANARY_PROVIDER_SEARCH_WAIT_MS = CANARY_PROVIDER_SEARCH_TIMEOUT_MS + 1_000L;
    private static final long CANARY_PROVIDER_METADATA_WAIT_MS = TimeUnit.SECONDS.toMillis(5L);
    public static final int MAX_CANARY_PROVIDER_SEARCH_RESULTS = 200;
    private static final String CANARY_PROVIDER_SEARCH_QUERY = "Avatar";
    private static final String CANARY_RUNTIME_STAGE_JOURNAL = "orbit-streamplay-v694-runtime-stages.log";
    private static final String CANARY_RUNTIME_STAGE_TAG = "OrbitCS3CanaryRuntime";
    private static final Object CANARY_RUNTIME_STAGE_JOURNAL_LOCK = new Object();

    private final Context appContext;
    /** Serializes plugin registry and profile lifecycle changes without blocking UI status reads. */
    private final Object lifecycleLock = new Object();
    private final Map<String, LoadedPlugin> loadedPlugins = new ConcurrentHashMap<>();
    private final Set<String> cleanupPendingPluginIds = new HashSet<>();
    private final Set<String> deferredCleanupPluginIds = new HashSet<>();
    private volatile String activeProfileId;
    private volatile boolean activeProfileFullyReconciled;
    private volatile boolean activeProfileNeedsPluginReconcile;
    private volatile PluginCleanupBarrier pluginCleanupBarrier;
    private String cleanupRecoveryProfileId;
    private boolean cleanupRecoveryWasFullyReconciled;
    private boolean cleanupRecoverySnapshotValid;
    private AuthorizationMarker armedCanaryAuthorization;

    public CS3PluginRuntime(Context context) {
        appContext = context.getApplicationContext();
    }

    public boolean isTargetPluginCrosswalkValidated() {
        return TARGET_PLUGIN_CROSSWALK_VALIDATED;
    }

    /** Whether ordinary activation is available in this build. The acceptance variant is
     * reserved for disposable gate profiles; regular builds require the validated crosswalk. */
    public boolean canAttemptOrdinaryPluginActivation() {
        return isOrdinaryPluginRuntimeEnabled();
    }

    /** Acceptance activation is a test-only path; product builds remain gated. */
    private static boolean isOrdinaryPluginRuntimeEnabled() {
        return ordinaryPluginRuntimeEnabled(
                TARGET_PLUGIN_CROSSWALK_VALIDATED, BuildConfig.CS3_ACCEPTANCE_BUILD);
    }

    static boolean ordinaryPluginRuntimeEnabled(boolean crosswalkValidated, boolean acceptanceBuild) {
        return crosswalkValidated || acceptanceBuild;
    }

    /** Arms only the isolated canary build when exactly one fresh private authorization marker exists. */
    public boolean armStreamPlayV694DebugCanaryOnce() {
        synchronized (lifecycleLock) {
            if (!BuildConfig.CS3_CANARY_BUILD || !isDebuggableBuild()) return false;
            AuthorizationMarker marker = readUniqueFreshCanaryAuthorizationMarker();
            if (marker == null || marker.consumedFile.exists()) return false;
            armedCanaryAuthorization = marker;
            return true;
        }
    }

    /** Clears an in-memory arm when Orbit could not enqueue the already authorized one-shot. */
    public void disarmUnscheduledStreamPlayV694DebugCanary() {
        synchronized (lifecycleLock) {
            armedCanaryAuthorization = null;
        }
    }

    /** Reports whether a well-formed, unconsumed fresh authorization marker is present. */
    public boolean hasFreshStreamPlayV694CanaryAuthorization() {
        synchronized (lifecycleLock) {
            if (!BuildConfig.CS3_CANARY_BUILD || !isDebuggableBuild()) return false;
            AuthorizationMarker marker = readUniqueFreshCanaryAuthorizationMarker();
            return marker != null && !marker.consumedFile.exists();
        }
    }

    /**
     * Test-only one-shot path. After staged load, it probes one first-page provider search and
     * rolls back. Ordinary activation remains closed by the global crosswalk gate.
     * This route is unavailable until a new unique authorization marker has been provisioned in
     * this isolated package's no-backup directory. The extractor cap limits registrations only;
     * arbitrary code in plugin constructors or callbacks is not sandboxed by that cap.
     */
    public CanaryResult runStreamPlayV694DebugCanary(
            CloudStreamExtensionManager.InstalledPlugin requestedPlugin, String activeProfileId) {
        final long canaryStartedNanos = System.nanoTime();
        if (!BuildConfig.CS3_CANARY_BUILD || !isDebuggableBuild()) {
            return CanaryResult.failure("The StreamPlay canary is available only in the isolated debuggable canary build.",
                    "canary_build_required", true, 0, 0);
        }
        // Record the dispatch boundary before taking the lifecycle lock. UI status queries use
        // independent lock-free/short paths and therefore remain responsive during plugin code.
        logCanaryRuntimeStage("canary.dispatch.before_lock", canaryStartedNanos, 0, 0, 0);
        synchronized (lifecycleLock) {
            logCanaryRuntimeStage("canary.dispatch.lock_acquired", canaryStartedNanos, 0, 0, 1);
            return runStreamPlayV694DebugCanaryLocked(
                    requestedPlugin, activeProfileId, canaryStartedNanos);
        }
    }

    private CanaryResult runStreamPlayV694DebugCanaryLocked(
            CloudStreamExtensionManager.InstalledPlugin requestedPlugin,
            String activeProfileId,
            long canaryStartedNanos) {
        logCanaryRuntimeStage("canary.entry", canaryStartedNanos, 0, 0, 0);
        AuthorizationMarker marker = armedCanaryAuthorization;
        AuthorizationMarker currentMarker = readUniqueFreshCanaryAuthorizationMarker();
        if (marker == null || currentMarker == null || !marker.matches(currentMarker)
                || marker.consumedFile.exists()) {
            armedCanaryAuthorization = null;
            return CanaryResult.failure("A new unique StreamPlay v694 authorization marker is required.",
                    "canary_authorization_required", true, 0, 0);
        }
        if (!"orbit-cs3-extension-worker".equals(Thread.currentThread().getName())) {
            return CanaryResult.failure("The StreamPlay canary must run on Orbit's serialized extension worker.",
                    "canary_worker_required", true, 0, 0);
        }
        if (!OrbitCs3HostServices.get().getExtensionManager().isStorageRecoveryReady()) {
            return CanaryResult.failure("The StreamPlay canary is blocked until CS3 package storage recovery completes.",
                    "canary_storage_unready", true, 0, 0);
        }
        if (activeProfileId == null || activeProfileId.trim().isEmpty()) {
            return CanaryResult.failure("The StreamPlay canary requires a resolved active profile to check its grant.",
                    "canary_profile_unresolved", true, 0, 0);
        }
        CloudStreamExtensionManager extensionManager = OrbitCs3HostServices.get().getExtensionManager();
        if (extensionManager.isEnabledForProfile(activeProfileId, STREAMPLAY_V694_CANARY_PLUGIN_ID)) {
            return CanaryResult.failure("The StreamPlay canary is blocked because the active profile has a StreamPlay grant.",
                    "canary_profile_granted", true, 0, 0);
        }
        OrbitCs3HostServices services = OrbitCs3HostServices.get();
        if (!loadedPlugins.isEmpty() || !cleanupPendingPluginIds.isEmpty()
                || !deferredCleanupPluginIds.isEmpty() || !services.getOutstandingPluginIds().isEmpty()) {
            return CanaryResult.failure("The StreamPlay canary requires an empty plugin runtime and registration registry.",
                    "canary_registry_not_empty", true, 0, 0);
        }
        String identityFailure = validateStreamPlayV694CanaryIdentity(requestedPlugin, extensionManager);
        if (identityFailure != null) {
            return CanaryResult.failure(identityFailure, "canary_identity_rejected", true, 0, 0);
        }
        logCanaryRuntimeStage("preflight.before", canaryStartedNanos, 0, 0, 0);
        Cs3DexCapabilityPreflight.Result preflight = preflightCompatibility(requestedPlugin);
        logCanaryRuntimeStage("preflight.after", canaryStartedNanos, 0, 0,
                preflight.isExactHostSurfacePresent() ? 1 : 0);
        if (!preflight.isExactHostSurfacePresent()) {
            return CanaryResult.failure("Static exact-member preflight failed without loading plugin code: "
                    + describePreflight(preflight), "canary_preflight_failed", true, 0, 0);
        }
        logCanaryRuntimeStage("authorization.consume.before", canaryStartedNanos, 0, 0, 0);
        if (!consumeCanaryAuthorizationMarker(marker)) {
            armedCanaryAuthorization = null;
            return CanaryResult.failure("The unique StreamPlay canary authorization marker could not be consumed.",
                    "canary_authorization_consumption_failed", true, 0, 0);
        }
        armedCanaryAuthorization = null;
        logCanaryRuntimeStage("authorization.consume.after", canaryStartedNanos, 0, 0, 1);

        PluginCleanupBarrier barrier = pluginCleanupBarrier;
        if (barrier == null) {
            logCanaryRuntimeStage("barrier.unavailable", canaryStartedNanos, 0, 0, 0);
            return recordCanaryCleanupPending(STREAMPLAY_V694_CANARY_PLUGIN_ID, null,
                    "The StreamPlay canary is blocked because the provider-call barrier is unavailable.", 0, 0);
        }
        final String pluginId = STREAMPLAY_V694_CANARY_PLUGIN_ID;
        try {
            logCanaryRuntimeStage("barrier.block.before", canaryStartedNanos, 0, 0, 0);
            barrier.blockPluginCalls(pluginId);
            logCanaryRuntimeStage("barrier.block.after", canaryStartedNanos, 0, 0, 1);
            logCanaryRuntimeStage("barrier.await.before", canaryStartedNanos, 0, 0, 0);
            if (!barrier.pauseAndAwaitPluginCalls(pluginId, PLUGIN_PROVIDER_QUIESCE_TIMEOUT_MS)) {
                logCanaryRuntimeStage("barrier.await.pending", canaryStartedNanos, 0, 0, 0);
                return recordCanaryCleanupPending(pluginId, null,
                        "Provider calls did not quiesce before the StreamPlay canary; the target remains blocked.", 0, 0);
            }
            logCanaryRuntimeStage("barrier.await.after", canaryStartedNanos, 0, 0, 1);
        } catch (InterruptedException interrupted) {
            logCanaryRuntimeStage("barrier.operation.interrupted", canaryStartedNanos, 0, 0, 0);
            Thread.currentThread().interrupt();
            return recordCanaryCleanupPending(pluginId, null,
                    "Provider-call quiescence was interrupted before the StreamPlay canary; the target remains blocked.",
                    0, 0);
        } catch (RuntimeException barrierFailure) {
            logCanaryRuntimeStage("barrier.operation.failed", canaryStartedNanos, 0, 0, 0);
            return recordCanaryCleanupPending(pluginId, null,
                    "The StreamPlay canary provider-call barrier failed; the target remains blocked: "
                            + rootMessage(barrierFailure), 0, 0);
        }

        File file = new File(requestedPlugin.filePath);
        PathClassLoader loader = null;
        Object instance = null;
        OrbitCs3HostServices.LoadTransaction transaction = null;
        AssetManager pluginAssets = null;
        boolean packageCodeEntered = false;
        boolean beforeUnloadSucceeded = false;
        boolean transactionRolledBack = false;
        boolean temporaryGrantChanged = false;
        Throwable loadFailure = null;
        Throwable beforeUnloadFailure = null;
        int stagedProviders = 0;
        int stagedExtractors = 0;
        CanaryProviderSearchEvidence searchEvidence = new CanaryProviderSearchEvidence();
        OrbitApplication orbitApp = (OrbitApplication) appContext;
        CloudStreamExtensionManager canaryExtensionManager = extensionManager;
        ProviderSelectionStore providerSelectionStore = orbitApp.getProviderSelectionStore();
        logCanaryRuntimeStage("profile.snapshot.before", canaryStartedNanos, 0, 0, 0);
        RuntimeProfileSnapshot priorRuntimeProfile = new RuntimeProfileSnapshot(this);
        List<String> priorProviderOrder = providerSelectionStore.getOrderedProviderIds(activeProfileId);
        logCanaryRuntimeStage("profile.snapshot.after", canaryStartedNanos, 0, 0, priorProviderOrder.size());
        LoadedPlugin canaryLoadedPlugin = null;
        try {
            if (Build.VERSION.SDK_INT >= 34 && file.canWrite()) {
                throw new IOException("The StreamPlay package is writable; the canary will not change its archive mode.");
            }
            logCanaryRuntimeStage("dex.class_load.before", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            File codeArchive = Cs3JobDefaultImplsNormalizer.prepareForClassLoading(
                    appContext, file, appContext.getClassLoader());
            loader = new PathClassLoader(codeArchive.getAbsolutePath(), appContext.getClassLoader());
            Class<?> basePlugin = Class.forName(BASE_PLUGIN, false, appContext.getClassLoader());
            Class<?> pluginClass = loader.loadClass(STREAMPLAY_CANARY_CLASS);
            logCanaryRuntimeStage("dex.class_load.after", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 1);
            if (!basePlugin.isAssignableFrom(pluginClass)) {
                throw new IllegalStateException(
                        "The pinned StreamPlay manifest class does not extend Orbit's CS3 BasePlugin API.");
            }
            packageCodeEntered = true;
            logCanaryRuntimeStage("plugin.construct.before", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            instance = pluginClass.getDeclaredConstructor().newInstance();
            logCanaryRuntimeStage("plugin.construct.after", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 1);
            transaction = services.beginPluginLoad(pluginId, file.getAbsolutePath(),
                    (com.lagradost.cloudstream3.plugins.BasePlugin) instance);
            Method setFilename = basePlugin.getMethod("setFilename", String.class);
            setFilename.invoke(instance, file.getAbsolutePath());
            logCanaryRuntimeStage("plugin.resources.before", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            pluginAssets = attachResources(instance, file, pluginClass.getClassLoader());
            logCanaryRuntimeStage("plugin.resources.after", canaryStartedNanos,
                    stagedProviders, stagedExtractors, pluginAssets == null ? 0 : 1);
            Class<?> androidPlugin = Class.forName(ANDROID_PLUGIN, false, appContext.getClassLoader());
            logCanaryRuntimeStage("plugin.load.before", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            if (androidPlugin.isAssignableFrom(pluginClass)) {
                Method load = pluginClass.getMethod("load", Context.class);
                load.invoke(instance, appContext);
            } else {
                Method load = basePlugin.getMethod("load");
                load.invoke(instance);
            }
            stagedProviders = transaction.getProviderCount();
            stagedExtractors = transaction.getExtractorCount();
            logCanaryRuntimeStage("plugin.load.after", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 1);
            logCanaryRuntimeStage("registrations.staged", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            if (transaction.hasExceededExtractorStageLimit()) {
                throw new IllegalStateException("The CS3 canary extractor registration limit was exceeded.");
            }

            // Prepare the canary profile while the transaction is still staged. Reconciliation
            // cleans committed registrations it does not own, so commit only after this snapshot.
            logCanaryRuntimeStage("profile.prepare.before", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            List<String> prepareProfileFailures = reconcileProfile(activeProfileId, canaryExtensionManager);
            logCanaryRuntimeStage("profile.prepare.after", canaryStartedNanos,
                    stagedProviders, stagedExtractors, prepareProfileFailures.size());
            if (!prepareProfileFailures.isEmpty()
                    || !activeProfileId.equals(getActiveProfileId())) {
                throw new IllegalStateException("The canary profile could not be prepared for its provider snapshot.");
            }

            logCanaryRuntimeStage("transaction.commit.before", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            transaction.commit();
            logCanaryRuntimeStage("transaction.commit.after", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 1);
            canaryLoadedPlugin = new LoadedPlugin(instance, loader, file, transaction, pluginAssets, pluginId);
            loadedPlugins.put(pluginId, canaryLoadedPlugin);
            boolean grantSaved = canaryExtensionManager.setEnabledForProfile(activeProfileId, pluginId, true);
            temporaryGrantChanged = canaryExtensionManager.isEnabledForProfile(activeProfileId, pluginId);
            if (!grantSaved || !temporaryGrantChanged) {
                throw new IllegalStateException("The temporary canary profile grant could not be recorded.");
            }

            // Keep the committed canary handle visible to reconciliation so it is not treated
            // as an orphan. The closed production gate means the grant stays inert.
            logCanaryRuntimeStage("profile.commit.before", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            List<String> committedProfileFailures = reconcileProfile(activeProfileId, canaryExtensionManager);
            logCanaryRuntimeStage("profile.commit.after", canaryStartedNanos,
                    stagedProviders, stagedExtractors, committedProfileFailures.size());
            if (!committedProfileFailures.isEmpty()
                    || !activeProfileId.equals(getActiveProfileId())) {
                throw new IllegalStateException("The committed canary profile snapshot could not be reconciled.");
            }
            barrier.pluginCleanupSucceeded(pluginId);

            MediaProvider selectedProvider = null;
            int matchingProviderCount = 0;
            logCanaryRuntimeStage("provider.select.before", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            Cs3ProviderRegistry providerRegistry = orbitApp.getCs3ProviderRegistry();
            Cs3ProviderRegistry.MetadataReadiness metadataReadiness =
                    providerRegistry.awaitProviderMetadata(activeProfileId, pluginId, 0,
                            CANARY_PROVIDER_METADATA_WAIT_MS);
            if (metadataReadiness == Cs3ProviderRegistry.MetadataReadiness.TIMED_OUT) {
                searchEvidence.status = "PROVIDER_METADATA_TIMEOUT";
                searchEvidence.providerIndex = 0;
            } else if (metadataReadiness == Cs3ProviderRegistry.MetadataReadiness.MISSING) {
                searchEvidence.status = "PROVIDER_NOT_FOUND";
                searchEvidence.providerIndex = 0;
            } else {
                for (MediaProvider candidate : providerRegistry.getProviders(activeProfileId)) {
                    if (candidate != null && pluginId.equals(candidate.owningPluginId())
                            && candidate.descriptor() != null && candidate.descriptor().titleSearch
                            && candidate.descriptor().id.endsWith(":provider-0")) {
                        matchingProviderCount++;
                        if (selectedProvider != null) {
                            throw new IllegalStateException("The canary profile exposed duplicate index-zero search providers.");
                        }
                        selectedProvider = candidate;
                    }
                }
                logCanaryRuntimeStage("provider.select.after", canaryStartedNanos,
                        stagedProviders, stagedExtractors, matchingProviderCount);
                if (selectedProvider == null) {
                    searchEvidence.status = "PROVIDER_NOT_FOUND";
                    searchEvidence.providerIndex = 0;
                } else if (!(selectedProvider instanceof Cs3MediaProviderAdapter)) {
                    searchEvidence.status = "ADAPTER_UNEXPECTED";
                    searchEvidence.providerIndex = 0;
                } else {
                    ((Cs3MediaProviderAdapter) selectedProvider)
                            .limitSearchResultsForCanary(MAX_CANARY_PROVIDER_SEARCH_RESULTS);
                    logCanaryRuntimeStage("provider.search.before", canaryStartedNanos,
                            stagedProviders, stagedExtractors, 0);
                    searchEvidence = runCanaryProviderSearch(orbitApp, selectedProvider);
                    logCanaryRuntimeStage("provider.search.after", canaryStartedNanos,
                            stagedProviders, stagedExtractors,
                            searchEvidence.providerCallbackCount + searchEvidence.resultCount);
                }
            }
        } catch (Throwable error) {
            loadFailure = error;
            if (transaction != null) {
                stagedProviders = transaction.getProviderCount();
                stagedExtractors = transaction.getExtractorCount();
            }
            logCanaryRuntimeStage("load.failed", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
        }

        try {
            logCanaryRuntimeStage("cleanup.quiesce.before", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            barrier.blockPluginCalls(pluginId);
            if (!barrier.pauseAndAwaitPluginCalls(pluginId, PLUGIN_PROVIDER_QUIESCE_TIMEOUT_MS)) {
                LoadedPlugin retained = canaryLoadedPlugin;
                if (retained == null && packageCodeEntered) {
                    retained = new LoadedPlugin(instance, loader, file, transaction, pluginAssets, pluginId);
                    loadedPlugins.put(pluginId, retained);
                }
                if (retained != null) retained.cleanupPending = true;
                logCanaryRuntimeStage("cleanup.quiesce.pending", canaryStartedNanos,
                        stagedProviders, stagedExtractors, 0);
                return recordCanaryCleanupPending(pluginId, retained,
                        "StreamPlay provider work did not quiesce; Orbit remains fail-closed.",
                        stagedProviders, stagedExtractors, searchEvidence);
            }
            logCanaryRuntimeStage("cleanup.quiesce.after", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 1);
        } catch (InterruptedException interrupted) {
            logCanaryRuntimeStage("cleanup.quiesce.interrupted", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            Thread.currentThread().interrupt();
            LoadedPlugin retained = canaryLoadedPlugin;
            if (retained == null && packageCodeEntered) {
                retained = new LoadedPlugin(instance, loader, file, transaction, pluginAssets, pluginId);
                loadedPlugins.put(pluginId, retained);
            }
            if (retained != null) retained.cleanupPending = true;
            return recordCanaryCleanupPending(pluginId, retained,
                    "StreamPlay provider cleanup was interrupted; Orbit remains fail-closed.",
                    stagedProviders, stagedExtractors, searchEvidence);
        } catch (RuntimeException barrierFailure) {
            logCanaryRuntimeStage("cleanup.quiesce.failed", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            LoadedPlugin retained = canaryLoadedPlugin;
            if (retained == null && packageCodeEntered) {
                retained = new LoadedPlugin(instance, loader, file, transaction, pluginAssets, pluginId);
                loadedPlugins.put(pluginId, retained);
            }
            if (retained != null) retained.cleanupPending = true;
            return recordCanaryCleanupPending(pluginId, retained,
                    "The StreamPlay provider cleanup barrier failed; Orbit remains fail-closed.",
                    stagedProviders, stagedExtractors, searchEvidence);
        }

        boolean grantRestored = true;
        if (temporaryGrantChanged) {
            logCanaryRuntimeStage("cleanup.grant_revoke.before", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 1);
            boolean grantRevoked = canaryExtensionManager.setEnabledForProfile(activeProfileId, pluginId, false);
            grantRestored = grantRevoked
                    && !canaryExtensionManager.isEnabledForProfile(activeProfileId, pluginId);
            logCanaryRuntimeStage("cleanup.grant_revoke.after", canaryStartedNanos,
                    stagedProviders, stagedExtractors, grantRestored ? 1 : 0);
            if (!grantRestored && loadFailure == null) {
                loadFailure = new IllegalStateException("The temporary canary profile grant could not be revoked.");
            }
        }

        boolean beforeUnloadInvoked = false;
        if (packageCodeEntered) {
            beforeUnloadInvoked = true;
            logCanaryRuntimeStage("cleanup.before_unload.before", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 1);
            if (instance == null) {
                beforeUnloadFailure = new IllegalStateException(
                        "StreamPlay package code started but no plugin instance was available for beforeUnload.");
            } else {
                try {
                    invokeBeforeUnload(instance);
                    beforeUnloadSucceeded = true;
                } catch (Throwable error) {
                    beforeUnloadFailure = error;
                }
            }
            logCanaryRuntimeStage("cleanup.before_unload.after", canaryStartedNanos,
                    stagedProviders, stagedExtractors, beforeUnloadSucceeded ? 1 : 0);
        }
        if (transaction != null) {
            try {
                logCanaryRuntimeStage("cleanup.rollback.before", canaryStartedNanos,
                        stagedProviders, stagedExtractors, 0);
                transaction.rollback();
                transactionRolledBack = !services.hasOutstandingRegistrations(pluginId);
                logCanaryRuntimeStage("cleanup.rollback.after", canaryStartedNanos,
                        stagedProviders, stagedExtractors, transactionRolledBack ? 1 : 0);
            } catch (Throwable rollbackFailure) {
                logCanaryRuntimeStage("cleanup.rollback.failed", canaryStartedNanos,
                        stagedProviders, stagedExtractors, 0);
                if (loadFailure == null) loadFailure = rollbackFailure;
            }
        } else {
            transactionRolledBack = !packageCodeEntered;
        }
        if (!transactionRolledBack && services.hasOutstandingRegistrations(pluginId)) {
            try {
                services.unloadPlugin(pluginId);
                transactionRolledBack = !services.hasOutstandingRegistrations(pluginId);
            } catch (Throwable retryFailure) {
                if (loadFailure == null) loadFailure = retryFailure;
            }
        }
        boolean registrationsClean = transactionRolledBack && (!packageCodeEntered || beforeUnloadSucceeded)
                && !services.hasOutstandingRegistrations(pluginId);
        if (!registrationsClean) {
            logCanaryRuntimeStage("cleanup.registrations.pending", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            String cleanupMessage = beforeUnloadFailure != null
                    ? "StreamPlay beforeUnload failed; Orbit is fail-closed and requires an app restart."
                    : "StreamPlay registrations were not fully cleaned up; Orbit remains fail-closed.";
            LoadedPlugin retained = canaryLoadedPlugin;
            if (retained == null && packageCodeEntered) {
                retained = new LoadedPlugin(instance, loader, file, transaction, pluginAssets, pluginId);
            }
            if (retained != null) {
                retained.cleanupPending = true;
                retained.beforeUnloadInvoked = beforeUnloadInvoked;
                retained.beforeUnloadFailure = beforeUnloadFailure == null
                        ? cleanupMessage : rootMessage(beforeUnloadFailure);
                loadedPlugins.put(pluginId, retained);
            }
            return recordCanaryCleanupPending(pluginId, retained, cleanupMessage,
                    stagedProviders, stagedExtractors, searchEvidence);
        }

        loadedPlugins.remove(pluginId, canaryLoadedPlugin);
        List<String> restoreProfileFailures = Collections.emptyList();
        boolean providerOrderRestored = false;
        try {
            logCanaryRuntimeStage("cleanup.profile_restore.before", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            restoreProfileFailures = reconcileProfile(activeProfileId, canaryExtensionManager);
            if (restoreProfileFailures.isEmpty()) {
                providerSelectionStore.setOrderedProviderIds(activeProfileId, priorProviderOrder);
                providerOrderRestored = priorProviderOrder.equals(
                        providerSelectionStore.getOrderedProviderIds(activeProfileId));
            }
            logCanaryRuntimeStage("cleanup.profile_restore.after", canaryStartedNanos,
                    stagedProviders, stagedExtractors,
                    restoreProfileFailures.size() + (providerOrderRestored ? 0 : 1));
        } catch (Throwable restoreFailure) {
            if (loadFailure == null) loadFailure = restoreFailure;
        }
        boolean profileRestored = restoreProfileFailures.isEmpty()
                && providerOrderRestored && grantRestored;
        if (profileRestored) priorRuntimeProfile.restore(this);
        if (!profileRestored) {
            logCanaryRuntimeStage("cleanup.profile_restore.pending", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            return recordCanaryCleanupPending(pluginId, null,
                    "The StreamPlay canary profile grant or registry state could not be restored; Orbit remains fail-closed.",
                    stagedProviders, stagedExtractors, searchEvidence);
        }

        try {
            barrier.pluginCleanupSucceeded(pluginId);
        } catch (RuntimeException barrierFailure) {
            logCanaryRuntimeStage("cleanup.admission.pending", canaryStartedNanos,
                    stagedProviders, stagedExtractors, 0);
            return recordCanaryCleanupPending(pluginId, null,
                    "StreamPlay was rolled back, but provider-call admission could not be restored.",
                    stagedProviders, stagedExtractors, searchEvidence);
        }
        logCanaryRuntimeStage("cleanup.complete", canaryStartedNanos,
                stagedProviders, stagedExtractors, 1);
        if (loadFailure != null) {
            return CanaryResult.failure("StreamPlay v694 load failed and its staged registrations were rolled back: "
                            + safeCanaryFailureDiagnostic(loadFailure), safeCanaryFailureCategory(loadFailure),
                    safeCanaryFailureDiagnostic(loadFailure), true, stagedProviders, stagedExtractors,
                    searchEvidence);
        }
        if (!"SUCCESS".equals(searchEvidence.status) || searchEvidence.providerCallbackCount != 1) {
            String category = "TIMEOUT".equals(searchEvidence.status)
                    ? "canary_provider_search_timeout"
                    : "PROVIDER_METADATA_TIMEOUT".equals(searchEvidence.status)
                    ? "canary_provider_metadata_timeout"
                    : "EMPTY_RESULT".equals(searchEvidence.status)
                    ? "canary_provider_search_empty" : "canary_provider_search_failed";
            return CanaryResult.failure("The bounded StreamPlay provider selection or search did not complete successfully.",
                    category, null, true, stagedProviders, stagedExtractors, searchEvidence);
        }
        return CanaryResult.success(stagedProviders, stagedExtractors, searchEvidence);
    }

    private CanaryProviderSearchEvidence runCanaryProviderSearch(
            OrbitApplication orbitApp, MediaProvider provider) {
        CanaryProviderSearchEvidence evidence = new CanaryProviderSearchEvidence();
        evidence.attempted = true;
        evidence.providerIndex = 0;
        long startedNanos = System.nanoTime();
        CountDownLatch terminalBatch = new CountDownLatch(1);
        ProviderBatch<List<AttributedSearchHit>> batch = null;
        try {
            ProviderOperationCoordinator coordinator = orbitApp.getProviderOperationCoordinator();
            batch = coordinator.search(Collections.singletonList(provider),
                    new SearchRequest(CANARY_PROVIDER_SEARCH_QUERY, SearchType.ALL, 1),
                    CANARY_PROVIDER_SEARCH_TIMEOUT_MS, outcomes -> {
                        if (outcomes.size() == 1 && outcomes.get(0).isTerminal()) terminalBatch.countDown();
                    });
            if (!terminalBatch.await(CANARY_PROVIDER_SEARCH_WAIT_MS, TimeUnit.MILLISECONDS)) {
                evidence.status = "TIMEOUT";
                return evidence;
            }
            evidence.batchCompleted = batch.isComplete();
            List<ProviderOutcome<List<AttributedSearchHit>>> outcomes = batch.snapshot();
            if (outcomes.size() != 1) {
                evidence.status = "FAILURE";
                return evidence;
            }
            ProviderOutcome<List<AttributedSearchHit>> outcome = outcomes.get(0);
            evidence.status = outcome.status.name();
            if (outcome.status == ProviderOutcome.Status.SUCCESS) {
                evidence.providerCallbackCount = 1;
                int returnedCount = outcome.value == null ? 0 : outcome.value.size();
                evidence.resultCountCapped = returnedCount > MAX_CANARY_PROVIDER_SEARCH_RESULTS;
                evidence.resultCount = Math.min(returnedCount, MAX_CANARY_PROVIDER_SEARCH_RESULTS);
                // A callback without usable results is smoke-test evidence, not provider acceptance.
                if (returnedCount == 0) evidence.status = "EMPTY_RESULT";
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            evidence.status = "CANCELLED";
        } catch (RuntimeException ignored) {
            // Keep provider exceptions, URLs, titles, and query diagnostics out of canary output.
            evidence.status = "FAILURE";
        } finally {
            if (batch != null) batch.close();
            evidence.elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        }
        return evidence;
    }

    private void logCanaryRuntimeStage(String stage, long startedNanos,
                                       int stagedProviders, int stagedExtractors, int count) {
        if (!isAllowedCanaryRuntimeStage(stage)) return;
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
        String row = "stage=" + stage + " elapsed_ms=" + elapsedMs
                + " providers=" + Math.max(0, stagedProviders)
                + " extractors=" + Math.max(0, stagedExtractors)
                + " count=" + Math.max(0, count);
        Log.i(CANARY_RUNTIME_STAGE_TAG, row);
        try {
            File journal = new File(appContext.getFilesDir(), CANARY_RUNTIME_STAGE_JOURNAL);
            synchronized (CANARY_RUNTIME_STAGE_JOURNAL_LOCK) {
                try (FileOutputStream output = new FileOutputStream(journal, true)) {
                    output.write((row + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {
            // Diagnostic I/O must not alter the isolated canary's load or cleanup behavior.
        }
    }

    private static boolean isAllowedCanaryRuntimeStage(String stage) {
        if (stage == null) return false;
        switch (stage) {
            case "canary.dispatch.before_lock":
            case "canary.dispatch.lock_acquired":
            case "canary.entry":
            case "preflight.before":
            case "preflight.after":
            case "authorization.consume.before":
            case "authorization.consume.after":
            case "barrier.block.before":
            case "barrier.block.after":
            case "barrier.await.before":
            case "barrier.await.after":
            case "barrier.await.pending":
            case "barrier.operation.interrupted":
            case "barrier.operation.failed":
            case "barrier.unavailable":
            case "profile.snapshot.before":
            case "profile.snapshot.after":
            case "dex.class_load.before":
            case "dex.class_load.after":
            case "plugin.construct.before":
            case "plugin.construct.after":
            case "plugin.resources.before":
            case "plugin.resources.after":
            case "plugin.load.before":
            case "plugin.load.after":
            case "registrations.staged":
            case "profile.prepare.before":
            case "profile.prepare.after":
            case "transaction.commit.before":
            case "transaction.commit.after":
            case "profile.commit.before":
            case "profile.commit.after":
            case "provider.select.before":
            case "provider.select.after":
            case "provider.search.before":
            case "provider.search.after":
            case "load.failed":
            case "cleanup.quiesce.before":
            case "cleanup.quiesce.after":
            case "cleanup.quiesce.pending":
            case "cleanup.quiesce.interrupted":
            case "cleanup.quiesce.failed":
            case "cleanup.grant_revoke.before":
            case "cleanup.grant_revoke.after":
            case "cleanup.before_unload.before":
            case "cleanup.before_unload.after":
            case "cleanup.rollback.before":
            case "cleanup.rollback.after":
            case "cleanup.rollback.failed":
            case "cleanup.registrations.pending":
            case "cleanup.profile_restore.before":
            case "cleanup.profile_restore.after":
            case "cleanup.profile_restore.pending":
            case "cleanup.admission.pending":
            case "cleanup.complete":
                return true;
            default:
                return false;
        }
    }

    private CanaryResult recordCanaryCleanupPending(String pluginId, LoadedPlugin loaded,
                                                     String message, int providers, int extractors) {
        return recordCanaryCleanupPending(pluginId, loaded, message, providers, extractors,
                new CanaryProviderSearchEvidence());
    }

    private CanaryResult recordCanaryCleanupPending(String pluginId, LoadedPlugin loaded,
                                                     String message, int providers, int extractors,
                                                     CanaryProviderSearchEvidence searchEvidence) {
        cleanupPendingPluginIds.add(pluginId);
        if (loaded != null) loaded.cleanupPending = true;
        activeProfileFullyReconciled = false;
        return CanaryResult.failure(message, "canary_cleanup_pending", null, false,
                providers, extractors, searchEvidence);
    }
    /** Reads the durable marker left by the authorized run without modifying it. */
    public boolean isStreamPlayV694CanaryConsumed() {
        try { return canaryConsumptionMarker().exists(); }
        catch (RuntimeException error) { return true; }
    }

    private File canaryConsumptionMarker() {
        return new File(appContext.getNoBackupFilesDir(), STREAMPLAY_V694_CANARY_CONSUMED_MARKER);
    }

    private AuthorizationMarker readUniqueFreshCanaryAuthorizationMarker() {
        try {
            File directory = appContext.getNoBackupFilesDir();
            File[] candidates = directory.listFiles(file -> file.isFile()
                    && file.getName().startsWith(STREAMPLAY_V694_CANARY_AUTHORIZATION_MARKER_PREFIX)
                    && file.getName().endsWith(STREAMPLAY_V694_CANARY_AUTHORIZATION_MARKER_SUFFIX));
            if (candidates == null || candidates.length != 1) return null;
            File file = candidates[0];
            String name = file.getName();
            int start = STREAMPLAY_V694_CANARY_AUTHORIZATION_MARKER_PREFIX.length();
            int end = name.length() - STREAMPLAY_V694_CANARY_AUTHORIZATION_MARKER_SUFFIX.length();
            if (end <= start) return null;
            String authorizationId = name.substring(start, end);
            if (authorizationId.length() > MAX_STREAMPLAY_CANARY_AUTHORIZATION_ID_CHARS
                    || !authorizationId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                    || authorizationId.equals(STREAMPLAY_V694_CANARY_AUTHORIZATION_ID)
                    || file.length() <= 0L
                    || file.length() > MAX_STREAMPLAY_CANARY_AUTHORIZATION_MARKER_BYTES) {
                return null;
            }
            String expected = "authorization=" + authorizationId + "\nsha256="
                    + STREAMPLAY_CANARY_SHA256 + "\n";
            if (!expected.equals(readSmallUtf8File(file, MAX_STREAMPLAY_CANARY_AUTHORIZATION_MARKER_BYTES))) {
                return null;
            }
            File consumed = new File(directory,
                    STREAMPLAY_V694_CANARY_CONSUMED_AUTHORIZATION_PREFIX + authorizationId
                            + STREAMPLAY_V694_CANARY_AUTHORIZATION_MARKER_SUFFIX);
            return new AuthorizationMarker(authorizationId, file, expected, consumed);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static String readSmallUtf8File(File file, int maximumBytes) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) throw new IOException("Input stream made no progress.");
                if (output.size() + count > maximumBytes) throw new IOException("marker exceeds the fixed size bound");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static boolean consumeCanaryAuthorizationMarker(AuthorizationMarker authorization) {
        if (authorization == null) return false;
        try {
            if (!authorization.file.isFile()
                    || !authorization.contents.equals(readSmallUtf8File(
                    authorization.file, MAX_STREAMPLAY_CANARY_AUTHORIZATION_MARKER_BYTES))) return false;
            if (!authorization.consumedFile.createNewFile()) return false;
            try (FileOutputStream output = new FileOutputStream(authorization.consumedFile, false)) {
                output.write(authorization.contents.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            return true;
        } catch (IOException | RuntimeException ignored) {
            // Keep a partially written consumed marker: uncertainty must never permit a replay.
            return false;
        }
    }

    private static final class AuthorizationMarker {
        final String authorizationId;
        final File file;
        final String contents;
        final File consumedFile;

        AuthorizationMarker(String id, File file, String contents, File consumedFile) {
            authorizationId = id;
            this.file = file;
            this.contents = contents;
            this.consumedFile = consumedFile;
        }

        boolean matches(AuthorizationMarker other) {
            return other != null && authorizationId.equals(other.authorizationId)
                    && file.equals(other.file) && contents.equals(other.contents)
                    && consumedFile.equals(other.consumedFile);
        }
    }

    private String validateStreamPlayV694CanaryIdentity(
            CloudStreamExtensionManager.InstalledPlugin plugin,
            CloudStreamExtensionManager extensionManager) {
        if (plugin == null) return "The exact authorized StreamPlay v694 package is not installed.";
        if (!STREAMPLAY_V694_CANARY_PLUGIN_ID.equals(plugin.id)
                || !STREAMPLAY_CANARY_REPOSITORY_URL.equals(plugin.repositoryUrl)
                || !STREAMPLAY_CANARY_NAME.equals(plugin.name)
                || !STREAMPLAY_CANARY_NAME.equals(plugin.internalName)
                || plugin.version != STREAMPLAY_CANARY_VERSION
                || !STREAMPLAY_CANARY_CLASS.equals(plugin.pluginClassName)
                || !plugin.requiresResources
                || !plugin.hashWasPublished
                || !STREAMPLAY_CANARY_SHA256.equals(plugin.sha256)) {
            return "Installed StreamPlay metadata does not match the exact authorized v694 package identity.";
        }
        File file = plugin.filePath == null ? null : new File(plugin.filePath);
        if (file == null || !file.isFile() || file.length() != STREAMPLAY_CANARY_SIZE) {
            return "The installed StreamPlay package size does not match the pinned v694 archive.";
        }
        try {
            if (!STREAMPLAY_CANARY_SHA256.equals(sha256(file))) {
                return "The installed StreamPlay package bytes do not match the authorized SHA-256.";
            }
            JSONObject manifest = readCanaryManifest(file);
            if (!STREAMPLAY_CANARY_NAME.equals(manifest.optString("name", ""))
                    || !STREAMPLAY_CANARY_CLASS.equals(manifest.optString("pluginClassName", ""))
                    || manifest.optLong("version", -1L) != STREAMPLAY_CANARY_VERSION
                    || !manifest.has("requiresResources") || !manifest.optBoolean("requiresResources", false)) {
                return "The installed StreamPlay archive manifest does not match the authorized v694 identity.";
            }
        } catch (IOException | JSONException error) {
            return "The authorized StreamPlay archive could not be verified. failure_category="
                    + safeCanaryFailureCategory(error);
        }

        CloudStreamExtensionManager.PluginInfo record = null;
        int matches = 0;
        for (CloudStreamExtensionManager.PluginInfo candidate
                : extensionManager.getCatalog(STREAMPLAY_CANARY_REPOSITORY_URL)) {
            if (STREAMPLAY_V694_CANARY_PLUGIN_ID.equals(candidate.id)) {
                record = candidate;
                matches++;
            }
        }
        if (matches != 1 || record == null
                || !STREAMPLAY_CANARY_REPOSITORY_URL.equals(record.repositoryUrl)
                || !STREAMPLAY_CANARY_NAME.equals(record.name)
                || !STREAMPLAY_CANARY_NAME.equals(record.internalName)
                || record.version != STREAMPLAY_CANARY_VERSION
                || (record.expectedSize > 0L && record.expectedSize != STREAMPLAY_CANARY_SIZE)
                || !STREAMPLAY_CANARY_SHA256.equals(record.expectedSha256)) {
            return "The saved catalog does not contain exactly one matching StreamPlay v694 record; no refresh was attempted.";
        }
        return null;
    }

    private static JSONObject readCanaryManifest(File archive) throws IOException, JSONException {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null || entry.getSize() < 0 || entry.getSize() > MAX_CANARY_MANIFEST_BYTES) {
                throw new IOException("root manifest.json is missing or exceeds the canary size limit");
            }
            try (InputStream input = zip.getInputStream(entry);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) throw new IOException("Input stream made no progress.");
                    if (output.size() + count > MAX_CANARY_MANIFEST_BYTES) {
                        throw new IOException("root manifest.json exceeds the canary size limit");
                    }
                    output.write(buffer, 0, count);
                }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        }
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException("SHA-256 is unavailable.", error); }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) throw new IOException("Input stream made no progress.");
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private boolean isDebuggableBuild() {
        return (appContext.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /** True when profile navigation succeeded while storage recovery deferred saved grants. */
    public boolean hasPendingPluginProfileReconcile() {
        return activeProfileNeedsPluginReconcile;
    }

    /** Marks an enabled profile as needing a serialized reconcile after a direct plugin reload failure. */
    public boolean markPluginProfileReconcilePending(String profileId) {
        synchronized (lifecycleLock) {
            if (profileId == null || !profileId.equals(activeProfileId)
                    || (!isOrdinaryPluginRuntimeEnabled() && activeProfileFullyReconciled)) return false;
            activeProfileNeedsPluginReconcile = true;
            return true;
        }
    }

    public void setPluginCleanupBarrier(PluginCleanupBarrier barrier) {
        pluginCleanupBarrier = barrier;
    }

    public List<String> getMissingHostSymbols() {
        ArrayList<String> missing = new ArrayList<>();
        requireClass(BASE_PLUGIN, missing);
        requireClass(ANDROID_PLUGIN, missing);
        requireClass(MAIN_API, missing);
        requireClass(API_HOLDER, missing);
        requireClass("kotlin.Metadata", missing);
        requireClass("kotlin.coroutines.Continuation", missing);
        return Collections.unmodifiableList(missing);
    }

    public List<String> getMissingMegaProviderSymbols() {
        ArrayList<String> missing = new ArrayList<>(getMissingHostSymbols());
        requireClass(REPOSITORY_MANAGER, missing);
        return Collections.unmodifiableList(missing);
    }

    public boolean isReadyForPlugin(CloudStreamExtensionManager.InstalledPlugin plugin) {
        return isOrdinaryPluginRuntimeEnabled()
                && OrbitCs3HostServices.get().getExtensionManager().isStorageRecoveryReady()
                && getMissingHostSymbols().isEmpty()
                && plugin != null && plugin.filePath != null
                && new File(plugin.filePath).isFile();
    }

    public boolean isReadyForMegaProvider(CloudStreamExtensionManager.InstalledPlugin plugin) {
        return isOrdinaryPluginRuntimeEnabled()
                && OrbitCs3HostServices.get().getExtensionManager().isStorageRecoveryReady()
                && getMissingMegaProviderSymbols().isEmpty()
                && plugin != null
                && plugin.filePath != null
                && new File(plugin.filePath).isFile();
    }

    public ActivationResult activate(CloudStreamExtensionManager.InstalledPlugin plugin) {
        synchronized (lifecycleLock) {
            return activateLocked(plugin);
        }
    }

    private ActivationResult activateLocked(CloudStreamExtensionManager.InstalledPlugin plugin) {
        if (!isOrdinaryPluginRuntimeEnabled()) {
            return activationFailure("Plugin activation is blocked until the CS3 compatibility gate passes.");
        }
        if (plugin == null) return activationFailure("Installed plugin metadata is missing.");
        if (!OrbitCs3HostServices.get().getExtensionManager().isStorageRecoveryReady()) {
            return ActivationResult.failure("Activation is blocked until CS3 package storage recovery completes.");
        }
        if (plugin.filePath == null) {
            return activationFailure("Installed plugin code path is missing.");
        }
        File file = new File(plugin.filePath);
        if (!file.isFile()) return activationFailure("Installed CS3 package is missing from Orbit storage.");
        if (Build.VERSION.SDK_INT >= 34 && file.canWrite() && !file.setReadOnly()) {
            return activationFailure("Orbit could not mark the DEX package read-only before loading.");
        }
        ActivationPreparation preparation = prepareArchiveForActivation(
                appContext, file, appContext.getClassLoader());
        if (!preparation.isPrepared()) {
            Log.e("OrbitCS3", "CS3 DEX compatibility preparation failed for "
                    + plugin.id + "; activation is blocked", preparation.failure);
            return activationFailure("CS3 DEX compatibility preparation failed; plugin activation is blocked.");
        }
        File codeArchive = preparation.codeArchive;
        // The accepted test variant exercises isolated profiles. Product activation reaches
        // this point only after the validated crosswalk gate is enabled.
        List<String> missing = isMegaProvider(plugin)
                ? getMissingMegaProviderSymbols() : getMissingHostSymbols();
        if (!missing.isEmpty()) {
            return activationFailure("Activation is blocked. This Orbit build does not include required CS3 host APIs: "
                    + join(missing, ", "));
        }
        LoadedPlugin existing = loadedPlugins.get(plugin.id);
        if (existing != null) {
            return existing.cleanupPending
                    ? activationFailure("The prior plugin registration transaction is still awaiting cleanup.")
                    : ActivationResult.success();
        }

        PathClassLoader loader = null;
        Object instance = null;
        OrbitCs3HostServices.LoadTransaction transaction = null;
        AssetManager pluginAssets = null;
        try {
            loader = new PathClassLoader(codeArchive.getAbsolutePath(), appContext.getClassLoader());
            Class<?> basePlugin = Class.forName(BASE_PLUGIN, false, appContext.getClassLoader());
            Class<?> pluginClass = loader.loadClass(plugin.pluginClassName);
            if (!basePlugin.isAssignableFrom(pluginClass)) {
                return activationFailure("The manifest class does not extend the CS3 BasePlugin API.");
            }
            instance = pluginClass.getDeclaredConstructor().newInstance();
            Method setFilename = basePlugin.getMethod("setFilename", String.class);
            setFilename.invoke(instance, file.getAbsolutePath());
            transaction = OrbitCs3HostServices.get().beginPluginLoad(plugin.id, file.getAbsolutePath(),
                    (com.lagradost.cloudstream3.plugins.BasePlugin) instance);
            if (plugin.requiresResources) pluginAssets = attachResources(instance, file, pluginClass.getClassLoader());

            Class<?> androidPlugin = Class.forName(ANDROID_PLUGIN, false, appContext.getClassLoader());
            if (androidPlugin.isAssignableFrom(pluginClass)) {
                Method load = pluginClass.getMethod("load", Context.class);
                load.invoke(instance, appContext);
            } else {
                Method load = basePlugin.getMethod("load");
                load.invoke(instance);
            }
            transaction.commit();
            loadedPlugins.put(plugin.id, new LoadedPlugin(instance, loader, file, transaction, pluginAssets));
            return ActivationResult.success();
        } catch (Throwable error) {
            if (instance != null) try { invokeBeforeUnload(instance); } catch (Throwable ignored) { }
            if (transaction != null) try { transaction.rollback(); } catch (Throwable ignored) { }
            return activationFailure("CS3 plugin load failed: " + rootMessage(error));
        }
    }

    private ActivationResult activationFailure(String message) {
        if (activeProfileId != null) activeProfileFullyReconciled = false;
        return ActivationResult.failure(message);
    }

    static ActivationPreparation prepareArchiveForActivation(
            Context context, File sourceArchive, ClassLoader hostLoader) {
        try {
            return new ActivationPreparation(
                    Cs3JobDefaultImplsNormalizer.prepareForClassLoading(
                            context, sourceArchive, hostLoader), null);
        } catch (IOException | RuntimeException failure) {
            return new ActivationPreparation(null, failure);
        }
    }

    public void deactivate(String pluginId) {
        synchronized (lifecycleLock) {
            if (pluginId != null) cleanupPluginRegistrations(pluginId);
        }
    }

    /** Returns false until provider/extractor rollback has completed for this plugin ID. */
    public boolean deactivateIfClean(String pluginId) {
        synchronized (lifecycleLock) {
            if (pluginId == null) return false;
            return cleanupPluginRegistrations(pluginId);
        }
    }

    /** User-facing cleanup diagnostic for flows blocked by a retained runtime handle. */
    public String getCleanupFailureMessage(String pluginId) {
        synchronized (lifecycleLock) {
            if (pluginId == null) return "A plugin ID is required.";
            LoadedPlugin loaded = loadedPlugins.get(pluginId);
            if (loaded != null && loaded.beforeUnloadFailure != null) {
                return "Plugin cleanup is unsafe to retry in this process. Restart Orbit before changing this package or profile."
                        + " beforeUnload: " + loaded.beforeUnloadFailure;
            }
            if ((loaded != null && loaded.cleanupPending) || cleanupPendingPluginIds.contains(pluginId)
                    || OrbitCs3HostServices.get().hasOutstandingRegistrations(pluginId)) {
                return "Plugin registrations are still being cleaned up. Keep the package and profile assignment unchanged and retry.";
            }
            return null;
        }
    }

    /** Keeps the class/transaction handle while rollback fails so cleanup can be retried safely. */
    private boolean cleanupPluginRegistrations(String pluginId) {
        LoadedPlugin loaded = loadedPlugins.get(pluginId);
        OrbitCs3HostServices services = OrbitCs3HostServices.get();
        captureCleanupProfileState();
        if (!services.invalidatePluginSettingsForPlugin(pluginId)) {
            return recordCleanupPending(pluginId, loaded, services,
                    "Plugin settings could not be detached safely; package and profile changes remain blocked.");
        }
        PluginCleanupBarrier barrier = pluginCleanupBarrier;
        if (barrier == null) {
            return recordCleanupPending(pluginId, loaded, services,
                    "Provider cleanup barrier is unavailable; plugin cleanup is blocked.");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { barrier.blockPluginCalls(pluginId); }
            catch (RuntimeException barrierFailure) {
                return recordCleanupPending(pluginId, loaded, services,
                        "Provider cleanup could not be deferred safely: " + rootMessage(barrierFailure));
            }
            if (!deferredCleanupPluginIds.add(pluginId)) {
                return recordCleanupPending(pluginId, loaded, services,
                        "Plugin cleanup is already queued on Orbit's extension worker; retry after it finishes.");
            }
            boolean scheduled;
            try {
                scheduled = barrier.scheduleCleanupRetry(pluginId, () -> {
                    try { deactivateIfClean(pluginId); }
                    finally {
                        synchronized (lifecycleLock) {
                            deferredCleanupPluginIds.remove(pluginId);
                        }
                    }
                });
            } catch (RuntimeException scheduleFailure) {
                scheduled = false;
            }
            if (!scheduled) {
                deferredCleanupPluginIds.remove(pluginId);
                return recordCleanupPending(pluginId, loaded, services,
                        "Plugin cleanup could not be scheduled; it remains blocked and requires a retry.");
            }
            return recordCleanupPending(pluginId, loaded, services,
                    "Plugin cleanup is queued on Orbit's extension worker; package changes remain blocked until it finishes.");
        }
        try {
            if (!barrier.pauseAndAwaitPluginCalls(pluginId, PLUGIN_PROVIDER_QUIESCE_TIMEOUT_MS)) {
                return recordCleanupPending(pluginId, loaded, services,
                        "Provider calls did not quiesce within 15 seconds; retry cleanup after the active call returns.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return recordCleanupPending(pluginId, loaded, services,
                    "Provider cleanup was interrupted before calls quiesced; retry cleanup.");
        } catch (RuntimeException barrierFailure) {
            return recordCleanupPending(pluginId, loaded, services,
                    "Provider cleanup barrier failed; cleanup remains blocked: " + rootMessage(barrierFailure));
        }

        String lifecycleFailure = null;
        if (loaded != null) {
            loaded.cleanupPending = true;
            if (!loaded.beforeUnloadInvoked) {
                loaded.beforeUnloadInvoked = true;
                try { invokeBeforeUnload(loaded.instance); }
                catch (Throwable error) {
                    lifecycleFailure = rootMessage(error);
                    loaded.beforeUnloadFailure = lifecycleFailure;
                }
            }
            if (loaded.beforeUnloadFailure != null) lifecycleFailure = loaded.beforeUnloadFailure;
            try { loaded.transaction.rollback(); }
            catch (Throwable ignored) { }
        }
        if (services.hasOutstandingRegistrations(pluginId)) {
            try { services.unloadPlugin(pluginId); }
            catch (Throwable ignored) { }
        }
        boolean pending = services.hasOutstandingRegistrations(pluginId)
                || (loaded != null && loaded.beforeUnloadFailure != null);
        if (!pending) {
            try { barrier.pluginCleanupSucceeded(pluginId); }
            catch (RuntimeException barrierFailure) {
                return recordCleanupPending(pluginId, loaded, services,
                        "Provider cleanup could not be reopened after rollback; retry cleanup: "
                                + rootMessage(barrierFailure));
            }
            if (loaded != null) loadedPlugins.remove(pluginId, loaded);
            completeCleanupProfileState(pluginId);
            if (lifecycleFailure != null) services.getExtensionManager().recordActivationError(
                    pluginId, "Plugin beforeUnload callback failed during cleanup: " + lifecycleFailure);
            return true;
        }
        cleanupPendingPluginIds.add(pluginId);
        activeProfileFullyReconciled = false;
        String cleanupMessage = loaded != null && loaded.beforeUnloadFailure != null
                ? "Plugin beforeUnload failed; its runtime handle is retained and package/profile changes require an Orbit restart: "
                    + loaded.beforeUnloadFailure
                : "Plugin registrations remain active because rollback is still pending.";
        services.getExtensionManager().recordActivationError(pluginId, cleanupMessage);
        return false;
    }

    private boolean recordCleanupPending(String pluginId, LoadedPlugin loaded,
                                         OrbitCs3HostServices services, String message) {
        captureCleanupProfileState();
        cleanupPendingPluginIds.add(pluginId);
        if (loaded != null) loaded.cleanupPending = true;
        activeProfileFullyReconciled = false;
        services.getExtensionManager().recordActivationError(pluginId, message);
        return false;
    }

    private void captureCleanupProfileState() {
        if (cleanupRecoverySnapshotValid || !activeProfileFullyReconciled || activeProfileId == null) return;
        cleanupRecoveryProfileId = activeProfileId;
        cleanupRecoveryWasFullyReconciled = true;
        cleanupRecoverySnapshotValid = true;
    }

    private void completeCleanupProfileState(String pluginId) {
        cleanupPendingPluginIds.remove(pluginId);
        if (!cleanupPendingPluginIds.isEmpty()) return;
        if (cleanupRecoverySnapshotValid && cleanupRecoveryWasFullyReconciled
                && cleanupRecoveryProfileId != null
                && cleanupRecoveryProfileId.equals(activeProfileId)) {
            activeProfileFullyReconciled = true;
        }
        invalidateCleanupProfileState();
    }

    private void invalidateCleanupProfileState() {
        cleanupRecoveryProfileId = null;
        cleanupRecoveryWasFullyReconciled = false;
        cleanupRecoverySnapshotValid = false;
    }

    public boolean deactivateByPath(String pluginPath) {
        synchronized (lifecycleLock) {
            if (pluginPath == null) return false;
            String canonical = new File(pluginPath).getAbsolutePath();
            for (Map.Entry<String, LoadedPlugin> entry : new ArrayList<>(loadedPlugins.entrySet())) {
                if (canonical.equals(entry.getValue().file.getAbsolutePath())) {
                    return cleanupPluginRegistrations(entry.getKey());
                }
            }
            return false;
        }
    }

    /** Switches the live host to exactly the installed set explicitly enabled for this profile. */
    public List<String> reconcileProfile(
            String profileId, CloudStreamExtensionManager extensionManager) {
        synchronized (lifecycleLock) {
            return reconcileProfileInternal(profileId, extensionManager, false);
        }
    }

    /** Orbit's normal profile batch mirrors CloudStream's post-local-plugin-load event. */
    public List<String> reconcileProfileForOrbitApplication(
            String profileId, CloudStreamExtensionManager extensionManager) {
        synchronized (lifecycleLock) {
            return reconcileProfileInternal(profileId, extensionManager, true);
        }
    }

    private List<String> reconcileProfileInternal(
            String profileId, CloudStreamExtensionManager extensionManager,
            boolean publishAfterPluginBatch) {
        // Profile reconciliation owns readiness transitions itself. Discard any plugin-only
        // recovery snapshot so cleanup in this path cannot restore an earlier profile state.
        invalidateCleanupProfileState();
        boolean hasTargetProfile = profileId != null && !profileId.isEmpty();
        String targetProfileId = hasTargetProfile ? profileId : null;
        boolean cleanupRetryPending = !cleanupPendingPluginIds.isEmpty();
        for (LoadedPlugin loaded : loadedPlugins.values()) {
            if (loaded.cleanupPending) { cleanupRetryPending = true; break; }
        }
        if (!cleanupRetryPending) {
            for (String id : OrbitCs3HostServices.get().getOutstandingPluginIds()) {
                if (!loadedPlugins.containsKey(id)) { cleanupRetryPending = true; break; }
            }
        }
        boolean storageRecoveryRetryReady = activeProfileNeedsPluginReconcile
                && isOrdinaryPluginRuntimeEnabled()
                && extensionManager.isStorageRecoveryReady();
        boolean profileChanging = !java.util.Objects.equals(targetProfileId, activeProfileId)
                || cleanupRetryPending || !activeProfileFullyReconciled || storageRecoveryRetryReady;
        if (!profileChanging) {
            ArrayList<String> retryFailures = new ArrayList<>();
            for (LoadedPlugin loaded : new ArrayList<>(loadedPlugins.values())) {
                if (loaded.cleanupPending && !cleanupPluginRegistrations(loaded.pluginId)) {
                    retryFailures.add(loaded.pluginId + ": registry cleanup is still pending.");
                }
            }
            for (String id : OrbitCs3HostServices.get().getOutstandingPluginIds()) {
                if (!loadedPlugins.containsKey(id) && !cleanupPluginRegistrations(id)) {
                    retryFailures.add(id + ": registry cleanup is still pending.");
                }
            }
            return Collections.unmodifiableList(retryFailures);
        }

        String previouslyActiveProfileId = activeProfileId;
        // As soon as a switch starts, the previous profile may be partially
        // unloaded. Do not keep reporting it active if cleanup later fails.
        activeProfileId = null;
        activeProfileFullyReconciled = false;
        activeProfileNeedsPluginReconcile = false;
        for (String id : new ArrayList<>(loadedPlugins.keySet())) cleanupPluginRegistrations(id);
        for (String id : OrbitCs3HostServices.get().getOutstandingPluginIds()) {
            if (!loadedPlugins.containsKey(id)) cleanupPluginRegistrations(id);
        }
        List<String> outstanding = OrbitCs3HostServices.get().getOutstandingPluginIds();
        ArrayList<String> retainedHandles = new ArrayList<>();
        for (LoadedPlugin loaded : loadedPlugins.values()) {
            if (loaded.cleanupPending) retainedHandles.add(loaded.pluginId);
        }
        if (!outstanding.isEmpty() || !retainedHandles.isEmpty()) {
            ArrayList<String> cleanupFailures = new ArrayList<>();
            for (String id : outstanding) cleanupFailures.add(id + ": registry cleanup is still pending; profile switch is blocked.");
            for (String id : retainedHandles) {
                LoadedPlugin loaded = loadedPlugins.get(id);
                String detail = loaded != null && loaded.beforeUnloadFailure != null
                        ? "beforeUnload failed; restart Orbit before changing profiles."
                        : "runtime cleanup is still pending; profile switch is blocked.";
                cleanupFailures.add(id + ": " + detail);
            }
            appendPreviousProfileRecovery(previouslyActiveProfileId, extensionManager, cleanupFailures);
            return Collections.unmodifiableList(cleanupFailures);
        }
        if (!hasTargetProfile) {
            activeProfileId = null;
            activeProfileFullyReconciled = true;
            activeProfileNeedsPluginReconcile = false;
            return Collections.emptyList();
        }
        // Profile grants remain subject to storage recovery and cleanup checks.
        // Static matrix crosswalk is diagnostic and does not prevent installed plugins from loading.
        if (!isOrdinaryPluginRuntimeEnabled()) {
            activeProfileId = profileId;
            activeProfileFullyReconciled = true;
            activeProfileNeedsPluginReconcile = false;
            return Collections.emptyList();
        }
        if (!extensionManager.isStorageRecoveryReady()) {
            activeProfileId = profileId;
            activeProfileFullyReconciled = true;
            activeProfileNeedsPluginReconcile = true;
            return Collections.emptyList();
        }
        ArrayList<String> failures = new ArrayList<>();
        ArrayList<String> activatedIds = new ArrayList<>();
        boolean storageRecoveryDeferred = false;
        for (String id : extensionManager.getEnabledPluginIds(profileId)) {
            if (!extensionManager.isStorageRecoveryReady()) {
                storageRecoveryDeferred = true;
                break;
            }
            CloudStreamExtensionManager.InstalledPlugin plugin = extensionManager.getInstalledPlugin(id);
            ActivationResult result = activate(plugin);
            if (!result.success) {
                if (!extensionManager.isStorageRecoveryReady()) {
                    storageRecoveryDeferred = true;
                    break;
                }
                extensionManager.recordActivationError(id, result.message);
                failures.add((plugin == null ? id : plugin.name) + ": " + result.message);
            } else {
                activatedIds.add(id);
                extensionManager.clearActivationError(id);
            }
        }
        if (!storageRecoveryDeferred && !extensionManager.isStorageRecoveryReady()) {
            storageRecoveryDeferred = true;
        }
        Throwable afterPluginsLoadedFailure = null;
        if (publishAfterPluginBatch && !storageRecoveryDeferred) {
            // This is the end of Orbit's enabled-plugin batch, matching
            // PluginManager.loadAllLocalPlugins(forceReload) after each file was attempted.
            // The event Boolean is forceReload, not a success flag; Orbit has no hot-reload
            // path here, so normal profile reconciliation publishes false.
            try {
                com.lagradost.cloudstream3.MainActivity.Companion
                        .getAfterPluginsLoadedEvent().invoke(Boolean.FALSE);
            } catch (Throwable observerFailure) {
                afterPluginsLoadedFailure = observerFailure;
                String observerType = observerFailure.getClass().getSimpleName();
                failures.add("afterPluginsLoadedEvent observer failed ("
                        + (observerType.isEmpty() ? "exception" : observerType) + ").");
            }
        }
        if (storageRecoveryDeferred) {
            ArrayList<String> cleanupFailures = new ArrayList<>();
            for (String id : activatedIds) {
                if (!cleanupPluginRegistrations(id)) {
                    cleanupFailures.add(id + ": cleanup failed after storage recovery became unavailable.");
                }
            }
            if (!cleanupFailures.isEmpty()) {
                appendPreviousProfileRecovery(previouslyActiveProfileId, extensionManager, cleanupFailures);
                return Collections.unmodifiableList(cleanupFailures);
            }
            activeProfileId = profileId;
            activeProfileFullyReconciled = true;
            activeProfileNeedsPluginReconcile = true;
            return Collections.emptyList();
        }
        if (failures.isEmpty()) {
            try {
                // CloudStream initializes every registered provider after plugin loading so
                // MainAPI can apply process-local name, URL, and credential overrides.
                APIHolder.INSTANCE.initAll();
            } catch (Throwable initializationError) {
                failures.add("Provider initialization failed; the profile activation was rolled back.");
            }
        }
        if (!failures.isEmpty()) {
            if (afterPluginsLoadedFailure != null) {
                for (String id : activatedIds) {
                    try {
                        if (!cleanupPluginRegistrations(id)) {
                            failures.add(id + ": cleanup failed after profile activation was rejected.");
                        }
                    } catch (Throwable cleanupFailure) {
                        if (cleanupFailure != afterPluginsLoadedFailure) {
                            afterPluginsLoadedFailure.addSuppressed(cleanupFailure);
                        }
                    }
                }
                try {
                    appendPreviousProfileRecovery(previouslyActiveProfileId, extensionManager, failures);
                } catch (Throwable recoveryFailure) {
                    if (recoveryFailure != afterPluginsLoadedFailure) {
                        afterPluginsLoadedFailure.addSuppressed(recoveryFailure);
                    }
                }
                if (failures.size() > 1) {
                    afterPluginsLoadedFailure.addSuppressed(
                            new IllegalStateException(join(failures, "; ")));
                }
                CS3PluginRuntime.<RuntimeException>throwUnchecked(afterPluginsLoadedFailure);
                return Collections.unmodifiableList(failures);
            }
            for (String id : activatedIds) {
                if (!cleanupPluginRegistrations(id)) {
                    failures.add(id + ": cleanup failed after profile activation was rejected.");
                }
            }
            appendPreviousProfileRecovery(previouslyActiveProfileId, extensionManager, failures);
            return Collections.unmodifiableList(failures);
        }
        activeProfileId = profileId;
        activeProfileFullyReconciled = true;
        activeProfileNeedsPluginReconcile = false;
        return Collections.unmodifiableList(failures);
    }

    private void appendPreviousProfileRecovery(String previousProfileId,
                                               CloudStreamExtensionManager extensionManager,
                                               List<String> failures) {
        if (previousProfileId == null || previousProfileId.isEmpty()) return;
        List<String> restoreFailures = reconcileProfile(previousProfileId, extensionManager);
        if (!restoreFailures.isEmpty()) {
            failures.add("Previous profile recovery is incomplete: " + join(restoreFailures, "; "));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }

    public boolean isLoaded(String pluginId) {
        return loadedPlugins.containsKey(pluginId);
    }

    /** Static ZIP/DEX inventory only. It never creates or resolves a plugin class. */
    public Cs3DexCapabilityPreflight.Result preflightCompatibility(
            CloudStreamExtensionManager.InstalledPlugin plugin) {
        if (plugin == null || plugin.filePath == null) {
            return Cs3DexCapabilityPreflight.inspect(null, appContext.getClassLoader());
        }
        try {
            File sourceArchive = new File(plugin.filePath);
            File codeArchive = Cs3JobDefaultImplsNormalizer.prepareForClassLoading(
                    appContext, sourceArchive, appContext.getClassLoader());
            return inspectPreparedArchive(plugin, codeArchive);
        } catch (IOException | RuntimeException normalizationFailure) {
            return Cs3DexCapabilityPreflight.Result.failure(
                    "CS3 DEX compatibility preparation failed closed");
        }
    }

    private Cs3DexCapabilityPreflight.Result inspectPreparedArchive(
            CloudStreamExtensionManager.InstalledPlugin plugin, File codeArchive) {
        return Cs3DexCapabilityPreflight.inspect(codeArchive, plugin.pluginClassName,
                plugin.requiresResources, appContext.getClassLoader());
    }

    private String describePreparedArchiveFailure(
            CloudStreamExtensionManager.InstalledPlugin plugin, File codeArchive) {
        Cs3DexCapabilityPreflight.Result preflight = inspectPreparedArchive(plugin, codeArchive);
        return preflight.isExactHostSurfacePresent() ? null : describePreflight(preflight);
    }

    /**
     * Exact-package static compatibility report for the isolated canary harness. This verifies
     * the approved archive and cached catalog record before scanning ZIP/DEX metadata; it never
     * defines, resolves, constructs, or executes a plugin class.
     */
    public CanaryPreflightResult inspectStreamPlayV694CanaryCompatibility() {
        if (!BuildConfig.CS3_CANARY_BUILD || !isDebuggableBuild()) {
            return CanaryPreflightResult.identityRejected();
        }
        CloudStreamExtensionManager extensionManager =
                OrbitCs3HostServices.get().getExtensionManager();
        CloudStreamExtensionManager.InstalledPlugin plugin =
                extensionManager.getInstalledPlugin(STREAMPLAY_V694_CANARY_PLUGIN_ID);
        if (validateStreamPlayV694CanaryIdentity(plugin, extensionManager) != null) {
            return CanaryPreflightResult.identityRejected();
        }
        return CanaryPreflightResult.verified(preflightCompatibility(plugin));
    }

    public String getActiveProfileId() {
        return activeProfileFullyReconciled ? activeProfileId : null;
    }

    public interface PluginCleanupBarrier {
        void blockPluginCalls(String pluginId);
        boolean scheduleCleanupRetry(String pluginId, Runnable cleanup);
        boolean pauseAndAwaitPluginCalls(String pluginId, long timeoutMs) throws InterruptedException;
        void pluginCleanupSucceeded(String pluginId);
    }

    private AssetManager attachResources(Object plugin, File file, ClassLoader loader) throws Exception {
        Class<?> assetManagerClass = Class.forName("android.content.res.AssetManager");
        AssetManager assets = (AssetManager) assetManagerClass.getDeclaredConstructor().newInstance();
        Method addAssetPath = assetManagerClass.getMethod("addAssetPath", String.class);
        Object cookie = addAssetPath.invoke(assets, file.getAbsolutePath());
        if (!(cookie instanceof Integer) || ((Integer) cookie) == 0) {
            throw new IllegalStateException("Could not attach plugin resources.");
        }
        Class<?> pluginClass = Class.forName(ANDROID_PLUGIN, false, appContext.getClassLoader());
        if (!pluginClass.isAssignableFrom(plugin.getClass())) {
            throw new IllegalStateException("Resource-bearing package does not use the Android Plugin API.");
        }
        Resources resources = createPluginResources(assets);
        pluginClass.getMethod("setResources", Resources.class).invoke(plugin, resources);
        return assets;
    }

    /** Refreshes plugin Resources wrappers after Orbit receives a configuration change. */
    public void refreshPluginResources() {
        synchronized (lifecycleLock) {
            for (LoadedPlugin loaded : loadedPlugins.values()) {
                if (loaded.assets == null) continue;
                try {
                    Resources next = createPluginResources(loaded.assets);
                    Method setter = Class.forName(ANDROID_PLUGIN, false, appContext.getClassLoader())
                            .getMethod("setResources", Resources.class);
                    setter.invoke(loaded.instance, next);
                } catch (Throwable error) {
                    // A resource refresh failure is recorded against the installed item without loading code.
                    OrbitCs3HostServices.get().getExtensionManager().recordActivationError(
                            loaded.pluginId, "Plugin resources could not follow Orbit configuration: " + rootMessage(error));
                }
            }
        }
    }

    /**
     * Binds an existing plugin-specific AssetManager to the CloudStream Resources API.
     * The public ResourcesLoader path starts at API 30; Orbit still supports API 23–29 and
     * must provide per-plugin assets through Plugin.setResources on every supported API.
     */
    @SuppressWarnings("deprecation")
    private Resources createPluginResources(AssetManager assets) {
        return new Resources(assets, appContext.getResources().getDisplayMetrics(),
                appContext.getResources().getConfiguration());
    }

    private static void invokeBeforeUnload(Object plugin) throws Exception {
        Class<?> parent = plugin.getClass();
        while (parent != null) {
            try {
                Method beforeUnload = parent.getDeclaredMethod("beforeUnload");
                beforeUnload.setAccessible(true);
                beforeUnload.invoke(plugin);
                return;
            } catch (NoSuchMethodException ignored) { parent = parent.getSuperclass(); }
        }
    }

    private void requireClass(String className, List<String> missing) {
        try { Class.forName(className, false, appContext.getClassLoader()); }
        catch (Throwable ignored) { missing.add(className); }
    }

    private static String describePreflight(Cs3DexCapabilityPreflight.Result result) {
        ArrayList<String> detail = new ArrayList<>();
        if (!result.errors.isEmpty()) detail.add("archive=" + join(result.errors, "; "));
        if (!result.missingHostTypes.isEmpty()) detail.add("types=" + join(result.missingHostTypes, ", "));
        if (!result.missingHostMethods.isEmpty()) detail.add("methods=" + join(result.missingHostMethods, ", "));
        if (!result.missingHostFields.isEmpty()) detail.add("fields=" + join(result.missingHostFields, ", "));
        return detail.isEmpty() ? "unknown preflight failure" : join(detail, " | ");
    }

    private static boolean isMegaProvider(CloudStreamExtensionManager.InstalledPlugin plugin) {
        return plugin.internalName.equalsIgnoreCase("MegaProvider")
                || plugin.name.equalsIgnoreCase("MegaProvider");
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) { if (result.length() > 0) result.append(separator); result.append(value); }
        return result.toString();
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.trim().isEmpty() ? root.getClass().getSimpleName() : message;
    }

    /** Returns only a fixed exception-family category suitable for canary diagnostics. */
    public static String safeCanaryFailureCategory(Throwable error) {
        Throwable root = error;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (int depth = 0; root != null && depth < MAX_CANARY_FAILURE_CAUSE_DEPTH; depth++) {
            if (!visited.add(root)) return "other_error";
            Throwable cause;
            try {
                cause = root.getCause();
            } catch (Throwable ignored) {
                return "other_error";
            }
            if (cause == null || cause == root) break;
            if (depth + 1 >= MAX_CANARY_FAILURE_CAUSE_DEPTH) return "other_error";
            root = cause;
        }
        if (root == null) return "other_error";
        if (root instanceof ClassNotFoundException || root instanceof NoClassDefFoundError) {
            return "missing_type";
        }
        if (root instanceof NoSuchMethodException || root instanceof NoSuchMethodError
                || root instanceof AbstractMethodError) {
            return "missing_method";
        }
        if (root instanceof NoSuchFieldException || root instanceof NoSuchFieldError) {
            return "missing_field";
        }
        if (root instanceof VerifyError) return "verify_error";
        if (root instanceof SecurityException) return "security_error";
        if (root instanceof LinkageError) return "linkage_error";
        if (root instanceof RuntimeException) return "runtime_exception";
        return "other_error";
    }

    /** Extractor counts remain observable, but only a valid positive provider count qualifies. */
    public static boolean hasCanaryProviderRegistrationEvidence(int providers, int extractors) {
        return providers >= 0 && extractors >= 0 && providers > 0;
    }

    /**
     * Returns a bounded diagnostic made only from the deepest exception class and a few
     * validated stack class/method identifiers. Messages and source-location metadata are omitted.
     */
    public static String safeCanaryFailureDiagnostic(Throwable error) {
        try {
            Throwable deepest = error;
            Set<Throwable> visited = Collections.newSetFromMap(
                    new IdentityHashMap<Throwable, Boolean>());
            for (int depth = 0; deepest != null && depth < MAX_CANARY_FAILURE_CAUSE_DEPTH
                    && visited.add(deepest); depth++) {
                Throwable cause;
                try {
                    cause = deepest.getCause();
                } catch (Throwable ignored) {
                    break;
                }
                if (cause == null || cause == deepest) break;
                deepest = cause;
            }

            String exceptionClass = "unknown";
            if (deepest != null) {
                try {
                    String candidate = safeCanaryIdentifier(
                            deepest.getClass().getName(), MAX_CANARY_DIAGNOSTIC_CLASS_CHARS, true);
                    if (candidate != null) exceptionClass = candidate;
                } catch (Throwable ignored) {
                    // Diagnostics must not interfere with canary cleanup or rollback.
                }
            }

            StringBuilder diagnostic = new StringBuilder(160);
            diagnostic.append("exception=").append(exceptionClass);
            StackTraceElement[] frames = null;
            try {
                if (deepest != null) frames = deepest.getStackTrace();
            } catch (Throwable ignored) {
                // Keep the exception class when stack extraction is unavailable.
            }
            if (frames != null) {
                int retained = 0;
                int inspected = Math.min(frames.length, MAX_CANARY_DIAGNOSTIC_FRAMES_INSPECTED);
                for (int index = 0; index < inspected && retained < MAX_CANARY_DIAGNOSTIC_FRAMES; index++) {
                    StackTraceElement frame = frames[index];
                    if (frame == null) continue;
                    String className;
                    String methodName;
                    try {
                        className = safeCanaryIdentifier(
                                frame.getClassName(), MAX_CANARY_DIAGNOSTIC_CLASS_CHARS, true);
                        methodName = safeCanaryIdentifier(
                                frame.getMethodName(), MAX_CANARY_DIAGNOSTIC_METHOD_CHARS, false);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (className == null || methodName == null) continue;
                    String entry = className + "#" + methodName;
                    String separator = retained == 0 ? ";frames=" : ",";
                    if (diagnostic.length() + separator.length() + entry.length()
                            > MAX_CANARY_FAILURE_DIAGNOSTIC_CHARS) break;
                    diagnostic.append(separator).append(entry);
                    retained++;
                }
            }
            if (diagnostic.length() > MAX_CANARY_FAILURE_DIAGNOSTIC_CHARS) {
                return diagnostic.substring(0, MAX_CANARY_FAILURE_DIAGNOSTIC_CHARS);
            }
            return diagnostic.toString();
        } catch (Throwable ignored) {
            return "exception=unknown";
        }
    }

    private static String safeCanaryIdentifier(String value, int maxChars, boolean allowDots) {
        if (value == null || value.isEmpty() || value.length() > maxChars) return null;
        if (!allowDots && ("<init>".equals(value) || "<clinit>".equals(value))) return value;
        boolean segmentStart = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (allowDots && character == '.') {
                if (segmentStart) return null;
                segmentStart = true;
                continue;
            }
            boolean letter = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z');
            boolean digit = character >= '0' && character <= '9';
            if (!(letter || character == '_' || character == '$' || (!segmentStart && digit))) {
                return null;
            }
            segmentStart = false;
        }
        return segmentStart ? null : value;
    }

    private static final class LoadedPlugin {
        final Object instance;
        final PathClassLoader loader;
        final File file;
        final OrbitCs3HostServices.LoadTransaction transaction;
        final AssetManager assets;
        final String pluginId;
        boolean beforeUnloadInvoked;
        boolean cleanupPending;
        String beforeUnloadFailure;
        LoadedPlugin(Object instance, PathClassLoader loader, File file,
                     OrbitCs3HostServices.LoadTransaction transaction, AssetManager assets) {
            this(instance, loader, file, transaction, assets,
                    transaction == null ? null : transaction.pluginId);
        }
        LoadedPlugin(Object instance, PathClassLoader loader, File file,
                     OrbitCs3HostServices.LoadTransaction transaction, AssetManager assets,
                     String pluginId) {
            this.instance = instance; this.loader = loader; this.file = file;
            this.transaction = transaction; this.assets = assets; this.pluginId = pluginId;
        }
    }

    private static final class RuntimeProfileSnapshot {
        final String activeProfileId;
        final boolean activeProfileFullyReconciled;
        final boolean activeProfileNeedsPluginReconcile;
        final String cleanupRecoveryProfileId;
        final boolean cleanupRecoveryWasFullyReconciled;
        final boolean cleanupRecoverySnapshotValid;

        RuntimeProfileSnapshot(CS3PluginRuntime runtime) {
            activeProfileId = runtime.activeProfileId;
            activeProfileFullyReconciled = runtime.activeProfileFullyReconciled;
            activeProfileNeedsPluginReconcile = runtime.activeProfileNeedsPluginReconcile;
            cleanupRecoveryProfileId = runtime.cleanupRecoveryProfileId;
            cleanupRecoveryWasFullyReconciled = runtime.cleanupRecoveryWasFullyReconciled;
            cleanupRecoverySnapshotValid = runtime.cleanupRecoverySnapshotValid;
        }

        void restore(CS3PluginRuntime runtime) {
            runtime.activeProfileId = activeProfileId;
            runtime.activeProfileFullyReconciled = activeProfileFullyReconciled;
            runtime.activeProfileNeedsPluginReconcile = activeProfileNeedsPluginReconcile;
            runtime.cleanupRecoveryProfileId = cleanupRecoveryProfileId;
            runtime.cleanupRecoveryWasFullyReconciled = cleanupRecoveryWasFullyReconciled;
            runtime.cleanupRecoverySnapshotValid = cleanupRecoverySnapshotValid;
        }
    }

    private static final class CanaryProviderSearchEvidence {
        String status = "NOT_RUN";
        int providerIndex = -1;
        int providerCallbackCount;
        int resultCount;
        boolean resultCountCapped;
        boolean attempted;
        boolean batchCompleted;
        long elapsedMs;
    }

    /** Result details contain fixed status text, a safe category, bounded diagnostic, and counts. */
    public static final class CanaryResult {
        public final boolean success;
        public final boolean cleanupComplete;
        public final int stagedProviderCount;
        public final int stagedExtractorCount;
        public final String failureCategory;
        public final String failureDiagnostic;
        public final String message;
        public final String providerSearchStatus;
        public final int providerSearchProviderIndex;
        public final int providerSearchCallbackCount;
        public final int providerSearchResultCount;
        public final boolean providerSearchResultCountCapped;
        public final boolean providerSearchAttempted;
        public final boolean providerSearchBatchCompleted;
        public final long providerSearchElapsedMs;

        private CanaryResult(boolean success, boolean cleanupComplete, String message, String failureCategory,
                             String failureDiagnostic, int providers, int extractors) {
            this(success, cleanupComplete, message, failureCategory, failureDiagnostic,
                    providers, extractors, new CanaryProviderSearchEvidence());
        }

        private CanaryResult(boolean success, boolean cleanupComplete, String message, String failureCategory,
                             String failureDiagnostic, int providers, int extractors,
                             CanaryProviderSearchEvidence searchEvidence) {
            this.success = success;
            this.cleanupComplete = cleanupComplete;
            this.message = message;
            this.failureCategory = failureCategory;
            this.failureDiagnostic = failureDiagnostic;
            stagedProviderCount = providers;
            stagedExtractorCount = extractors;
            providerSearchStatus = searchEvidence.status;
            providerSearchProviderIndex = searchEvidence.providerIndex;
            providerSearchCallbackCount = searchEvidence.providerCallbackCount;
            providerSearchResultCount = searchEvidence.resultCount;
            providerSearchResultCountCapped = searchEvidence.resultCountCapped;
            providerSearchAttempted = searchEvidence.attempted;
            providerSearchBatchCompleted = searchEvidence.batchCompleted;
            providerSearchElapsedMs = searchEvidence.elapsedMs;
        }

        private static CanaryResult success(int providers, int extractors,
                                            CanaryProviderSearchEvidence searchEvidence) {
            return new CanaryResult(true, true,
                    "StreamPlay v694 provider search completed and its registrations were rolled back.",
                    null, null, providers, extractors, searchEvidence);
        }

        private static CanaryResult failure(String message, boolean cleanupComplete,
                                             int providers, int extractors) {
            return failure(message, "other_error", cleanupComplete, providers, extractors);
        }

        private static CanaryResult failure(String message, String failureCategory, boolean cleanupComplete,
                                             int providers, int extractors) {
            return failure(message, failureCategory, null, cleanupComplete, providers, extractors);
        }

        private static CanaryResult failure(String message, String failureCategory, String failureDiagnostic,
                                             boolean cleanupComplete, int providers, int extractors) {
            return new CanaryResult(false, cleanupComplete, message,
                    failureCategory == null ? "other_error" : failureCategory,
                    failureDiagnostic, providers, extractors);
        }

        private static CanaryResult failure(String message, String failureCategory, String failureDiagnostic,
                                             boolean cleanupComplete, int providers, int extractors,
                                             CanaryProviderSearchEvidence searchEvidence) {
            return new CanaryResult(false, cleanupComplete, message,
                    failureCategory == null ? "other_error" : failureCategory,
                    failureDiagnostic, providers, extractors, searchEvidence);
        }
    }

    /** Result of a read-only exact-artifact and static host-surface preflight. */
    public static final class CanaryPreflightResult {
        public final boolean exactAuthorizedPackage;
        public final Cs3DexCapabilityPreflight.Result hostSurface;

        private CanaryPreflightResult(boolean exactAuthorizedPackage,
                                      Cs3DexCapabilityPreflight.Result hostSurface) {
            this.exactAuthorizedPackage = exactAuthorizedPackage;
            this.hostSurface = hostSurface;
        }

        private static CanaryPreflightResult identityRejected() {
            return new CanaryPreflightResult(false, null);
        }

        private static CanaryPreflightResult verified(Cs3DexCapabilityPreflight.Result hostSurface) {
            return new CanaryPreflightResult(true, hostSurface);
        }
    }

    public static final class ActivationResult {
        public final boolean success;
        public final String message;
        private ActivationResult(boolean success, String message) { this.success = success; this.message = message; }
        static ActivationResult success() { return new ActivationResult(true, "Activated for the current profile."); }
        static ActivationResult failure(String message) { return new ActivationResult(false, message); }
    }

    static final class ActivationPreparation {
        final File codeArchive;
        final Throwable failure;

        private ActivationPreparation(File codeArchive, Throwable failure) {
            this.codeArchive = codeArchive;
            this.failure = failure;
        }

        boolean isPrepared() {
            return codeArchive != null && failure == null;
        }
    }
}
