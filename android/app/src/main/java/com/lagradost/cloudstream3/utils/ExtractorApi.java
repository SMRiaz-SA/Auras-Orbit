package com.lagradost.cloudstream3.utils;

import com.lagradost.cloudstream3.SubtitleFile;

import java.util.List;

import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;

/**
 * Orbit-owned CS3 extractor base surface, implemented against the pinned
 * Cloudstream pre-release contract. Extractors run only when a caller invokes
 * one of these methods; this class does not perform URL or network resolution.
 */
public abstract class ExtractorApi {
    private volatile String sourcePlugin;

    public ExtractorApi() { }

    public String getName() { return getClass().getSimpleName(); }
    public String getMainUrl() { return ""; }
    public boolean getRequiresReferer() { return false; }

    public String getSourcePlugin() { return sourcePlugin; }
    public void setSourcePlugin(String value) { sourcePlugin = value; }

    /**
     * Source-shaped callback overload; legacy subclasses may override the list form.
     * Keep the bridge continuation wildcarded: typing it to Unit makes Kotlin treat
     * this Java method as a suspend declaration and hides the explicit parameter.
     */
    public Object getUrl(String url, String referer,
                         Function1<? super SubtitleFile, Unit> subtitleCallback,
                         Function1<? super ExtractorLink, Unit> callback,
                         Continuation<?> continuation) {
        if (url == null) throw new NullPointerException("url");
        if (subtitleCallback == null) throw new NullPointerException("subtitleCallback");
        if (callback == null) throw new NullPointerException("callback");
        Continuation<List<ExtractorLink>> completion = linksCompletion(continuation, callback);
        Object result = getUrl(url, referer, completion);
        if (result == kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()) return result;
        emitLinks(result, callback);
        return Unit.INSTANCE;
    }

    /** Matches the pinned safe wrapper by converting extractor exceptions to an empty result. */
    public Object getSafeUrl(String url, String referer,
                             Function1<? super SubtitleFile, Unit> subtitleCallback,
                             Function1<? super ExtractorLink, Unit> callback,
                             Continuation<?> continuation) {
        if (url == null) throw new NullPointerException("url");
        if (subtitleCallback == null) throw new NullPointerException("subtitleCallback");
        if (callback == null) throw new NullPointerException("callback");
        Continuation<Unit> completion = swallowingCompletion(continuation);
        try {
            Object result = getUrl(url, referer, subtitleCallback, callback, completion);
            return result == kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()
                    ? result : Unit.INSTANCE;
        } catch (Exception ignored) {
            return Unit.INSTANCE;
        }
    }

    /** Legacy suspend overload retained by the pinned ABI and Kotlin override shape. */
    public Object getUrl(String url, String referer,
                         Continuation<?> continuation) {
        if (url == null) throw new NullPointerException("url");
        return java.util.Collections.emptyList();
    }

    public String getExtractorUrl(String id) { return id; }

    /** Kotlin default-argument bridge from the pinned ExtractorApi ABI. */
    public static Object getUrl$default(ExtractorApi receiver, String url, String referer,
                                        Function1<? super SubtitleFile, Unit> subtitleCallback,
                                        Function1<? super ExtractorLink, Unit> callback,
                                        Continuation<?> continuation, int mask, Object marker) {
        if ((mask & 0x2) != 0) referer = null;
        return receiver.getUrl(url, referer, subtitleCallback, callback, continuation);
    }

    public static Object getSafeUrl$default(ExtractorApi receiver, String url, String referer,
                                            Function1<? super SubtitleFile, Unit> subtitleCallback,
                                            Function1<? super ExtractorLink, Unit> callback,
                                            Continuation<?> continuation, int mask, Object marker) {
        if ((mask & 0x2) != 0) referer = null;
        return receiver.getSafeUrl(url, referer, subtitleCallback, callback, continuation);
    }

    public static Object getUrl$default(ExtractorApi receiver, String url, String referer,
                                        Continuation<?> continuation,
                                        int mask, Object marker) {
        if ((mask & 0x2) != 0) referer = null;
        return receiver.getUrl(url, referer, continuation);
    }

    private static Continuation<List<ExtractorLink>> linksCompletion(
            final Continuation<?> continuation,
            final Function1<? super ExtractorLink, Unit> callback) {
        if (continuation == null) throw new NullPointerException("continuation");
        return new Continuation<List<ExtractorLink>>() {
            @Override public kotlin.coroutines.CoroutineContext getContext() {
                return continuation.getContext();
            }

            @Override public void resumeWith(Object outcome) {
                try {
                    ResultKt.throwOnFailure(outcome);
                    emitLinks(outcome, callback);
                } catch (Throwable error) {
                    continuation.resumeWith(ResultKt.createFailure(error));
                    return;
                }
                continuation.resumeWith(Unit.INSTANCE);
            }
        };
    }

    private static Continuation<Unit> swallowingCompletion(final Continuation<?> continuation) {
        if (continuation == null) throw new NullPointerException("continuation");
        return new Continuation<Unit>() {
            @Override public kotlin.coroutines.CoroutineContext getContext() {
                return continuation.getContext();
            }

            @Override public void resumeWith(Object outcome) {
                try {
                    ResultKt.throwOnFailure(outcome);
                } catch (Throwable error) {
                    if (!(error instanceof Exception)) {
                        continuation.resumeWith(ResultKt.createFailure(error));
                        return;
                    }
                }
                continuation.resumeWith(Unit.INSTANCE);
            }
        };
    }

    private static void emitLinks(Object value, Function1<? super ExtractorLink, Unit> callback) {
        if (!(value instanceof Iterable<?>)) return;
        for (Object link : (Iterable<?>) value) {
            if (link instanceof ExtractorLink) callback.invoke((ExtractorLink) link);
        }
    }
}
