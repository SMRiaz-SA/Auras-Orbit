package com.lagradost.cloudstream3.utils;

import java.util.Objects;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function3;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.Job;

/** Source-shaped coroutine helpers used by reachable CloudStream plugin code. */
public class Coroutines {
    private Coroutines() { super(); }
    public static final Coroutines INSTANCE = new Coroutines();

    /**
     * Matches CloudStream's `value.ioSafe { value -> ... }`: execute on IO,
     * keep the receiver value, and log/suppress failures like `launchSafe`.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Job ioSafe(final Object receiver, final Function3 work) {
        Objects.requireNonNull(work, "work");
        final CoroutineScope scope = CoroutineScopeKt.CoroutineScope(Dispatchers.getIO());
        Function2 block = new Function2() {
            @Override
            public Object invoke(Object scopeValue, Object continuationValue) {
                final Continuation completion = (Continuation) continuationValue;
                try {
                    Object result = work.invoke(
                            scopeValue,
                            receiver,
                            new SafeCompletion(completion)
                    );
                    return result == IntrinsicsKt.getCOROUTINE_SUSPENDED()
                            ? result : Unit.INSTANCE;
                } catch (Throwable failure) {
                    com.lagradost.cloudstream3.mvvm.ArchComponentExtKt.logError(failure);
                    return Unit.INSTANCE;
                }
            }
        };
        return BuildersKt.launch(
                scope,
                EmptyCoroutineContext.INSTANCE,
                CoroutineStart.DEFAULT,
                block
        );
    }

    /** Converts resumed suspend failures into the same logged, completed result as launchSafe. */
    private static final class SafeCompletion implements Continuation<Object> {
        private final Continuation completion;

        private SafeCompletion(Continuation completion) {
            this.completion = completion;
        }

        @Override
        public CoroutineContext getContext() {
            return completion.getContext();
        }

        @Override
        public void resumeWith(Object result) {
            Throwable failure = null;
            try {
                ResultKt.throwOnFailure(result);
            } catch (Throwable thrown) {
                failure = thrown;
            }
            if (failure != null) {
                com.lagradost.cloudstream3.mvvm.ArchComponentExtKt.logError(failure);
            }
            completion.resumeWith(Unit.INSTANCE);
        }
    }
}
