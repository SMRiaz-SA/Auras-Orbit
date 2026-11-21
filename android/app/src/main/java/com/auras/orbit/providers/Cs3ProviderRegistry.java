package com.auras.orbit.providers;

import android.os.Handler;
import android.os.Looper;

import com.auras.orbit.extensions.CloudStreamExtensionManager;
import com.auras.orbit.extensions.CS3PluginRuntime;
import com.auras.orbit.extensions.OrbitCs3HostServices;
import com.auras.orbit.downloads.DownloadProviderHookToken;
import com.auras.orbit.profile.ProviderSelectionStore;
import com.lagradost.cloudstream3.MainAPI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Profile-scoped Orbit view of only committed and enabled CS3 MainAPI registrations. */
public final class Cs3ProviderRegistry {
    private final OrbitCs3HostServices hostServices;
    private final CloudStreamExtensionManager extensionManager;
    private final ProviderSelectionStore selectionStore;
    private final ProviderOperationCoordinator operationCoordinator;
    private final CS3PluginRuntime pluginRuntime;
    private final AtomicLong profileGeneration = new AtomicLong();
    private final Map<String, Long> pluginGenerations = new HashMap<>();
    private final Object snapshotMonitor = new Object();
    private final Map<String, Integer> activeSnapshotsByPlugin = new HashMap<>();
    private final Set<String> pluginCleanupInProgress = new HashSet<>();
    private final Cs3ProviderMetadata providerMetadata = new Cs3ProviderMetadata();
    private final CopyOnWriteArrayList<ProviderMetadataListener> metadataListeners =
            new CopyOnWriteArrayList<>();
    private final Handler metadataNotificationHandler = new Handler(Looper.getMainLooper());
    private final Object metadataNotificationMonitor = new Object();
    private final Set<String> pendingMetadataNotificationProfiles = new HashSet<>();
    private boolean metadataNotificationScheduled;
    private int activeSnapshots;
    private volatile boolean profileReconcileInProgress;
    private static final long VERIFIER_METADATA_WAIT_MS = TimeUnit.SECONDS.toMillis(5L);

    public interface ProviderMetadataListener {
        void onProviderMetadataChanged(String profileId);
    }

    public enum MetadataReadiness { READY, TIMED_OUT, MISSING }

    public Cs3ProviderRegistry(OrbitCs3HostServices hostServices,
                               CloudStreamExtensionManager extensionManager,
                               ProviderSelectionStore selectionStore,
                               ProviderOperationCoordinator operationCoordinator,
                               CS3PluginRuntime pluginRuntime) {
        if (hostServices == null || extensionManager == null || selectionStore == null
                || operationCoordinator == null || pluginRuntime == null) {
            throw new IllegalArgumentException("CS3 provider registry dependencies are required");
        }
        this.hostServices = hostServices;
        this.extensionManager = extensionManager;
        this.selectionStore = selectionStore;
        this.operationCoordinator = operationCoordinator;
        this.pluginRuntime = pluginRuntime;
    }

    /** Returns providers only for the profile fully reconciled by CS3PluginRuntime. */
    public List<MediaProvider> getProviders(String profileId) {
        if (profileReconcileInProgress || profileId == null) return Collections.emptyList();
        long profileEpoch = profileGeneration.get();
        String activeProfileId = pluginRuntime.getActiveProfileId();
        if (!profileId.equals(activeProfileId) || !isProfileSnapshotCurrent(profileId, profileEpoch)) {
            return Collections.emptyList();
        }
        Set<String> enabled = extensionManager.getEnabledPluginIds(profileId);
        ArrayList<OrbitCs3HostServices.ProviderRegistration> registrations =
                new ArrayList<>(hostServices.getCommittedProviderRegistrations());
        LinkedHashMap<String, MediaProvider> byId = new LinkedHashMap<>();
        Map<String, Long> pluginEpochByProviderId = new HashMap<>();

        for (OrbitCs3HostServices.ProviderRegistration registration : registrations) {
            if (registration.provider == null || !enabled.contains(registration.pluginId)) continue;
            if (!isProfileSnapshotCurrent(profileId, profileEpoch)
                    || !isCommittedRegistration(registration)) continue;
            long pluginEpoch = generationForPlugin(registration.pluginId);
            if (isPluginCleanupInProgress(registration.pluginId)) continue;
            String providerId = providerId(registration.pluginId, registration.registrationIndex);
            String fallbackName = pluginName(registration.pluginId);
            String pluginArchiveSha256 = pluginArchiveSha256(registration.pluginId);
            Cs3ProviderMetadata.Key metadataKey = metadataKey(profileId, registration,
                    profileEpoch, pluginEpoch);
            Cs3ProviderMetadata.Snapshot metadata = providerMetadata.cached(metadataKey);
            requestMetadata(profileId, registration, fallbackName, metadataKey);
            Cs3MediaProviderAdapter adapter = new Cs3MediaProviderAdapter(
                    registration.pluginId, profileId, registration.registrationIndex,
                    providerId, fallbackName, registration.provider, metadataKey, metadata,
                    pluginArchiveSha256,
                    operationCoordinator,
                    () -> isCurrent(profileId, registration.pluginId,
                            registration.registrationIndex, registration.provider,
                            profileEpoch, pluginEpoch));
            byId.put(providerId, adapter);
            pluginEpochByProviderId.put(providerId, pluginEpoch);
        }
        if (byId.isEmpty() || !isProfileSnapshotCurrent(profileId, profileEpoch)) {
            return Collections.emptyList();
        }
        List<String> orderedIds = selectionStore.orderAvailableProviderIds(profileId,
                new ArrayList<>(byId.keySet()));
        ArrayList<MediaProvider> ordered = new ArrayList<>(orderedIds.size());
        for (String id : orderedIds) {
            MediaProvider provider = byId.get(id);
            if (provider != null && (!(provider instanceof Cs3MediaProviderAdapter)
                    || ((Cs3MediaProviderAdapter) provider).isRegistrationCurrent())) {
                ordered.add(provider);
            }
        }
        synchronized (snapshotMonitor) {
            if (profileReconcileInProgress || profileEpoch != profileGeneration.get()
                    || !profileId.equals(pluginRuntime.getActiveProfileId())) return Collections.emptyList();
            ArrayList<MediaProvider> available = new ArrayList<>(ordered.size());
            synchronized (pluginGenerations) {
                for (MediaProvider provider : ordered) {
                    String pluginId = provider.owningPluginId();
                    Long expectedPluginEpoch = pluginEpochByProviderId.get(provider.descriptor().id);
                    if (pluginCleanupInProgress.contains(pluginId) || expectedPluginEpoch == null
                            || expectedPluginEpoch != generationForPluginLocked(pluginId)) continue;
                    available.add(provider);
                }
            }
            return Collections.unmodifiableList(available);
        }
    }

    /** Invalidates every prior provider snapshot and cancels batches before profile cleanup starts. */
    public int onProfileReconcileStarted() {
        synchronized (snapshotMonitor) {
            profileReconcileInProgress = true;
            profileGeneration.incrementAndGet();
        }
        providerMetadata.invalidateAll();
        return operationCoordinator.pauseProviderCallsAndCancelAll();
    }

    /** Reopens snapshots after reconciliation completes or a request is skipped before runtime mutation. */
    public void onProfileReconcileFinished() {
        operationCoordinator.resumeProviderCalls();
        synchronized (snapshotMonitor) {
            profileReconcileInProgress = false;
            snapshotMonitor.notifyAll();
        }
    }

    public boolean isProfileReconcileInProgress() {
        return profileReconcileInProgress;
    }

    /** Blocks one plugin, invalidates snapshots, cancels work, and drains both call and adapter leases. */
    public boolean pauseAndAwaitPluginCalls(String pluginId, long timeoutMs)
            throws InterruptedException {
        if (pluginId == null || timeoutMs <= 0L) return false;
        blockPluginCalls(pluginId);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        if (!operationCoordinator.awaitPluginProviderCallsQuiesced(
                pluginId, remainingMillis(deadline))) return false;
        return awaitAdapterSnapshotsQuiesced(pluginId, remainingMillis(deadline));
    }

    /** Blocks a plugin and cancels its work without waiting; safe to call from the main looper. */
    public void blockPluginCalls(String pluginId) {
        if (pluginId == null) return;
        synchronized (snapshotMonitor) {
            pluginCleanupInProgress.add(pluginId);
            synchronized (pluginGenerations) {
                pluginGenerations.put(pluginId, generationForPluginLocked(pluginId) + 1L);
            }
        }
        providerMetadata.invalidatePlugin(pluginId);
        operationCoordinator.pausePluginProviderCallsAndCancel(pluginId);
    }

    /** Waits for plugin metadata getters to return while their adapter snapshot leases remain held. */
    public boolean awaitProviderSnapshotsQuiesced(long timeoutMs) throws InterruptedException {
        if (timeoutMs <= 0L) return false;
        return awaitAdapterSnapshotsQuiesced(null, timeoutMs);
    }

    /** Waits for both active MediaProvider calls and plugin getter snapshots to finish. */
    public boolean awaitAllProviderWorkQuiesced(long timeoutMs) throws InterruptedException {
        if (timeoutMs <= 0L) return false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        if (!operationCoordinator.awaitProviderCallsQuiesced(remainingMillis(deadline))) return false;
        return awaitAdapterSnapshotsQuiesced(null, remainingMillis(deadline));
    }

    /** Clears a plugin block only after its runtime cleanup/rollback completed successfully. */
    public void onPluginCleanupFinished(String pluginId) {
        if (pluginId == null) return;
        operationCoordinator.resumePluginProviderCalls(pluginId);
        synchronized (snapshotMonitor) {
            pluginCleanupInProgress.remove(pluginId);
            snapshotMonitor.notifyAll();
        }
    }

    /** Invalidates the changed plugin's wrappers and cancels aggregate batches that include it. */
    public int onPluginProviderChange(String pluginId) {
        if (pluginId == null) return 0;
        synchronized (pluginGenerations) {
            pluginGenerations.put(pluginId, generationForPluginLocked(pluginId) + 1L);
        }
        providerMetadata.invalidatePlugin(pluginId);
        return operationCoordinator.cancelForPlugin(pluginId);
    }

    public void addProviderMetadataListener(ProviderMetadataListener listener) {
        if (listener != null) metadataListeners.addIfAbsent(listener);
    }

    public void removeProviderMetadataListener(ProviderMetadataListener listener) {
        if (listener != null) metadataListeners.remove(listener);
    }

    /** Bounded readiness for background selection. Waits never cancel a running getter. */
    public MetadataReadiness awaitProviderMetadata(String profileId, String pluginId,
                                                   int registrationIndex, long timeoutMs)
            throws InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Provider metadata readiness cannot block the UI thread.");
        }
        if (profileId == null || pluginId == null || registrationIndex < 0 || timeoutMs <= 0L) {
            return MetadataReadiness.MISSING;
        }
        long profileEpoch = profileGeneration.get();
        if (!isProfileSnapshotCurrent(profileId, profileEpoch)
                || !extensionManager.isEnabledForProfile(profileId, pluginId)) {
            return MetadataReadiness.MISSING;
        }
        for (OrbitCs3HostServices.ProviderRegistration registration
                : hostServices.getCommittedProviderRegistrations()) {
            if (!pluginId.equals(registration.pluginId)
                    || registration.registrationIndex != registrationIndex
                    || registration.provider == null
                    || !isCommittedRegistration(registration)) continue;
            long pluginEpoch = generationForPlugin(pluginId);
            if (isPluginCleanupInProgress(pluginId)) return MetadataReadiness.MISSING;
            Cs3ProviderMetadata.Key key = metadataKey(profileId, registration,
                    profileEpoch, pluginEpoch);
            requestMetadata(profileId, registration, pluginName(pluginId), key, false);
            Cs3ProviderMetadata.AwaitResult result = providerMetadata.await(key, timeoutMs);
            if (result.getStatus() == Cs3ProviderMetadata.AwaitStatus.TIMED_OUT) {
                return MetadataReadiness.TIMED_OUT;
            }
            if (result.getStatus() != Cs3ProviderMetadata.AwaitStatus.READY
                    || result.getSnapshot() == null
                    || !isCurrent(profileId, pluginId, registrationIndex, registration.provider,
                            profileEpoch, pluginEpoch)) {
                return MetadataReadiness.MISSING;
            }
            return MetadataReadiness.READY;
        }
        return MetadataReadiness.MISSING;
    }

    /** Recreates only the CS3 verifier owner for a persisted token; any identity mismatch fails closed. */
    public Cs3PlaybackHooks restoreVerifierOnlyHooks(DownloadProviderHookToken token) {
        if (token == null || token.version != DownloadProviderHookToken.CURRENT_VERSION
                || isBlank(token.profileId) || isBlank(token.pluginId)
                || token.registrationIndex < 0
                || !providerId(token.pluginId, token.registrationIndex).equals(token.providerId)
                || isBlank(token.apiClassName) || isBlank(token.apiName)
                || token.extractorData == null
                || token.extractorData.length() > DownloadProviderHookToken.MAX_FIELD_LENGTH
                || isBlank(token.sourceId)
                || !isSha256(token.pluginArchiveSha256) || !isSha256(token.sourceUrlSha256)
                || profileReconcileInProgress
                || !token.profileId.equals(pluginRuntime.getActiveProfileId())
                || !extensionManager.isEnabledForProfile(token.profileId, token.pluginId)) {
            return null;
        }

        CloudStreamExtensionManager.InstalledPlugin installed =
                extensionManager.getInstalledPlugin(token.pluginId);
        if (installed == null || !isSha256(installed.sha256)
                || !installed.sha256.equalsIgnoreCase(token.pluginArchiveSha256)) return null;

        long profileEpoch = profileGeneration.get();
        long pluginEpoch = generationForPlugin(token.pluginId);
        if (!isProfileSnapshotCurrent(token.profileId, profileEpoch)
                || isPluginCleanupInProgress(token.pluginId)) return null;
        for (OrbitCs3HostServices.ProviderRegistration registration
                : hostServices.getCommittedProviderRegistrations()) {
            if (!token.pluginId.equals(registration.pluginId)
                    || token.registrationIndex != registration.registrationIndex
                    || registration.provider == null
                    || !token.apiClassName.equals(registration.provider.getClass().getName())
                    || !isCommittedRegistration(registration)) continue;

            Cs3ProviderMetadata.Key key = metadataKey(token.profileId, registration,
                    profileEpoch, pluginEpoch);
            Cs3ProviderMetadata.Snapshot metadata = providerMetadata.cached(key);
            String fallbackName = pluginName(token.pluginId);
            if (metadata == null && !token.apiName.equals(fallbackName)) {
                MetadataReadiness readiness;
                try {
                    readiness = awaitProviderMetadata(token.profileId, token.pluginId,
                            token.registrationIndex, VERIFIER_METADATA_WAIT_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                if (readiness != MetadataReadiness.READY) return null;
                metadata = providerMetadata.cached(key);
                if (metadata == null) return null;
            }
            // The token captures the name used by the adapter when it was created. Metadata
            // may since have warmed, so either the stable plugin fallback or current API name
            // is valid; the restored adapter itself uses the current snapshot when available.
            if (!token.apiName.equals(fallbackName)
                    && (metadata == null || !token.apiName.equals(metadata.getName()))) return null;
            if (!isCurrent(token.profileId, token.pluginId, token.registrationIndex,
                    registration.provider, profileEpoch, pluginEpoch)) return null;

            Cs3MediaProviderAdapter adapter = new Cs3MediaProviderAdapter(
                    token.pluginId, token.profileId, token.registrationIndex, token.providerId,
                    fallbackName, registration.provider, key, metadata,
                    installed.sha256, operationCoordinator,
                    () -> isCurrent(token.profileId, token.pluginId, token.registrationIndex,
                            registration.provider, profileEpoch, pluginEpoch));
            return adapter.createVerifierOnlyHooks(token.extractorData);
        }
        return null;
    }

    boolean isCurrent(String profileId, String pluginId, int registrationIndex, MainAPI provider,
                      long expectedProfileGeneration, long expectedPluginGeneration) {
        if (profileReconcileInProgress || isPluginCleanupInProgress(pluginId) || profileId == null
                || !profileId.equals(pluginRuntime.getActiveProfileId())) return false;
        if (expectedProfileGeneration != profileGeneration.get()) return false;
        if (expectedPluginGeneration != generationForPlugin(pluginId)) return false;
        if (!extensionManager.isEnabledForProfile(profileId, pluginId)) return false;
        for (OrbitCs3HostServices.ProviderRegistration registration
                : hostServices.getCommittedProviderRegistrations()) {
            if (registration.pluginId.equals(pluginId)
                    && registration.registrationIndex == registrationIndex
                    && registration.provider == provider) {
                return isCurrentSnapshot(profileId, pluginId,
                        expectedProfileGeneration, expectedPluginGeneration);
            }
        }
        return false;
    }

    private boolean isProfileSnapshotCurrent(String profileId, long expectedProfileGeneration) {
        return !profileReconcileInProgress
                && expectedProfileGeneration == profileGeneration.get()
                && profileId.equals(pluginRuntime.getActiveProfileId());
    }

    private boolean isCurrentSnapshot(String profileId, String pluginId,
                                      long expectedProfileGeneration, long expectedPluginGeneration) {
        return !isPluginCleanupInProgress(pluginId)
                && isProfileSnapshotCurrent(profileId, expectedProfileGeneration)
                && expectedPluginGeneration == generationForPlugin(pluginId)
                && extensionManager.isEnabledForProfile(profileId, pluginId);
    }

    private long generationForPlugin(String pluginId) {
        synchronized (pluginGenerations) { return generationForPluginLocked(pluginId); }
    }

    private boolean beginAdapterSnapshot(String profileId, String pluginId,
                                         long expectedProfileGeneration,
                                         long expectedPluginGeneration) {
        synchronized (snapshotMonitor) {
            if (profileReconcileInProgress || pluginCleanupInProgress.contains(pluginId)
                    || expectedProfileGeneration != profileGeneration.get()
                    || !profileId.equals(pluginRuntime.getActiveProfileId())) return false;
            synchronized (pluginGenerations) {
                if (expectedPluginGeneration != generationForPluginLocked(pluginId)) return false;
            }
            activeSnapshots++;
            activeSnapshotsByPlugin.put(pluginId, activeSnapshotsByPlugin.getOrDefault(pluginId, 0) + 1);
            return true;
        }
    }

    private Cs3ProviderMetadata.Key metadataKey(String profileId,
            OrbitCs3HostServices.ProviderRegistration registration,
            long expectedProfileGeneration, long expectedPluginGeneration) {
        return new Cs3ProviderMetadata.Key(profileId, registration.pluginId,
                expectedProfileGeneration, expectedPluginGeneration,
                registration.registrationIndex, registration.provider);
    }

    private void requestMetadata(String profileId,
            OrbitCs3HostServices.ProviderRegistration registration, String fallbackName,
            Cs3ProviderMetadata.Key key) {
        requestMetadata(profileId, registration, fallbackName, key, true);
    }

    private void requestMetadata(String profileId,
            OrbitCs3HostServices.ProviderRegistration registration, String fallbackName,
            Cs3ProviderMetadata.Key key, boolean notifyOnPublish) {
        providerMetadata.request(key, fallbackName,
                () -> beginAdapterSnapshot(profileId, registration.pluginId,
                        key.getProfileGeneration(), key.getPluginGeneration()),
                () -> endAdapterSnapshot(registration.pluginId),
                () -> isCurrent(profileId, registration.pluginId, registration.registrationIndex,
                        registration.provider, key.getProfileGeneration(), key.getPluginGeneration()),
                () -> {
                    if (notifyOnPublish && isCurrent(profileId, registration.pluginId,
                            registration.registrationIndex, registration.provider,
                            key.getProfileGeneration(), key.getPluginGeneration())) {
                        notifyProviderMetadataChanged(profileId);
                    }
                });
    }

    private void notifyProviderMetadataChanged(String profileId) {
        synchronized (metadataNotificationMonitor) {
            pendingMetadataNotificationProfiles.add(profileId);
            if (metadataNotificationScheduled) return;
            metadataNotificationScheduled = true;
            metadataNotificationHandler.postDelayed(() -> {
                ArrayList<String> changedProfiles;
                synchronized (metadataNotificationMonitor) {
                    changedProfiles = new ArrayList<>(pendingMetadataNotificationProfiles);
                    pendingMetadataNotificationProfiles.clear();
                    metadataNotificationScheduled = false;
                }
                for (String changedProfile : changedProfiles) {
                    for (ProviderMetadataListener listener : metadataListeners) {
                        try {
                            listener.onProviderMetadataChanged(changedProfile);
                        } catch (RuntimeException ignored) {
                            // One lifecycle-bound listener must not prevent delivery to the others.
                        }
                    }
                }
            }, METADATA_NOTIFICATION_COALESCE_MS);
        }
    }

    private void endAdapterSnapshot(String pluginId) {
        synchronized (snapshotMonitor) {
            activeSnapshots--;
            int remaining = activeSnapshotsByPlugin.getOrDefault(pluginId, 0) - 1;
            if (remaining <= 0) activeSnapshotsByPlugin.remove(pluginId);
            else activeSnapshotsByPlugin.put(pluginId, remaining);
            snapshotMonitor.notifyAll();
        }
    }

    private boolean awaitAdapterSnapshotsQuiesced(String pluginId, long timeoutMs)
            throws InterruptedException {
        if (timeoutMs <= 0L) return false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (snapshotMonitor) {
            while (pluginId == null ? activeSnapshots > 0
                    : activeSnapshotsByPlugin.getOrDefault(pluginId, 0) > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return false;
                TimeUnit.NANOSECONDS.timedWait(snapshotMonitor, remaining);
            }
            return true;
        }
    }

    private boolean isPluginCleanupInProgress(String pluginId) {
        synchronized (snapshotMonitor) { return pluginCleanupInProgress.contains(pluginId); }
    }

    private boolean isCommittedRegistration(OrbitCs3HostServices.ProviderRegistration wanted) {
        for (OrbitCs3HostServices.ProviderRegistration current
                : hostServices.getCommittedProviderRegistrations()) {
            if (current.pluginId.equals(wanted.pluginId)
                    && current.registrationIndex == wanted.registrationIndex
                    && current.provider == wanted.provider) return true;
        }
        return false;
    }

    private static long remainingMillis(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining <= 0L ? 0L : Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private long generationForPluginLocked(String pluginId) {
        Long value = pluginGenerations.get(pluginId);
        return value == null ? 0L : value;
    }

    private String pluginName(String pluginId) {
        CloudStreamExtensionManager.InstalledPlugin installed = extensionManager.getInstalledPlugin(pluginId);
        return installed == null || installed.name == null || installed.name.trim().isEmpty()
                ? pluginId : installed.name.trim();
    }

    private String pluginArchiveSha256(String pluginId) {
        CloudStreamExtensionManager.InstalledPlugin installed = extensionManager.getInstalledPlugin(pluginId);
        return installed == null || installed.sha256 == null ? "" : installed.sha256;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static String providerId(String pluginId, int index) {
        return "cs3:" + pluginId + ":provider-" + index;
    }

    private static final long METADATA_NOTIFICATION_COALESCE_MS = 80L;
}
