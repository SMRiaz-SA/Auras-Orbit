package com.auras.orbit.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Ordered per-provider operation set with independent timeout, cancel, and retry controls.
 * Listener callbacks can arrive on a worker or timeout thread; UI consumers must marshal them
 * to the main thread. Cancellation interrupts provider work, but an adapter that ignores
 * interruption can continue occupying one bounded worker until that call returns.
 */
public final class ProviderBatch<T> implements AutoCloseable {
    public interface Listener<T> { void onChanged(List<ProviderOutcome<T>> orderedOutcomes); }
    interface Call<T> { T run(MediaProvider provider) throws Exception; }
    interface ProgressCall<T> {
        T run(MediaProvider provider, ProgressListener<T> listener) throws Exception;
    }
    interface ProgressListener<T> { void onProgress(T value); }
    interface CloseCallback { void onClosed(ProviderBatch<?> batch); }

    private static final class Entry<T> {
        final MediaProvider provider;
        final ProviderDescriptor descriptor;
        final int order;
        int attempt;
        boolean retryPending;
        ProviderOutcome.Status status = ProviderOutcome.Status.LOADING;
        T value;
        String message = "";
        FutureTask<T> task;
        ScheduledFuture<?> timeout;
        Entry(MediaProvider provider, ProviderDescriptor descriptor, int order) {
            this.provider = provider;
            this.descriptor = descriptor;
            this.order = order;
        }
    }

    private final List<Entry<T>> entries = new ArrayList<>();
    private final Map<String, Entry<T>> byId = new HashMap<>();
    private final ExecutorService workers;
    private final ScheduledExecutorService timer;
    private final long timeoutMs;
    private final Call<T> call;
    private final ProgressCall<T> progressCall;
    private final Listener<T> listener;
    private final CloseCallback closeCallback;
    private boolean closed;
    private final Object notificationLock = new Object();

    ProviderBatch(List<MediaProvider> providers, ExecutorService workers, ScheduledExecutorService timer,
                  long timeoutMs, Call<T> call, Listener<T> listener, CloseCallback closeCallback) {
        this(providers, workers, timer, timeoutMs, call, null, listener, closeCallback);
    }

    ProviderBatch(List<MediaProvider> providers, ExecutorService workers, ScheduledExecutorService timer,
                  long timeoutMs, ProgressCall<T> call, Listener<T> listener, CloseCallback closeCallback) {
        this(providers, workers, timer, timeoutMs, null, call, listener, closeCallback);
    }

    private ProviderBatch(List<MediaProvider> providers, ExecutorService workers,
                  ScheduledExecutorService timer, long timeoutMs, Call<T> call,
                  ProgressCall<T> progressCall, Listener<T> listener, CloseCallback closeCallback) {
        if (providers == null) throw new IllegalArgumentException("provider list is required");
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeout must be positive");
        if (call == null && progressCall == null) {
            throw new IllegalArgumentException("provider operation is required");
        }
        this.workers = workers;
        this.timer = timer;
        this.timeoutMs = timeoutMs;
        this.call = call;
        this.progressCall = progressCall;
        this.listener = listener;
        this.closeCallback = closeCallback;
        int order = 0;
        for (MediaProvider provider : providers) {
            if (provider == null || provider.descriptor() == null) {
                throw new IllegalArgumentException("provider and descriptor are required");
            }
            ProviderDescriptor descriptor = provider.descriptor();
            String id = descriptor.id;
            if (byId.containsKey(id)) throw new IllegalArgumentException("duplicate provider id: " + id);
            Entry<T> entry = new Entry<>(provider, descriptor, order++);
            entries.add(entry);
            byId.put(id, entry);
        }
    }

    /** Called exactly once after the coordinator has registered lifecycle ownership. */
    void begin() {
        if (entries.isEmpty()) emit();
        else for (Entry<T> entry : entries) start(entry);
    }

    public synchronized List<ProviderOutcome<T>> snapshot() {
        ArrayList<ProviderOutcome<T>> result = new ArrayList<>(entries.size());
        for (Entry<T> entry : entries) result.add(outcome(entry));
        return Collections.unmodifiableList(result);
    }

    public synchronized boolean isComplete() {
        for (Entry<T> entry : entries) if (entry.status == ProviderOutcome.Status.LOADING) return false;
        return true;
    }

    synchronized boolean usesPlugin(String pluginId) {
        if (pluginId == null) return false;
        for (Entry<T> entry : entries) {
            if (pluginId.equals(entry.provider.owningPluginId())) return true;
        }
        return false;
    }

    synchronized boolean usesProfile(String profileId) {
        if (profileId == null) return false;
        for (Entry<T> entry : entries) {
            if (profileId.equals(entry.provider.profileScopeId())) return true;
        }
        return false;
    }

    public boolean retry(String providerId) {
        Entry<T> entry;
        synchronized (this) {
            if (closed) return false;
            entry = byId.get(providerId);
            if (entry == null || (entry.status != ProviderOutcome.Status.FAILURE
                    && entry.status != ProviderOutcome.Status.TIMEOUT
                    && entry.status != ProviderOutcome.Status.CANCELLED) || entry.retryPending) return false;
            entry.retryPending = true;
        }
        start(entry);
        return true;
    }

    public boolean cancel(String providerId) {
        Entry<T> entry;
        Future<?> task;
        ScheduledFuture<?> timeout;
        synchronized (this) {
            if (closed) return false;
            entry = byId.get(providerId);
            if (entry == null || entry.status != ProviderOutcome.Status.LOADING) return false;
            entry.status = ProviderOutcome.Status.CANCELLED;
            entry.message = "Cancelled";
            task = entry.task;
            timeout = entry.timeout;
            entry.task = null;
            entry.timeout = null;
        }
        if (timeout != null) timeout.cancel(false);
        if (task != null) {
            task.cancel(true);
            discardQueuedTask(task);
        }
        emit();
        return true;
    }

    public void cancelAll() {
        List<String> ids = new ArrayList<>();
        synchronized (this) {
            for (Entry<T> entry : entries) if (entry.status == ProviderOutcome.Status.LOADING) {
                ids.add(entry.descriptor.id);
            }
        }
        for (String id : ids) cancel(id);
    }

    private void start(Entry<T> entry) {
        final int attempt;
        final FutureTask<T> task;
        boolean timerFailure = false;
        synchronized (this) {
            if (closed) return;
            entry.retryPending = false;
            if (entry.timeout != null) entry.timeout.cancel(false);
            entry.attempt++;
            attempt = entry.attempt;
            entry.status = ProviderOutcome.Status.LOADING;
            entry.value = null;
            entry.message = "";
            task = new FutureTask<T>(() -> progressCall == null
                    ? call.run(entry.provider)
                    : progressCall.run(entry.provider,
                            value -> publishProgress(entry, attempt, value))) {
                @Override protected void done() { onDone(entry, attempt, this); }
            };
            entry.task = task;
            try {
                entry.timeout = timer.schedule(() -> onTimeout(entry, attempt, task), timeoutMs,
                        TimeUnit.MILLISECONDS);
            } catch (RuntimeException error) {
                entry.status = ProviderOutcome.Status.FAILURE;
                entry.task = null;
                entry.timeout = null;
                entry.message = safeDiagnostic(error);
                timerFailure = true;
            }
        }
        if (timerFailure) {
            emit();
            return;
        }
        emit();
        try {
            workers.execute(task);
            // A short timeout can race with execute(): remove a task that was cancelled
            // before it reached the queue, as well as one cancelled while enqueued.
            if (task.isCancelled()) discardQueuedTask(task);
        } catch (RejectedExecutionException rejected) {
            failIfCurrent(entry, attempt, task, ProviderOutcome.Status.FAILURE,
                    "Provider is busy; retry shortly");
        } catch (RuntimeException error) {
            failIfCurrent(entry, attempt, task, ProviderOutcome.Status.FAILURE, safeDiagnostic(error));
        }
    }

    private void onTimeout(Entry<T> entry, int attempt, FutureTask<T> task) {
        boolean changed;
        synchronized (this) {
            changed = !closed && entry.attempt == attempt && entry.task == task
                    && entry.status == ProviderOutcome.Status.LOADING;
            if (changed) {
                entry.status = ProviderOutcome.Status.TIMEOUT;
                entry.message = "Timed out after " + timeoutMs + " ms";
                entry.task = null;
                entry.timeout = null;
            }
        }
        if (changed) {
            task.cancel(true);
            discardQueuedTask(task);
            emit();
        }
    }

    private void publishProgress(Entry<T> entry, int attempt, T value) {
        if (value == null) return;
        boolean changed;
        synchronized (this) {
            changed = !closed && entry.attempt == attempt
                    && entry.status == ProviderOutcome.Status.LOADING;
            if (changed) entry.value = value;
        }
        if (changed) emit();
    }

    private void onDone(Entry<T> entry, int attempt, FutureTask<T> task) {
        T value = null;
        ProviderOutcome.Status status;
        String message = "";
        try {
            value = task.get();
            status = ProviderOutcome.Status.SUCCESS;
        } catch (CancellationException error) {
            status = ProviderOutcome.Status.CANCELLED;
            message = "Cancelled";
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof CancellationException) {
                status = ProviderOutcome.Status.CANCELLED;
                message = "Cancelled";
            } else {
                status = ProviderOutcome.Status.FAILURE;
                message = safeDiagnostic(cause);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            status = ProviderOutcome.Status.CANCELLED;
            message = "Cancelled";
        }
        failIfCurrent(entry, attempt, task, status, message, value);
    }

    private void failIfCurrent(Entry<T> entry, int attempt, FutureTask<T> task,
                               ProviderOutcome.Status status, String message) {
        failIfCurrent(entry, attempt, task, status, message, null);
    }

    private void failIfCurrent(Entry<T> entry, int attempt, FutureTask<T> task,
                               ProviderOutcome.Status status, String message, T value) {
        boolean changed;
        synchronized (this) {
            changed = !closed && entry.attempt == attempt && entry.task == task
                    && entry.status == ProviderOutcome.Status.LOADING;
            if (changed) {
                if (entry.timeout != null) entry.timeout.cancel(false);
                entry.timeout = null;
                entry.task = null;
                entry.status = status;
                entry.message = message == null ? "" : message;
                // Keep the newest callback snapshot available if the provider later errors,
                // times out, or is cancelled. A successful final value still wins.
                if (value != null) entry.value = value;
            }
        }
        if (changed) emit();
    }

    private synchronized ProviderOutcome<T> outcome(Entry<T> entry) {
        return new ProviderOutcome<>(entry.descriptor, entry.order, entry.attempt,
                entry.status, entry.value, entry.message);
    }

    private void emit() {
        if (listener == null) return;
        synchronized (notificationLock) {
            List<ProviderOutcome<T>> values = snapshot();
            try { listener.onChanged(values); }
            catch (RuntimeException ignored) { /* A consumer callback must not stop provider work. */ }
        }
    }

    private static String safeDiagnostic(Throwable error) {
        if (com.auras.orbit.BuildConfig.DEBUG) {
            StringBuilder types = new StringBuilder();
            Throwable current = error;
            for (int depth = 0; current != null && depth < 4; depth++) {
                if (depth > 0) types.append(" <- ");
                types.append(current.getClass().getName());
                Throwable cause = current.getCause();
                if (cause == current) break;
                current = cause;
            }
            android.util.Log.w("OrbitProviderDetails", "Failure types: " + types);
            StackTraceElement[] frames = error.getStackTrace();
            StringBuilder locations = new StringBuilder();
            for (int index = 0; index < frames.length && index < 8; index++) {
                if (index > 0) locations.append(" <- ");
                StackTraceElement frame = frames[index];
                locations.append(frame.getClassName()).append('.')
                        .append(frame.getMethodName()).append(':').append(frame.getLineNumber());
            }
            android.util.Log.w("OrbitProviderDetails", "Failure frames: " + locations);
        }
        if (error instanceof SocketTimeoutException) return "Connection timed out";
        if (error instanceof UnknownHostException) return "Network unavailable";
        if (error instanceof SSLException) return "Secure connection failed";
        if (error instanceof IOException) return "Network request failed";
        return "Provider request failed";
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        cancelAllAfterClose();
        if (closeCallback != null) closeCallback.onClosed(this);
    }

    private void cancelAllAfterClose() {
        List<FutureTask<T>> tasks = new ArrayList<>();
        List<ScheduledFuture<?>> timeouts = new ArrayList<>();
        synchronized (this) {
            for (Entry<T> entry : entries) {
                if (entry.status == ProviderOutcome.Status.LOADING) {
                    entry.status = ProviderOutcome.Status.CANCELLED;
                    entry.message = "Cancelled";
                }
                if (entry.task != null) tasks.add(entry.task);
                if (entry.timeout != null) timeouts.add(entry.timeout);
                entry.task = null;
                entry.timeout = null;
            }
        }
        for (ScheduledFuture<?> timeout : timeouts) timeout.cancel(false);
        for (FutureTask<T> task : tasks) {
            task.cancel(true);
            discardQueuedTask(task);
        }
        emit();
    }

    /** FutureTask.cancel() alone does not unlink a queued task from ThreadPoolExecutor. */
    private void discardQueuedTask(Future<?> task) {
        if (workers instanceof ThreadPoolExecutor && task instanceof Runnable) {
            ((ThreadPoolExecutor) workers).remove((Runnable) task);
        }
    }
}