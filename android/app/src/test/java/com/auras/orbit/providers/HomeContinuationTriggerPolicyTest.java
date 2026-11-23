package com.auras.orbit.providers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.Test;

public final class HomeContinuationTriggerPolicyTest {
    @Test public void providerDetailsContentDoesNotOwnHomeContinuation() {
        assertTrue(HomeContinuationTriggerPolicy.isHomeContinuationContent(true, false));
        assertFalse(HomeContinuationTriggerPolicy.isHomeContinuationContent(true, true));
        assertFalse(HomeContinuationTriggerPolicy.isHomeContinuationContent(false, false));
    }

    @Test public void renderRestoreAndRecompositionDoNotTriggerAContinuation() {
        assertFalse(HomeContinuationTriggerPolicy.shouldRequest(
                true, false, false, true, false, 5L, -1L));
    }

    @Test public void nearBottomDownwardGestureTriggersOnlyOncePerGesture() {
        assertTrue(HomeContinuationTriggerPolicy.shouldRequest(
                true, false, false, true, true, 7L, -1L));
        assertFalse(HomeContinuationTriggerPolicy.shouldRequest(
                true, false, false, true, true, 7L, 7L));
        assertTrue(HomeContinuationTriggerPolicy.shouldRequest(
                true, false, false, true, true, 8L, 7L));
    }

    @Test public void loadingFailureNoCursorOrAwayFromBottomBlocksAutomaticRequest() {
        assertFalse(HomeContinuationTriggerPolicy.shouldRequest(
                false, false, false, true, true, 1L, -1L));
        assertFalse(HomeContinuationTriggerPolicy.shouldRequest(
                true, true, false, true, true, 1L, -1L));
        assertFalse(HomeContinuationTriggerPolicy.shouldRequest(
                true, false, true, true, true, 1L, -1L));
        assertFalse(HomeContinuationTriggerPolicy.shouldRequest(
                true, false, false, false, true, 1L, -1L));
    }

    @Test public void cursorAlreadyConsumedByEarlierPageStopsContinuationCycle() {
        assertFalse(HomeContinuationTriggerPolicy.shouldContinueAfterPage(
                1, "cursor-b", "cursor-a", new HashSet<>(Arrays.asList("cursor-a", "cursor-b"))));
        assertTrue(HomeContinuationTriggerPolicy.shouldContinueAfterPage(
                1, "cursor-b", "cursor-c", new HashSet<>(Arrays.asList("cursor-a", "cursor-b"))));
        assertFalse(HomeContinuationTriggerPolicy.shouldContinueAfterPage(
                0, "cursor-b", "cursor-c", Collections.emptySet()));
    }

    @Test public void downwardGestureRemainsEligibleForBottomReachedDuringFling() {
        boolean activeAfterRelease = HomeContinuationTriggerPolicy
                .keepDownwardGestureActiveAfterRelease(true, true);
        assertTrue(activeAfterRelease);
        assertTrue(HomeContinuationTriggerPolicy.shouldRequest(
                true, false, false, true, activeAfterRelease, 9L, -1L));
        assertFalse(HomeContinuationTriggerPolicy.shouldRequest(
                true, false, false, true, activeAfterRelease, 9L, 9L));
        assertFalse(HomeContinuationTriggerPolicy.keepDownwardGestureActiveAfterRelease(false, true));
    }

    @Test public void touchStartIsCapturedForHomeBeforeChildDispatch() {
        assertTrue(HomeContinuationTriggerPolicy.shouldBeginGestureTracking(true, true));
        assertFalse(HomeContinuationTriggerPolicy.shouldBeginGestureTracking(false, true));
        assertFalse(HomeContinuationTriggerPolicy.shouldBeginGestureTracking(true, false));
    }

    @Test public void onlyPositiveVerticalScrollFromCapturedGestureCanTrigger() {
        assertTrue(HomeContinuationTriggerPolicy.isCapturedDownwardScroll(
                true, true, 12L, 12L, 101, 100));
        assertFalse(HomeContinuationTriggerPolicy.isCapturedDownwardScroll(
                true, true, 12L, 12L, 100, 100));
        assertFalse(HomeContinuationTriggerPolicy.isCapturedDownwardScroll(
                true, true, 11L, 12L, 101, 100));
        assertFalse(HomeContinuationTriggerPolicy.isCapturedDownwardScroll(
                true, false, 12L, 12L, 101, 100));
    }

    @Test public void bottomVerticalDragCanLoadWithoutScrollButHorizontalSwipeCannot() {
        assertTrue(HomeContinuationTriggerPolicy.isVerticalHomeDrag(true, 2f, -24f, 8f));
        assertFalse(HomeContinuationTriggerPolicy.isVerticalHomeDrag(true, 2f, 24f, 8f));
        assertTrue(HomeContinuationTriggerPolicy.shouldTriggerForHomeGesture(
                true, true, false, true, 2f, -24f, 8f));
        assertFalse(HomeContinuationTriggerPolicy.shouldTriggerForHomeGesture(
                true, true, false, true, 2f, 24f, 8f));
        assertFalse(HomeContinuationTriggerPolicy.shouldTriggerForHomeGesture(
                true, true, false, true, 24f, -2f, 8f));
        assertFalse(HomeContinuationTriggerPolicy.shouldTriggerForHomeGesture(
                true, false, false, true, 2f, -24f, 8f));
        assertFalse(HomeContinuationTriggerPolicy.shouldTriggerForHomeGesture(
                false, true, false, true, 2f, -24f, 8f));
        assertTrue(HomeContinuationTriggerPolicy.shouldTriggerForHomeGesture(
                true, true, true, false, 24f, -2f, 8f));
    }
}
