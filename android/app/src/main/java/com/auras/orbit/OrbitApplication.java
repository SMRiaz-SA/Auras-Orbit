package com.auras.orbit;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.auras.orbit.BuildConfig;
import com.auras.orbit.profile.LocalProfileStore;
import com.auras.orbit.profile.ProviderSelectionStore;
import com.auras.orbit.extensions.CloudStreamExtensionManager;
import com.auras.orbit.extensions.CS3PluginRuntime;
import com.auras.orbit.extensions.ExtensionOperationController;
import com.auras.orbit.extensions.OrbitCs3HostServices;
import com.auras.orbit.library.LocalLibraryStore;
import com.auras.orbit.backup.OrbitLibraryArchiveService;
import com.auras.orbit.jellyfin.JellyfinClient;
import com.auras.orbit.jellyfin.JellyfinCredentialStore;
import com.auras.orbit.downloads.DownloadQueueManager;
import com.auras.orbit.metadata.store.OrbitMetadataCacheDatabase;
import com.auras.orbit.metadata.store.OrbitMetadataUserDecisions;
import com.auras.orbit.metadata.TitleCatalogRecord;
import com.auras.orbit.metadata.TmdbApiKeyStore;
import com.auras.orbit.metadata.TmdbTitleMetadataRepository;
import com.auras.orbit.metadata.MetadataFieldPolicy;
import com.auras.orbit.providers.DetailsRequestSingleFlight;
import com.auras.orbit.providers.Cs3ProviderRegistry;
import com.auras.orbit.providers.MediaProvider;
import com.auras.orbit.providers.ProviderOperationCoordinator;
import com.lagradost.cloudstream3.LoadResponse;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OrbitApplication extends Application {
    private static final long PROFILE_PROVIDER_QUIESCE_TIMEOUT_MS = 15_000L;
    private LocalProfileStore localProfileStore;
    private ProviderSelectionStore providerSelectionStore;
    private CloudStreamExtensionManager cloudStreamExtensionManager;
    private CS3PluginRuntime cs3PluginRuntime;
    private ExtensionOperationController extensionOperationController;
    private LocalLibraryStore localLibraryStore;
    private OrbitMetadataCacheDatabase metadataCacheDatabase;
    private OrbitMetadataUserDecisions metadataUserDecisions;
    private final TmdbTitleMetadataRepository tmdbTitleMetadataRepository = new TmdbTitleMetadataRepository();
    private final DetailsRequestSingleFlight<TitleCatalogRecord> tmdbTitleSingleFlight =
            new DetailsRequestSingleFlight<>();
    private final ExecutorService metadataCatalogWorker = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
        Thread worker = new Thread(runnable, "orbit-tmdb-catalog");
        worker.setDaemon(true);
        return worker;
    }, new ThreadPoolExecutor.AbortPolicy());
    /** Bounded queue: optional metadata requests must not grow without limit. */
    private final ExecutorService metadataWorker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), runnable -> {
        Thread worker = new Thread(runnable, "orbit-metadata-store");
        worker.setDaemon(true);
        return worker;
    }, new ThreadPoolExecutor.AbortPolicy());
    private OrbitLibraryArchiveService libraryArchiveService;
    private String archiveRecoveryFailure;
    private JellyfinClient jellyfinClient;
    private JellyfinCredentialStore jellyfinCredentialStore;
    private DownloadQueueManager downloadQueueManager;
    private ProviderOperationCoordinator providerOperationCoordinator;
    private Cs3ProviderRegistry cs3ProviderRegistry;
    private final ExecutorService extensionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "orbit-cs3-extension-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService libraryArchiveExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "orbit-library-archive-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService jellyfinUiExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "orbit-jellyfin-ui-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService jellyfinArtworkExecutor = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(96), runnable -> {
        Thread thread = new Thread(runnable, "orbit-jellyfin-artwork-worker");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService downloadActionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "orbit-download-action-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Object profileReconcileLock = new Object();
    private String requestedPluginProfileId;
    private String deferredPluginProfileId;
    private String reconciledPluginProfileId;
    private boolean profileReconcileCompleting;
    private boolean lastProfileReconcileSucceeded;
    private boolean streamPlayCanaryInProgress;
    private List<String> lastProfileReconcileFailures = Collections.emptyList();
    private final ArrayList<PluginProfileReconcileCallback> profileReconcileCallbacks = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        TmdbApiKeyStore.initialize(this);
        LoadResponse.initializeSyncIdPrefixes();
        localProfileStore = new LocalProfileStore(this);
        localLibraryStore = new LocalLibraryStore(this);
        // Separate databases and failure domains: corrupt metadata must never affect Library.
        metadataCacheDatabase = new OrbitMetadataCacheDatabase(this);
        metadataUserDecisions = new OrbitMetadataUserDecisions(this);
        libraryArchiveService = new OrbitLibraryArchiveService(localProfileStore, localLibraryStore);
        try {
            libraryArchiveService.recoverInterruptedImport();
        } catch (com.auras.orbit.backup.OrbitLibraryArchiveCodec.ArchiveException error) {
            archiveRecoveryFailure = error.getMessage();
            LocalProfileStore.setArchiveRecoveryBlocked(true);
            return;
        }
        providerSelectionStore = new ProviderSelectionStore(this);
        cloudStreamExtensionManager = new CloudStreamExtensionManager(this);
        OrbitCs3HostServices hostServices = OrbitCs3HostServices.install(this, cloudStreamExtensionManager);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) { }
            @Override public void onActivityStarted(android.app.Activity activity) { hostServices.onActivityStarted(activity); }
            @Override public void onActivityResumed(android.app.Activity activity) { hostServices.onActivityResumed(activity); }
            @Override public void onActivityPaused(android.app.Activity activity) { hostServices.onActivityPaused(activity); }
            @Override public void onActivityStopped(android.app.Activity activity) { hostServices.onActivityStopped(activity); }
            @Override public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) { }
            @Override public void onActivityDestroyed(android.app.Activity activity) { hostServices.onActivityStopped(activity); }
        });
        cs3PluginRuntime = new CS3PluginRuntime(this);
        extensionOperationController = new ExtensionOperationController(extensionExecutor,
                cloudStreamExtensionManager, cs3PluginRuntime, localProfileStore,
                profileId -> mainHandler.post(() -> {
                    LocalProfileStore.Profile active = localProfileStore.getActiveProfile();
                    if (active != null && profileId.equals(active.id)) {
                        reconcilePluginsForProfile(profileId);
                    }
                }));
        providerOperationCoordinator = new ProviderOperationCoordinator(4);
        cs3ProviderRegistry = new Cs3ProviderRegistry(hostServices, cloudStreamExtensionManager,
                providerSelectionStore, providerOperationCoordinator, cs3PluginRuntime);
        cs3PluginRuntime.setPluginCleanupBarrier(new CS3PluginRuntime.PluginCleanupBarrier() {
            @Override public void blockPluginCalls(String pluginId) {
                cs3ProviderRegistry.blockPluginCalls(pluginId);
            }

            @Override public boolean scheduleCleanupRetry(String pluginId, Runnable cleanup) {
                try {
                    extensionExecutor.execute(cleanup);
                    return true;
                } catch (RuntimeException rejected) {
                    return false;
                }
            }

            @Override public boolean pauseAndAwaitPluginCalls(String pluginId, long timeoutMs)
                    throws InterruptedException {
                return cs3ProviderRegistry.pauseAndAwaitPluginCalls(pluginId, timeoutMs);
            }

            @Override public void pluginCleanupSucceeded(String pluginId) {
                cs3ProviderRegistry.onPluginCleanupFinished(pluginId);
            }
        });
        hostServices.setProviderLifecycleListener(cs3ProviderRegistry::onPluginProviderChange);
        jellyfinClient = new JellyfinClient();
        jellyfinCredentialStore = new JellyfinCredentialStore(this);
        downloadQueueManager = new DownloadQueueManager(this);
        downloadQueueManager.setProviderHookRestorer(cs3ProviderRegistry::restoreVerifierOnlyHooks);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (cs3PluginRuntime != null) cs3PluginRuntime.refreshPluginResources();
    }

    /** Read-only status for a fail-closed startup screen; exposes no archive contents. */
    public boolean isArchiveRecoveryBlocked() {
        return archiveRecoveryFailure != null || LocalProfileStore.isArchiveRecoveryBlocked();
    }

    public OrbitMetadataCacheDatabase getMetadataCacheDatabase() {
        requireArchiveRecovery();
        return metadataCacheDatabase;
    }

    public OrbitMetadataUserDecisions getMetadataUserDecisions() {
        requireArchiveRecovery();
        return metadataUserDecisions;
    }

    /** Never block Details/Play or the main thread on an optional display cache. */
    public void recordSafeMetadataDisplay(String profileId, String providerId,
            com.auras.orbit.providers.MediaItem seed, com.auras.orbit.providers.MediaItem details) {
        if (metadataCacheDatabase == null) return;
        try {
            metadataWorker.execute(() -> {
                try {
                    metadataCacheDatabase.saveProviderDisplay(profileId, providerId, seed,
                            details, System.currentTimeMillis());
                } catch (android.database.sqlite.SQLiteDatabaseCorruptException corrupt) {
                    metadataCacheDatabase.recoverCorruptCache(this);
                } catch (RuntimeException ignored) {
                    // Disposable metadata failure must never block provider actions.
                }
            });
        } catch (RuntimeException ignored) { /* Worker stopping, skip optional cache. */ }
    }

    /** An immutable background-read result, never a provider Details or playback object. */
    public static final class MetadataDisplaySnapshot {
        public final OrbitMetadataCacheDatabase.Display cache;
        public final String userTitle;
        public final String userSynopsis;
        public final OrbitMetadataUserDecisions.TmdbLink tmdbLink;
        public MetadataDisplaySnapshot(OrbitMetadataCacheDatabase.Display cache,
                                       String userTitle, String userSynopsis,
                                       OrbitMetadataUserDecisions.TmdbLink tmdbLink) {
            this.cache = cache;
            this.userTitle = userTitle;
            this.userSynopsis = userSynopsis;
            this.tmdbLink = tmdbLink;
        }
    }

    public void readSafeMetadataDisplay(String profileId, String providerId,
            com.auras.orbit.providers.MediaItem seed, Consumer<MetadataDisplaySnapshot> callback) {
        if (seed == null || profileId == null || providerId == null || callback == null) return;
        try {
            metadataWorker.execute(() -> {
                OrbitMetadataCacheDatabase.Display cached = null;
                String title = null, synopsis = null;
                OrbitMetadataUserDecisions.TmdbLink tmdbLink = null;
                try {
                    cached = metadataCacheDatabase.readProviderDisplay(profileId, providerId,
                            seed, System.currentTimeMillis());
                } catch (android.database.sqlite.SQLiteDatabaseCorruptException corrupt) {
                    metadataCacheDatabase.recoverCorruptCache(this);
                } catch (RuntimeException ignored) {
                    // Optional cached artwork/text can fail without suppressing user decisions.
                }
                try {
                    title = metadataUserDecisions.read(profileId, providerId, seed.stableId,
                            seed.detailUrl, OrbitMetadataUserDecisions.Field.TITLE);
                    synopsis = metadataUserDecisions.read(profileId, providerId, seed.stableId,
                            seed.detailUrl, OrbitMetadataUserDecisions.Field.SYNOPSIS);
                    tmdbLink = metadataUserDecisions.readTmdbLink(profileId, providerId,
                            seed.stableId, seed.detailUrl, seed.kind.name());
                } catch (RuntimeException ignored) {
                    // Durable decisions remain intact; a read failure is never an instruction
                    // to delete or rebuild the user-decision or Library database.
                }
                MetadataDisplaySnapshot snapshot = new MetadataDisplaySnapshot(cached, title,
                        synopsis, tmdbLink);
                mainHandler.post(() -> callback.accept(snapshot));
            });
        } catch (RuntimeException ignored) { /* No callback on queue saturation or shutdown. */ }
    }

    /** Durable corrections are explicit, revertible user operations, never automatic matching. */
    public void updateUserDisplayCorrection(String profileId, String providerId,
            com.auras.orbit.providers.MediaItem seed, OrbitMetadataUserDecisions.Field field,
            String value, Consumer<Boolean> callback) {
        if (seed == null || profileId == null || providerId == null || field == null) {
            if (callback != null) mainHandler.post(() -> callback.accept(false));
            return;
        }
        try {
            metadataWorker.execute(() -> {
                boolean saved = false;
                try {
                    if (value == null) metadataUserDecisions.clear(profileId, providerId,
                            seed.stableId, seed.detailUrl, field);
                    else metadataUserDecisions.put(profileId, providerId, seed.stableId,
                            seed.detailUrl, field, value, System.currentTimeMillis());
                    saved = true;
                } catch (RuntimeException ignored) { /* Report failure to user. */ }
                final boolean result = saved;
                if (callback != null) mainHandler.post(() -> callback.accept(result));
            });
        } catch (RuntimeException ignored) {
            if (callback != null) mainHandler.post(() -> callback.accept(false));
        }
    }

    /** Undo the last explicit display change on the durable metadata worker. */
    public void undoUserDisplayCorrection(String profileId, String providerId,
            com.auras.orbit.providers.MediaItem seed, OrbitMetadataUserDecisions.Field field,
            Consumer<Boolean> callback) {
        if (seed == null || profileId == null || providerId == null || field == null) {
            if (callback != null) mainHandler.post(() -> callback.accept(false));
            return;
        }
        try {
            metadataWorker.execute(() -> {
                boolean undone = false;
                try {
                    undone = metadataUserDecisions.undoLastDisplayChange(profileId, providerId,
                            seed.stableId, seed.detailUrl, field, System.currentTimeMillis());
                } catch (RuntimeException ignored) { /* Report failure, never change provider state. */ }
                boolean result = undone;
                if (callback != null) mainHandler.post(() -> callback.accept(result));
            });
        } catch (RuntimeException ignored) {
            if (callback != null) mainHandler.post(() -> callback.accept(false));
        }
    }

    /** Only an explicit typed user link is eligible for catalog fetch or enrichment. */
    public void loadVerifiedTmdbTitle(OrbitMetadataUserDecisions.TmdbLink link,
                                      Consumer<TitleCatalogRecord> callback) {
        if (callback == null) return;
        if (link == null || link.rejected || link.id <= 0) {
            mainHandler.post(() -> callback.accept(null));
            return;
        }
        try {
            metadataWorker.execute(() -> {
                TitleCatalogRecord cached = null;
                try {
                    cached = metadataCacheDatabase.readCatalogTitle(link.id, link.kind,
                            System.currentTimeMillis());
                } catch (android.database.sqlite.SQLiteDatabaseCorruptException corrupt) {
                    metadataCacheDatabase.recoverCorruptCache(this);
                } catch (RuntimeException ignored) { /* Cache is disposable. */ }
                if (cached != null) {
                    final TitleCatalogRecord ready = cached;
                    mainHandler.post(() -> callback.accept(ready));
                    return;
                }
                try {
                    metadataCatalogWorker.execute(() -> {
                        TitleCatalogRecord fetched = null;
                        try {
                            fetched = tmdbTitleSingleFlight.execute(tmdbTitleMetadataRepository,
                                    link.kind, Integer.toString(link.id),
                                    () -> tmdbTitleMetadataRepository.load(link.id, link.kind));
                            if (fetched != null && fetched.catalogId == link.id
                                    && link.kind.equals(fetched.kind)) {
                                final TitleCatalogRecord accepted = fetched;
                                try {
                                    metadataWorker.execute(() -> {
                                        try { metadataCacheDatabase.saveCatalogTitle(accepted,
                                                System.currentTimeMillis()); }
                                        catch (android.database.sqlite.SQLiteDatabaseCorruptException corrupt) {
                                            metadataCacheDatabase.recoverCorruptCache(this);
                                        } catch (RuntimeException ignored) { /* Optional cache. */ }
                                    });
                                } catch (RuntimeException ignored) { /* Worker busy. */ }
                            } else fetched = null;
                        } catch (Exception | LinkageError ignored) {
                            // Failure remains a missing catalog field, not unavailable playback.
                        }
                        final TitleCatalogRecord result = fetched;
                        mainHandler.post(() -> callback.accept(result));
                    });
                } catch (RuntimeException rejected) {
                    mainHandler.post(() -> callback.accept(null));
                }
            });
        } catch (RuntimeException rejected) { mainHandler.post(() -> callback.accept(null)); }
    }

    public void updateUserTmdbLink(String profileId, String providerId,
            com.auras.orbit.providers.MediaItem seed, Integer tmdbId,
            boolean reject, Consumer<Boolean> callback) {
        if (seed == null || profileId == null || providerId == null) {
            if (callback != null) mainHandler.post(() -> callback.accept(false));
            return;
        }
        try {
            metadataWorker.execute(() -> {
                boolean saved = false;
                try {
                    if (tmdbId == null) metadataUserDecisions.clearTmdbLink(profileId,
                            providerId, seed.stableId, seed.detailUrl, seed.kind.name());
                    else metadataUserDecisions.putTmdbLink(profileId, providerId, seed.stableId,
                            seed.detailUrl, seed.kind.name(), tmdbId, reject, System.currentTimeMillis());
                    saved = true;
                } catch (RuntimeException ignored) { /* Notify caller. */ }
                final boolean result = saved;
                if (callback != null) mainHandler.post(() -> callback.accept(result));
            });
        } catch (RuntimeException rejected) {
            if (callback != null) mainHandler.post(() -> callback.accept(false));
        }
    }

    public MetadataFieldPolicy.Mode getMetadataMode(String profileId) {
        if (profileId == null) return MetadataFieldPolicy.Mode.INTELLIGENT_HYBRID;
        String key = "metadata_mode:" + OrbitMetadataCacheDatabase.sourceKey(profileId, "", "", "");
        String value = getSharedPreferences("orbit_metadata_modes_v1", MODE_PRIVATE)
                .getString(key, MetadataFieldPolicy.Mode.INTELLIGENT_HYBRID.name());
        try { return MetadataFieldPolicy.Mode.valueOf(value); }
        catch (RuntimeException ignored) { return MetadataFieldPolicy.Mode.INTELLIGENT_HYBRID; }
    }

    public void setMetadataMode(String profileId, MetadataFieldPolicy.Mode mode) {
        if (profileId == null || mode == null) throw new IllegalArgumentException("Profile and mode required");
        String key = "metadata_mode:" + OrbitMetadataCacheDatabase.sourceKey(profileId, "", "", "");
        if (!getSharedPreferences("orbit_metadata_modes_v1", MODE_PRIVATE)
                .edit().putString(key, mode.name()).commit())
            throw new IllegalStateException("Could not save metadata display mode");
    }

    public LocalProfileStore getLocalProfileStore() {
        requireArchiveRecovery();
        return localProfileStore;
    }

    public ProviderSelectionStore getProviderSelectionStore() {
        return providerSelectionStore;
    }

    public CloudStreamExtensionManager getCloudStreamExtensionManager() {
        return cloudStreamExtensionManager;
    }

    public CS3PluginRuntime getCS3PluginRuntime() {
        return cs3PluginRuntime;
    }

    public ExtensionOperationController getExtensionOperationController() {
        return extensionOperationController;
    }

    public LocalLibraryStore getLocalLibraryStore() {
        requireArchiveRecovery();
        return localLibraryStore;
    }

    public OrbitLibraryArchiveService getLibraryArchiveService() {
        requireArchiveRecovery();
        return libraryArchiveService;
    }

    private void requireArchiveRecovery() {
        if (archiveRecoveryFailure != null || LocalProfileStore.isArchiveRecoveryBlocked()) {
            throw new IllegalStateException("Orbit could not recover the library import. Restart the app to retry.",
                    archiveRecoveryFailure == null ? null : new IllegalStateException(archiveRecoveryFailure));
        }
    }

    public ExecutorService getLibraryArchiveExecutor() {
        return libraryArchiveExecutor;
    }

    public JellyfinClient getJellyfinClient() {
        return jellyfinClient;
    }

    public JellyfinCredentialStore getJellyfinCredentialStore() {
        return jellyfinCredentialStore;
    }

    public ExecutorService getJellyfinUiExecutor() {
        return jellyfinUiExecutor;
    }

    public ExecutorService getJellyfinArtworkExecutor() {
        return jellyfinArtworkExecutor;
    }

    public DownloadQueueManager getDownloadQueueManager() {
        return downloadQueueManager;
    }

    public ProviderOperationCoordinator getProviderOperationCoordinator() {
        return providerOperationCoordinator;
    }

    public Cs3ProviderRegistry getCs3ProviderRegistry() {
        return cs3ProviderRegistry;
    }

    /** Returns the ordered CS3 providers only for the fully reconciled active profile. */
    public List<MediaProvider> getMediaProvidersForProfile(String profileId) {
        requireArchiveRecovery();
        return cs3ProviderRegistry == null ? Collections.emptyList()
                : cs3ProviderRegistry.getProviders(profileId);
    }

    public ExecutorService getDownloadActionExecutor() {
        return downloadActionExecutor;
    }

    public ExecutorService getExtensionExecutor() {
        return extensionExecutor;
    }

    /** Queues the test-only one-shot canary on the serialized extension worker. */
    public Future<CS3PluginRuntime.CanaryResult> enqueueDebugStreamPlayV694Canary() {
        if (!BuildConfig.CS3_CANARY_BUILD
                || (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            throw new UnsupportedOperationException(
                    "StreamPlay v694 canary loads are available only in the isolated debuggable canary build.");
        }
        final String activeProfileId;
        synchronized (profileReconcileLock) {
            if (streamPlayCanaryInProgress || requestedPluginProfileId != null) {
                throw new IllegalStateException("A profile reconciliation or StreamPlay canary is already in progress.");
            }
            if (cs3PluginRuntime == null || !cs3PluginRuntime.hasFreshStreamPlayV694CanaryAuthorization()) {
                throw new IllegalStateException("A new unique StreamPlay v694 authorization marker is required.");
            }
            if (extensionOperationController == null || extensionOperationController.getSnapshot().isRunning()) {
                throw new IllegalStateException("The extension worker is busy; the StreamPlay canary was not queued.");
            }
            if (cloudStreamExtensionManager == null
                    || !cloudStreamExtensionManager.isStorageRecoveryReady()) {
                throw new IllegalStateException(
                        "The StreamPlay canary is blocked until CS3 package storage recovery completes.");
            }
            if (localProfileStore == null) {
                throw new IllegalStateException("Orbit profile state is unavailable for the StreamPlay canary.");
            }
            LocalProfileStore.Profile activeProfile = localProfileStore.getActiveProfile();
            if (activeProfile == null) {
                throw new IllegalStateException("The StreamPlay canary requires a resolved active profile.");
            }
            activeProfileId = activeProfile.id;
            if (cloudStreamExtensionManager.isEnabledForProfile(
                    activeProfileId, CS3PluginRuntime.STREAMPLAY_V694_CANARY_PLUGIN_ID)) {
                throw new IllegalStateException(
                        "The active profile has a StreamPlay grant; the canary was not started.");
            }
            if (!cs3PluginRuntime.armStreamPlayV694DebugCanaryOnce()) {
                throw new IllegalStateException("The new unique StreamPlay v694 authorization marker is unavailable.");
            }
            streamPlayCanaryInProgress = true;
        }

        FutureTask<CS3PluginRuntime.CanaryResult> task = new FutureTask<>(() -> {
            CS3PluginRuntime.CanaryResult result = null;
            try {
                result = cs3PluginRuntime.runStreamPlayV694DebugCanary(
                        cloudStreamExtensionManager.getInstalledPlugin(
                                CS3PluginRuntime.STREAMPLAY_V694_CANARY_PLUGIN_ID),
                        activeProfileId);
                return result;
            } finally {
                if (result != null && result.cleanupComplete) {
                    synchronized (profileReconcileLock) { streamPlayCanaryInProgress = false; }
                }
            }
        });
        try {
            extensionExecutor.execute(task);
        } catch (RuntimeException rejected) {
            cs3PluginRuntime.disarmUnscheduledStreamPlayV694DebugCanary();
            synchronized (profileReconcileLock) { streamPlayCanaryInProgress = false; }
            throw rejected;
        }
        return task;
    }
    /** Reconcile exactly one installed-plugin set after the user confirms a profile. */
    public void reconcilePluginsForProfile(String profileId) {
        reconcilePluginsForProfile(profileId, null);
    }

    /**
     * Reconciles before the caller commits its selected profile. Callback delivery
     * occurs on the main thread and reports whether the requested plugin set is live.
     */
    public void reconcilePluginsForProfile(String profileId, PluginProfileReconcileCallback callback) {
        requireArchiveRecovery();
        if (profileId == null || profileId.isEmpty()) {
            dispatchProfileReconcileCallback(callback, false,
                    Collections.singletonList("A profile must be selected before plugin reconciliation."));
            return;
        }
        boolean start = false;
        synchronized (profileReconcileLock) {
            if (streamPlayCanaryInProgress) {
                if (callback != null) dispatchProfileReconcileCallback(callback, false,
                        Collections.singletonList("Profile reconciliation is blocked while the StreamPlay canary runs."));
                return;
            }
            if (profileId.equals(requestedPluginProfileId)) {
                if (callback != null) {
                    if (profileReconcileCompleting) {
                        boolean success = lastProfileReconcileSucceeded;
                        List<String> failures = lastProfileReconcileFailures;
                        mainHandler.post(() -> callback.onComplete(success, failures));
                    } else profileReconcileCallbacks.add(callback);
                }
                return;
            }
            if (requestedPluginProfileId != null) {
                LocalProfileStore.Profile active = localProfileStore.getActiveProfile();
                if (active != null && profileId.equals(active.id)) {
                    deferredPluginProfileId = profileId;
                }
                if (callback != null) dispatchProfileReconcileCallback(callback, false,
                        Collections.singletonList("Another profile change is still reconciling. Please retry after it finishes."));
                return;
            }
            if (profileId.equals(reconciledPluginProfileId)
                    && profileId.equals(cs3PluginRuntime.getActiveProfileId())
                    && !cs3PluginRuntime.hasPendingPluginProfileReconcile()
                    && lastProfileReconcileSucceeded
                    && cs3ProviderRegistry != null
                    && !cs3ProviderRegistry.isProfileReconcileInProgress()) {
                dispatchProfileReconcileCallback(callback, true, Collections.emptyList());
                return;
            }
            requestedPluginProfileId = profileId;
            profileReconcileCallbacks.clear();
            if (callback != null) profileReconcileCallbacks.add(callback);
            start = true;
        }
        if (!start) return;
        if (cs3ProviderRegistry != null) cs3ProviderRegistry.onProfileReconcileStarted();
        try {
            extensionExecutor.execute(() -> {
                try {
                    if (cs3ProviderRegistry == null
                            || !cs3ProviderRegistry.awaitAllProviderWorkQuiesced(
                                    PROFILE_PROVIDER_QUIESCE_TIMEOUT_MS)) {
                        finishProfileReconcile(profileId, false,
                                Collections.singletonList("Provider calls did not quiesce within 15 seconds; plugin profile reconciliation was skipped. Retry after the active provider call returns."));
                        return;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    finishProfileReconcile(profileId, false,
                            Collections.singletonList("Provider call quiescence was interrupted; plugin profile reconciliation was skipped. Retry."));
                    return;
                }
                List<String> failures;
                try {
                    failures = cs3PluginRuntime.reconcileProfileForOrbitApplication(
                            profileId, cloudStreamExtensionManager);
                } catch (Throwable error) {
                    String message = error.getMessage();
                    if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
                    failures = Collections.singletonList("Plugin profile reconciliation failed: " + message);
                }
                boolean succeeded = failures.isEmpty() && profileId.equals(cs3PluginRuntime.getActiveProfileId());
                finishProfileReconcile(profileId, succeeded, failures);
            });
        } catch (RuntimeException error) {
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
            finishProfileReconcile(profileId, false,
                    Collections.singletonList("Orbit could not schedule plugin reconciliation: " + message));
        }
    }

    private void finishProfileReconcile(String profileId, boolean succeeded, List<String> failures) {
        List<String> immutableFailures = Collections.unmodifiableList(new ArrayList<>(failures));
        mainHandler.post(() -> {
            List<PluginProfileReconcileCallback> callbacks;
            synchronized (profileReconcileLock) {
                if (!profileId.equals(requestedPluginProfileId)) return;
                profileReconcileCompleting = true;
                lastProfileReconcileSucceeded = succeeded;
                lastProfileReconcileFailures = immutableFailures;
                if (succeeded) reconciledPluginProfileId = profileId;
                callbacks = new ArrayList<>(profileReconcileCallbacks);
                profileReconcileCallbacks.clear();
            }
            // The runtime attempt either completed or was skipped before changing runtime state.
            // In both cases the same active plugin set is safe for provider calls again.
            if (cs3ProviderRegistry != null) {
                cs3ProviderRegistry.onProfileReconcileFinished();
            }
            try {
                for (PluginProfileReconcileCallback callback : callbacks) {
                    try { callback.onComplete(succeeded, immutableFailures); }
                    catch (Throwable ignored) { }
                }
            } finally {
                String deferredProfile = null;
                synchronized (profileReconcileLock) {
                    if (profileId.equals(requestedPluginProfileId)) requestedPluginProfileId = null;
                    profileReconcileCompleting = false;
                    LocalProfileStore.Profile active = localProfileStore.getActiveProfile();
                    if (deferredPluginProfileId != null && active != null
                            && deferredPluginProfileId.equals(active.id)) {
                        deferredProfile = deferredPluginProfileId;
                    }
                    deferredPluginProfileId = null;
                }
                if (deferredProfile != null) {
                    String retryProfile = deferredProfile;
                    mainHandler.post(() -> reconcilePluginsForProfile(retryProfile));
                }
            }
        });
    }

    /** Returns the active Orbit profile only after it resolves to a valid stored profile. */
    public String getActiveProfileId() {
        LocalProfileStore store = localProfileStore;
        if (store == null) return null;
        try {
            LocalProfileStore.Profile active = store.getActiveProfile();
            return active == null ? null : active.id;
        } catch (RuntimeException unavailableProfileState) {
            return null;
        }
    }

    private void dispatchProfileReconcileCallback(PluginProfileReconcileCallback callback,
                                                  boolean succeeded, List<String> failures) {
        if (callback != null) mainHandler.post(() -> callback.onComplete(succeeded, failures));
    }

    public interface PluginProfileReconcileCallback {
        void onComplete(boolean succeeded, List<String> failures);
    }

    @Override
    public void onTerminate() {
        if (providerOperationCoordinator != null) providerOperationCoordinator.close();
        extensionExecutor.shutdownNow();
        metadataWorker.shutdownNow();
        metadataCatalogWorker.shutdownNow();
        if (metadataCacheDatabase != null) metadataCacheDatabase.close();
        if (metadataUserDecisions != null) metadataUserDecisions.close();
        libraryArchiveExecutor.shutdownNow();
        jellyfinUiExecutor.shutdownNow();
        jellyfinArtworkExecutor.shutdownNow();
        downloadActionExecutor.shutdownNow();
        super.onTerminate();
    }
}
