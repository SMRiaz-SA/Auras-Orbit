package com.auras.orbit.jellyfin;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounds network work and unlinks cancelled futures before they can pin callers. */
public final class BoundedFutureExecutor implements AutoCloseable {
    private final ThreadPoolExecutor pool;

    public BoundedFutureExecutor(String name, int threads, int pending) {
        if (name == null || name.trim().isEmpty()
                || threads < 1 || threads > 16 || pending < 1 || pending > 4096) {
            throw new IllegalArgumentException("Invalid worker budget");
        }
        AtomicInteger index = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread worker = new Thread(task, name + "-" + index.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        };
        pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(pending), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Always returns a Future; overload becomes an immediately failed Future. */
    public <T> Future<T> submit(Callable<T> work) {
        if (work == null) throw new IllegalArgumentException("Missing work");
        RemovableFuture<T> task = new RemovableFuture<>(work, pool);
        try {
            pool.execute(task);
            return task;
        } catch (RejectedExecutionException busyOrClosed) {
            FutureTask<T> failed = new FutureTask<>(() -> {
                throw new IOException("Jellyfin is busy; retry this operation.");
            });
            failed.run();
            return failed;
        }
    }

    public int queuedCount() { return pool.getQueue().size(); }
    public void shutdownNow() {
        // ThreadPoolExecutor returns pending work but does not complete its Futures.
        // Settling these explicitly prevents callers from waiting on work that cannot run.
        for (Runnable pending : pool.shutdownNow()) {
            if (pending instanceof Future<?>) ((Future<?>) pending).cancel(false);
        }
    }
    @Override public void close() { shutdownNow(); }

    private static final class RemovableFuture<T> extends FutureTask<T> {
        private final ThreadPoolExecutor owner;
        RemovableFuture(Callable<T> work, ThreadPoolExecutor owner) {
            super(work);
            this.owner = owner;
        }
        @Override public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            if (cancelled) owner.remove(this);
            return cancelled;
        }
    }
}
