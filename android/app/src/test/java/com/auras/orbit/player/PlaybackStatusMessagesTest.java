package com.auras.orbit.player;

import androidx.media3.common.PlaybackException;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class PlaybackStatusMessagesTest {
    @Test public void unsupportedFormatAndPreparationRemainVisibleTogether() {
        Class<?> messages = statusMessagesClass();
        String failure = (String) invoke(messages, "sourceFailureMessage", new Class<?>[]{int.class},
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES);

        assertEquals("This source format isn't supported on this device. Trying the next source…", failure);
        assertEquals(failure + "\nPreparing playback…",
                invoke(messages, "sourceLoadingMessage", new Class<?>[]{String.class}, failure));
    }

    @Test public void unsupportedCodecUsesTheSameClearRetryMessage() {
        Class<?> messages = statusMessagesClass();
        String failure = (String) invoke(messages, "sourceFailureMessage", new Class<?>[]{int.class},
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED);

        assertEquals("This source format isn't supported on this device. Trying the next source…", failure);
        assertEquals(failure + "\nPreparing playback…",
                invoke(messages, "sourceLoadingMessage", new Class<?>[]{String.class}, failure));
    }

    @Test public void otherFailuresKeepGenericRetryAndInitialLoadsStayUnchanged() {
        Class<?> messages = statusMessagesClass();
        String failure = (String) invoke(messages, "sourceFailureMessage", new Class<?>[]{int.class},
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
        assertEquals("This source failed. Trying the next available source…", failure);
        assertEquals(failure + "\nPreparing playback…",
                invoke(messages, "sourceLoadingMessage", new Class<?>[]{String.class}, failure));
        assertEquals("Preparing playback…",
                invoke(messages, "sourceLoadingMessage", new Class<?>[]{String.class}, (Object) null));
        assertEquals(failure + "\nStill looking for links…",
                invoke(messages, "waitingForNextSource", new Class<?>[]{String.class}, failure));
    }

    private static Class<?> statusMessagesClass() {
        try {
            return Class.forName("com.auras.orbit.player.PlaybackStatusMessages");
        } catch (ClassNotFoundException missingImplementation) {
            fail("PlaybackStatusMessages must keep source failure and preparation visible together");
            throw new AssertionError("unreachable after JUnit fail");
        }
    }

    private static Object invoke(Class<?> type, String methodName, Class<?>[] parameterTypes,
            Object... arguments) {
        try {
            Method method = type.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (ReflectiveOperationException missingBehavior) {
            throw new AssertionError("PlaybackStatusMessages must implement " + methodName,
                    missingBehavior instanceof InvocationTargetException
                            ? ((InvocationTargetException) missingBehavior).getCause()
                            : missingBehavior);
        }
    }
}
