package com.auras.orbit.jellyfin;

import com.auras.orbit.jellyfin.JellyfinModels.Connection;
import com.auras.orbit.jellyfin.JellyfinModels.MediaItem;
import com.auras.orbit.jellyfin.JellyfinModels.PlaybackEvent;
import com.auras.orbit.jellyfin.JellyfinModels.PlaybackInfo;
import com.auras.orbit.jellyfin.JellyfinModels.PlaybackSource;
import com.auras.orbit.player.OrbitPlayerActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Reports the shared player's Jellyfin session without putting credentials in the player intent. */
public final class JellyfinPlaybackObserver implements OrbitPlayerActivity.PlaybackObserver {
    private final JellyfinClient client;
    private final Connection connection;
    private final MediaItem item;
    private final PlaybackInfo playbackInfo;
    private final PlaybackSource source;
    private final ExecutorService reporter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "orbit-jellyfin-session-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean progressQueued = new AtomicBoolean();
    private final AtomicReference<PlaybackEvent> latestProgress = new AtomicReference<>();
    private volatile boolean playerError;

    public JellyfinPlaybackObserver(JellyfinClient client, Connection connection, MediaItem item,
                                    PlaybackInfo playbackInfo, PlaybackSource source) {
        if (client == null || connection == null || item == null || playbackInfo == null || source == null) {
            throw new IllegalArgumentException("A complete Jellyfin playback session is required.");
        }
        this.client = client;
        this.connection = connection;
        this.item = item;
        this.playbackInfo = playbackInfo;
        this.source = source;
    }

    @Override public void onPlaybackReady(OrbitPlayerActivity.PlaybackSnapshot snapshot) { }

    @Override public void onPlaybackStarted(OrbitPlayerActivity.PlaybackSnapshot snapshot) {
        if (snapshot == null || terminal.get() || !started.compareAndSet(false, true)) return;
        PlaybackEvent event = event(snapshot);
        if (!enqueueReport(() -> reportStarted(event))) started.set(false);
    }

    @Override public void onPlaybackProgress(OrbitPlayerActivity.PlaybackSnapshot snapshot) {
        if (snapshot == null || terminal.get()) return;
        publishProgress(event(snapshot));
    }

    private void publishProgress(PlaybackEvent update) {
        latestProgress.set(update);
        if (terminal.get()) {
            latestProgress.compareAndSet(update, null);
            return;
        }
        scheduleProgress();
    }

    @Override public void onPlaybackError(OrbitPlayerActivity.PlaybackSnapshot snapshot) {
        playerError = true;
    }

    @Override public void onPlaybackStopped(OrbitPlayerActivity.PlaybackSnapshot snapshot,
                                            OrbitPlayerActivity.StopReason reason) {
        if (snapshot == null || !terminal.compareAndSet(false, true)) return;
        latestProgress.set(null);
        PlaybackEvent event = event(snapshot);
        boolean failed = playerError || reason == OrbitPlayerActivity.StopReason.FAILURE;
        if (!enqueueReport(() -> {
            try {
                client.reportPlaybackStopped(connection, event, failed).get(35, TimeUnit.SECONDS);
            } catch (Exception ignored) { }
            finally { reporter.shutdown(); }
        })) reporter.shutdown();
    }

    private void scheduleProgress() {
        if (!progressQueued.compareAndSet(false, true)) return;
        if (!enqueueReport(() -> {
            try {
                PlaybackEvent update = latestProgress.getAndSet(null);
                if (update != null && !terminal.get()) {
                    client.reportPlaybackProgress(connection, update).get(35, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) { }
            finally {
                progressQueued.set(false);
                if (latestProgress.get() != null && !terminal.get()) scheduleProgress();
            }
        })) {
            latestProgress.set(null);
            progressQueued.set(false);
        }
    }

    /** A stop callback can shut down the session executor between the terminal check and enqueue. */
    private boolean enqueueReport(Runnable action) {
        try {
            reporter.execute(action);
            return true;
        } catch (RejectedExecutionException closed) {
            return false;
        }
    }

    private void reportStarted(PlaybackEvent event) {
        if (terminal.get()) return;
        try {
            client.reportPlaybackStarted(connection, event).get(35, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
    }

    private PlaybackEvent event(OrbitPlayerActivity.PlaybackSnapshot snapshot) {
        long positionTicks = Math.min(Long.MAX_VALUE / 10_000L,
                Math.max(0L, snapshot.positionMs)) * 10_000L;
        String playMethod = source.supportsDirectPlay ? "DirectPlay"
                : source.supportsDirectStream ? "DirectStream" : "Transcode";
        return new PlaybackEvent(item.id, source.id, playbackInfo.playSessionId,
                positionTicks, true, !snapshot.isPlaying, false, null, null, playMethod);
    }
}
