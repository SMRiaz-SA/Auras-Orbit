package com.auras.orbit.player;
public final class OrbitPlayerActivity {
    public interface PlaybackObserver {
        void onPlaybackReady(PlaybackSnapshot snapshot);
        void onPlaybackStarted(PlaybackSnapshot snapshot);
        void onPlaybackProgress(PlaybackSnapshot snapshot);
        void onPlaybackError(PlaybackSnapshot snapshot);
        void onPlaybackStopped(PlaybackSnapshot snapshot, StopReason reason);
    }
    public enum StopReason { COMPLETED, USER_EXIT, FAILURE }
    public static final class PlaybackSnapshot {
        public final long positionMs;
        public final boolean isPlaying;
        public PlaybackSnapshot(long positionMs, boolean isPlaying) {
            this.positionMs = positionMs; this.isPlaying = isPlaying;
        }
    }
}
