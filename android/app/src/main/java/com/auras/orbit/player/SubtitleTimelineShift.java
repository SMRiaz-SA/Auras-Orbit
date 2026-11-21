package com.auras.orbit.player;

import androidx.media3.common.C;

/** Bounded cue-start adjustment used by the Media3 subtitle parser adapter. */
public final class SubtitleTimelineShift {
    public static final long MAX_DELAY_MS = 10_000L;

    private SubtitleTimelineShift() { }

    public static long normalizeDelayMs(long delayMs) {
        return Math.max(-MAX_DELAY_MS, Math.min(MAX_DELAY_MS, delayMs));
    }

    /** Positive delay moves a cue later; negative delay moves it earlier, never before time zero. */
    public static long shiftStartUs(long startTimeUs, long delayMs) {
        if (isTimeSentinel(startTimeUs)) return startTimeUs;
        long shiftUs = normalizeDelayMs(delayMs) * 1_000L;
        if (shiftUs < 0 && startTimeUs < -shiftUs) return 0L;
        if (shiftUs > 0 && startTimeUs > Long.MAX_VALUE - shiftUs) return Long.MAX_VALUE;
        return Math.max(0L, startTimeUs + shiftUs);
    }

    /**
     * Shifts a finite cue interval by adjusting both endpoints. When an early cue is clamped to
     * timeline zero, its duration shrinks by the portion that would have started before zero.
     * Media3 uses negative duration values for unset/indefinite sentinels; those stay unchanged.
     */
    public static long shiftDurationUs(long startTimeUs, long durationUs, long delayMs) {
        if (isTimeSentinel(startTimeUs) || durationUs < 0L) return durationUs;
        long normalizedDelayMs = normalizeDelayMs(delayMs);
        long endTimeUs = addSaturated(startTimeUs, durationUs);
        long shiftedStartUs = shiftStartUs(startTimeUs, normalizedDelayMs);
        long shiftedEndUs = shiftStartUs(endTimeUs, normalizedDelayMs);
        return shiftedEndUs <= shiftedStartUs ? 0L : shiftedEndUs - shiftedStartUs;
    }

    private static boolean isTimeSentinel(long timeUs) {
        return timeUs == C.TIME_UNSET || timeUs == C.TIME_END_OF_SOURCE;
    }

    private static long addSaturated(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0L && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }
}
