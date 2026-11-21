package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.C;

import org.junit.Test;

public final class SubtitleTimelineShiftTest {
    @Test public void positiveDelayMovesCueStartLater() {
        assertEquals(2_500_000L, SubtitleTimelineShift.shiftStartUs(1_500_000L, 1_000L));
    }

    @Test public void negativeDelayMovesCueStartEarlierButNeverBeforeTimelineZero() {
        assertEquals(0L, SubtitleTimelineShift.shiftStartUs(500_000L, -1_000L));
        assertEquals(1_500_000L, SubtitleTimelineShift.shiftStartUs(2_500_000L, -1_000L));
    }

    @Test public void delayIsBoundedToTenSecondsInEitherDirection() {
        assertEquals(11_000_000L, SubtitleTimelineShift.shiftStartUs(1_000_000L, 60_000L));
        assertEquals(0L, SubtitleTimelineShift.shiftStartUs(1_000_000L, -60_000L));
    }

    @Test public void negativeDelayClampsBothCueEndpointsAtTimelineZero() {
        assertEquals(0L, SubtitleTimelineShift.shiftStartUs(500_000L, -1_000L));
        assertEquals(500_000L,
                SubtitleTimelineShift.shiftDurationUs(500_000L, 1_000_000L, -1_000L));
        assertEquals(1_000_000L,
                SubtitleTimelineShift.shiftDurationUs(2_500_000L, 1_000_000L, -1_000L));
        assertEquals(1_000_000L,
                SubtitleTimelineShift.shiftDurationUs(500_000L, 1_000_000L, 1_000L));
    }

    @Test public void unsetAndIndefiniteCueTimesRemainUnchanged() {
        assertEquals(C.TIME_UNSET, SubtitleTimelineShift.shiftStartUs(C.TIME_UNSET, -1_000L));
        assertEquals(C.TIME_END_OF_SOURCE,
                SubtitleTimelineShift.shiftStartUs(C.TIME_END_OF_SOURCE, -1_000L));
        assertEquals(C.TIME_UNSET,
                SubtitleTimelineShift.shiftDurationUs(500_000L, C.TIME_UNSET, -1_000L));
        assertEquals(C.TIME_END_OF_SOURCE,
                SubtitleTimelineShift.shiftDurationUs(500_000L, C.TIME_END_OF_SOURCE, -1_000L));
        assertEquals(1_000_000L,
                SubtitleTimelineShift.shiftDurationUs(C.TIME_UNSET, 1_000_000L, -1_000L));
    }

    @Test public void finiteNegativeCueStartsStillShiftAndClamp() {
        assertEquals(0L, SubtitleTimelineShift.shiftStartUs(-500_000L, 0L));
        assertEquals(500_000L, SubtitleTimelineShift.shiftStartUs(-500_000L, 1_000L));
        assertEquals(500_000L,
                SubtitleTimelineShift.shiftDurationUs(-500_000L, 1_000_000L, 0L));
    }
}
