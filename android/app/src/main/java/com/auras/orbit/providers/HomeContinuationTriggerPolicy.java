package com.auras.orbit.providers;

import java.util.Set;

/** Pure gate for scroll-triggered provider Home continuation. */
public final class HomeContinuationTriggerPolicy {
    private HomeContinuationTriggerPolicy() { }

    public static boolean isHomeContinuationContent(boolean homeRoute,
            boolean providerTitleDetailsOpen) {
        return homeRoute && !providerTitleDetailsOpen;
    }

    public static boolean shouldRequest(boolean hasCursor, boolean loading, boolean failed,
            boolean nearBottom, boolean userScroll, long gestureId, long lastTriggeredGestureId) {
        return hasCursor && !loading && !failed && nearBottom && userScroll
                && gestureId >= 0L && gestureId != lastTriggeredGestureId;
    }

    public static boolean keepDownwardGestureActiveAfterRelease(boolean homeRoute,
            boolean movedDownward) {
        return homeRoute && movedDownward;
    }

    public static boolean shouldBeginGestureTracking(boolean homeRoute, boolean actionDown) {
        return homeRoute && actionDown;
    }

    public static boolean isCapturedDownwardScroll(boolean homeRoute, boolean gesturePending,
            long eventGestureId, long activeGestureId, int scrollY, int oldScrollY) {
        return homeRoute && gesturePending && eventGestureId >= 0L
                && eventGestureId == activeGestureId && scrollY > oldScrollY;
    }

    public static boolean shouldTriggerForHomeGesture(boolean homeRoute, boolean nearBottom,
            boolean positiveVerticalScroll, boolean verticalRelease, float deltaX, float deltaY,
            float touchSlop) {
        return homeRoute && nearBottom && (positiveVerticalScroll
                || (verticalRelease && isVerticalHomeDrag(homeRoute, deltaX, deltaY, touchSlop)));
    }

    public static boolean isVerticalHomeDrag(boolean homeRoute, float deltaX, float deltaY,
            float touchSlop) {
        return homeRoute && touchSlop >= 0f && deltaY < -touchSlop
                && Math.abs(deltaY) > Math.abs(deltaX);
    }

    public static boolean shouldContinueAfterPage(int addedSections, String requestedCursor,
            String nextCursor, Set<String> consumedCursors) {
        return addedSections > 0 && nextCursor != null && !nextCursor.isEmpty()
                && !nextCursor.equals(requestedCursor)
                && (consumedCursors == null || !consumedCursors.contains(nextCursor));
    }
}
