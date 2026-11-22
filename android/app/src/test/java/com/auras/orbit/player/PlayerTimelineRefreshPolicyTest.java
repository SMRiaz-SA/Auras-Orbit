package com.auras.orbit.player;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class PlayerTimelineRefreshPolicyTest {
    @Test public void refreshRunsOnlyDuringVisiblePlaybackInResumedActivity() {
        Class<?> policy = policyClass();

        assertTrue(shouldRefresh(policy, true, true, true));
        assertFalse(shouldRefresh(policy, false, true, true));
        assertFalse(shouldRefresh(policy, true, false, true));
        assertFalse(shouldRefresh(policy, true, true, false));
    }

    @Test public void uiPositionRefreshIntervalIsFiveHundredMilliseconds() {
        assertEquals(500L, refreshIntervalMs(policyClass()));
    }

    private static Class<?> policyClass() {
        try {
            return Class.forName("com.auras.orbit.player.PlayerTimelineRefreshPolicy");
        } catch (ClassNotFoundException missingImplementation) {
            fail("PlayerTimelineRefreshPolicy must define the timeline refresh cadence and eligibility");
            throw new AssertionError("unreachable after JUnit fail");
        }
    }

    private static boolean shouldRefresh(Class<?> policy, boolean playbackActive,
            boolean controlsVisible, boolean activityResumed) {
        return (Boolean) invoke(policy, "shouldRefresh",
                new Class<?>[]{boolean.class, boolean.class, boolean.class},
                playbackActive, controlsVisible, activityResumed);
    }

    private static long refreshIntervalMs(Class<?> policy) {
        return (Long) invoke(policy, "refreshIntervalMs", new Class<?>[0]);
    }

    private static Object invoke(Class<?> policy, String methodName, Class<?>[] parameterTypes,
            Object... arguments) {
        try {
            Method method = policy.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (ReflectiveOperationException missingBehavior) {
            throw new AssertionError("PlayerTimelineRefreshPolicy must implement " + methodName,
                    missingBehavior instanceof InvocationTargetException
                            ? ((InvocationTargetException) missingBehavior).getCause()
                            : missingBehavior);
        }
    }
}
