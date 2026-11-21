package com.auras.orbit.player;

/** Monotonic, in-process lifetime rule for one-time player handoff tokens. */
public final class PlaybackHandoffExpiry {
    private PlaybackHandoffExpiry() { }

    public static boolean isExpired(long createdElapsedMs, long nowElapsedMs, long ttlMs) {
        if (createdElapsedMs < 0 || nowElapsedMs < 0 || ttlMs < 0) return true;
        // The elapsed-realtime clock cannot roll backwards during one process lifetime.
        // If an invalid timestamp slips through, fail closed rather than retain secrets.
        if (nowElapsedMs < createdElapsedMs) return true;
        return nowElapsedMs - createdElapsedMs > ttlMs;
    }
}
