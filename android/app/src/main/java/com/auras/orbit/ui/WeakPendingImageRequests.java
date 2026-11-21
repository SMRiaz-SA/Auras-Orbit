package com.auras.orbit.ui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Bounded per-key image listeners that cannot strongly retain obsolete screen views. */
public final class WeakPendingImageRequests<T> {
    private final int maxWaitersPerKey;
    private final Map<String, ArrayList<WeakReference<T>>> pending = new HashMap<>();

    public WeakPendingImageRequests(int maxWaitersPerKey) {
        if (maxWaitersPerKey < 1 || maxWaitersPerKey > 1024) {
            throw new IllegalArgumentException("Invalid per-image waiter limit");
        }
        this.maxWaitersPerKey = maxWaitersPerKey;
    }

    /** Returns true only to the caller that must schedule the first worker for this key. */
    public synchronized boolean startOrJoin(String key, T view) {
        if (key == null || key.isEmpty() || view == null) {
            throw new IllegalArgumentException("Image key and view are required");
        }
        ArrayList<WeakReference<T>> waiters = pending.get(key);
        if (waiters == null) {
            waiters = new ArrayList<>();
            waiters.add(new WeakReference<>(view));
            pending.put(key, waiters);
            return true;
        }
        boolean alreadyWaiting = false;
        for (Iterator<WeakReference<T>> it = waiters.iterator(); it.hasNext();) {
            T previous = it.next().get();
            if (previous == null) it.remove();
            else if (previous == view) alreadyWaiting = true;
        }
        if (!alreadyWaiting && waiters.size() < maxWaitersPerKey) {
            waiters.add(new WeakReference<>(view));
        }
        return false;
    }

    /** Completes the key and returns only live views. A second completion returns no views. */
    public synchronized List<T> complete(String key) {
        ArrayList<WeakReference<T>> waiters = pending.remove(key);
        if (waiters == null) return Collections.emptyList();
        ArrayList<T> live = new ArrayList<>(waiters.size());
        for (WeakReference<T> waiter : waiters) {
            T view = waiter.get();
            if (view != null) live.add(view);
        }
        return live;
    }

    public synchronized void cancel(String key) { pending.remove(key); }
    public synchronized int pendingCount() { return pending.size(); }
    public synchronized int liveWaiterCount(String key) {
        ArrayList<WeakReference<T>> waiters = pending.get(key);
        if (waiters == null) return 0;
        int count = 0;
        for (WeakReference<T> waiter : waiters) if (waiter.get() != null) count++;
        return count;
    }
}
