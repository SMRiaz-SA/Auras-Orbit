package com.auras.orbit.providers;

import java.lang.ref.WeakReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded worker pool for independent provider calls. Snapshots always retain caller source order. */
public final class ProviderOperationCoordinator implements AutoCloseable {
    /** Bound aggregate provider fan-out before allocating or iterating caller collections. */
    public static final int MAX_PROVIDERS_PER_BATCH = 256;
    private final DetailsRequestSingleFlight<MediaItem> detailsSingleFlight = new DetailsRequestSingleFlight<>();
    private final ExecutorService workers;
    private final ScheduledExecutorService timer;
    /** Registration/close handshake prevents callbacks from running before tracking begins. */
    private final Object batchRegistrationLock = new Object();
    private final Object providerCallMonitor = new Object();
    private int activeProviderCalls;
    private final Map<String, Integer> activeProviderCallsByPlugin = new HashMap<>();
    private final Set<String> blockedPluginCalls = new HashSet<>();
    private boolean providerCallAdmissionOpen = true;
    /** Weak ownership: completed batches may be collected when their UI owner releases them. */
    private final Set<WeakReference<ProviderBatch<?>>> batches = Collections.newSetFromMap(
            new ConcurrentHashMap<WeakReference<ProviderBatch<?>>, Boolean>());
    private final ReferenceQueue<ProviderBatch<?>> expiredBatches = new ReferenceQueue<>();
    private final Set<ProviderResource> providerResources = Collections.newSetFromMap(
            new ConcurrentHashMap<ProviderResource, Boolean>());
    /** Candidate hooks are weakly owned until playback/download takes ownership of a link. */
    private final Set<WeakReference<ProviderResource>> candidateProviderResources =
            Collections.newSetFromMap(new ConcurrentHashMap<WeakReference<ProviderResource>, Boolean>());
    private volatile boolean closed;

    public ProviderOperationCoordinator(int maxConcurrentProviders) {
        if (maxConcurrentProviders < 1 || maxConcurrentProviders > 64) {
            throw new IllegalArgumentException("maxConcurrentProviders must be between 1 and 64");
        }
        // A stalled provider must not create an unbounded heap of queued requests. Reject
        // overflow promptly; the affected batch can be retried after capacity returns.
        int maxQueuedCalls = Math.min(512, Math.max(32, maxConcurrentProviders * 32));
        this.workers = new ThreadPoolExecutor(maxConcurrentProviders, maxConcurrentProviders,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(maxQueuedCalls),
                namedFactory("orbit-provider"), new ThreadPoolExecutor.AbortPolicy());
        java.util.concurrent.ScheduledThreadPoolExecutor scheduler =
                new java.util.concurrent.ScheduledThreadPoolExecutor(1, namedFactory("orbit-provider-timeout"));
        // Removing cancelled timeouts is essential during fast typing / profile transitions.
        scheduler.setRemoveOnCancelPolicy(true);
        this.timer = scheduler;
    }

    public ProviderBatch<List<AttributedHomeSection>> loadHome(
            List<MediaProvider> orderedProviders, HomeRequest request, long timeoutMs,
            ProviderBatch.Listener<List<AttributedHomeSection>> listener) {
        if (request == null) throw new IllegalArgumentException("home request is required");
        ensureOpen();
        final List<MediaProvider> providerOrder = copyProviderOrder(orderedProviders);
        ProviderBatch<List<AttributedHomeSection>> batch = new ProviderBatch<>(providerOrder,
                workers, timer, timeoutMs, provider -> {
                    if (!provider.descriptor().homeFeed) {
                        throw new UnsupportedOperationException("Provider does not offer a Home feed");
                    }
                    List<HomeSection> sections = invokeProvider(provider.owningPluginId(),
                            () -> provider.loadHome(request));
                    if (sections == null) throw new IllegalStateException("Provider returned no Home response");
                    return ProviderResultBudget.home(provider.descriptor(),
                            sourceOrderOf(providerOrder, provider), sections);
                }, listener, this::removeBatch);
        return registerAndStart(batch);
    }

    /** Home page-aware path; the legacy loadHome() API remains available to existing callers. */
    public ProviderBatch<AttributedHomePage> loadHomePages(
            List<MediaProvider> orderedProviders, HomeRequest request, long timeoutMs,
            ProviderBatch.Listener<AttributedHomePage> listener) {
        if (request == null) throw new IllegalArgumentException("home request is required");
        ensureOpen();
        final List<MediaProvider> providerOrder = copyProviderOrder(orderedProviders);
        ProviderBatch<AttributedHomePage> batch = new ProviderBatch<>(providerOrder,
                workers, timer, timeoutMs, provider -> {
                    if (!provider.descriptor().homeFeed) {
                        throw new UnsupportedOperationException("Provider does not offer a Home feed");
                    }
                    HomePage page = invokeProvider(provider.owningPluginId(),
                            () -> provider.loadHomePage(request));
                    if (page == null) throw new IllegalStateException("Provider returned no Home response");
                    return ProviderResultBudget.homePage(provider.descriptor(),
                            sourceOrderOf(providerOrder, provider), page);
                }, listener, this::removeBatch);
        return registerAndStart(batch);
    }

    public ProviderBatch<List<AttributedSearchHit>> search(
            List<MediaProvider> orderedProviders, SearchRequest request, long timeoutMs,
            ProviderBatch.Listener<List<AttributedSearchHit>> listener) {
        if (request == null) throw new IllegalArgumentException("search request is required");
        ensureOpen();
        final List<MediaProvider> providerOrder = copyProviderOrder(orderedProviders);
        ProviderBatch<List<AttributedSearchHit>> batch = new ProviderBatch<>(providerOrder,
                workers, timer, timeoutMs, provider -> {
                    if (!provider.descriptor().titleSearch) {
                        throw new UnsupportedOperationException("Provider does not offer title search");
                    }
                    List<MediaItem> items = invokeProvider(provider.owningPluginId(),
                            () -> provider.search(request));
                    if (items == null) throw new IllegalStateException("Provider returned no search response");
                    return ProviderResultBudget.search(provider.descriptor(), items, request.type);
                }, listener, this::removeBatch);
        return registerAndStart(batch);
    }

    /** Provider-scoped Home quick search, separate from paginated universal title search. */
    public ProviderBatch<List<AttributedSearchHit>> quickSearch(
            List<MediaProvider> orderedProviders, SearchRequest request, long timeoutMs,
            ProviderBatch.Listener<List<AttributedSearchHit>> listener) {
        if (request == null) throw new IllegalArgumentException("quick-search request is required");
        if (request.page != 1) throw new IllegalArgumentException("quick search does not accept a page after 1");
        ensureOpen();
        final List<MediaProvider> providerOrder = copyProviderOrder(orderedProviders);
        ProviderBatch<List<AttributedSearchHit>> batch = new ProviderBatch<>(providerOrder,
                workers, timer, timeoutMs, provider -> {
                    if (!provider.descriptor().quickSearch) {
                        throw new UnsupportedOperationException("Provider does not offer Home quick search");
                    }
                    List<MediaItem> items = invokeProvider(provider.owningPluginId(),
                            () -> provider.quickSearch(request));
                    if (items == null) throw new IllegalStateException("Provider returned no quick-search response");
                    return ProviderResultBudget.search(provider.descriptor(), items, request.type);
                }, listener, this::removeBatch);
        return registerAndStart(batch);
    }

    /** Loads details from one provider while retaining its identity in the batch outcome. */
    public ProviderBatch<MediaItem> loadDetails(
            MediaProvider provider, String providerItemId, long timeoutMs,
            ProviderBatch.Listener<MediaItem> listener) {
        if (provider == null || provider.descriptor() == null) {
            throw new IllegalArgumentException("provider and descriptor are required");
        }
        if (providerItemId == null || providerItemId.trim().isEmpty()) {
            throw new IllegalArgumentException("provider item id is required");
        }
        final String selectedItemId = providerItemId.trim();
        ensureOpen();
        final List<MediaProvider> providerOrder = copyProviderOrder(
                Collections.singletonList(provider));
        ProviderBatch<MediaItem> batch = new ProviderBatch<>(providerOrder,
                workers, timer, timeoutMs, selectedProvider -> {
                    if (!selectedProvider.descriptor().details) {
                        throw new UnsupportedOperationException(
                                "Provider does not offer title details");
                    }
                    MediaItem details = detailsSingleFlight.execute(selectedProvider,
                            selectedProvider.profileScopeId(), selectedItemId,
                            () -> invokeProvider(selectedProvider.owningPluginId(),
                                    () -> selectedProvider.loadDetails(selectedItemId)), timeoutMs);
                    if (details == null) {
                        throw new IllegalStateException("Provider returned no title details");
                    }
                    return details;
                }, listener, this::removeBatch);
        return registerAndStart(batch);
    }

    /** Resolves links for one provider item; callers decide separately whether to open playback. */
    public ProviderBatch<ProviderLinkResult> loadLinks(
            MediaProvider provider, String providerItemId, boolean isCasting, long timeoutMs,
            ProviderBatch.Listener<ProviderLinkResult> listener) {
        return loadLinks(provider, providerItemId, isCasting, timeoutMs, listener, false, false);
    }

    /** Resolves links and publishes each provider callback snapshot while the operation is loading. */
    public ProviderBatch<ProviderLinkResult> loadLinksProgressively(
            MediaProvider provider, String providerItemId, boolean isCasting, long timeoutMs,
            ProviderBatch.Listener<ProviderLinkResult> listener) {
        return loadLinks(provider, providerItemId, isCasting, timeoutMs, listener, false, true);
    }

    /** Provider override bounded to CloudStream's five-second to eight-minute range. */
    public static long boundedLinkResolutionTimeoutMs(MediaProvider provider) {
        long requested = provider == null
                ? MediaProvider.DEFAULT_LINK_RESOLUTION_TIMEOUT_MS
                : provider.linkResolutionTimeoutMs();
        return Math.max(MediaProvider.MIN_LINK_RESOLUTION_TIMEOUT_MS,
                Math.min(MediaProvider.MAX_LINK_RESOLUTION_TIMEOUT_MS, requested));
    }

    private ProviderBatch<ProviderLinkResult> loadLinks(
            MediaProvider provider, String providerItemId, boolean isCasting, long timeoutMs,
            ProviderBatch.Listener<ProviderLinkResult> listener, boolean preserveOpaquePayload,
            boolean progressive) {
        if (provider == null || provider.descriptor() == null) {
            throw new IllegalArgumentException("provider and descriptor are required");
        }
        if (providerItemId == null || providerItemId.trim().isEmpty()) {
            throw new IllegalArgumentException("provider item id is required");
        }
        final String selectedItemId = preserveOpaquePayload ? providerItemId : providerItemId.trim();
        ensureOpen();
        final List<MediaProvider> providerOrder = copyProviderOrder(
                Collections.singletonList(provider));
        ProviderBatch<ProviderLinkResult> batch;
        if (progressive) {
            batch = new ProviderBatch<>(providerOrder, workers, timer, timeoutMs,
                    (providerRef, progress) -> {
                        if (!providerRef.descriptor().links) {
                            throw new UnsupportedOperationException(
                                    "Provider does not offer link resolution");
                        }
                        ProviderLinkResult result = invokeProvider(providerRef.owningPluginId(),
                                () -> providerRef.resolveLinks(selectedItemId, isCasting,
                                        progress::onProgress));
                        return requireResolvedLinkResult(result);
                    }, listener, this::removeBatch);
        } else {
            batch = new ProviderBatch<>(providerOrder, workers, timer, timeoutMs, providerRef -> {
                    if (!providerRef.descriptor().links) {
                        throw new UnsupportedOperationException(
                                "Provider does not offer link resolution");
                    }
                    ProviderLinkResult result = invokeProvider(providerRef.owningPluginId(),
                            () -> providerRef.resolveLinks(selectedItemId, isCasting));
                    return requireResolvedLinkResult(result);
                }, listener, this::removeBatch);
        }
        return registerAndStart(batch);
    }

    private static ProviderLinkResult requireResolvedLinkResult(ProviderLinkResult result) {
        if (result == null) {
            throw new IllegalStateException("Provider returned no link-resolution result");
        }
        if (!result.resolved) {
            throw new UnsupportedOperationException(result.diagnostic.isEmpty()
                    ? "Provider did not resolve any playable links" : result.diagnostic);
        }
        return result;
    }

    /** Resolves the opaque provider payload carried by a details result, without opening a link. */
    public ProviderBatch<ProviderLinkResult> loadLinks(
            MediaProvider provider, MediaItem item, boolean isCasting, long timeoutMs,
            ProviderBatch.Listener<ProviderLinkResult> listener) {
        if (item == null) throw new IllegalArgumentException("media item is required");
        if (item.providerData == null || item.providerData.trim().isEmpty()) {
            throw new UnsupportedOperationException(
                    "This item has no provider link-resolution payload");
        }
        return loadLinks(provider, item.providerData, isCasting, timeoutMs, listener,
                item.kind == MediaKind.LIVE, false);
    }

    /** Cancels every in-flight batch before a profile reconciliation changes the active source set. */
    public int cancelAll() {
        int cancelled = 0;
        for (ProviderBatch<?> batch : liveBatches()) {
            if (!batch.isComplete()) {
                batch.close();
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Closes provider-call admission before cancelling batches for a profile transition. */
    public int pauseProviderCallsAndCancelAll() {
        synchronized (providerCallMonitor) {
            providerCallAdmissionOpen = false;
        }
        cancelProviderResources(null, null);
        return cancelAll();
    }

    /** Blocks one plugin before canceling every aggregate operation that includes it. */
    public int pausePluginProviderCallsAndCancel(String pluginId) {
        if (pluginId == null) return 0;
        synchronized (providerCallMonitor) {
            blockedPluginCalls.add(pluginId);
        }
        return cancelForPlugin(pluginId);
    }

    /** Waits until every admitted MediaProvider invocation has left its call boundary. */
    public boolean awaitProviderCallsQuiesced(long timeoutMs) throws InterruptedException {
        if (timeoutMs <= 0L) throw new IllegalArgumentException("provider-call wait must be positive");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (providerCallMonitor) {
            while (activeProviderCalls > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return false;
                TimeUnit.NANOSECONDS.timedWait(providerCallMonitor, remaining);
            }
            return true;
        }
    }

    /** Waits until all admitted calls owned by one plugin have exited. */
    public boolean awaitPluginProviderCallsQuiesced(String pluginId, long timeoutMs)
            throws InterruptedException {
        if (pluginId == null) throw new IllegalArgumentException("plugin ID is required");
        if (timeoutMs <= 0L) throw new IllegalArgumentException("provider-call wait must be positive");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (providerCallMonitor) {
            while (activeProviderCallsByPlugin.getOrDefault(pluginId, 0) > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return false;
                TimeUnit.NANOSECONDS.timedWait(providerCallMonitor, remaining);
            }
            return true;
        }
    }

    /** Reopens provider-call admission only after the runtime transition has returned. */
    public void resumeProviderCalls() {
        synchronized (providerCallMonitor) {
            if (!closed) providerCallAdmissionOpen = true;
            providerCallMonitor.notifyAll();
        }
    }

    /** Reopens one plugin only after its cleanup/rollback has fully completed. */
    public void resumePluginProviderCalls(String pluginId) {
        if (pluginId == null) return;
        synchronized (providerCallMonitor) {
            blockedPluginCalls.remove(pluginId);
            providerCallMonitor.notifyAll();
        }
    }

    /** Cancels any aggregate batch that contains a provider from the changed plugin. */
    public int cancelForPlugin(String pluginId) {
        if (pluginId == null) return 0;
        cancelProviderResources(pluginId, null);
        int cancelled = 0;
        for (ProviderBatch<?> batch : liveBatches()) {
            if (batch.usesPlugin(pluginId)) {
                batch.close();
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Cancels every aggregate batch tied to the profile being left or reconciled. */
    public int cancelForProfile(String profileId) {
        if (profileId == null) return 0;
        cancelProviderResources(null, profileId);
        int cancelled = 0;
        for (ProviderBatch<?> batch : liveBatches()) {
            if (batch.usesProfile(profileId)) {
                batch.close();
                cancelled++;
            }
        }
        return cancelled;
    }

    private static int sourceOrderOf(List<MediaProvider> providers, MediaProvider wanted) {
        if (providers == null) return 0;
        for (int i = 0; i < providers.size(); i++) {
            MediaProvider provider = providers.get(i);
            if (provider != null && provider.descriptor().id.equals(wanted.descriptor().id)) return i;
        }
        return 0;
    }

    private static List<MediaProvider> copyProviderOrder(List<MediaProvider> providers) {
        if (providers == null) throw new IllegalArgumentException("provider order is required");
        int count = providers.size();
        if (count > MAX_PROVIDERS_PER_BATCH) {
            throw new IllegalArgumentException("Too many providers in one operation");
        }
        ArrayList<MediaProvider> copy = new ArrayList<>(count);
        // Index by the validated size. A plugin-supplied List may report a small size but
        // expose a nonterminating iterator; do not trust that iterator for admission.
        for (int index = 0; index < count; index++) {
            MediaProvider provider = providers.get(index);
            if (provider == null || provider.descriptor() == null) {
                throw new IllegalArgumentException("provider and descriptor are required");
            }
            copy.add(provider);
        }
        return Collections.unmodifiableList(copy);
    }

    private <T> T invokeProvider(String pluginId, Callable<T> operation) throws Exception {
        synchronized (providerCallMonitor) {
            if (closed || !providerCallAdmissionOpen
                    || (pluginId != null && blockedPluginCalls.contains(pluginId))) {
                throw new CancellationException("Provider calls are paused for a profile transition");
            }
            activeProviderCalls++;
            if (pluginId != null) {
                activeProviderCallsByPlugin.put(pluginId,
                        activeProviderCallsByPlugin.getOrDefault(pluginId, 0) + 1);
            }
        }
        try {
            return operation.call();
        } finally {
            synchronized (providerCallMonitor) {
                activeProviderCalls--;
                if (pluginId != null) {
                    int remaining = activeProviderCallsByPlugin.getOrDefault(pluginId, 0) - 1;
                    if (remaining <= 0) activeProviderCallsByPlugin.remove(pluginId);
                    else activeProviderCallsByPlugin.put(pluginId, remaining);
                }
                providerCallMonitor.notifyAll();
            }
        }
    }

    /** Registers a revocable player/download owner with the profile/plugin cleanup boundary. */
    public ProviderResource registerProviderResource(String pluginId, String profileId,
                                                     Runnable onRevoked) {
        if (pluginId == null || pluginId.trim().isEmpty()
                || profileId == null || profileId.trim().isEmpty() || onRevoked == null) {
            throw new IllegalArgumentException("Plugin, profile, and revoke callback are required");
        }
        synchronized (providerCallMonitor) {
            ensureOwnedProviderAdmission(pluginId);
            ProviderResource resource = new ProviderResource(this, pluginId, profileId, onRevoked);
            providerResources.add(resource);
            return resource;
        }
    }

    /**
     * Registers a revocable candidate hook without pinning its link result in the coordinator.
     * The candidate retains this handle while live; profile/plugin transitions can still revoke it.
     */
    public ProviderResource registerWeakProviderResource(String pluginId, String profileId,
                                                         Runnable onRevoked) {
        if (pluginId == null || pluginId.trim().isEmpty()
                || profileId == null || profileId.trim().isEmpty() || onRevoked == null) {
            throw new IllegalArgumentException("Plugin, profile, and revoke callback are required");
        }
        synchronized (providerCallMonitor) {
            ensureOwnedProviderAdmission(pluginId);
            pruneCandidateProviderResources();
            ProviderResource resource = new ProviderResource(this, pluginId, profileId, onRevoked);
            candidateProviderResources.add(new WeakReference<>(resource));
            return resource;
        }
    }

    /** Begins one synchronous hook call or suspend verifier job owned by a plugin/profile pair. */
    public ProviderOperation beginOwnedProviderOperation(String pluginId, String profileId) {
        if (pluginId == null || pluginId.trim().isEmpty()
                || profileId == null || profileId.trim().isEmpty()) {
            throw new IllegalArgumentException("Plugin and profile are required");
        }
        synchronized (providerCallMonitor) {
            ensureOwnedProviderAdmission(pluginId);
            activeProviderCalls++;
            activeProviderCallsByPlugin.put(pluginId,
                    activeProviderCallsByPlugin.getOrDefault(pluginId, 0) + 1);
        }
        return new ProviderOperation(this, pluginId);
    }

    private void ensureOwnedProviderAdmission(String pluginId) {
        if (closed || !providerCallAdmissionOpen || blockedPluginCalls.contains(pluginId)) {
            throw new CancellationException("Provider hooks are paused for a profile or plugin transition");
        }
    }

    private void finishOwnedProviderOperation(String pluginId) {
        synchronized (providerCallMonitor) {
            activeProviderCalls--;
            int remaining = activeProviderCallsByPlugin.getOrDefault(pluginId, 0) - 1;
            if (remaining <= 0) activeProviderCallsByPlugin.remove(pluginId);
            else activeProviderCallsByPlugin.put(pluginId, remaining);
            providerCallMonitor.notifyAll();
        }
    }

    private int cancelProviderResources(String pluginId, String profileId) {
        int cancelled = 0;
        for (ProviderResource resource : new ArrayList<>(providerResources)) {
            if ((pluginId == null || pluginId.equals(resource.pluginId))
                    && (profileId == null || profileId.equals(resource.profileId))) {
                resource.revoke();
                cancelled++;
            }
        }
        for (WeakReference<ProviderResource> reference : new ArrayList<>(candidateProviderResources)) {
            ProviderResource resource = reference.get();
            if (resource == null) {
                candidateProviderResources.remove(reference);
            } else if ((pluginId == null || pluginId.equals(resource.pluginId))
                    && (profileId == null || profileId.equals(resource.profileId))) {
                resource.revoke();
                cancelled++;
            }
        }
        return cancelled;
    }

    private void removeProviderResource(ProviderResource resource) {
        providerResources.remove(resource);
        for (WeakReference<ProviderResource> reference : new ArrayList<>(candidateProviderResources)) {
            ProviderResource registered = reference.get();
            if (registered == null || registered == resource) candidateProviderResources.remove(reference);
        }
    }

    private void pruneCandidateProviderResources() {
        for (WeakReference<ProviderResource> reference : candidateProviderResources) {
            if (reference.get() == null) candidateProviderResources.remove(reference);
        }
    }

    private static boolean matches(SearchType type, MediaKind kind) {
        if (type == SearchType.ALL) return true;
        if (type == SearchType.MOVIES) return kind == MediaKind.MOVIE;
        return kind == MediaKind.SERIES;
    }

    private <T> ProviderBatch<T> registerAndStart(ProviderBatch<T> batch) {
        synchronized (batchRegistrationLock) {
            if (closed) {
                batch.close();
                throw new IllegalStateException("Provider operation coordinator is closed");
            }
            pruneBatches();
            batches.add(new WeakReference<ProviderBatch<?>>(batch, expiredBatches));
        }
        // The first callback is emitted only after the close boundary can see this batch.
        batch.begin();
        return batch;
    }

    private List<ProviderBatch<?>> liveBatches() {
        List<ProviderBatch<?>> current = new ArrayList<>();
        for (WeakReference<ProviderBatch<?>> reference : batches) {
            ProviderBatch<?> batch = reference.get();
            if (batch == null) batches.remove(reference);
            else current.add(batch);
        }
        return current;
    }

    private void pruneBatches() {
        Reference<? extends ProviderBatch<?>> expired;
        while ((expired = expiredBatches.poll()) != null) batches.remove(expired);
    }

    private void removeBatch(ProviderBatch<?> batch) {
        for (WeakReference<ProviderBatch<?>> reference : batches) {
            ProviderBatch<?> registered = reference.get();
            if (registered == null || registered == batch) batches.remove(reference);
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Provider operation coordinator is closed");
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger next = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + next.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override public void close() {
        synchronized (batchRegistrationLock) {
            if (closed) return;
            closed = true;
        }
        synchronized (providerCallMonitor) {
            providerCallAdmissionOpen = false;
        }
        cancelProviderResources(null, null);
        for (ProviderBatch<?> batch : liveBatches()) batch.close();
        batches.clear();
        workers.shutdownNow();
        timer.shutdownNow();
    }

    /** A long-lived provider-owned resource that can revoke itself without holding the coordinator. */
    public static final class ProviderResource implements AutoCloseable {
        private final ProviderOperationCoordinator owner;
        private final String pluginId;
        private final String profileId;
        private final Runnable onRevoked;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ProviderResource(ProviderOperationCoordinator owner, String pluginId,
                                 String profileId, Runnable onRevoked) {
            this.owner = owner;
            this.pluginId = pluginId;
            this.profileId = profileId;
            this.onRevoked = onRevoked;
        }

        private void revoke() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                onRevoked.run();
            } catch (Throwable ignored) {
                // Revocation is a trust boundary: extension-owned cleanup can throw
                // RuntimeException, LinkageError, or other user-code failures.
                // Never let one broken owner prevent other resources from being
                // revoked or leave the coordinator worker/timer pools running.
            } finally {
                owner.removeProviderResource(this);
            }
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) owner.removeProviderResource(this);
        }
    }

    /** Quiescence token counted by existing plugin/provider cleanup waits. */
    public static final class ProviderOperation implements AutoCloseable {
        private final ProviderOperationCoordinator owner;
        private final String pluginId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ProviderOperation(ProviderOperationCoordinator owner, String pluginId) {
            this.owner = owner;
            this.pluginId = pluginId;
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) owner.finishOwnedProviderOperation(pluginId);
        }
    }
}
