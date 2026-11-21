package com.auras.orbit.extensions;

import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

import com.lagradost.cloudstream3.APIHolder;
import com.lagradost.cloudstream3.MainAPI;
import com.lagradost.cloudstream3.actions.VideoClickAction;
import com.lagradost.cloudstream3.actions.VideoClickActionHolder;
import com.lagradost.cloudstream3.actions.OrbitVideoActionRuntime;
import com.lagradost.cloudstream3.plugins.BasePlugin;
import com.lagradost.cloudstream3.plugins.Plugin;
import com.lagradost.cloudstream3.utils.AtomicMutableList;
import com.lagradost.cloudstream3.utils.ExtractorApi;
import com.lagradost.cloudstream3.utils.ExtractorApiKt;
import com.auras.orbit.network.OrbitWebViewResolverRuntime;
import com.auras.orbit.BuildConfig;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlinx.coroutines.Job;

/**
 * Orbit-owned CS3 service boundary for provider and extractor registration.
 * Registrations are staged during one plugin load and become visible only after
 * the load returns successfully. Ownership is kept by installed-plugin ID and
 * private package path so failed loads and profile switches can roll back only
 * that plugin's objects.
 */
public final class OrbitCs3HostServices {
    /** Registration-only ceiling for the separately packaged canary build. */
    public static final int MAX_CANARY_STAGED_EXTRACTORS = 160;
    private static final long VIDEO_ACTION_QUIESCE_TIMEOUT_MS = 2_000L;
    private static volatile OrbitCs3HostServices processInstance;
    private static final ThreadLocal<LoadTransaction> CURRENT_LOAD = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> INTERNAL_ACTION_MUTATION = new ThreadLocal<>();

    private final Context appContext;
    private final CloudStreamExtensionManager extensionManager;
    // Every action-list mutation and lifecycle operation uses the same monitor.
    private final Object lock = VideoClickActionHolder.INSTANCE.getAllVideoClickActions();
    private final Map<String, LoadTransaction> committedLoads = new HashMap<>();
    private final Map<String, LoadTransaction> cleanupPendingLoads = new HashMap<>();
    private final IdentityHashMap<MainAPI, LoadTransaction> providerOwners = new IdentityHashMap<>();
    private final IdentityHashMap<ExtractorApi, LoadTransaction> extractorOwners = new IdentityHashMap<>();
    private final IdentityHashMap<VideoClickAction, LoadTransaction> actionOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Job, String> videoActionJobs = new IdentityHashMap<>();
    private final IdentityHashMap<LoadTransaction, Integer> activeVideoActionResultCallbacks =
            new IdentityHashMap<>();
    private final java.util.Set<Job> cancellationRequestedActionJobs =
            Collections.newSetFromMap(new IdentityHashMap<Job, Boolean>());
    private final java.util.Set<String> quiescingActionPluginPaths = new java.util.HashSet<>();
    private final Map<String, PluginSettingsSession> pluginSettingsSessions = new HashMap<>();
    private final Map<String, String> pluginSettingsTokenByPluginId = new HashMap<>();
    private volatile ProviderLifecycleListener providerLifecycleListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<RepositoryConsentRequest> repositoryConsentQueue = new ArrayDeque<>();
    private volatile WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private volatile boolean currentActivityResumed;
    private RepositoryConsentRequest activeRepositoryConsent;

    private OrbitCs3HostServices(Context context, CloudStreamExtensionManager manager) {
        appContext = context.getApplicationContext();
        extensionManager = manager;
    }

    /** Preserves the upstream AtomicMutableList ABI while intercepting every live view mutation. */
    public static AtomicMutableList<VideoClickAction> newVideoActionRegistry() {
        OwnedActionList delegate = new OwnedActionList();
        AtomicMutableList<VideoClickAction> registry = new AtomicMutableList<>(delegate);
        delegate.monitor = registry;
        return registry;
    }

    private static final class OwnedActionList extends java.util.AbstractList<VideoClickAction> {
        private final ArrayList<VideoClickAction> values = new ArrayList<>();
        private Object monitor = this;
        @Override public VideoClickAction get(int index) { synchronized (monitor) { return values.get(index); } }
        @Override public int size() { synchronized (monitor) { return values.size(); } }
        @Override public void add(int index, VideoClickAction value) {
            synchronized (monitor) {
                if (index < 0 || index > values.size()) throw new IndexOutOfBoundsException();
                recordActionAddition(value);
                values.add(index, value);
                modCount++;
            }
        }
        @Override public VideoClickAction set(int index, VideoClickAction value) {
            synchronized (monitor) {
                VideoClickAction old = values.get(index);
                if (old == value) return old;
                ensureActionRemovalAllowed(old);
                recordActionAddition(value);
                values.set(index, value);
                // List sorting and permutation algorithms temporarily remove an
                // identity while they move it to another slot. Keep its owner
                // when the action remains in the completed list state.
                if (!containsIdentity(values, old)) recordActionRemoval(old, index);
                return old;
            }
        }
        @Override public VideoClickAction remove(int index) {
            synchronized (monitor) {
                VideoClickAction old = values.get(index);
                ensureActionRemovalAllowed(old);
                values.remove(index);
                modCount++;
                recordActionRemoval(old, index);
                return old;
            }
        }

        @Override public boolean remove(Object target) {
            synchronized (monitor) {
                int index = values.indexOf(target);
                if (index < 0) return false;
                remove(index);
                return true;
            }
        }

        @Override public boolean removeAll(java.util.Collection<?> targets) {
            synchronized (monitor) {
                ArrayList<Integer> indexes = new ArrayList<>();
                for (int index = 0; index < values.size(); index++) {
                    if (targets.contains(values.get(index))) indexes.add(index);
                }
                for (Integer index : indexes) ensureActionRemovalAllowed(values.get(index));
                for (int position = indexes.size() - 1; position >= 0; position--) remove(indexes.get(position));
                return !indexes.isEmpty();
            }
        }

        @Override public boolean retainAll(java.util.Collection<?> retained) {
            synchronized (monitor) {
                ArrayList<Integer> indexes = new ArrayList<>();
                for (int index = 0; index < values.size(); index++) {
                    if (!retained.contains(values.get(index))) indexes.add(index);
                }
                for (Integer index : indexes) ensureActionRemovalAllowed(values.get(index));
                for (int position = indexes.size() - 1; position >= 0; position--) remove(indexes.get(position));
                return !indexes.isEmpty();
            }
        }

        @Override public void clear() {
            synchronized (monitor) {
                for (VideoClickAction action : values) ensureActionRemovalAllowed(action);
                for (int index = values.size() - 1; index >= 0; index--) remove(index);
            }
        }

        @Override public boolean removeIf(Predicate<? super VideoClickAction> filter) {
            synchronized (monitor) {
                ArrayList<Integer> indexes = new ArrayList<>();
                for (int index = 0; index < values.size(); index++) {
                    if (filter.test(values.get(index))) indexes.add(index);
                }
                for (Integer index : indexes) ensureActionRemovalAllowed(values.get(index));
                for (int position = indexes.size() - 1; position >= 0; position--) remove(indexes.get(position));
                return !indexes.isEmpty();
            }
        }

        @Override public void replaceAll(UnaryOperator<VideoClickAction> operator) {
            synchronized (monitor) {
                java.util.Objects.requireNonNull(operator, "operator");
                ArrayList<VideoClickAction> before = new ArrayList<>(values);
                ArrayList<VideoClickAction> after = new ArrayList<>(values.size());
                IdentityHashMap<VideoClickAction, Integer> beforeCounts = new IdentityHashMap<>();
                IdentityHashMap<VideoClickAction, Integer> afterCounts = new IdentityHashMap<>();
                for (VideoClickAction action : before) {
                    beforeCounts.put(action, beforeCounts.getOrDefault(action, 0) + 1);
                    after.add(operator.apply(action));
                }
                for (VideoClickAction action : after) {
                    afterCounts.put(action, afterCounts.getOrDefault(action, 0) + 1);
                }

                // Validate every removal and new identity before changing either
                // the list or transaction ownership. A rejected later element
                // must leave earlier same-owner replacements untouched.
                for (int index = 0; index < before.size(); index++) {
                    if (before.get(index) != after.get(index)) {
                        // A permutation can keep the same identity counts while
                        // still moving a built-in or another plugin's action.
                        ensureActionRemovalAllowed(before.get(index));
                    }
                }
                for (VideoClickAction action : beforeCounts.keySet()) {
                    if (!afterCounts.containsKey(action)) ensureActionRemovalAllowed(action);
                }
                ArrayList<VideoClickAction> additions = new ArrayList<>();
                for (VideoClickAction action : afterCounts.keySet()) {
                    if (afterCounts.get(action) > beforeCounts.getOrDefault(action, 0)) {
                        ensureActionAdditionAllowed(action);
                        additions.add(action);
                    }
                }

                for (VideoClickAction action : additions) recordActionAddition(action);
                values.clear();
                values.addAll(after);
                for (VideoClickAction action : beforeCounts.keySet()) {
                    if (!afterCounts.containsKey(action)) {
                        recordActionRemoval(action, indexOfIdentity(before, action));
                    }
                }
            }
        }

        @Override public void sort(Comparator<? super VideoClickAction> comparator) {
            synchronized (monitor) {
                ArrayList<VideoClickAction> sorted = new ArrayList<>(values);
                sorted.sort(comparator);
                for (int index = 0; index < values.size(); index++) {
                    if (values.get(index) != sorted.get(index)) {
                        // Sorting is order-only, but plugins still may move only
                        // actions owned by their own package path.
                        ensureActionRemovalAllowed(values.get(index));
                    }
                }
                // Sorting only changes order. Apply the permutation directly so
                // List.set bookkeeping cannot transfer or drop ownership.
                for (int index = 0; index < sorted.size(); index++) values.set(index, sorted.get(index));
            }
        }
    }

    private static boolean isInternalActionMutation() {
        return Boolean.TRUE.equals(INTERNAL_ACTION_MUTATION.get());
    }

    private static void ensureActionRemovalAllowed(VideoClickAction action) {
        if (isInternalActionMutation()) return;
        OrbitCs3HostServices services = processInstance;
        if (services == null) throw new IllegalStateException(
                "Ownerless built-in actions may only be removed by an internal Orbit host mutation.");
        synchronized (services.lock) {
            LoadTransaction current = CURRENT_LOAD.get();
            LoadTransaction owner = services.actionOwners.get(action);
            if (owner == null) {
                throw new IllegalStateException(
                        "Ownerless built-in actions may only be removed by an internal Orbit host mutation.");
            }
            if (current == null || (owner != current && !owner.pluginPath.equals(current.pluginPath))) {
                throw new IllegalStateException("A plugin cannot remove another owner's video action.");
            }
        }
    }

    /** Side-effect-free check used to preflight a whole public list transformation. */
    private static void ensureActionAdditionAllowed(VideoClickAction action) {
        if (action == null) throw new IllegalArgumentException("A VideoClickAction cannot be null.");
        OrbitCs3HostServices services = processInstance;
        if (services == null) {
            if (action.getSourcePlugin() != null
                    || action.getClass().getClassLoader() != VideoClickAction.class.getClassLoader()) {
                throw new IllegalStateException("Plugin action registration requires an active Orbit host.");
            }
            return;
        }
        synchronized (services.lock) {
            LoadTransaction current = CURRENT_LOAD.get();
            LoadTransaction owner = services.actionOwners.get(action);
            if (owner != null) {
                if (current != owner && (current == null || !current.pluginPath.equals(owner.pluginPath))) {
                    throw new IllegalStateException("The action belongs to another plugin.");
                }
                if (owner.closed && !owner.committed) {
                    throw new IllegalStateException("The action's plugin is unloaded.");
                }
                return;
            }
            if (current == null) {
                for (LoadTransaction candidate : services.committedLoads.values()) {
                    if ((candidate.pluginPath.equals(action.getSourcePlugin())
                            && (action.getClass().getClassLoader() == VideoClickAction.class.getClassLoader()
                            || candidate.plugin.getClass().getClassLoader() == action.getClass().getClassLoader()))
                            || (action.getClass().getClassLoader() != VideoClickAction.class.getClassLoader()
                            && candidate.plugin.getClass().getClassLoader() == action.getClass().getClassLoader())) {
                        current = candidate;
                        break;
                    }
                }
            }
            if (current == null) {
                if (action.getSourcePlugin() != null
                        || action.getClass().getClassLoader() != VideoClickAction.class.getClassLoader()) {
                    throw new IllegalStateException("The action's plugin is not active.");
                }
                return;
            }
            if (current.services != services || (current.closed && !current.committed)) {
                throw new IllegalStateException("The action's plugin registration is closed.");
            }
        }
    }

    private static void recordActionAddition(VideoClickAction action) {
        if (isInternalActionMutation()) return;
        if (action == null) throw new IllegalArgumentException("A VideoClickAction cannot be null.");
        OrbitCs3HostServices services = processInstance;
        if (services == null) {
            if (action.getSourcePlugin() != null || action.getClass().getClassLoader() != VideoClickAction.class.getClassLoader()) {
                throw new IllegalStateException("Plugin action registration requires an active Orbit host.");
            }
            return;
        }
        synchronized (services.lock) {
            LoadTransaction current = CURRENT_LOAD.get();
            LoadTransaction owner = services.actionOwners.get(action);
            if (owner != null) {
                if (current != owner && (current == null || !current.pluginPath.equals(owner.pluginPath))) {
                    throw new IllegalStateException("The action belongs to another plugin.");
                }
                if (owner.closed && !owner.committed) throw new IllegalStateException("The action's plugin is unloaded.");
                owner.removedActions.remove(action);
                if (current != null && current != owner) {
                    current.removedActions.remove(action);
                }
                return;
            }
            if (current == null) {
                for (LoadTransaction candidate : services.committedLoads.values()) {
                    if ((candidate.pluginPath.equals(action.getSourcePlugin())
                            && (action.getClass().getClassLoader() == VideoClickAction.class.getClassLoader()
                            || candidate.plugin.getClass().getClassLoader() == action.getClass().getClassLoader()))
                            || (action.getClass().getClassLoader() != VideoClickAction.class.getClassLoader()
                            && candidate.plugin.getClass().getClassLoader() == action.getClass().getClassLoader())) {
                        current = candidate;
                        break;
                    }
                }
            }
            if (current == null) {
                if (action.getSourcePlugin() != null || action.getClass().getClassLoader() != VideoClickAction.class.getClassLoader()) {
                    throw new IllegalStateException("The action's plugin is not active.");
                }
                return; // Orbit-owned built-in action.
            }
            if (current.services != services || (current.closed && !current.committed)) {
                throw new IllegalStateException("The action's plugin registration is closed.");
            }
            services.actionOwners.put(action, current);
            try { current.actions.add(action); }
            catch (RuntimeException | Error error) { services.actionOwners.remove(action); throw error; }
            action.setSourcePlugin(current.pluginPath);
            current.removedActions.remove(action);
        }
    }

    private static void recordActionRemoval(VideoClickAction action, int index) {
        if (isInternalActionMutation()) return;
        OrbitCs3HostServices services = processInstance;
        if (services == null) return;
        synchronized (services.lock) {
            LoadTransaction owner = services.actionOwners.get(action);
            if (owner == null) return;
            LoadTransaction current = CURRENT_LOAD.get();
            LoadTransaction removalTransaction = owner == current ? owner : current;
            if (removalTransaction == null) return;
            removalTransaction.removedActions.add(action);
            ActionPosition original = removalTransaction.actionPositionsAtLoad.get(action);
            if (original != null) removalTransaction.removedActionPositions.putIfAbsent(action, original);
        }
    }

    public boolean isVideoClickActionAvailable(VideoClickAction action) {
        synchronized (lock) {
            if (action == null || !containsIdentity(VideoClickActionHolder.INSTANCE.getAllVideoClickActions(), action)) return false;
            LoadTransaction owner = actionOwners.get(action);
            return owner == null ? action.getSourcePlugin() == null
                    : owner.committed && committedLoads.get(owner.pluginId) == owner
                    && !quiescingActionPluginPaths.contains(owner.pluginPath);
        }
    }

    /** Captures the exact action identity and non-reused load generation without retaining plugin code. */
    public VideoClickActionGenerationToken getVideoClickActionGenerationToken(VideoClickAction action) {
        synchronized (lock) {
            if (action == null || !containsIdentity(
                    VideoClickActionHolder.INSTANCE.getAllVideoClickActions(), action)) return null;
            LoadTransaction owner = actionOwners.get(action);
            if (owner == null || !owner.committed || committedLoads.get(owner.pluginId) != owner
                    || quiescingActionPluginPaths.contains(owner.pluginPath)
                    || !owner.pluginPath.equals(action.getSourcePlugin())) return null;
            return new VideoClickActionGenerationToken(
                    action, owner.pluginPath, owner.actionGeneration);
        }
    }

    /** Acquires a bounded owner lease while dispatching a validated external player result. */
    public VideoActionResultCallbackLease acquireVideoActionResultCallback(
            VideoClickAction action, VideoClickActionGenerationToken token) {
        synchronized (lock) {
            if (!isVideoClickActionGenerationActiveLocked(action, token)) return null;
            LoadTransaction owner = actionOwners.get(action);
            Integer current = activeVideoActionResultCallbacks.get(owner);
            activeVideoActionResultCallbacks.put(owner, current == null ? 1 : current + 1);
            return new VideoActionResultCallbackLease(this, owner);
        }
    }

    /** Revalidates both the weak action identity and the exact still-committed load generation. */
    public boolean isVideoClickActionGenerationActive(
            VideoClickAction action, VideoClickActionGenerationToken token) {
        synchronized (lock) {
            return isVideoClickActionGenerationActiveLocked(action, token);
        }
    }

    private boolean isVideoClickActionGenerationActiveLocked(
            VideoClickAction action, VideoClickActionGenerationToken token) {
        if (action == null || token == null || token.action.get() != action
                || !containsIdentity(VideoClickActionHolder.INSTANCE.getAllVideoClickActions(), action)) return false;
        LoadTransaction owner = actionOwners.get(action);
        return owner != null && owner.committed && committedLoads.get(owner.pluginId) == owner
                && !quiescingActionPluginPaths.contains(owner.pluginPath)
                && owner.pluginPath.equals(token.pluginPath)
                && owner.actionGeneration == token.generation
                && owner.pluginPath.equals(action.getSourcePlugin());
    }

    /** Tracks the Orbit shell's action Job until its completion callback runs. */
    public void trackVideoActionJob(VideoClickAction action, Job job) {
        if (action == null || job == null) return;
        boolean cancelImmediately;
        synchronized (lock) {
            LoadTransaction owner = actionOwners.get(action);
            if (owner == null || !owner.committed || committedLoads.get(owner.pluginId) != owner) {
                job.cancel(null);
                return;
            }
            videoActionJobs.put(job, owner.pluginPath);
            cancelImmediately = quiescingActionPluginPaths.contains(owner.pluginPath);
            if (cancelImmediately) cancellationRequestedActionJobs.add(job);
        }
        job.invokeOnCompletion(error -> {
            synchronized (lock) {
                videoActionJobs.remove(job);
                cancellationRequestedActionJobs.remove(job);
                lock.notifyAll();
            }
            return kotlin.Unit.INSTANCE;
        });
        if (cancelImmediately) job.cancel(null);
    }

    /** Nonblocking UI cancellation; the Job remains tracked until completion. */
    public void cancelVideoActionJob(Job job) {
        if (job == null) return;
        synchronized (lock) {
            if (videoActionJobs.containsKey(job)) cancellationRequestedActionJobs.add(job);
        }
        job.cancel(null);
    }

    /** Lifecycle snapshot for unload handling and diagnostics. */
    public String getVideoActionJobLifecycle(String pluginPath) {
        if (pluginPath == null) return "QUIESCED";
        synchronized (lock) {
            int active = countVideoActionJobsLocked(pluginPath);
            int activeResultCallbacks = countVideoActionResultCallbacksLocked(pluginPath);
            int cancellationPending = 0;
            for (Job job : cancellationRequestedActionJobs) {
                if (pluginPath.equals(videoActionJobs.get(job))) cancellationPending++;
            }
            if (active == 0 && activeResultCallbacks > 0) return "RESULT_CALLBACK_ACTIVE";
            if (active == 0) return quiescingActionPluginPaths.contains(pluginPath)
                    ? "QUIESCED_PENDING_CLEANUP" : "QUIESCED";
            return cancellationPending == active ? "CANCEL_REQUESTED" : "ACTIVE";
        }
    }

    private int countVideoActionJobsLocked(String pluginPath) {
        int count = 0;
        for (String ownerPath : videoActionJobs.values()) if (pluginPath.equals(ownerPath)) count++;
        return count;
    }

    private int countVideoActionResultCallbacksLocked(String pluginPath) {
        int count = 0;
        for (Map.Entry<LoadTransaction, Integer> entry : activeVideoActionResultCallbacks.entrySet()) {
            if (pluginPath.equals(entry.getKey().pluginPath)) count += entry.getValue();
        }
        return count;
    }

    private void releaseVideoActionResultCallback(LoadTransaction owner) {
        synchronized (lock) {
            Integer count = activeVideoActionResultCallbacks.get(owner);
            if (count != null) {
                if (count <= 1) activeVideoActionResultCallbacks.remove(owner);
                else activeVideoActionResultCallbacks.put(owner, count - 1);
            }
            lock.notifyAll();
        }
    }

    /** Cancels and waits at most two seconds off-main; incomplete work keeps ownership intact. */
    private boolean cancelAndAwaitPluginActionJobs(String pluginPath) {
        ArrayList<Job> jobs = new ArrayList<>();
        synchronized (lock) {
            quiescingActionPluginPaths.add(pluginPath);
            for (Map.Entry<Job, String> entry : videoActionJobs.entrySet()) {
                if (pluginPath.equals(entry.getValue())) {
                    cancellationRequestedActionJobs.add(entry.getKey());
                    jobs.add(entry.getKey());
                }
            }
        }
        OrbitVideoActionRuntime.cancelPluginActions(pluginPath);
        for (Job job : jobs) job.cancel(null);

        if (Looper.myLooper() == Looper.getMainLooper()) {
            synchronized (lock) {
                return countVideoActionJobsLocked(pluginPath) == 0
                        && countVideoActionResultCallbacksLocked(pluginPath) == 0;
            }
        }

        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(VIDEO_ACTION_QUIESCE_TIMEOUT_MS);
        long deadline = System.nanoTime() + timeoutNanos;
        synchronized (lock) {
            while (countVideoActionJobsLocked(pluginPath) > 0
                    || countVideoActionResultCallbacksLocked(pluginPath) > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return false;
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    public String getVideoClickActionPluginPath(VideoClickAction action) {
        synchronized (lock) {
            LoadTransaction owner = actionOwners.get(action);
            return owner == null ? "" : owner.pluginPath;
        }
    }

    public List<VideoClickAction> getVideoClickActionSnapshot() {
        synchronized (lock) {
            ArrayList<VideoClickAction> result = new ArrayList<>();
            for (VideoClickAction action : VideoClickActionHolder.INSTANCE.getAllVideoClickActions()) {
                if (isVideoClickActionAvailable(action) && !containsIdentity(result, action)) result.add(action);
            }
            return Collections.unmodifiableList(result);
        }
    }

    public static synchronized OrbitCs3HostServices install(
            Context context, CloudStreamExtensionManager manager) {
        if (context == null || manager == null) throw new IllegalArgumentException("CS3 host services require Orbit context and extension storage.");
        OrbitCs3HostServices current = processInstance;
        if (current != null && current.appContext != context.getApplicationContext()) {
            throw new IllegalStateException("CS3 host services are already bound to a different Orbit process context.");
        }
        if (current == null) processInstance = new OrbitCs3HostServices(context, manager);
        return processInstance;
    }

    public static OrbitCs3HostServices get() {
        OrbitCs3HostServices current = processInstance;
        if (current == null) throw new IllegalStateException("Orbit CS3 host services have not been installed.");
        return current;
    }

    public Context getApplicationContext() {
        return appContext;
    }

    public CloudStreamExtensionManager getExtensionManager() {
        return extensionManager;
    }

    /** Returns the transaction ceiling compiled into this app variant; ordinary builds are uncapped here. */
    public static int getExtractorStageLimitForCurrentBuild() {
        return BuildConfig.CS3_CANARY_BUILD ? MAX_CANARY_STAGED_EXTRACTORS : Integer.MAX_VALUE;
    }

    /** Receives provider ownership changes so Orbit can cancel work before registrations disappear. */
    public void setProviderLifecycleListener(ProviderLifecycleListener listener) {
        providerLifecycleListener = listener;
    }

    private void notifyPluginProviderChange(String pluginId) {
        ProviderLifecycleListener listener = providerLifecycleListener;
        if (listener != null && pluginId != null) {
            try { listener.onPluginProviderChange(pluginId); }
            catch (RuntimeException ignored) { /* Cancellation reporting cannot interrupt registry cleanup. */ }
        }
    }

    /** Called by OrbitApplication lifecycle callbacks; the reference never owns an Activity. */
    public void onActivityStarted(Activity activity) {
        if (activity != null) {
            currentActivity = new WeakReference<>(activity);
            currentActivityResumed = false;
        }
    }

    public void onActivityResumed(Activity activity) {
        if (activity != null) {
            currentActivity = new WeakReference<>(activity);
            currentActivityResumed = true;
            mainHandler.post(() -> {
                RepositoryConsentRequest request;
                synchronized (lock) { request = activeRepositoryConsent; }
                if (request != null && request.activity == null && !request.choiceCompleted.get()) {
                    presentOrAwaitRepositoryConsent(request);
                } else {
                    startNextRepositoryConsentRequest();
                }
            });
        }
    }

    public void onActivityPaused(Activity activity) {
        if (currentActivity.get() == activity) currentActivityResumed = false;
    }

    public void onActivityStopped(Activity activity) {
        Activity current = currentActivity.get();
        if (current == activity) {
            currentActivity = new WeakReference<>(null);
            currentActivityResumed = false;
        }
        cancelRepositoryConsentForActivity(activity);
        OrbitWebViewResolverRuntime.cancelForActivity(activity);
    }

    public Activity getCurrentActivity() {
        return getResumedActivity();
    }

    private Activity getResumedActivity() {
        Activity activity = currentActivity.get();
        if (!currentActivityResumed || activity == null || activity.isFinishing()
                || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return null;
        return activity;
    }

    /** Stores metadata requested through the CS3 plugin API without loading its catalog. */
    public Object addRepositoryFromPlugin(
            com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData repository,
            kotlin.coroutines.Continuation<?> continuation) {
        if (repository == null || repository.getUrl() == null || repository.getUrl().trim().isEmpty()) {
            throw new IllegalArgumentException("A repository URL is required.");
        }
        if (continuation == null) throw new IllegalArgumentException("A suspend continuation is required.");
        String canonical = CloudStreamExtensionManager.canonicalize(repository.getUrl());
        if (canonical == null) throw new IllegalArgumentException("Repository URL must be a resolved HTTP(S) address.");
        return runSuspendIo(continuation, () -> {
            if (extensionManager.findRepository(canonical) == null) {
                extensionManager.addRepositoryMetadata(repository.getIconUrl(), repository.getName(),
                        canonical, repository.getDescription());
            }
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * Persists a repository request after explicit user confirmation.
     * No resumed Orbit Activity means fail closed without writes.
     */
    public Object addRepositoryWithConsent(
            com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData repository,
            kotlin.coroutines.Continuation<?> continuation) {
        if (repository == null || repository.getUrl() == null || repository.getUrl().trim().isEmpty()) {
            throw new IllegalArgumentException("A repository URL is required.");
        }
        if (continuation == null) throw new IllegalArgumentException("A suspend continuation is required.");
        String canonical = CloudStreamExtensionManager.canonicalize(repository.getUrl());
        if (canonical == null) throw new IllegalArgumentException("Repository URL must be a resolved HTTP(S) address.");
        if (extensionManager.findRepository(canonical) != null) return kotlin.Unit.INSTANCE;
        if (getResumedActivity() == null) throw new IllegalStateException(
                "Orbit needs a resumed screen to ask for consent before adding a CS3 repository.");
        RepositoryConsentRequest request = new RepositoryConsentRequest(repository, canonical, continuation);
        synchronized (lock) {
            repositoryConsentQueue.addLast(request);
        }
        startNextRepositoryConsentRequest();
        return kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED();
    }

    /** Shows one queued confirmation at a time and rechecks URL dedupe after the previous write. */
    private void startNextRepositoryConsentRequest() {
        mainHandler.post(() -> {
            RepositoryConsentRequest request;
            synchronized (lock) {
                if (activeRepositoryConsent != null) return;
                request = repositoryConsentQueue.pollFirst();
                if (request == null) return;
                activeRepositoryConsent = request;
            }
            if (extensionManager.findRepository(request.canonicalUrl) != null) {
                completeRepositoryConsent(request, kotlin.Unit.INSTANCE);
                return;
            }
            presentOrAwaitRepositoryConsent(request);
        });
    }

    private void presentOrAwaitRepositoryConsent(RepositoryConsentRequest request) {
        synchronized (lock) {
            if (activeRepositoryConsent != request) return;
        }
        if (request.choiceCompleted.get() || request.dialog != null) return;
        if (extensionManager.findRepository(request.canonicalUrl) != null) {
            if (request.choiceCompleted.compareAndSet(false, true)) {
                completeRepositoryConsent(request, kotlin.Unit.INSTANCE);
            }
            return;
        }
        Activity activity = getResumedActivity();
        if (activity == null) {
            if (request.noActivityTimeout == null) {
                Runnable timeout = () -> completeConsentFailure(request,
                        new IllegalStateException("Orbit has no resumed screen to request repository consent."));
                request.noActivityTimeout = timeout;
                mainHandler.postDelayed(timeout, 5000L);
            }
            return;
        }
        cancelNoActivityTimeout(request);
        request.activity = activity;
        if (activity.isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
            request.activity = null;
            presentOrAwaitRepositoryConsent(request);
            return;
        }

        String displayName = request.repository.getName() == null || request.repository.getName().trim().isEmpty()
                ? "Unnamed CloudStream repository" : request.repository.getName().trim();
        String message = "Repository name reported by the request: " + displayName
                + "\n\nRepository URL: " + request.repository.getUrl().trim()
                + "\n\nOrbit will save these repository details. Loading its catalog is a separate action; this does not install or enable any plugin.";
        try {
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Allow repository addition?")
                    .setMessage(message)
                    .setPositiveButton("Add repository", (ignored, which) -> grantRepositoryConsent(request))
                    .setNegativeButton("Cancel", (ignored, which) -> completeConsentFailure(
                            request, new CancellationException("Repository addition was declined.")))
                    .setOnCancelListener(ignored -> completeConsentFailure(
                            request, new CancellationException("Repository consent was dismissed.")))
                    .create();
            request.dialog = dialog;
            dialog.setOnDismissListener(ignored -> completeConsentFailure(
                    request, new CancellationException("Repository consent dialog was closed.")));
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        } catch (Throwable error) {
            request.dialog = null;
            request.activity = null;
            completeConsentFailure(request,
                    new IllegalStateException("Orbit could not show repository consent.", error));
        }
    }

    private void grantRepositoryConsent(RepositoryConsentRequest request) {
        if (!request.choiceCompleted.compareAndSet(false, true)) return;
        cancelNoActivityTimeout(request);
        request.activity = null;
        persistRepositoryAfterConsent(request);
    }

    private void cancelRepositoryConsentForActivity(Activity activity) {
        if (activity == null) return;
        RepositoryConsentRequest request;
        synchronized (lock) {
            request = activeRepositoryConsent;
            if (request == null || request.activity != activity || request.choiceCompleted.get()) return;
        }
        AlertDialog dialog = request.dialog;
        boolean cancelled = completeConsentFailure(request,
                new CancellationException("Repository consent was cancelled because its Orbit screen stopped."));
        if (!cancelled) return;
        if (dialog == null) return;
        Runnable dismiss = () -> { if (dialog.isShowing()) dialog.dismiss(); };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) dismiss.run();
        else activity.runOnUiThread(dismiss);
    }

    private void persistRepositoryAfterConsent(RepositoryConsentRequest request) {
        if (!(appContext instanceof com.auras.orbit.OrbitApplication)) {
            completeRepositoryConsent(request, kotlin.ResultKt.createFailure(
                    new IllegalStateException("Orbit extension worker is unavailable; repository was not saved.")));
            return;
        }
        try {
            ((com.auras.orbit.OrbitApplication) appContext).getExtensionExecutor().execute(() -> {
                Object outcome;
                try {
                    extensionManager.addRepositoryMetadata(request.repository.getIconUrl(), request.repository.getName(),
                            request.repository.getUrl(), request.repository.getDescription());
                    outcome = kotlin.Unit.INSTANCE;
                } catch (Throwable error) {
                    outcome = kotlin.ResultKt.createFailure(error);
                }
                completeRepositoryConsent(request, outcome);
            });
        } catch (RejectedExecutionException error) {
            completeRepositoryConsent(request, kotlin.ResultKt.createFailure(
                    new IllegalStateException("Orbit could not schedule repository persistence; no repository was saved.", error)));
        }
    }

    /** Runs a suspend-shaped host operation away from the caller, then resumes its continuation. */
    public Object runSuspendIo(kotlin.coroutines.Continuation<?> continuation, Callable<?> action) {
        if (continuation == null || action == null) throw new IllegalArgumentException("A continuation and host operation are required.");
        if (!(appContext instanceof com.auras.orbit.OrbitApplication)) {
            throw new IllegalStateException("Orbit extension worker is unavailable.");
        }
        try {
            ((com.auras.orbit.OrbitApplication) appContext).getExtensionExecutor().execute(() -> {
                Object outcome;
                try { outcome = action.call(); }
                catch (Throwable error) { outcome = kotlin.ResultKt.createFailure(error); }
                continuation.resumeWith(outcome);
            });
        } catch (RejectedExecutionException error) {
            throw new IllegalStateException("Orbit extension worker is shutting down.", error);
        }
        return kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED();
    }

    private boolean completeConsentFailure(RepositoryConsentRequest request, Throwable error) {
        if (!request.choiceCompleted.compareAndSet(false, true)) return false;
        cancelNoActivityTimeout(request);
        request.activity = null;
        completeRepositoryConsent(request, kotlin.ResultKt.createFailure(error));
        return true;
    }

    private void completeRepositoryConsent(RepositoryConsentRequest request, Object outcome) {
        if (!request.continuationCompleted.compareAndSet(false, true)) return;
        cancelNoActivityTimeout(request);
        request.dialog = null;
        request.activity = null;
        synchronized (lock) {
            if (activeRepositoryConsent == request) activeRepositoryConsent = null;
            else repositoryConsentQueue.remove(request);
        }
        try { request.continuation.resumeWith(outcome); }
        finally { startNextRepositoryConsentRequest(); }
    }

    private void cancelNoActivityTimeout(RepositoryConsentRequest request) {
        Runnable timeout = request.noActivityTimeout;
        if (timeout != null) {
            mainHandler.removeCallbacks(timeout);
            request.noActivityTimeout = null;
        }
    }

    private static final class RepositoryConsentRequest {
        final com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData repository;
        final String canonicalUrl;
        final kotlin.coroutines.Continuation<?> continuation;
        final AtomicBoolean choiceCompleted = new AtomicBoolean(false);
        final AtomicBoolean continuationCompleted = new AtomicBoolean(false);
        volatile Activity activity;
        volatile AlertDialog dialog;
        volatile Runnable noActivityTimeout;

        RepositoryConsentRequest(com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData repository,
                                 String canonicalUrl, kotlin.coroutines.Continuation<?> continuation) {
            this.repository = repository;
            this.canonicalUrl = canonicalUrl;
            this.continuation = continuation;
        }
    }

    /** True only while a committed loaded Android plugin exposes settings for the active profile. */
    public boolean hasPluginSettings(String pluginId) {
        return getActivePluginProfileId() != null && findPluginSettingsTransaction(pluginId) != null;
    }

    private LoadTransaction findPluginSettingsTransaction(String pluginId) {
        if (pluginId == null || pluginId.isEmpty()) return null;
        LoadTransaction transaction;
        synchronized (lock) { transaction = committedLoads.get(pluginId); }
        if (transaction == null || !(transaction.plugin instanceof com.lagradost.cloudstream3.plugins.Plugin)) return null;
        com.lagradost.cloudstream3.plugins.Plugin plugin = (com.lagradost.cloudstream3.plugins.Plugin) transaction.plugin;
        return plugin.getOpenSettings() == null ? null : transaction;
    }

    /** Launches the private AppCompat host with an opaque, process-local settings token. */
    public boolean openPluginSettings(String pluginId, Activity activity) {
        if (activity == null) return false;
        return runOnMainAndWait(() -> openPluginSettingsOnMain(pluginId, activity), false);
    }

    private boolean openPluginSettingsOnMain(String pluginId, Activity activity) {
        if (activity.isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return false;
        String token = createPluginSettingsSession(pluginId);
        if (token == null) return false;
        try {
            Intent intent = new Intent(activity, PluginSettingsHostActivity.class);
            intent.putExtra(PluginSettingsHostActivity.EXTRA_SESSION_TOKEN, token);
            activity.startActivity(intent);
            return true;
        } catch (Throwable error) {
            invalidatePluginSettingsSession(token, false);
            return false;
        }
    }

    /** Package-scoped so focused instrumentation can stage a token without racing Activity launch. */
    String createPluginSettingsSession(String pluginId) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return runOnMainAndWait(() -> createPluginSettingsSession(pluginId), null);
        }
        LoadTransaction transaction = findPluginSettingsTransaction(pluginId);
        String profileId = getActivePluginProfileId();
        if (transaction == null || profileId == null || profileId.isEmpty()) return null;
        String token = UUID.randomUUID().toString();
        PluginSettingsSession session = new PluginSettingsSession(token, pluginId, profileId, transaction);
        synchronized (lock) {
            if (committedLoads.get(pluginId) != transaction) return null;
            String existingToken = pluginSettingsTokenByPluginId.get(pluginId);
            if (existingToken != null) return null;
            pluginSettingsSessions.put(token, session);
            pluginSettingsTokenByPluginId.put(pluginId, token);
        }
        return token;
    }

    boolean hasPluginSettingsSession(String token) {
        if (token == null || token.isEmpty()) return false;
        synchronized (lock) {
            PluginSettingsSession session = pluginSettingsSessions.get(token);
            return session != null && !session.closed.get();
        }
    }

    /** Attaches the transient UI owner; this reference is never saved to Activity state. */
    boolean attachPluginSettingsHost(String token, PluginSettingsHostActivity host) {
        if (host == null || token == null) return false;
        synchronized (lock) {
            PluginSettingsSession session = pluginSettingsSessions.get(token);
            if (session == null || session.closed.get()) return false;
            session.host = new WeakReference<>(host);
            return true;
        }
    }

    /** Resolves the token against the current committed transaction and profile immediately before dispatch. */
    boolean invokePluginSettingsSession(String token, PluginSettingsHostActivity host) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return runOnMainAndWait(() -> invokePluginSettingsSession(token, host), false);
        }
        final PluginSettingsSession session;
        final com.lagradost.cloudstream3.plugins.Plugin plugin;
        synchronized (lock) {
            session = pluginSettingsSessions.get(token);
            if (session == null || session.closed.get() || session.invoked.get()
                    || session.host == null || session.host.get() != host
                    || committedLoads.get(session.pluginId) != session.transaction
                    || !(session.transaction.plugin instanceof com.lagradost.cloudstream3.plugins.Plugin)
                    || !session.profileId.equals(getActivePluginProfileId())) {
                return false;
            }
            plugin = (com.lagradost.cloudstream3.plugins.Plugin) session.transaction.plugin;
            if (plugin.getOpenSettings() == null || !session.invoked.compareAndSet(false, true)) return false;
        }
        try {
            return plugin.dispatchOpenSettings(host);
        } catch (Throwable error) {
            extensionManager.recordActivationError(session.pluginId,
                    "Plugin settings callback failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    void onPluginSettingsHostClosed(String token, PluginSettingsHostActivity host) {
        if (token == null) return;
        synchronized (lock) {
            PluginSettingsSession session = pluginSettingsSessions.get(token);
            if (session == null || (host != null && session.host != null && session.host.get() != host)) return;
            removePluginSettingsSessionLocked(session);
        }
    }

    /** Invalidates first, then removes all plugin dialogs before runtime cleanup can continue. */
    public boolean invalidatePluginSettingsForPlugin(String pluginId) {
        if (pluginId == null) return true;
        final PluginSettingsSession session;
        synchronized (lock) {
            String token = pluginSettingsTokenByPluginId.get(pluginId);
            session = token == null ? null : pluginSettingsSessions.get(token);
            if (session != null) session.closed.set(true);
        }
        if (session == null) return true;
        boolean detached = closePluginSettingsHost(session);
        if (detached) {
            synchronized (lock) {
                if (pluginSettingsSessions.get(session.token) == session) {
                    removePluginSettingsSessionLocked(session);
                }
            }
        }
        return detached;
    }

    private void invalidatePluginSettingsSession(String token, boolean finishHost) {
        PluginSettingsSession session;
        synchronized (lock) {
            session = pluginSettingsSessions.get(token);
            if (session != null) session.closed.set(true);
        }
        if (session == null) return;
        boolean detached = !finishHost || closePluginSettingsHost(session);
        if (detached) {
            synchronized (lock) {
                if (pluginSettingsSessions.get(session.token) == session) {
                    removePluginSettingsSessionLocked(session);
                }
            }
        }
    }

    void failClosedPluginSettingsSession(String token) {
        invalidatePluginSettingsSession(token, true);
    }

    private boolean closePluginSettingsHost(PluginSettingsSession session) {
        PluginSettingsHostActivity host = session.host == null ? null : session.host.get();
        if (host == null) return true;
        return runOnMainAndWait(() -> {
            return host.closePluginSettingsNow();
        }, false);
    }

    private void removePluginSettingsSessionLocked(PluginSettingsSession session) {
        session.closed.set(true);
        pluginSettingsSessions.remove(session.token, session);
        pluginSettingsTokenByPluginId.remove(session.pluginId, session.token);
    }

    private String getActivePluginProfileId() {
        if (!(appContext instanceof com.auras.orbit.OrbitApplication)) return null;
        com.auras.orbit.extensions.CS3PluginRuntime runtime =
                ((com.auras.orbit.OrbitApplication) appContext).getCS3PluginRuntime();
        return runtime == null ? null : runtime.getActiveProfileId();
    }

    private <T> T runOnMainAndWait(Callable<T> action, T failureValue) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return action.call(); }
            catch (Throwable ignored) { return failureValue; }
        }
        FutureTask<T> task = new FutureTask<>(action);
        if (!mainHandler.post(task)) return failureValue;
        try { return task.get(5L, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failureValue;
        } catch (ExecutionException | TimeoutException error) {
            return failureValue;
        }
    }

    private static final class PluginSettingsSession {
        final String token;
        final String pluginId;
        final String profileId;
        final LoadTransaction transaction;
        final AtomicBoolean invoked = new AtomicBoolean(false);
        final AtomicBoolean closed = new AtomicBoolean(false);
        volatile WeakReference<PluginSettingsHostActivity> host;

        PluginSettingsSession(String token, String pluginId, String profileId, LoadTransaction transaction) {
            this.token = token;
            this.pluginId = pluginId;
            this.profileId = profileId;
            this.transaction = transaction;
        }
    }

    /** Starts an isolated registration transaction immediately before plugin load. */
    public LoadTransaction beginPluginLoad(String pluginId, String pluginPath, BasePlugin plugin) {
        if (pluginId == null || pluginId.isEmpty() || plugin == null) {
            throw new IllegalArgumentException("A plugin ID and BasePlugin instance are required.");
        }
        File file = new File(pluginPath == null ? "" : pluginPath);
        if (!file.isFile()) throw new IllegalArgumentException("The plugin package path is not a regular file.");
        if (CURRENT_LOAD.get() != null) throw new IllegalStateException("Nested plugin load transactions are not supported.");
        synchronized (lock) {
            if (committedLoads.containsKey(pluginId)) throw new IllegalStateException("The plugin already owns a committed registration transaction.");
            if (cleanupPendingLoads.containsKey(pluginId)) throw new IllegalStateException(
                    "The plugin has registrations awaiting cleanup from an earlier failed transaction.");
        }
        LoadTransaction transaction = new LoadTransaction(this, pluginId, file.getAbsolutePath(), plugin);
        CURRENT_LOAD.set(transaction);
        return transaction;
    }

    /** Called by the binary BasePlugin facade while its load callback is running. */
    public static void registerMainAPI(BasePlugin owner, MainAPI provider) {
        LoadTransaction transaction = requireCurrent(owner);
        if (provider == null) throw new IllegalArgumentException("A MainAPI provider cannot be null.");
        transaction.services.stageProvider(transaction, provider);
        provider.setSourcePlugin(transaction.pluginPath);
    }

    /** Called by the binary BasePlugin facade while its load callback is running. */
    public static void registerExtractorAPI(BasePlugin owner, ExtractorApi extractor) {
        LoadTransaction transaction = requireCurrent(owner);
        if (extractor == null) throw new IllegalArgumentException("An ExtractorApi cannot be null.");
        transaction.services.stageExtractor(transaction, extractor);
        extractor.setSourcePlugin(transaction.pluginPath);
    }

    /** Called by the plugin facade while its transaction is open. */
    public static void registerVideoClickAction(Plugin owner, VideoClickAction action) {
        LoadTransaction transaction = requireCurrent(owner);
        if (action == null) throw new IllegalArgumentException("A VideoClickAction cannot be null.");
        transaction.services.stageVideoClickAction(transaction, action);
        action.setSourcePlugin(transaction.pluginPath);
    }

    /** Reserves a provider by identity before any plugin metadata on it is changed. */
    private void stageProvider(LoadTransaction transaction, MainAPI provider) {
        synchronized (lock) {
            if (transaction.closed) throw new IllegalStateException("The plugin registration transaction is closed.");
            LoadTransaction owner = providerOwners.get(provider);
            if (owner != null) {
                if (owner == transaction) return;
                throw new IllegalStateException("This MainAPI instance is already owned by another plugin transaction.");
            }
            if (containsIdentity(APIHolder.INSTANCE.getAllProviders(), provider)) {
                throw new IllegalStateException("This MainAPI instance is already present in the provider registry without this transaction's ownership.");
            }
            providerOwners.put(provider, transaction);
            try {
                transaction.providers.add(provider);
            } catch (RuntimeException | Error error) {
                if (providerOwners.get(provider) == transaction) providerOwners.remove(provider);
                throw error;
            }
        }
    }

    /** Reserves an extractor by identity before any plugin metadata on it is changed. */
    private void stageExtractor(LoadTransaction transaction, ExtractorApi extractor) {
        synchronized (lock) {
            if (transaction.closed) throw new IllegalStateException("The plugin registration transaction is closed.");
            LoadTransaction owner = extractorOwners.get(extractor);
            if (owner != null) {
                if (owner == transaction) return;
                throw new IllegalStateException("This ExtractorApi instance is already owned by another plugin transaction.");
            }
            if (containsIdentity(ExtractorApiKt.getExtractorApis(), extractor)) {
                throw new IllegalStateException("This ExtractorApi instance is already present in the extractor registry without this transaction's ownership.");
            }
            if (transaction.extractors.size() >= transaction.maxStagedExtractors) {
                transaction.extractorStageLimitExceeded = true;
                throw new ExtractorStageLimitExceededException(transaction.maxStagedExtractors);
            }
            extractorOwners.put(extractor, transaction);
            try {
                transaction.extractors.add(extractor);
            } catch (RuntimeException | Error error) {
                if (extractorOwners.get(extractor) == transaction) extractorOwners.remove(extractor);
                throw error;
            }
        }
    }

    /** Reserves an action identity before assigning its exact owning package path. */
    private void stageVideoClickAction(LoadTransaction transaction, VideoClickAction action) {
        AtomicMutableList<VideoClickAction> registry = VideoClickActionHolder.INSTANCE.getAllVideoClickActions();
        synchronized (lock) {
            if (transaction.closed) throw new IllegalStateException("The plugin registration transaction is closed.");
            LoadTransaction owner = actionOwners.get(action);
            if (owner != null) {
                if (owner == transaction) return;
                throw new IllegalStateException("This VideoClickAction instance is already owned by another plugin transaction.");
            }
            synchronized (registry) {
                if (containsIdentity(registry, action)) {
                    throw new IllegalStateException("This VideoClickAction instance is already present in the action registry without this transaction's ownership.");
                }
            }
            actionOwners.put(action, transaction);
            try {
                transaction.actions.add(action);
            } catch (RuntimeException | Error error) {
                if (actionOwners.get(action) == transaction) actionOwners.remove(action);
                throw error;
            }
        }
    }

    private static LoadTransaction requireCurrent(BasePlugin owner) {
        LoadTransaction transaction = CURRENT_LOAD.get();
        if (transaction == null || transaction.plugin != owner || transaction.closed) {
            throw new IllegalStateException("CS3 registration is only available during the owning plugin's load callback.");
        }
        return transaction;
    }

    private void commit(LoadTransaction transaction) {
        synchronized (lock) {
            if (transaction.closed || committedLoads.containsKey(transaction.pluginId)) {
                throw new IllegalStateException("Plugin registration transaction is no longer commit-ready.");
            }
            if (cleanupPendingLoads.containsKey(transaction.pluginId)) {
                throw new IllegalStateException("The plugin still has registrations awaiting cleanup.");
            }
            if (transaction.extractorStageLimitExceeded) {
                throw new ExtractorStageLimitExceededException(transaction.maxStagedExtractors);
            }
            ArrayList<MainAPI> providerAdds = new ArrayList<>();
            ArrayList<ExtractorApi> extractorAdds = new ArrayList<>();
            try {
                for (MainAPI provider : transaction.providers) {
                    if (providerOwners.get(provider) != transaction) {
                        throw new IllegalStateException("A MainAPI identity reservation was lost before commit.");
                    }
                    if (containsIdentity(APIHolder.INSTANCE.getAllProviders(), provider)) {
                        throw new IllegalStateException("A MainAPI instance entered the provider registry outside its owning transaction.");
                    }
                }
                for (ExtractorApi extractor : transaction.extractors) {
                    if (extractorOwners.get(extractor) != transaction) {
                        throw new IllegalStateException("An ExtractorApi identity reservation was lost before commit.");
                    }
                    if (containsIdentity(ExtractorApiKt.getExtractorApis(), extractor)) {
                        throw new IllegalStateException("An ExtractorApi instance entered the extractor registry outside its owning transaction.");
                    }
                }
                AtomicMutableList<VideoClickAction> actionRegistry =
                        VideoClickActionHolder.INSTANCE.getAllVideoClickActions();
                for (MainAPI provider : transaction.providers) {
                    // Record ownership before a registry mutation can throw so rollback
                    // is complete even if the mutation succeeds and bookkeeping fails.
                    providerAdds.add(provider);
                    APIHolder.INSTANCE.addPluginMapping(provider);
                }
                for (ExtractorApi extractor : transaction.extractors) {
                    extractorAdds.add(extractor);
                    ExtractorApiKt.registerExtractorApi(extractor);
                }
                synchronized (actionRegistry) {
                    for (VideoClickAction action : transaction.actions) {
                        if (actionOwners.get(action) != transaction) {
                            throw new IllegalStateException("A VideoClickAction identity reservation was lost before commit.");
                        }
                    }
                    for (VideoClickAction action : transaction.actions) {
                        if (!transaction.removedActions.contains(action) && !containsIdentity(actionRegistry, action)) {
                            actionRegistry.add(action);
                        }
                    }
                }
                finalizeActionRemovals(transaction);
                committedLoads.put(transaction.pluginId, transaction);
                transaction.committed = true;
                transaction.closed = true;
                notifyPluginProviderChange(transaction.pluginId);
            } catch (RuntimeException | Error error) {
                Throwable cleanupError = removeRegistrations(providerAdds, extractorAdds, transaction.actions);
                if (cleanupError != null) {
                    error.addSuppressed(cleanupError);
                    transaction.registryCleanupPending = true;
                    cleanupPendingLoads.put(transaction.pluginId, transaction);
                } else {
                    restoreRemovedActions(transaction);
                    releaseOwnership(transaction);
                }
                transaction.closed = true;
                throw error;
            } finally {
                if (CURRENT_LOAD.get() == transaction) CURRENT_LOAD.remove();
            }
        }
    }

    private void rollback(LoadTransaction transaction) {
        if (!cancelAndAwaitPluginActionJobs(transaction.pluginPath)) {
            synchronized (lock) {
                transaction.registryCleanupPending = true;
                transaction.closed = true;
                cleanupPendingLoads.put(transaction.pluginId, transaction);
                if (CURRENT_LOAD.get() == transaction) CURRENT_LOAD.remove();
            }
            throw new IllegalStateException(
                    "Plugin action jobs have not quiesced; registration cleanup remains pending.");
        }
        // Stop active provider calls before unregistering their exact MainAPI identities.
        notifyPluginProviderChange(transaction.pluginId);
        synchronized (lock) {
            Throwable cleanupError = null;
            if (transaction.committed) {
                cleanupError = removeRegistrations(transaction.providers, transaction.extractors, transaction.actions);
                if (cleanupError == null) {
                    committedLoads.remove(transaction.pluginId, transaction);
                    cleanupPendingLoads.remove(transaction.pluginId, transaction);
                    transaction.registryCleanupPending = false;
                    quiescingActionPluginPaths.remove(transaction.pluginPath);
                    releaseOwnership(transaction);
                    transaction.committed = false;
                }
            } else if (transaction.registryCleanupPending) {
                cleanupError = removeRegistrations(transaction.providers, transaction.extractors, transaction.actions);
                if (cleanupError == null) {
                    cleanupPendingLoads.remove(transaction.pluginId, transaction);
                    transaction.registryCleanupPending = false;
                    restoreRemovedActions(transaction);
                    quiescingActionPluginPaths.remove(transaction.pluginPath);
                    releaseOwnership(transaction);
                }
            } else {
                // The official action holder is publicly mutable. Also clear a staged
                // identity that plugin code inserted directly before commit.
                cleanupError = removeRegistrations(
                        Collections.<MainAPI>emptyList(),
                        Collections.<ExtractorApi>emptyList(),
                        transaction.actions);
                if (cleanupError == null) {
                    restoreRemovedActions(transaction);
                    quiescingActionPluginPaths.remove(transaction.pluginPath);
                    releaseOwnership(transaction);
                } else {
                    transaction.registryCleanupPending = true;
                    cleanupPendingLoads.put(transaction.pluginId, transaction);
                }
            }
            transaction.closed = true;
            if (CURRENT_LOAD.get() == transaction) CURRENT_LOAD.remove();
            if (cleanupError != null) throw new IllegalStateException(
                    "One or more CS3 registry entries could not be fully cleaned up.", cleanupError);
        }
    }

    /** Attempts every exact-identity removal and preserves cleanup diagnostics. */
    private static Throwable removeRegistrations(List<MainAPI> providers, List<ExtractorApi> extractors,
                                                List<VideoClickAction> actions) {
        Throwable firstError = null;
        for (MainAPI provider : providers) {
            try { APIHolder.INSTANCE.removePluginMapping(provider); }
            catch (Throwable error) {
                if (firstError == null) firstError = error;
                else firstError.addSuppressed(error);
            }
        }
        for (ExtractorApi extractor : extractors) {
            try { ExtractorApiKt.removeExtractorApi(extractor); }
            catch (Throwable error) {
                if (firstError == null) firstError = error;
                else firstError.addSuppressed(error);
            }
        }
        AtomicMutableList<VideoClickAction> actionRegistry =
                VideoClickActionHolder.INSTANCE.getAllVideoClickActions();
        Boolean previousInternal = INTERNAL_ACTION_MUTATION.get();
        INTERNAL_ACTION_MUTATION.set(Boolean.TRUE);
        try {
            for (VideoClickAction action : actions) {
                try {
                    synchronized (actionRegistry) {
                        for (int index = 0; index < actionRegistry.size(); index++) {
                            if (actionRegistry.get(index) == action) {
                                actionRegistry.remove(index);
                                index--;
                            }
                        }
                    }
                } catch (Throwable error) {
                    if (firstError == null) firstError = error;
                    else firstError.addSuppressed(error);
                }
            }
        } finally {
            if (previousInternal == null) INTERNAL_ACTION_MUTATION.remove();
            else INTERNAL_ACTION_MUTATION.set(previousInternal);
        }
        return firstError;
    }

    private void restoreRemovedActions(LoadTransaction transaction) {
        AtomicMutableList<VideoClickAction> registry = VideoClickActionHolder.INSTANCE.getAllVideoClickActions();
        ArrayList<Map.Entry<VideoClickAction, ActionPosition>> entries =
                new ArrayList<>(transaction.removedActionPositions.entrySet());
        entries.sort((left, right) -> Integer.compare(left.getValue().originalIndex, right.getValue().originalIndex));
        Boolean previousInternal = INTERNAL_ACTION_MUTATION.get();
        INTERNAL_ACTION_MUTATION.set(Boolean.TRUE);
        try {
            synchronized (registry) {
                for (Map.Entry<VideoClickAction, ActionPosition> entry : entries) {
                    VideoClickAction action = entry.getKey();
                    LoadTransaction owner = actionOwners.get(action);
                    if (owner == null || !owner.committed || !committedLoads.containsValue(owner)) continue;
                    ActionPosition position = entry.getValue();
                    int currentIndex = indexOfIdentity(registry, action);
                    if (currentIndex >= 0) registry.remove(currentIndex);
                    int nextIndex = position.next == null ? -1 : indexOfIdentity(registry, position.next);
                    int previousIndex = position.previous == null ? -1 : indexOfIdentity(registry, position.previous);
                    int insertAt = nextIndex >= 0 ? nextIndex
                            : previousIndex >= 0 ? previousIndex + 1
                            : Math.min(position.originalIndex, registry.size());
                    registry.add(insertAt, action);
                }
            }
        } finally {
            if (previousInternal == null) INTERNAL_ACTION_MUTATION.remove();
            else INTERNAL_ACTION_MUTATION.set(previousInternal);
        }
        transaction.removedActions.clear();
        transaction.removedActionPositions.clear();
    }

    private void finalizeActionRemovals(LoadTransaction transaction) {
        for (VideoClickAction action : transaction.removedActions) {
            LoadTransaction owner = actionOwners.get(action);
            if (owner == null) continue;
            if (owner != transaction && !transaction.removedActionPositions.containsKey(action)) continue;
            actionOwners.remove(action);
            removeIdentity(owner.actions, action);
        }
        transaction.removedActions.clear();
        transaction.removedActionPositions.clear();
    }

    private static int indexOfIdentity(List<VideoClickAction> actions, VideoClickAction target) {
        for (int index = 0; index < actions.size(); index++) if (actions.get(index) == target) return index;
        return -1;
    }

    private static void removeIdentity(List<VideoClickAction> actions, VideoClickAction target) {
        for (int index = 0; index < actions.size(); index++) {
            if (actions.get(index) == target) { actions.remove(index); return; }
        }
    }

    private void releaseOwnership(LoadTransaction transaction) {
        for (MainAPI provider : transaction.providers) {
            if (providerOwners.get(provider) == transaction) providerOwners.remove(provider);
        }
        for (ExtractorApi extractor : transaction.extractors) {
            if (extractorOwners.get(extractor) == transaction) extractorOwners.remove(extractor);
        }
        for (VideoClickAction action : transaction.actions) {
            if (actionOwners.get(action) == transaction) actionOwners.remove(action);
        }
    }

    private static boolean containsIdentity(Iterable<?> values, Object target) {
        for (Object value : values) if (value == target) return true;
        return false;
    }

    /** Idempotently removes all provider/extractor registrations owned by a plugin. */
    public boolean unloadPlugin(String pluginId) {
        if (pluginId == null) return false;
        LoadTransaction transaction;
        synchronized (lock) {
            transaction = committedLoads.get(pluginId);
            if (transaction == null) transaction = cleanupPendingLoads.get(pluginId);
        }
        if (transaction == null) return false;
        transaction.rollback();
        return true;
    }

    public boolean unloadPluginByPath(String pluginPath) {
        if (pluginPath == null) return false;
        String canonical = new File(pluginPath).getAbsolutePath();
        String match = null;
        synchronized (lock) {
            for (Map.Entry<String, LoadTransaction> item : committedLoads.entrySet()) {
                if (canonical.equals(item.getValue().pluginPath)) { match = item.getKey(); break; }
            }
        }
        return match != null && unloadPlugin(match);
    }

    public List<MainAPI> getProviderSnapshot() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(APIHolder.INSTANCE.getAllProviders()));
        }
    }

    /** Returns only providers owned by committed registration transactions, with plugin identity. */
    public List<ProviderRegistration> getCommittedProviderRegistrations() {
        synchronized (lock) {
            ArrayList<LoadTransaction> transactions = new ArrayList<>(committedLoads.values());
            transactions.sort((left, right) -> left.pluginId.compareTo(right.pluginId));
            ArrayList<ProviderRegistration> result = new ArrayList<>();
            for (LoadTransaction transaction : transactions) {
                for (int index = 0; index < transaction.providers.size(); index++) {
                    result.add(new ProviderRegistration(transaction.pluginId,
                            transaction.providers.get(index), index));
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    public List<ExtractorApi> getExtractorSnapshot() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(ExtractorApiKt.getExtractorApis()));
        }
    }

    public boolean hasCommittedRegistrations(String pluginId) {
        synchronized (lock) { return committedLoads.containsKey(pluginId); }
    }

    public boolean hasOutstandingRegistrations(String pluginId) {
        synchronized (lock) {
            return committedLoads.containsKey(pluginId) || cleanupPendingLoads.containsKey(pluginId);
        }
    }

    /** Returns active or cleanup-pending plugin IDs for reconciliation and artifact-change guards. */
    public List<String> getOutstandingPluginIds() {
        synchronized (lock) {
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(committedLoads.keySet());
            ids.addAll(cleanupPendingLoads.keySet());
            return Collections.unmodifiableList(new ArrayList<>(ids));
        }
    }

    public static final class LoadTransaction implements AutoCloseable {
        private final OrbitCs3HostServices services;
        public final String pluginId;
        public final String pluginPath;
        private final BasePlugin plugin;
        private final Object actionGeneration = new Object();
        private final int maxStagedExtractors;
        private final List<MainAPI> providers = new ArrayList<>();
        private final List<ExtractorApi> extractors = new ArrayList<>();
        private final List<VideoClickAction> actions = new ArrayList<>();
        private final java.util.Set<VideoClickAction> removedActions =
                Collections.newSetFromMap(new IdentityHashMap<VideoClickAction, Boolean>());
        private final IdentityHashMap<VideoClickAction, ActionPosition> actionPositionsAtLoad = new IdentityHashMap<>();
        private final IdentityHashMap<VideoClickAction, ActionPosition> removedActionPositions = new IdentityHashMap<>();
        private boolean committed;
        private boolean closed;
        private boolean registryCleanupPending;
        private boolean extractorStageLimitExceeded;

        private LoadTransaction(OrbitCs3HostServices services, String id, String path, BasePlugin plugin) {
            this.services = services;
            pluginId = id;
            pluginPath = path;
            this.plugin = plugin;
            maxStagedExtractors = getExtractorStageLimitForCurrentBuild();
            AtomicMutableList<VideoClickAction> registry = VideoClickActionHolder.INSTANCE.getAllVideoClickActions();
            synchronized (services.lock) {
                for (int index = 0; index < registry.size(); index++) {
                    VideoClickAction previous = index > 0 ? registry.get(index - 1) : null;
                    VideoClickAction action = registry.get(index);
                    VideoClickAction next = index + 1 < registry.size() ? registry.get(index + 1) : null;
                    actionPositionsAtLoad.put(action, new ActionPosition(index, previous, next));
                }
            }
        }

        public void commit() { services.commit(this); }

        public void rollback() { services.rollback(this); }

        @Override public void close() { if (!closed) rollback(); }

        public boolean isCommitted() { return committed; }

        public int getProviderCount() { return providers.size(); }

        public int getExtractorCount() { return extractors.size(); }

        public int getMaxStagedExtractors() { return maxStagedExtractors; }

        public boolean hasExceededExtractorStageLimit() { return extractorStageLimitExceeded; }
    }

    /** Weakly binds a pending external result to one exact action and one opaque load generation. */
    public static final class VideoClickActionGenerationToken {
        private final WeakReference<VideoClickAction> action;
        private final String pluginPath;
        private final Object generation;

        private VideoClickActionGenerationToken(
                VideoClickAction action, String pluginPath, Object generation) {
            this.action = new WeakReference<>(action);
            this.pluginPath = pluginPath;
            this.generation = generation;
        }
    }

    /** Holds transaction ownership only until the external-result callback returns. */
    public static final class VideoActionResultCallbackLease implements AutoCloseable {
        private final OrbitCs3HostServices services;
        private final LoadTransaction owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private VideoActionResultCallbackLease(
                OrbitCs3HostServices services, LoadTransaction owner) {
            this.services = services;
            this.owner = owner;
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                services.releaseVideoActionResultCallback(owner);
            }
        }
    }

    private static final class ActionPosition {
        final int originalIndex;
        final VideoClickAction previous;
        final VideoClickAction next;

        ActionPosition(int originalIndex, VideoClickAction previous, VideoClickAction next) {
            this.originalIndex = originalIndex;
            this.previous = previous;
            this.next = next;
        }
    }

    public static final class ExtractorStageLimitExceededException extends IllegalStateException {
        public final int limit;

        private ExtractorStageLimitExceededException(int limit) {
            super("The CS3 canary extractor registration limit was exceeded.");
            this.limit = limit;
        }
    }

    public interface ProviderLifecycleListener {
        void onPluginProviderChange(String pluginId);
    }

    public static final class ProviderRegistration {
        public final String pluginId;
        public final MainAPI provider;
        public final int registrationIndex;

        private ProviderRegistration(String pluginId, MainAPI provider, int registrationIndex) {
            this.pluginId = pluginId;
            this.provider = provider;
            this.registrationIndex = registrationIndex;
        }
    }
}
