package com.auras.orbit.ui;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed-size background executor with a hard cap on pending UI work. No Android dependency. */
public final class BoundedTaskExecutor implements AutoCloseable {
    private final ThreadPoolExecutor pool;

    public BoundedTaskExecutor(String threadName, int concurrency, int maxPending) {
        if (threadName == null || threadName.trim().isEmpty()
                || concurrency < 1 || concurrency > 16 || maxPending < 1 || maxPending > 4096) {
            throw new IllegalArgumentException("Invalid bounded background executor settings");
        }
        AtomicInteger threadId = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread worker = new Thread(task, threadName + "-" + threadId.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        };
        pool = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxPending), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Returns false rather than accumulating unlimited references when callers overload the queue. */
    public boolean submit(Runnable task) {
        if (task == null) throw new IllegalArgumentException("Task is required");
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException saturatedOrClosed) {
            return false;
        }
    }

    public int queuedCount() { return pool.getQueue().size(); }

    @Override public void close() { pool.shutdownNow(); }
}
