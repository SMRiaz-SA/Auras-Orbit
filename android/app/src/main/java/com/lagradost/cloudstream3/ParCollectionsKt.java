package com.lagradost.cloudstream3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.coroutines.jvm.internal.ContinuationImpl;
import kotlin.ResultKt;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function3;
import kotlin.jvm.functions.Function1;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.Deferred;
import kotlinx.coroutines.JobKt;

/** Orbit-owned static facade implementing the pinned CS3 concurrent collection helpers. */
public class ParCollectionsKt {
    private ParCollectionsKt() { super(); }

    public static <K, V, R> java.lang.Object amap(final java.util.Map<? extends K, V> values,
            final kotlin.jvm.functions.Function2<? super Map.Entry<K, V>,
                    ? super kotlin.coroutines.Continuation<? super R>, ? extends Object> transform,
            kotlin.coroutines.Continuation<? super java.util.List<R>> continuation) {
        requireArgument(values, "<this>");
        requireArgument(transform, "f");
        return CoroutineScopeKt.coroutineScope(
                new Function2<CoroutineScope, Continuation<? super java.util.List<R>>, Object>() {
            @Override public Object invoke(CoroutineScope scope,
                                           Continuation<? super java.util.List<R>> completion) {
                return asyncMapEntriesInScope(scope, values, transform, completion, true);
            }
        }, continuation);
    }

    public static <A, B> java.lang.Object amap(final java.util.List<? extends A> values,
            final kotlin.jvm.functions.Function2<? super A,
                    ? super kotlin.coroutines.Continuation<? super B>, ? extends Object> transform,
            kotlin.coroutines.Continuation<? super java.util.List<B>> continuation) {
        requireArgument(values, "<this>");
        requireArgument(transform, "f");
        return CoroutineScopeKt.coroutineScope(
                new Function2<CoroutineScope, Continuation<? super java.util.List<B>>, Object>() {
            @Override public Object invoke(CoroutineScope scope,
                                           Continuation<? super java.util.List<B>> completion) {
                return asyncMapInScope(scope, values, transform, null, completion, true);
            }
        }, continuation);
    }

    public static <A, B> java.lang.Object amapIndexed(final java.util.List<? extends A> values,
            final kotlin.jvm.functions.Function3<? super Integer, ? super A,
                    ? super kotlin.coroutines.Continuation<? super B>, ? extends Object> transform,
            kotlin.coroutines.Continuation<? super java.util.List<B>> continuation) {
        requireArgument(values, "<this>");
        requireArgument(transform, "f");
        return CoroutineScopeKt.coroutineScope(
                new Function2<CoroutineScope, Continuation<? super java.util.List<B>>, Object>() {
            @Override public Object invoke(CoroutineScope scope,
                                           Continuation<? super java.util.List<B>> completion) {
                return asyncMapInScope(scope, values, null, transform, completion, true);
            }
        }, continuation);
    }

    public static <A, B> java.util.List<B> apmap(final java.util.List<? extends A> values,
            final kotlin.jvm.functions.Function2<? super A,
                    ? super kotlin.coroutines.Continuation<? super B>, ? extends Object> transform) {
        requireArgument(values, "<this>");
        requireArgument(transform, "f");
        return runBlockingTyped(new Function2<CoroutineScope, Continuation<? super java.util.List<B>>, Object>() {
            @Override public Object invoke(CoroutineScope scope,
                                           Continuation<? super java.util.List<B>> completion) {
                return asyncMapInScope(scope, values, transform, null, completion, false);
            }
        });
    }

    public static <K, V, R> java.util.List<R> apmap(final java.util.Map<? extends K, V> values,
            final kotlin.jvm.functions.Function2<? super Map.Entry<K, V>,
                    ? super kotlin.coroutines.Continuation<? super R>, ? extends Object> transform) {
        requireArgument(values, "<this>");
        requireArgument(transform, "f");
        return runBlockingTyped(new Function2<CoroutineScope, Continuation<? super java.util.List<R>>, Object>() {
            @Override public Object invoke(CoroutineScope scope,
                                           Continuation<? super java.util.List<R>> completion) {
                return asyncMapEntriesInScope(scope, values, transform, completion, false);
            }
        });
    }

    public static <A, B> java.util.List<B> apmapIndexed(final java.util.List<? extends A> values,
            final kotlin.jvm.functions.Function3<? super Integer, ? super A,
                    ? super kotlin.coroutines.Continuation<? super B>, ? extends Object> transform) {
        requireArgument(values, "<this>");
        requireArgument(transform, "f");
        return runBlockingTyped(new Function2<CoroutineScope, Continuation<? super java.util.List<B>>, Object>() {
            @Override public Object invoke(CoroutineScope scope,
                                           Continuation<? super java.util.List<B>> completion) {
                return asyncMapInScope(scope, values, null, transform, completion, false);
            }
        });
    }

    private static <K, V, R> Object asyncMapEntriesInScope(CoroutineScope scope,
            Map<? extends K, V> values,
            Function2<? super Map.Entry<K, V>,
                    ? super Continuation<? super R>, ? extends Object> transform,
            Continuation<? super List<R>> completion, boolean ensureActive) {
        if (ensureActive) JobKt.ensureActive(scope.getCoroutineContext());
        List<Map.Entry<? extends K, V>> entries = new ArrayList<>(values.entrySet());
        return asyncMapInScope(scope, entries, transform, null, completion, false);
    }

    @SafeVarargs
    public static <R> java.util.List<@Nullable R> argamap(
            final kotlin.jvm.functions.Function1<? super Continuation<? super R>, ? extends Object>... transforms) {
        requireArgument(transforms, "transforms");
        return runBlockingTyped(new Function2<CoroutineScope,
                Continuation<? super java.util.List<@Nullable R>>, Object>() {
            @Override public Object invoke(CoroutineScope scope,
                    Continuation<? super java.util.List<@Nullable R>> completion) {
                return asyncFunctionsInScope(scope, transforms,
                        completion, false, true);
            }
        });
    }

    /**
     * Pinned Kotlin source declares a vararg before its suspend continuation. Java requires a
     * varargs parameter to be last, so retain the array formal here to preserve that suspend ABI.
     */
    public static <R> java.lang.Object runAllAsync(
            final kotlin.jvm.functions.Function1<? super Continuation<? super R>, ? extends Object>[] transforms,
            kotlin.coroutines.Continuation<? super java.util.List<@Nullable R>> continuation) {
        requireArgument(transforms, "transforms");
        return CoroutineScopeKt.coroutineScope(
                new Function2<CoroutineScope,
                        Continuation<? super java.util.List<@Nullable R>>, Object>() {
            @Override public Object invoke(CoroutineScope scope,
                    Continuation<? super java.util.List<@Nullable R>> completion) {
                return asyncFunctionsInScope(scope, transforms, completion, true, true);
            }
        }, continuation);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object asyncMapInScope(final CoroutineScope scope, List values,
            final Function2 transform, final Function3 indexedTransform, Continuation completion,
            boolean ensureActive) {
        if (ensureActive) JobKt.ensureActive(scope.getCoroutineContext());

        ArrayList<Deferred<?>> deferreds = new ArrayList<>(values.size());
        Iterator<?> iterator = values.iterator();
        int index = 0;
        while (iterator.hasNext()) {
            final int itemIndex = index++;
            final Object item = iterator.next();
            deferreds.add(BuildersKt.async(scope, EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT,
                    new Function2() {
                        @Override public Object invoke(Object childScope, Object childCompletion) {
                            if (indexedTransform != null) {
                                return indexedTransform.invoke(itemIndex, item, childCompletion);
                            }
                            return transform.invoke(item, childCompletion);
                        }
                    }));
        }
        return awaitInInputOrder(deferreds, completion);
    }

    private static <R> Object asyncFunctionsInScope(final CoroutineScope scope,
            Function1<? super Continuation<? super R>, ? extends Object>[] transforms,
            Continuation<? super java.util.List<@Nullable R>> completion,
            boolean ensureActive, boolean catchFailures) {
        if (ensureActive) JobKt.ensureActive(scope.getCoroutineContext());

        ArrayList<Deferred<R>> deferreds = new ArrayList<>(transforms.length);
        for (final Function1<? super Continuation<? super R>, ? extends Object> transform : transforms) {
            deferreds.add(BuildersKt.async(scope, EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT,
                    new Function2<CoroutineScope, Continuation<? super R>, Object>() {
                        @Override public Object invoke(CoroutineScope childScope,
                                                       Continuation<? super R> childCompletion) {
                            if (!catchFailures) return transform.invoke(childCompletion);
                            return invokeCatchingException(transform, childCompletion);
                        }
                    }));
        }
        return awaitInInputOrder(deferreds, completion);
    }

    private static <R> Object invokeCatchingException(
            Function1<? super Continuation<? super R>, ? extends Object> transform,
            Continuation<? super R> completion) {
        try {
            return transform.invoke(new ExceptionAsNullContinuation<>(completion));
        } catch (Exception failure) {
            com.lagradost.cloudstream3.mvvm.ArchComponentExtKt.logError(failure);
            return null;
        }
    }

    /** Applies the source try/catch when a suspend lambda fails after its initial invocation. */
    private static final class ExceptionAsNullContinuation<R> implements Continuation<R> {
        private final Continuation<? super R> completion;

        ExceptionAsNullContinuation(Continuation<? super R> completion) {
            this.completion = completion;
        }

        @Override public CoroutineContext getContext() {
            return completion.getContext();
        }

        @Override public void resumeWith(Object result) {
            Object forwarded = result;
            try {
                ResultKt.throwOnFailure(result);
            } catch (Throwable failure) {
                if (failure instanceof Exception) {
                    try {
                        com.lagradost.cloudstream3.mvvm.ArchComponentExtKt.logError(failure);
                        forwarded = null;
                    } catch (Throwable loggingFailure) {
                        forwarded = ResultKt.createFailure(loggingFailure);
                    }
                }
            }
            completion.resumeWith(forwarded);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object awaitInInputOrder(List<? extends Deferred<?>> deferreds,
                                            Continuation<?> completion) {
        return new InputOrderedAwait(deferreds, completion).start();
    }

    /** Mirrors map { deferred.await() }: later child cancellation is observed in input order. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class InputOrderedAwait extends ContinuationImpl {
        private final List<? extends Deferred<?>> deferreds;
        private final ArrayList<Object> values;
        private int index;

        InputOrderedAwait(List<? extends Deferred<?>> deferreds, Continuation completion) {
            super(completion);
            this.deferreds = deferreds;
            this.values = new ArrayList<>(deferreds.size());
        }

        Object start() {
            return awaitNext();
        }

        private Object awaitNext() {
            while (index < deferreds.size()) {
                Object value = deferreds.get(index).await(this);
                if (value == IntrinsicsKt.getCOROUTINE_SUSPENDED()) return value;
                values.add(value);
                index++;
            }
            return values;
        }

        @Override protected Object invokeSuspend(Object result) {
            ResultKt.throwOnFailure(result);
            values.add(result);
            index++;
            return awaitNext();
        }
    }

    private static <T> T runBlockingTyped(
            Function2<? super CoroutineScope, ? super Continuation<? super T>, ? extends Object> block) {
        try {
            return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, block);
        } catch (InterruptedException interrupted) {
            return ParCollectionsKt.<T, RuntimeException>throwUnchecked(interrupted);
        }
    }

    /** Preserves the original private erased descriptor used by existing compiled callers. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List runBlocking(Function2 block) {
        try {
            return (List) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, block);
        } catch (InterruptedException interrupted) {
            return ParCollectionsKt.<List, RuntimeException>throwUnchecked(interrupted);
        }
    }

    private static void requireArgument(Object value, String name) {
        if (value == null) throw new NullPointerException(name);
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T throwUnchecked(Throwable failure) throws E {
        throw (E) failure;
    }
}
