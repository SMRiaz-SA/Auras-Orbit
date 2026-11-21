package com.auras.orbit.extensions;

import android.os.Handler;
import android.os.Looper;

import com.lagradost.cloudstream3.APIHolder;
import com.auras.orbit.profile.LocalProfileStore;
import com.auras.orbit.profile.LocalProfileStore.Profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.io.IOException;

/** Keeps extension-operation ownership and visible state at application scope. */
public final class ExtensionOperationController {
    public enum Status { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELED }
    public enum Phase { NONE, PREPARING, DOWNLOADING, COMMITTING }

    public interface OperationPhaseListener {
        void onDownloadStarted();
        void onCommitStarted();
    }

    /** Package install/update work that receives the owning operation ID and its phase callback. */
    public interface Operation extends Callable<String> {
        String call(String operationId, OperationPhaseListener phaseListener) throws Exception;

        @Override default String call() throws Exception {
            return call(null, new OperationPhaseListener() {
                @Override public void onDownloadStarted() { }
                @Override public void onCommitStarted() { }
            });
        }
    }

    public static final class Snapshot {
        public final String id;
        public final String target;
        public final String label;
        public final String message;
        public final Status status;
        public final Phase phase;
        public final boolean cancellationRequested;

        private Snapshot(String id, String target, String label, String message, Status status,
                Phase phase, boolean cancellationRequested) {
            this.id = id;
            this.target = target;
            this.label = label;
            this.message = message;
            this.status = status;
            this.phase = phase;
            this.cancellationRequested = cancellationRequested;
        }

        public boolean isRunning() { return status == Status.RUNNING; }
        public boolean canCancel() {
            return isRunning() && phase == Phase.DOWNLOADING && !cancellationRequested;
        }
    }

    public interface Listener {
        void onExtensionOperationChanged(Snapshot snapshot);
    }

    @FunctionalInterface
    public interface ProfileReconcileRequester {
        void request(String profileId);
    }

    private final Object lock = new Object();
    private final ExecutorService executor;
    private final CloudStreamExtensionManager extensionManager;
    private final CS3PluginRuntime pluginRuntime;
    private final LocalProfileStore profileStore;
    private final ProfileReconcileRequester profileReconcileRequester;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private Snapshot current = new Snapshot(null, null, null, null, Status.IDLE, Phase.NONE, false);

    public ExtensionOperationController(ExecutorService executor,
                                        CloudStreamExtensionManager extensionManager,
                                        CS3PluginRuntime pluginRuntime,
                                        LocalProfileStore profileStore) {
        this(executor, extensionManager, pluginRuntime, profileStore, null);
    }

    public ExtensionOperationController(ExecutorService executor,
                                        CloudStreamExtensionManager extensionManager,
                                        CS3PluginRuntime pluginRuntime,
                                        LocalProfileStore profileStore,
                                        ProfileReconcileRequester profileReconcileRequester) {
        if (executor == null || extensionManager == null || pluginRuntime == null || profileStore == null) {
            throw new IllegalArgumentException("Extension operation dependencies are required.");
        }
        this.executor = executor;
        this.extensionManager = extensionManager;
        this.pluginRuntime = pluginRuntime;
        this.profileStore = profileStore;
        this.profileReconcileRequester = profileReconcileRequester;
    }

    public Snapshot getSnapshot() {
        synchronized (lock) { return current; }
    }

    /** Cancels only the active package transfer; once manager cleanup/commit begins this returns false. */
    public boolean cancel(String operationId) {
        if (operationId == null) return false;
        synchronized (lock) {
            if (!operationId.equals(current.id) || !current.canCancel()) return false;
        }
        if (!extensionManager.cancelPackageDownload(operationId)) {
            setPhase(operationId, Phase.COMMITTING);
            return false;
        }
        synchronized (lock) {
            if (!operationId.equals(current.id) || !current.isRunning()) return true;
            current = new Snapshot(current.id, current.target, current.label,
                    current.label + " Canceling the transfer and removing its staging file…",
                    Status.RUNNING, current.phase, true);
        }
        publish();
        return true;
    }

    public void addListener(Listener listener) {
        if (listener == null) return;
        synchronized (lock) {
            if (!listeners.contains(listener)) listeners.add(listener);
        }
        postToListener(listener);
    }

    public void removeListener(Listener listener) {
        if (listener == null) return;
        synchronized (lock) { listeners.remove(listener); }
    }

    /** Returns app-scoped work; no Activity or View is retained by the executor. */
    public Callable<String> addRepositoryOperation(String input) {
        return () -> {
            CloudStreamExtensionManager.AddRepositoryResult result = extensionManager.addRepository(input);
            if (result.alreadyPresent) return result.repository.name + " is already present. No duplicate was added.";
            return "Added " + result.repository.name + " · " + result.pluginCount + " plugin entries retrieved.";
        };
    }

    public Callable<String> refreshRepositoryOperation(String repositoryUrl) {
        return () -> {
            CloudStreamExtensionManager.RepositoryInfo refreshed = extensionManager.refreshRepository(repositoryUrl);
            return "Refreshed " + refreshed.name + " · "
                    + extensionManager.getCatalog(repositoryUrl).size() + " plugin entries.";
        };
    }

    public Callable<String> removeRepositoryOperation(String repositoryUrl, String repositoryName) {
        return () -> {
            if (!extensionManager.removeRepository(repositoryUrl)) {
                throw new IOException("Orbit could not remove this repository and its cached catalog.");
            }
            return repositoryName + " was removed. Installed plugins were kept.";
        };
    }

    public Operation installPluginOperation(CloudStreamExtensionManager.PluginInfo plugin) {
        return (operationId, phaseListener) -> {
            CloudStreamExtensionManager.InstalledPlugin installed = extensionManager.installPlugin(
                    plugin, operationId, packageInstallListener(phaseListener));
            return installed.name + " installed as a private Orbit file. It remains disabled until explicitly enabled for a profile.";
        };
    }

    public Operation updatePluginOperation(CloudStreamExtensionManager.PluginInfo requestedUpdate) {
        return (operationId, phaseListener) -> performPluginUpdate(requestedUpdate, operationId, phaseListener);
    }

    private CloudStreamExtensionManager.PackageInstallListener packageInstallListener(
            OperationPhaseListener phaseListener) {
        return new CloudStreamExtensionManager.PackageInstallListener() {
            @Override public void onDownloadStarted() { phaseListener.onDownloadStarted(); }
            @Override public void onCommitStarted() { phaseListener.onCommitStarted(); }
        };
    }

    public Callable<String> activatePluginOperation(CloudStreamExtensionManager.InstalledPlugin plugin,
                                                     String profileId, String profileName) {
        return () -> {
            if (!extensionManager.isStorageRecoveryReady()) {
                throw new IOException("Plugin activation is blocked until CS3 package storage recovery completes.");
            }
            CS3PluginRuntime.ActivationResult result = pluginRuntime.activate(plugin);
            if (!result.success) {
                extensionManager.recordActivationError(plugin.id, result.message);
                requestProfileReconcile(profileId);
                throw new IOException(result.message);
            }
            if (!initializeLoadedProviders()) {
                boolean unloaded = pluginRuntime.deactivateIfClean(plugin.id);
                String message = unloaded
                        ? "Provider initialization failed; the plugin was unloaded."
                        : cleanupBlockedMessage(plugin.id)
                                + " Provider initialization failed and cleanup is still pending.";
                extensionManager.recordActivationError(plugin.id, message);
                throw new IOException(message);
            }
            if (!extensionManager.isStorageRecoveryReady()) {
                pluginRuntime.deactivateIfClean(plugin.id);
                throw new IOException("CS3 package storage recovery became unavailable; the profile assignment was not changed.");
            }
            boolean grantSaved = extensionManager.setEnabledForProfile(profileId, plugin.id, true);
            if (!grantSaved || !extensionManager.isEnabledForProfile(profileId, plugin.id)) {
                pluginRuntime.deactivateIfClean(plugin.id);
                throw new IOException("Orbit could not save the plugin's profile assignment.");
            }
            extensionManager.clearActivationError(plugin.id);
            return plugin.name + " is active for " + profileName + ".";
        };
    }

    public Callable<String> disablePluginOperation(CloudStreamExtensionManager.InstalledPlugin plugin,
                                                    String profileId, String profileName) {
        return () -> {
            if (!extensionManager.isStorageRecoveryReady()) {
                throw new IOException("Plugin changes are blocked until CS3 package storage recovery completes.");
            }
            boolean grantRevoked = extensionManager.setEnabledForProfile(profileId, plugin.id, false);
            if (!grantRevoked || extensionManager.isEnabledForProfile(profileId, plugin.id)) {
                throw new IOException("Orbit could not save the plugin's profile assignment.");
            }
            if (!pluginRuntime.deactivateIfClean(plugin.id)) {
                boolean assignmentRestored = extensionManager.setEnabledForProfile(profileId, plugin.id, true);
                if (!assignmentRestored || !extensionManager.isEnabledForProfile(profileId, plugin.id)) {
                    throw new IOException(cleanupBlockedMessage(plugin.id)
                            + " The plugin runtime remains blocked because Orbit could not restore its profile assignment.");
                }
                throw new IOException(cleanupBlockedMessage(plugin.id)
                        + " The plugin remains enabled and installed.");
            }
            return plugin.name + " is disabled for " + profileName + ". The package remains installed.";
        };
    }

    public Callable<String> uninstallPluginOperation(CloudStreamExtensionManager.InstalledPlugin plugin) {
        return () -> {
            if (!extensionManager.isStorageRecoveryReady()) {
                throw new IOException("Plugin changes are blocked until CS3 package storage recovery completes.");
            }
            if (!pluginRuntime.deactivateIfClean(plugin.id)) {
                throw new IOException(cleanupBlockedMessage(plugin.id) + " The package remains installed.");
            }
            if (!extensionManager.removePlugin(plugin.id)) {
                throw new IOException("Orbit could not remove the app-private CS3 file.");
            }
            return plugin.name + " was uninstalled.";
        };
    }

    public static CloudStreamExtensionManager.PluginInfo findCatalogUpdate(
            CloudStreamExtensionManager extensionManager,
            CloudStreamExtensionManager.PluginInfo displayed,
            CloudStreamExtensionManager.InstalledPlugin installed) {
        if (displayed == null || installed == null || !displayed.id.equals(installed.id)) return null;
        CloudStreamExtensionManager.RepositoryInfo repository = extensionManager.findRepository(displayed.repositoryUrl);
        if (repository == null) return null;
        for (CloudStreamExtensionManager.PluginInfo candidate : extensionManager.getCatalog(repository.url)) {
            if (candidate.id.equals(installed.id) && candidate.status != 0 && candidate.version > installed.version) {
                return candidate;
            }
        }
        return null;
    }

    private String performPluginUpdate(CloudStreamExtensionManager.PluginInfo requestedUpdate,
            String operationId, OperationPhaseListener phaseListener) {
        if (!extensionManager.isStorageRecoveryReady()) {
            return "Update failed: CS3 package storage recovery is not ready. The installed package and profile assignments were kept.";
        }
        CloudStreamExtensionManager.InstalledPlugin previous =
                extensionManager.getInstalledPlugin(requestedUpdate == null ? null : requestedUpdate.id);
        CloudStreamExtensionManager.PluginInfo update = findCatalogUpdate(extensionManager, requestedUpdate, previous);
        if (update == null) {
            return "Update cancelled: this repository no longer has a higher available version of the installed plugin.";
        }

        Profile activeBeforeUpdate = profileStore.getActiveProfile();
        String activeProfileId = activeBeforeUpdate == null ? null : activeBeforeUpdate.id;
        boolean enabledForActiveProfile = activeProfileId != null
                && extensionManager.isEnabledForProfile(activeProfileId, previous.id);
        boolean wasLoaded = pluginRuntime.isLoaded(previous.id);
        if (!pluginRuntime.deactivateIfClean(previous.id)) {
            return "Update paused. " + cleanupBlockedMessage(previous.id)
                    + " The installed version and profile assignment were kept.";
        }

        CloudStreamExtensionManager.InstalledPlugin updated;
        try {
            updated = extensionManager.installPlugin(update, operationId,
                    packageInstallListener(phaseListener));
        } catch (Throwable updateError) {
            String failure = updateError.getMessage();
            if (failure == null || failure.trim().isEmpty()) failure = updateError.getClass().getSimpleName();
            boolean cancelled = updateError instanceof CancellationException;
            String result = (cancelled ? "Update cancelled: " : "Update failed: ") + failure;
            if (wasLoaded && enabledForActiveProfile && !failure.contains("Restart Orbit to recover")) {
                Profile activeNow = profileStore.getActiveProfile();
                CloudStreamExtensionManager.InstalledPlugin current = extensionManager.getInstalledPlugin(previous.id);
                if (activeNow != null && activeProfileId.equals(activeNow.id)
                        && extensionManager.isEnabledForProfile(activeProfileId, previous.id) && current != null) {
                    CS3PluginRuntime.ActivationResult restore = pluginRuntime.activate(current);
                    if (restore.success) {
                        if (initializeLoadedProviders()) {
                            extensionManager.clearActivationError(previous.id);
                            result += " The previous package was reloaded.";
                        } else {
                            boolean unloaded = pluginRuntime.deactivateIfClean(previous.id);
                            requestProfileReconcile(activeProfileId);
                            String initFailure = unloaded
                                    ? "Provider initialization failed; the previous package was unloaded."
                                    : cleanupBlockedMessage(previous.id)
                                            + " Provider initialization failed and cleanup is still pending.";
                            extensionManager.recordActivationError(previous.id, initFailure);
                            result += " The previous package could not be initialized: " + initFailure;
                        }
                    } else {
                        extensionManager.recordActivationError(previous.id, restore.message);
                        requestProfileReconcile(activeProfileId);
                        result += cancelled
                                ? " The previous package could not be reloaded: " + restore.message
                                : " The update failed, and reloading the previous package also failed: " + restore.message;
                    }
                } else {
                    result += " The previous package was not reloaded because the active profile changed.";
                }
            } else if (wasLoaded && enabledForActiveProfile) {
                result += " The package transaction needs recovery; the plugin remains unloaded until Orbit recovers it.";
            }
            return result;
        }

        if (!enabledForActiveProfile) {
            return "Updated " + updated.name + " from v" + previous.version + " to v" + updated.version
                    + ". The existing profile enablement and settings were preserved; the plugin remains disabled for the active profile.";
        }
        Profile activeNow = profileStore.getActiveProfile();
        if (activeNow == null || !activeProfileId.equals(activeNow.id)
                || !extensionManager.isEnabledForProfile(activeProfileId, updated.id)) {
            return "Updated " + updated.name + " from v" + previous.version + " to v" + updated.version
                    + ". Package update succeeded; it was not reloaded because it is no longer enabled for the active profile.";
        }

        CS3PluginRuntime.ActivationResult reload = pluginRuntime.activate(updated);
        if (!reload.success) {
            extensionManager.recordActivationError(updated.id, reload.message);
            requestProfileReconcile(activeProfileId);
            return "Updated " + updated.name + " from v" + previous.version + " to v" + updated.version
                    + ". Package update succeeded; plugin reload failed: " + reload.message;
        }
        if (!initializeLoadedProviders()) {
            boolean unloaded = pluginRuntime.deactivateIfClean(updated.id);
            requestProfileReconcile(activeProfileId);
            String message = unloaded
                    ? "Provider initialization failed; the updated plugin was unloaded."
                    : cleanupBlockedMessage(updated.id)
                            + " Provider initialization failed and cleanup is still pending.";
            extensionManager.recordActivationError(updated.id, message);
            return "Updated " + updated.name + " from v" + previous.version + " to v" + updated.version
                    + ". Package update succeeded; provider initialization failed: " + message;
        }
        extensionManager.clearActivationError(updated.id);
        return "Updated " + updated.name + " from v" + previous.version + " to v" + updated.version
                + " and reloaded it for the active profile.";
    }

    private String cleanupBlockedMessage(String pluginId) {
        String diagnostic = pluginRuntime.getCleanupFailureMessage(pluginId);
        if (diagnostic != null && diagnostic.startsWith("Plugin cleanup is unsafe to retry")) {
            return "Cleanup failed. Restart Orbit before changing this plugin.";
        }
        return "Plugin cleanup is pending. Retry after it completes.";
    }

    /** Mirrors CloudStream's post-load initAll step for direct enable, update, and restore paths. */
    private static boolean initializeLoadedProviders() {
        try {
            APIHolder.INSTANCE.initAll();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void requestProfileReconcile(String profileId) {
        if (!pluginRuntime.markPluginProfileReconcilePending(profileId)) return;
        if (profileReconcileRequester != null) profileReconcileRequester.request(profileId);
    }

    /** Returns false while another operation owns the extension worker. */
    public boolean submit(String target, String label, Callable<String> operation) {
        if (operation == null) throw new IllegalArgumentException("An extension operation is required.");
        final String operationId = UUID.randomUUID().toString();
        final Snapshot running;
        synchronized (lock) {
            if (current.isRunning()) return false;
            running = new Snapshot(operationId, nonEmpty(target, "extension"),
                    nonEmpty(label, "Working…"), nonEmpty(label, "Working…"), Status.RUNNING,
                    Phase.PREPARING, false);
            current = running;
        }
        publish();
        try {
            executor.execute(() -> run(operationId, operation));
        } catch (RuntimeException rejected) {
            finish(operationId, Status.FAILED, "Could not schedule operation: " + safeMessage(rejected));
        }
        return true;
    }

    private void run(String operationId, Callable<String> operation) {
        try {
            String message;
            if (operation instanceof Operation) {
                message = ((Operation) operation).call(operationId, new OperationPhaseListener() {
                    @Override public void onDownloadStarted() {
                        setPhase(operationId, Phase.DOWNLOADING);
                    }
                    @Override public void onCommitStarted() {
                        setPhase(operationId, Phase.COMMITTING);
                    }
                });
            } else {
                message = operation.call();
            }
            String result = message == null || message.trim().isEmpty() ? "Operation completed." : message;
            finish(operationId, classify(result), result);
        } catch (CancellationException cancelled) {
            finish(operationId, Status.CANCELED,
                    "Plugin download canceled before package commit. The previous package was kept and staging was removed.");
        } catch (Throwable error) {
            finish(operationId, Status.FAILED, "Could not complete operation: " + safeMessage(error));
        }
    }

    private void setPhase(String operationId, Phase phase) {
        synchronized (lock) {
            if (!operationId.equals(current.id) || !current.isRunning()) return;
            String message = phase == Phase.DOWNLOADING
                    ? current.label + " Transfer active. You can cancel until package finalization starts."
                    : current.label + " Finalizing package changes. Cancellation is no longer available.";
            current = new Snapshot(current.id, current.target, current.label, message,
                    current.status, phase, false);
        }
        publish();
    }

    private void finish(String operationId, Status status, String message) {
        Snapshot finished;
        synchronized (lock) {
            if (!operationId.equals(current.id) || !current.isRunning()) return;
            finished = new Snapshot(current.id, current.target, current.label,
                    nonEmpty(message, "Operation completed."), status, Phase.NONE, false);
            current = finished;
        }
        publish();
    }

    private void publish() {
        mainHandler.post(() -> {
            List<Listener> copy;
            synchronized (lock) { copy = Collections.unmodifiableList(new ArrayList<>(listeners)); }
            for (Listener listener : copy) {
                Snapshot latest;
                synchronized (lock) {
                    if (!listeners.contains(listener)) continue;
                    latest = current;
                }
                try { listener.onExtensionOperationChanged(latest); }
                catch (RuntimeException ignored) { }
            }
        });
    }

    private void postToListener(Listener listener) {
        mainHandler.post(() -> {
            Snapshot latest;
            synchronized (lock) {
                if (!listeners.contains(listener)) return;
                latest = current;
            }
            try { listener.onExtensionOperationChanged(latest); }
            catch (RuntimeException ignored) { }
        });
    }

    private static Status classify(String message) {
        String lower = message == null ? "" : message.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("update cancelled:")) return Status.CANCELED;
        if (lower.startsWith("update paused.") || lower.startsWith("update failed:")
                || lower.contains(" reload failed:")) return Status.FAILED;
        return Status.SUCCEEDED;
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error == null ? "Unknown error" : error.getClass().getSimpleName()
                : message.trim();
    }
}
