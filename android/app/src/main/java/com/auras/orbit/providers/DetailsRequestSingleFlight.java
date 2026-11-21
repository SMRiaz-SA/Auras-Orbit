package com.auras.orbit.providers;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Shares an in-flight Details call between subscribers to the *same live provider object*.
 * No completed response, plugin action or provider instance is cached by this coordinator.
 * Closing one waiter interrupts only that wait; an owner failure is delivered to all waiters
 * and is immediately retryable. The provider reference prevents crossing plugin generations.
 */
public final class DetailsRequestSingleFlight<T> {
    private final ConcurrentHashMap<Key, CompletableFuture<T>> active = new ConcurrentHashMap<>();

    public T execute(Object providerInstance, String profileScope, String providerItemId,
                     Callable<T> operation) throws Exception {
        return execute(providerInstance, profileScope, providerItemId, operation, 0L);
    }

    /** A timed subscriber may abandon its wait without cancelling the common provider call. */
    public T execute(Object providerInstance, String profileScope, String providerItemId,
                     Callable<T> operation, long subscriberTimeoutMs) throws Exception {
        if (subscriberTimeoutMs < 0) throw new IllegalArgumentException("negative timeout");
        Objects.requireNonNull(providerInstance, "provider instance");
        Objects.requireNonNull(providerItemId, "provider item id");
        Objects.requireNonNull(operation, "operation");
        final Key key = new Key(providerInstance, profileScope, providerItemId);
        CompletableFuture<T> ours = new CompletableFuture<>();
        CompletableFuture<T> shared = active.putIfAbsent(key, ours);
        if (shared == null) {
            try {
                T result = operation.call();
                if (result == null) throw new IllegalStateException("Provider returned null Details");
                ours.complete(result);
                return result;
            } catch (Throwable failure) {
                ours.completeExceptionally(failure);
                if (failure instanceof Exception) throw (Exception) failure;
                throw (Error) failure;
            } finally {
                active.remove(key, ours);
            }
        }
        try {
            return subscriberTimeoutMs == 0L ? shared.get()
                    : shared.get(subscriberTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Provider Details request failed", cause);
        }
    }

    public int activeCount() { return active.size(); }

    private static final class Key {
        private final Object provider;
        private final String profile;
        private final String item;
        private Key(Object provider, String profile, String item) {
            this.provider = provider;
            this.profile = profile == null ? "" : profile;
            this.item = item;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return provider == key.provider && profile.equals(key.profile) && item.equals(key.item);
        }
        @Override public int hashCode() {
            return 31 * (31 * System.identityHashCode(provider) + profile.hashCode()) + item.hashCode();
        }
    }
}
