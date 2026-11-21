package com.auras.orbit.jellyfin;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.auras.orbit.jellyfin.JellyfinModels.*;
public final class JellyfinClient {
    public final AtomicInteger started = new AtomicInteger();
    public final AtomicInteger progress = new AtomicInteger();
    public final AtomicInteger stopped = new AtomicInteger();
    public Future<Void> reportPlaybackStarted(Connection connection, PlaybackEvent event) {
        started.incrementAndGet(); return CompletableFuture.completedFuture(null);
    }
    public Future<Void> reportPlaybackProgress(Connection connection, PlaybackEvent event) {
        progress.incrementAndGet(); return CompletableFuture.completedFuture(null);
    }
    public Future<Void> reportPlaybackStopped(Connection connection, PlaybackEvent event, boolean failed) {
        stopped.incrementAndGet(); return CompletableFuture.completedFuture(null);
    }
}
