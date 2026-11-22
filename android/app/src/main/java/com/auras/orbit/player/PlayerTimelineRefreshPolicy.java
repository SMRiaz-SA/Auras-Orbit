package com.auras.orbit.player;

final class PlayerTimelineRefreshPolicy {
    private static final long REFRESH_INTERVAL_MS = 500L;

    private PlayerTimelineRefreshPolicy() { }

    static boolean shouldRefresh(boolean playbackActive, boolean controlsVisible,
            boolean activityResumed) {
        return playbackActive && controlsVisible && activityResumed;
    }

    static long refreshIntervalMs() {
        return REFRESH_INTERVAL_MS;
    }
}
