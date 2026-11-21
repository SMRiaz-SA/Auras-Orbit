package com.lagradost.cloudstream3.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.net.IDN;
import java.net.URI;
import java.util.concurrent.CancellationException;

import android.util.Log;
import com.auras.orbit.cs3.BoundedLevenshtein;
import com.lagradost.cloudstream3.SubtitleFile;
import com.lagradost.cloudstream3.extractors.DoodLaExtractor;
import com.lagradost.cloudstream3.extractors.DoodYtExtractor;
import com.lagradost.cloudstream3.extractors.FileMoon;
import com.lagradost.cloudstream3.extractors.FileMoonIn;
import com.lagradost.cloudstream3.extractors.FileMoonSx;
import com.lagradost.cloudstream3.extractors.FilemoonV2;
import com.lagradost.cloudstream3.extractors.MetaGnathTuggers;
import com.lagradost.cloudstream3.extractors.Mp4Upload;
import com.lagradost.cloudstream3.extractors.NathanFromSubject;
import com.lagradost.cloudstream3.extractors.Odnoklassniki;
import com.lagradost.cloudstream3.extractors.OkRuHTTP;
import com.lagradost.cloudstream3.extractors.OkRuHTTPMobile;
import com.lagradost.cloudstream3.extractors.OkRuSSL;
import com.lagradost.cloudstream3.extractors.OkRuSSLMobile;
import com.lagradost.cloudstream3.extractors.PixelDrain;
import com.lagradost.cloudstream3.extractors.Server1uns;
import com.lagradost.cloudstream3.extractors.Simpulumlamerop;
import com.lagradost.cloudstream3.extractors.Smoothpre;
import com.lagradost.cloudstream3.extractors.Tubeless;
import com.lagradost.cloudstream3.extractors.Urochsunloath;
import com.lagradost.cloudstream3.extractors.VidStack;
import com.lagradost.cloudstream3.extractors.VidHideHub;
import com.lagradost.cloudstream3.extractors.VidHidePro;
import com.lagradost.cloudstream3.extractors.VidHidePro1;
import com.lagradost.cloudstream3.extractors.VidHidePro2;
import com.lagradost.cloudstream3.extractors.VidHidePro3;
import com.lagradost.cloudstream3.extractors.VidHidePro4;
import com.lagradost.cloudstream3.extractors.VidHidePro5;
import com.lagradost.cloudstream3.extractors.VidHidePro6;
import com.lagradost.cloudstream3.extractors.VidhideExtractor;
import com.lagradost.cloudstream3.extractors.Ryderjet;
import com.lagradost.cloudstream3.extractors.Dhtpre;
import com.lagradost.cloudstream3.extractors.Dhcplay;
import com.lagradost.cloudstream3.extractors.HglinkTo;
import com.lagradost.cloudstream3.extractors.HgplayCDN;
import com.lagradost.cloudstream3.extractors.InternetArchiveExtractor;
import com.lagradost.cloudstream3.extractors.Habetar;
import com.lagradost.cloudstream3.extractors.Yuguaab;
import com.lagradost.cloudstream3.extractors.Guxhag;
import com.lagradost.cloudstream3.extractors.Auvexiug;
import com.lagradost.cloudstream3.extractors.Xenolyzb;
import com.lagradost.cloudstream3.extractors.Haxloppd;
import com.lagradost.cloudstream3.extractors.Cavanhabg;
import com.lagradost.cloudstream3.extractors.Dumbalag;
import com.lagradost.cloudstream3.extractors.Uasopt;
import com.lagradost.cloudstream3.extractors.Peytonepre;
import com.lagradost.cloudstream3.extractors.Voe;
import com.lagradost.cloudstream3.extractors.Voe1;
import com.lagradost.cloudstream3.extractors.Voe2;
import com.lagradost.cloudstream3.extractors.Yipsu;
import kotlinx.coroutines.JobKt;
import okhttp3.HttpUrl;
import okhttp3.internal.publicsuffix.PublicSuffixDatabase;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.text.Regex;

/**
 * Orbit-owned bridge for the pinned CS3 ExtractorApi helpers. It keeps a
 * plugin-owned registry and invokes only a registered extractor selected by
 * an explicit loadExtractor call. Short-link resolution is bounded and runs
 * only when its public suspend helper or loadExtractor is explicitly called.
 */
public final class ExtractorApiKt {
    private static final AtomicMutableList<ExtractorApi> extractorApis = new AtomicMutableList<>();
    private static final String PACKED_PREFIX = "eval(function(p,a,c,k,e,";
    private static final String PACKED_SUFFIX = "))";
    private static final int MAX_MIRROR_URL_CHARS = 4_096;
    private static final int MAX_EXTRACTOR_URL_CHARS = 4_096;
    private static final int MAX_MIRROR_REGISTRY_ENTRIES = 512;
    private static final long MAX_MIRROR_MATRIX_CELLS_PER_CANDIDATE = 1_000_000L;
    private static final long MAX_MIRROR_MATRIX_CELLS_TOTAL = 4_000_000L;
    private static final long MAX_MIRROR_SCORE_CELLS_TOTAL = 8_000_000L;
    private static final String EXTRACTOR_DIAGNOSTIC_TAG = "AurasOrbitExtractor";
    private static final int MAX_DIAGNOSTIC_CLASS_NAME_CHARS = 96;
    private static final String UNKNOWN_DIAGNOSTIC_CLASS = "unknown";
    private static final long EXTRACTOR_DIAGNOSTIC_WINDOW_NANOS = 60_000_000_000L;
    private static final long[] EXTRACTOR_DIAGNOSTIC_TIMES = new long[6];
    private static final Object EXTRACTOR_DIAGNOSTIC_LOCK = new Object();
    private static int extractorDiagnosticNext = 0;
    private static int extractorDiagnosticCount = 0;
    private static final UUID CLEARKEY_UUID = new UUID(-0x1d8e62a7567a4c37L, 0x781AB030AF78D30EL);
    private static final UUID WIDEVINE_UUID = new UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L);
    private static final UUID PLAYREADY_UUID = new UUID(-0x65fb0f8667bfbd7aL, -0x546d19a41f77a06bL);
    private static final kotlin.uuid.Uuid CLEARKEY_DRM_UUID = kotlin.uuid.Uuid.Companion.fromLongs(
            -0x1d8e62a7567a4c37L, 0x781AB030AF78D30EL);
    private static final kotlin.uuid.Uuid WIDEVINE_DRM_UUID = kotlin.uuid.Uuid.Companion.fromLongs(
            -0x121074568629b532L, -0x5c37d8232ae2de13L);
    private static final kotlin.uuid.Uuid PLAYREADY_DRM_UUID = kotlin.uuid.Uuid.Companion.fromLongs(
            -0x65fb0f8667bfbd7aL, -0x546d19a41f77a06bL);
    private static final Regex SCHEMA_STRIP_REGEX = new Regex("^(https:|)//(www\\.|)");

    private static final Function2<Object, Continuation<? super Unit>, Object> NO_OP_INITIALIZER =
            new Function2<Object, Continuation<? super Unit>, Object>() {
                @Override public Object invoke(Object receiver,
                                               Continuation<? super Unit> continuation) {
                    return Unit.INSTANCE;
                }
            };

    static {
        // Orbit-owned built-in is present before any later plugin extractor registration.
        registerExtractorApi(new Mp4Upload());
        // Handles the single-file route used by CloudStream's official Internet Archive provider.
        registerExtractorApi(new InternetArchiveExtractor());
        // findDirectMatch/findMirrorMatch scan newest-first. Keep the original first entry for
        // name-lookup fallback, keep Dood below all existing built-ins in reverse priority, and
        // let downloaded plugin registrations (which append later) retain their precedence.
        registerExtractorApi(new DoodLaExtractor());
        registerExtractorApi(new DoodYtExtractor());
        // Preserve the pinned Filemoon alias order before Odnoklassniki dispatch.
        registerExtractorApi(new FileMoonIn());
        registerExtractorApi(new FileMoon());
        registerExtractorApi(new FileMoonSx());
        registerExtractorApi(new FilemoonV2());
        // Cover Odnoklassniki's canonical host without changing HTTP/SSL precedence.
        registerExtractorApi(new Odnoklassniki());
        // Preserve PixelDrain's position immediately after Odnoklassniki and before OkRu.
        registerExtractorApi(new PixelDrain());
        // Preserve the pinned relative priority: OkRuHTTP is later than OkRuSSL.
        registerExtractorApi(new OkRuSSL());
        registerExtractorApi(new OkRuSSLMobile());
        registerExtractorApi(new OkRuHTTP());
        registerExtractorApi(new OkRuHTTPMobile());
        // Preserve the pinned Voe family built-in registration order.
        registerExtractorApi(new Voe());
        registerExtractorApi(new Voe1());
        registerExtractorApi(new Voe2());
        registerExtractorApi(new Tubeless());
        registerExtractorApi(new Simpulumlamerop());
        registerExtractorApi(new Urochsunloath());
        registerExtractorApi(new NathanFromSubject());
        registerExtractorApi(new Yipsu());
        registerExtractorApi(new MetaGnathTuggers());
        // Preserve the pinned VidHidePro family order and keep redirect-only CineMM
        // extractors out of this direct page-fetch family.
        registerExtractorApi(new VidhideExtractor());
        registerExtractorApi(new VidHidePro());
        registerExtractorApi(new VidHidePro1());
        registerExtractorApi(new VidHidePro2());
        registerExtractorApi(new VidHidePro3());
        registerExtractorApi(new VidHidePro4());
        registerExtractorApi(new VidHidePro5());
        registerExtractorApi(new VidHidePro6());
        registerExtractorApi(new VidHideHub());
        registerExtractorApi(new Ryderjet());
        registerExtractorApi(new Dhtpre());
        registerExtractorApi(new Dhcplay());
        registerExtractorApi(new HglinkTo());
        registerExtractorApi(new HgplayCDN());
        registerExtractorApi(new Habetar());
        registerExtractorApi(new Yuguaab());
        registerExtractorApi(new Guxhag());
        registerExtractorApi(new Auvexiug());
        registerExtractorApi(new Xenolyzb());
        registerExtractorApi(new Haxloppd());
        registerExtractorApi(new Cavanhabg());
        registerExtractorApi(new Dumbalag());
        registerExtractorApi(new Uasopt());
        registerExtractorApi(new Smoothpre());
        registerExtractorApi(new Peytonepre());
        // Preserve the pinned VidStack-before-Server1uns relative registration order.
        registerExtractorApi(new VidStack());
        registerExtractorApi(new Server1uns());
    }

    private ExtractorApiKt() { }

    /** Converts seconds to microseconds using the pinned Long.toUs contract. */
    public static long toUs(long seconds) { return seconds * 1_000_000L; }

    public static UUID getCLEARKEY_UUID() { return CLEARKEY_UUID; }
    public static UUID getWIDEVINE_UUID() { return WIDEVINE_UUID; }
    public static UUID getPLAYREADY_UUID() { return PLAYREADY_UUID; }
    public static kotlin.uuid.Uuid getCLEARKEY_DRM_UUID() { return CLEARKEY_DRM_UUID; }
    public static kotlin.uuid.Uuid getWIDEVINE_DRM_UUID() { return WIDEVINE_DRM_UUID; }
    public static kotlin.uuid.Uuid getPLAYREADY_DRM_UUID() { return PLAYREADY_DRM_UUID; }
    public static Regex getSchemaStripRegex() { return SCHEMA_STRIP_REGEX; }

    /** Adds one exact extractor object to Orbit's ordered CS3 extractor registry. */
    public static void registerExtractorApi(ExtractorApi extractor) {
        if (extractor == null) return;
        synchronized (extractorApis) {
            for (ExtractorApi registered : extractorApis) if (registered == extractor) return;
            extractorApis.add(extractor);
        }
    }

    /** Removes the exact extractor object during plugin transaction rollback/unload. */
    public static void removeExtractorApi(ExtractorApi extractor) {
        if (extractor == null) return;
        synchronized (extractorApis) {
            for (int index = 0; index < extractorApis.size(); index++) {
                if (extractorApis.get(index) == extractor) {
                    extractorApis.remove(index);
                    return;
                }
            }
        }
    }

    /** Returns the live ordered registry using the pinned AtomicMutableList JVM descriptor. */
    public static AtomicMutableList<ExtractorApi> getExtractorApis() {
        return extractorApis;
    }

    /** Pinned behavior: exact name lookup, falling back to the first registered extractor. */
    public static ExtractorApi getExtractorApiFromName(String name) {
        if (name == null) throw new NullPointerException("name");
        synchronized (extractorApis) {
            for (ExtractorApi extractor : extractorApis) {
                if (name.equals(extractor.getName())) return extractor;
            }
            return extractorApis.get(0);
        }
    }

    public static boolean requireReferer(String name) {
        return getExtractorApiFromName(name).getRequiresReferer();
    }

    /** Resolves relative and protocol-relative paths against an extractor base URL. */
    public static String fixUrl(ExtractorApi extractor, String url) {
        if (url == null) return null;
        if (url.startsWith("http") || url.startsWith("{\"")) return url;
        if (url.isEmpty()) return "";
        if (url.startsWith("//")) return "https:" + url;
        String base = extractor == null ? "" : extractor.getMainUrl();
        if (base == null) base = "";
        if (url.startsWith("/")) return base + url;
        return base + "/" + url;
    }

    /** Returns the input unchanged when no bounded P.A.C.K.E.R. payload can be decoded. */
    public static String getAndUnpack(String value) {
        if (value == null) return null;
        String packed = getPacked(value);
        if (packed == null) return value;
        try {
            String unpacked = new JsUnpacker(packed).unpack();
            return unpacked == null ? value : unpacked;
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    /** The pinned API uses null to request URL-based type inference. */
    public static ExtractorLinkType getINFER_TYPE() { return null; }

    public static String getPacked(String value) {
        if (value == null || value.length() > JsUnpacker.MAX_INPUT_CHARS) return null;
        // Linear equivalent of the pinned prefix + greedy same-line `.*))` pattern.
        int lineStart = 0;
        while (lineStart <= value.length()) {
            int lineEnd = lineStart;
            while (lineEnd < value.length() && !isPackedLineTerminator(value.charAt(lineEnd))) {
                lineEnd++;
            }

            int prefixStart = findPackedPrefix(value, lineStart, lineEnd);
            if (prefixStart >= 0) {
                int suffixStart = findPackedSuffix(value, prefixStart, lineEnd);
                if (suffixStart >= prefixStart) {
                    return value.substring(prefixStart, suffixStart + PACKED_SUFFIX.length());
                }
            }

            if (lineEnd == value.length()) break;
            char terminator = value.charAt(lineEnd);
            lineStart = lineEnd + ((terminator == '\r' && lineEnd + 1 < value.length() &&
                    value.charAt(lineEnd + 1) == '\n') ? 2 : 1);
        }
        return null;
    }

    private static int findPackedPrefix(String value, int start, int end) {
        int lastPossibleStart = end - PACKED_PREFIX.length();
        for (int index = start; index <= lastPossibleStart; index++) {
            if (value.charAt(index) == 'e' &&
                    value.regionMatches(index, PACKED_PREFIX, 0, PACKED_PREFIX.length())) return index;
        }
        return -1;
    }

    private static int findPackedSuffix(String value, int start, int end) {
        for (int index = end - PACKED_SUFFIX.length(); index >= start; index--) {
            if (value.charAt(index) == ')' && value.charAt(index + 1) == ')') return index;
        }
        return -1;
    }

    /** Matches the line boundaries excluded by Java regex dot without DOTALL mode. */
    private static boolean isPackedLineTerminator(char value) {
        return value == '\n' || value == '\r' || value == '\u0085' ||
                value == '\u2028' || value == '\u2029';
    }

    /** Parses the common p/4K quality labels; unknown names use CS3's 400 sentinel. */
    public static int getQualityFromName(String qualityName) {
        if (qualityName == null) return 400;
        String normalized = qualityName.toLowerCase(Locale.ROOT).replace("p", "").trim();
        if ("4k".equals(normalized)) return 2160;
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            return 400;
        }
    }

    /** Accepts only bounded, trimmed HTTP(S) URLs with a valid authority and no credentials. */
    public static boolean isBoundedExtractorHttpUrl(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_EXTRACTOR_URL_CHARS &&
                value.equals(value.trim()) && parseHttpUrl(value) != null;
    }

    /** Validates a public extractor URL without fetching it, then emits one inferred-type link. */
    public static Object emitValidatedExtractorLink(String source, String name, String candidateUrl,
                                                    String referer,
                                                    Function1<? super ExtractorLink, Unit> callback,
                                                    Continuation<?> continuation) {
        if (source == null) throw new NullPointerException("source");
        if (name == null) throw new NullPointerException("name");
        if (candidateUrl == null) throw new NullPointerException("candidateUrl");
        if (referer == null) throw new NullPointerException("referer");
        if (callback == null) throw new NullPointerException("callback");
        if (continuation == null) throw new NullPointerException("continuation");
        JobKt.ensureActive(continuation.getContext());

        Continuation<String> validationCompletion = new Continuation<String>() {
            @Override public kotlin.coroutines.CoroutineContext getContext() {
                return continuation.getContext();
            }

            @Override public void resumeWith(Object outcome) {
                try {
                    ResultKt.throwOnFailure(outcome);
                    JobKt.ensureActive(continuation.getContext());
                    String validatedUrl = (String) outcome;
                    if (validatedUrl != null) callback.invoke(createValidatedExtractorLink(
                            source, name, validatedUrl, referer));
                    continuation.resumeWith(Unit.INSTANCE);
                } catch (Throwable failure) {
                    continuation.resumeWith(ResultKt.createFailure(failure));
                }
            }
        };

        Object validation = OrbitShortLinkResolver.validatePublicExtractorMediaUrl(
                candidateUrl, validationCompletion);
        if (validation == kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
            return validation;
        }
        String validatedUrl = (String) validation;
        if (validatedUrl != null) callback.invoke(createValidatedExtractorLink(
                source, name, validatedUrl, referer));
        return Unit.INSTANCE;
    }

    private static ExtractorLink createValidatedExtractorLink(String source, String name,
                                                              String url, String referer) {
        return new ExtractorLink(source, name, url, referer,
                400, Collections.<String, String>emptyMap(), null, null,
                Collections.emptyList());
    }

    public static String httpsify(String url) {
        if (url == null) return null;
        return url.startsWith("//") ? "https:" + url : url;
    }

    /** Delegates the pinned suspend form helper through Orbit's shared transport. */
    public static Object getPostForm(String requestUrl, String html,
                                     Continuation<? super String> continuation) {
        return OrbitPostForm.getPostForm(requestUrl, html, continuation);
    }

    /** Resolves recognized short links safely, returning the original input on failures. */
    public static Object unshortenLinkSafe(String url, Continuation<? super String> continuation) {
        return OrbitShortLinkResolver.unshortenLinkSafe(url, continuation);
    }

    /** Explicit callback-driven extractor dispatch with newest registrations taking priority. */
    /**
     * Keep callback parameters star-projected at this Java/Kotlin source boundary. Exposing
     * SubtitleFile in the public Function1 signature prevents Kotlin from resolving the callback.
     */
    public static Object loadExtractor(String url, String referer,
                                       Function1<?, ?> subtitleCallback,
                                       Function1<?, ?> callback,
                                       Continuation<?> continuation) {
        if (url == null) throw new NullPointerException("url");
        if (subtitleCallback == null) throw new NullPointerException("subtitleCallback");
        if (callback == null) throw new NullPointerException("callback");
        if (continuation == null) throw new NullPointerException("continuation");

        // Match the pinned coroutine preflight before resolving or matching any URL.
        JobKt.ensureActive(continuation.getContext());
        final Function1<? super SubtitleFile, Unit> typedSubtitleCallback =
                asSubtitleCallback(subtitleCallback);
        final Function1<? super ExtractorLink, Unit> typedLinkCallback =
                asExtractorLinkCallback(callback);

        Continuation<String> resolvedContinuation = new Continuation<String>() {
            @Override public kotlin.coroutines.CoroutineContext getContext() {
                return continuation.getContext();
            }

            @Override public void resumeWith(Object outcome) {
                try {
                    ResultKt.throwOnFailure(outcome);
                } catch (Throwable failure) {
                    continuation.resumeWith(ResultKt.createFailure(failure));
                    return;
                }
                try {
                    // Do not dispatch after cancellation while resolution was suspended.
                    JobKt.ensureActive(continuation.getContext());
                    Object dispatched = dispatchLoadExtractor((String) outcome, referer,
                            typedSubtitleCallback, typedLinkCallback, continuation);
                    if (dispatched != kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
                        continuation.resumeWith(dispatched);
                    }
                } catch (Throwable failure) {
                    continuation.resumeWith(ResultKt.createFailure(failure));
                }
            }
        };

        Object resolved = unshortenLinkSafe(url, resolvedContinuation);
        if (resolved == kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
            return resolved;
        }
        JobKt.ensureActive(continuation.getContext());
        return dispatchLoadExtractor((String) resolved, referer,
                typedSubtitleCallback, typedLinkCallback, continuation);
    }

    /** The Kotlin source stub must not expose the same-module SubtitleFile type on this boundary. */
    @SuppressWarnings("unchecked")
    private static Function1<? super SubtitleFile, Unit> asSubtitleCallback(Function1<?, ?> callback) {
        return (Function1<? super SubtitleFile, Unit>) callback;
    }

    @SuppressWarnings("unchecked")
    private static Function1<? super ExtractorLink, Unit> asExtractorLinkCallback(Function1<?, ?> callback) {
        return (Function1<? super ExtractorLink, Unit>) callback;
    }

    private static Object dispatchLoadExtractor(String resolvedUrl, String referer,
                                               Function1<? super SubtitleFile, Unit> subtitleCallback,
                                               Function1<? super ExtractorLink, Unit> callback,
                                               Continuation<?> continuation) {
        JobKt.ensureActive(continuation.getContext());
        // Validate the raw or resolved input before either direct or mirror dispatch.
        ParsedHttpUrl requestUrl = parseHttpUrl(resolvedUrl);
        if (requestUrl == null) return Boolean.FALSE;
        List<ExtractorApi> snapshot = extractorApis.withLock(
                () -> new ArrayList<ExtractorApi>(extractorApis));
        ExtractorApi selected = findDirectMatch(snapshot, requestUrl);
        if (selected == null) selected = findMirrorMatch(snapshot, requestUrl, resolvedUrl);
        if (selected == null) return Boolean.FALSE;
        return invokeExtractor(selected, resolvedUrl, referer, subtitleCallback, callback, continuation);
    }

    /** Overload without a referer, matching the pinned extension helper. */
    public static Object loadExtractor(String url,
                                       Function1<?, ?> subtitleCallback,
                                       Function1<?, ?> callback,
                                       Continuation<?> continuation) {
        return loadExtractor(url, null, subtitleCallback, callback, continuation);
    }

    /** Kotlin default-argument bridge for the nullable referer. */
    public static Object loadExtractor$default(String url, String referer,
                                               Function1<?, ?> subtitleCallback,
                                               Function1<?, ?> callback,
                                               Continuation<?> continuation, int mask, Object marker) {
        if ((mask & 0x2) != 0) referer = null;
        return loadExtractor(url, referer, subtitleCallback, callback, continuation);
    }

    /** Creates a mutable link, infers its type when omitted, and awaits its suspend initializer. */
    public static Object newExtractorLink(String source, String name, String url,
                                          ExtractorLinkType type,
                                          Function2<? super ExtractorLink,
                                                  ? super Continuation<? super Unit>, ? extends Object> initializer,
                                          Continuation<?> continuation) {
        if (source == null) throw new NullPointerException("source");
        if (name == null) throw new NullPointerException("name");
        if (url == null) throw new NullPointerException("url");
        if (initializer == null) throw new NullPointerException("initializer");
        if (continuation == null) throw new NullPointerException("continuation");

        // These are the defaults used by the pinned deprecated constructor called by
        // newExtractorLink: blank referer, Unknown quality, empty headers and tracks.
        ExtractorLink link = new ExtractorLink(source, name, url, "", 400,
                Collections.<String, String>emptyMap(), null, type, Collections.emptyList());
        Continuation<Unit> completion = fixedValueCompletion(continuation, link);
        Object result = initializer.invoke(link, completion);
        return result == kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()
                ? result : link;
    }

    /** Kotlin default-argument bridge for inferred type and the no-op initializer. */
    public static Object newExtractorLink$default(String source, String name, String url,
                                                  ExtractorLinkType type,
                                                  Function2<? super ExtractorLink,
                                                          ? super Continuation<? super Unit>, ? extends Object> initializer,
                                                  Continuation<?> continuation,
                                                  int mask, Object marker) {
        if ((mask & 0x8) != 0) type = null;
        if ((mask & 0x10) != 0) initializer = NO_OP_INITIALIZER;
        return newExtractorLink(source, name, url, type, initializer, continuation);
    }

    /** Creates a DRM link from the Java UUID overload and awaits its suspend initializer. */
    public static Object newDrmExtractorLink(String source, String name, String url,
                                             ExtractorLinkType type, UUID uuid,
                                             Function2<? super DrmExtractorLink,
                                                     ? super Continuation<? super Unit>, ? extends Object> initializer,
                                             Continuation<? super DrmExtractorLink> continuation) {
        if (uuid == null) throw new NullPointerException("uuid");
        kotlin.uuid.Uuid kotlinUuid = kotlin.uuid.Uuid.Companion.fromLongs(
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
        return createDrmExtractorLink(source, name, url, type, kotlinUuid, initializer, continuation);
    }

    /** Creates a DRM link from the pinned Kotlin Uuid overload. */
    public static Object newDrmExtractorLink(String source, String name, String url,
                                             ExtractorLinkType type, kotlin.uuid.Uuid uuid,
                                             Function2<? super DrmExtractorLink,
                                                     ? super Continuation<? super Unit>, ? extends Object> initializer,
                                             Continuation<? super DrmExtractorLink> continuation) {
        return createDrmExtractorLink(source, name, url, type, uuid, initializer, continuation);
    }

    /** Default bridge for the Java UUID overload (nullable type and suspend initializer). */
    public static Object newDrmExtractorLink$default(String source, String name, String url,
                                                     ExtractorLinkType type, UUID uuid,
                                                     Function2<? super DrmExtractorLink,
                                                             ? super Continuation<? super Unit>, ? extends Object> initializer,
                                                     Continuation<? super DrmExtractorLink> continuation,
                                                     int mask, Object marker) {
        if ((mask & 0x8) != 0) type = null;
        if ((mask & 0x20) != 0) initializer = NO_OP_INITIALIZER;
        return newDrmExtractorLink(source, name, url, type, uuid, initializer, continuation);
    }

    /** Default bridge for the Kotlin Uuid overload (nullable type and suspend initializer). */
    public static Object newDrmExtractorLink$default(String source, String name, String url,
                                                     ExtractorLinkType type, kotlin.uuid.Uuid uuid,
                                                     Function2<? super DrmExtractorLink,
                                                             ? super Continuation<? super Unit>, ? extends Object> initializer,
                                                     Continuation<? super DrmExtractorLink> continuation,
                                                     int mask, Object marker) {
        if ((mask & 0x8) != 0) type = null;
        if ((mask & 0x20) != 0) initializer = NO_OP_INITIALIZER;
        return newDrmExtractorLink(source, name, url, type, uuid, initializer, continuation);
    }

    @SuppressWarnings("deprecation") // Required to preserve the pinned builder defaults.
    private static Object createDrmExtractorLink(String source, String name, String url,
            ExtractorLinkType type, kotlin.uuid.Uuid uuid,
            Function2<? super DrmExtractorLink,
                    ? super Continuation<? super Unit>, ? extends Object> initializer,
            Continuation<? super DrmExtractorLink> continuation) {
        if (source == null) throw new NullPointerException("source");
        if (name == null) throw new NullPointerException("name");
        if (url == null) throw new NullPointerException("url");
        if (uuid == null) throw new NullPointerException("uuid");
        if (initializer == null) throw new NullPointerException("initializer");
        if (continuation == null) throw new NullPointerException("continuation");

        DrmExtractorLink link = new DrmExtractorLink(source, name, url, "", 400, type,
                Collections.<String, String>emptyMap(), null, null, null, uuid, "oct",
                new HashMap<String, String>(), null);
        Continuation<Unit> completion = fixedValueCompletion(continuation, link);
        Object result = initializer.invoke(link, completion);
        return result == kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()
                ? result : link;
    }

    private static Object invokeExtractor(ExtractorApi extractor, String url, String referer,
                                          Function1<? super SubtitleFile, Unit> subtitleCallback,
                                          Function1<? super ExtractorLink, Unit> callback,
                                          Continuation<?> continuation) {
        Continuation<Object> completion = extractorCompletion(extractor, continuation);
        try {
            Object result = extractor.getUrl(url, referer, subtitleCallback, callback, completion);
            if (result == kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
                return result;
            }
            JobKt.ensureActive(continuation.getContext());
            return Boolean.TRUE;
        } catch (Exception error) {
            if (error instanceof CancellationException) throw (CancellationException) error;
            JobKt.ensureActive(continuation.getContext());
            logExtractorFailure(extractor, error);
            return Boolean.TRUE;
        }
    }

    private static Continuation<Object> extractorCompletion(final ExtractorApi extractor,
                                                             final Continuation<?> continuation) {
        return new Continuation<Object>() {
            @Override public kotlin.coroutines.CoroutineContext getContext() {
                return continuation.getContext();
            }

            @Override public void resumeWith(Object outcome) {
                Exception extractorFailure = null;
                try {
                    ResultKt.throwOnFailure(outcome);
                } catch (Throwable error) {
                    if (error instanceof CancellationException || !(error instanceof Exception)) {
                        continuation.resumeWith(ResultKt.createFailure(error));
                        return;
                    }
                    extractorFailure = (Exception) error;
                }
                try {
                    JobKt.ensureActive(continuation.getContext());
                } catch (Throwable cancellation) {
                    continuation.resumeWith(ResultKt.createFailure(cancellation));
                    return;
                }
                if (extractorFailure != null) logExtractorFailure(extractor, extractorFailure);
                continuation.resumeWith(Boolean.TRUE);
            }
        };
    }

    /** Logs only fixed metadata, with a process-wide cap of six events per rolling minute. */
    private static void logExtractorFailure(ExtractorApi extractor, Exception error) {
        if (!tryAcquireExtractorDiagnosticSlot()) return;
        Log.w(EXTRACTOR_DIAGNOSTIC_TAG, "extractor_failure extractor_class=" +
                sanitizeDiagnosticClassName(extractor.getClass()) + " exception_class=" +
                sanitizeDiagnosticClassName(error.getClass()));
    }

    private static String sanitizeDiagnosticClassName(Class<?> type) {
        if (type == null) return UNKNOWN_DIAGNOSTIC_CLASS;
        String name = type.getName();
        if (name == null || name.isEmpty()) return UNKNOWN_DIAGNOSTIC_CLASS;
        int length = Math.min(name.length(), MAX_DIAGNOSTIC_CLASS_NAME_CHARS);
        StringBuilder sanitized = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            char value = name.charAt(index);
            boolean allowed = (value >= 'A' && value <= 'Z') ||
                    (value >= 'a' && value <= 'z') ||
                    (value >= '0' && value <= '9') || value == '_' || value == '.' || value == '$';
            if (!allowed) return UNKNOWN_DIAGNOSTIC_CLASS;
            sanitized.append(value);
        }
        return sanitized.length() == 0 ? UNKNOWN_DIAGNOSTIC_CLASS : sanitized.toString();
    }

    private static boolean tryAcquireExtractorDiagnosticSlot() {
        synchronized (EXTRACTOR_DIAGNOSTIC_LOCK) {
            long now = System.nanoTime();
            while (extractorDiagnosticCount > 0) {
                int oldest = (extractorDiagnosticNext - extractorDiagnosticCount +
                        EXTRACTOR_DIAGNOSTIC_TIMES.length) % EXTRACTOR_DIAGNOSTIC_TIMES.length;
                if (now - EXTRACTOR_DIAGNOSTIC_TIMES[oldest] < EXTRACTOR_DIAGNOSTIC_WINDOW_NANOS) {
                    break;
                }
                extractorDiagnosticCount--;
            }
            if (extractorDiagnosticCount >= EXTRACTOR_DIAGNOSTIC_TIMES.length) return false;
            EXTRACTOR_DIAGNOSTIC_TIMES[extractorDiagnosticNext] = now;
            extractorDiagnosticNext = (extractorDiagnosticNext + 1) % EXTRACTOR_DIAGNOSTIC_TIMES.length;
            extractorDiagnosticCount++;
            return true;
        }
    }

    private static <T> Continuation<Unit> fixedValueCompletion(
            final Continuation<?> continuation, final T value) {
        return new Continuation<Unit>() {
            @Override public kotlin.coroutines.CoroutineContext getContext() {
                return continuation.getContext();
            }

            @Override public void resumeWith(Object outcome) {
                try {
                    ResultKt.throwOnFailure(outcome);
                } catch (Throwable error) {
                    continuation.resumeWith(ResultKt.createFailure(error));
                    return;
                }
                continuation.resumeWith(value);
            }
        };
    }

    /**
     * Static cases: / accepts paths; /catalog accepts /catalog/one, not /catalogue or /catalog:evil.
     * Explicit main ports must match; a portless main URL permits any syntactically valid port.
     */
    private static ExtractorApi findDirectMatch(List<ExtractorApi> registry, ParsedHttpUrl requestUrl) {
        for (int index = registry.size() - 1; index >= 0; index--) {
            ExtractorApi extractor = registry.get(index);
            String mainUrl = extractor.getMainUrl();
            if (mainUrl == null || mainUrl.isEmpty()) continue;
            ParsedHttpUrl parsedMainUrl = parseHttpUrl(mainUrl);
            if (parsedMainUrl == null || !parsedMainUrl.host.equals(requestUrl.host)) continue;
            if (parsedMainUrl.explicitPort >= 0 &&
                    parsedMainUrl.explicitPort != requestUrl.explicitPort) continue;
            if (hasPathPrefix(requestUrl, parsedMainUrl)) return extractor;
        }
        return null;
    }

    /**
     * Scores raw full URLs only when normalized hosts are identical or belong to the same
     * registrable domain according to OkHttp's pinned Public Suffix List.
     */
    private static ExtractorApi findMirrorMatch(List<ExtractorApi> registry,
                                                ParsedHttpUrl requestUrl, String url) {
        // Bounds are checked before scoring. Refuse the entire pass on exhaustion so an
        // older candidate can never win after a newer candidate was left unscored.
        if (registry.size() > MAX_MIRROR_REGISTRY_ENTRIES || url == null ||
                url.length() > MAX_MIRROR_URL_CHARS) return null;
        long[] remainingMatrixCells = {MAX_MIRROR_MATRIX_CELLS_TOTAL};
        long[] remainingScoreCells = {MAX_MIRROR_SCORE_CELLS_TOTAL};

        for (int index = registry.size() - 1; index >= 0; index--) {
            ExtractorApi extractor = registry.get(index);
            String mainUrl = extractor.getMainUrl();
            if (mainUrl == null || mainUrl.isEmpty()) continue;
            if (mainUrl.length() > MAX_MIRROR_URL_CHARS) return null;
            ParsedHttpUrl parsedMainUrl = parseHttpUrl(mainUrl);
            if (parsedMainUrl == null || !canFuzzyMatchHosts(requestUrl, parsedMainUrl)) continue;
            Integer score = BoundedLevenshtein.partialRatio(
                    mainUrl,
                    url,
                    MAX_MIRROR_URL_CHARS,
                    MAX_MIRROR_MATRIX_CELLS_PER_CANDIDATE,
                    remainingMatrixCells,
                    remainingScoreCells
            );
            if (score == null) return null;
            if (score > 80) return extractor;
        }
        return null;
    }

    /**
     * Prevents fuzzy mirror scores across unrelated registrable domains. Host parsing has
     * already passed through HttpUrl; this extra canonical form check rejects IPs and any
     * host that cannot safely be supplied to the pinned PSL.
     */
    private static boolean canFuzzyMatchHosts(ParsedHttpUrl request, ParsedHttpUrl main) {
        if (request == null || main == null) return false;
        String requestHost = canonicalDnsMatchHost(request.host);
        String mainHost = canonicalDnsMatchHost(main.host);
        if (requestHost == null || mainHost == null) return false;
        if (isIpAddressHost(requestHost) || isIpAddressHost(mainHost)) return false;
        if (requestHost.equals(mainHost)) return true;

        try {
            PublicSuffixDatabase suffixDatabase = PublicSuffixDatabase.Companion.get();
            String requestRegistrableDomain = suffixDatabase.getEffectiveTldPlusOne(requestHost);
            String mainRegistrableDomain = suffixDatabase.getEffectiveTldPlusOne(mainHost);
            return requestRegistrableDomain != null &&
                    requestRegistrableDomain.equals(mainRegistrableDomain);
        } catch (RuntimeException | LinkageError invalidSuffixData) {
            // Missing or malformed PSL data must disable this candidate's fuzzy score.
            return false;
        }
    }

    /** Returns a lower-case ASCII DNS host, or null when canonicalization is unsafe. */
    private static String canonicalDnsMatchHost(String host) {
        if (host == null || host.isEmpty() || host.length() > 253) return null;
        final String asciiHost;
        try {
            asciiHost = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
        } catch (RuntimeException invalidHost) {
            return null;
        }
        if (!asciiHost.equals(host)) return null;

        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.charAt(0) == '-' ||
                    label.charAt(label.length() - 1) == '-') return null;
            for (int index = 0; index < label.length(); index++) {
                char value = label.charAt(index);
                if (!((value >= 'a' && value <= 'z') || (value >= '0' && value <= '9') ||
                        value == '-')) return null;
            }
        }
        return host;
    }

    /** HttpUrl canonicalizes recognized IPv4 forms; IPv6 hosts retain their colons. */
    private static boolean isIpAddressHost(String host) {
        if (host.indexOf(':') >= 0) return true;
        for (int index = 0; index < host.length(); index++) {
            char value = host.charAt(index);
            if (!((value >= '0' && value <= '9') || value == '.')) return false;
        }
        return true;
    }

    /**
     * Only HTTP(S) URLs with a valid authority, no userinfo and a valid explicit port are
     * dispatchable. For example, trusted.com:8443/catalog can match the same host's /catalog
     * prefix, but trusted.com:80@evil.net and file://trusted.com are rejected before dispatch.
     */
    private static ParsedHttpUrl parseHttpUrl(String value) {
        if (value == null) return null;
        String candidate = value.trim();
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") ||
                    scheme.equalsIgnoreCase("https")) || uri.getRawUserInfo() != null ||
                    !hasValidAuthority(uri.getRawAuthority())) return null;

            HttpUrl parsed = HttpUrl.get(candidate);
            String host = normalizeMatchHost(parsed.host());
            if (host.isEmpty()) return null;
            return new ParsedHttpUrl(host, explicitPort(uri.getRawAuthority()), parsed.encodedPath(),
                    parsed.encodedQuery(), parsed.encodedFragment());
        } catch (Exception invalidUrl) {
            return null;
        }
    }

    private static boolean hasValidAuthority(String authority) {
        if (authority == null || authority.isEmpty() || authority.indexOf('@') >= 0) return false;
        if (authority.charAt(0) == '[') {
            int closingBracket = authority.indexOf(']');
            if (closingBracket <= 1) return false;
            if (authority.substring(1, closingBracket).indexOf('%') >= 0) return false;
            String suffix = authority.substring(closingBracket + 1);
            if (suffix.isEmpty()) return true;
            return suffix.charAt(0) == ':' && isValidPort(suffix.substring(1));
        }
        if (authority.indexOf('[') >= 0 || authority.indexOf(']') >= 0) return false;

        int colon = authority.indexOf(':');
        if (colon < 0) return !authority.isEmpty();
        if (colon != authority.lastIndexOf(':') || colon == 0) return false;
        return isValidPort(authority.substring(colon + 1));
    }

    private static boolean isValidPort(String value) {
        if (value.isEmpty()) return false;
        int port = 0;
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') return false;
            port = port * 10 + (digit - '0');
            if (port > 65_535) return false;
        }
        return port > 0;
    }

    /** Returns -1 when authority has no explicit port; authority has already been validated. */
    private static int explicitPort(String authority) {
        if (authority.charAt(0) == '[') {
            int closingBracket = authority.indexOf(']');
            String suffix = authority.substring(closingBracket + 1);
            return suffix.isEmpty() ? -1 : Integer.parseInt(suffix.substring(1));
        }
        int colon = authority.indexOf(':');
        return colon < 0 ? -1 : Integer.parseInt(authority.substring(colon + 1));
    }

    private static String normalizeMatchHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.endsWith(".") || normalized.contains("..")) return "";
        if (normalized.startsWith("www.")) normalized = normalized.substring(4);
        return normalized.length() > 255 ? "" : normalized;
    }

    private static boolean hasPathPrefix(ParsedHttpUrl request, ParsedHttpUrl main) {
        String requestPath = request.path.toLowerCase(Locale.ROOT);
        String mainPath = main.path.toLowerCase(Locale.ROOT);
        if (!requestPath.startsWith(mainPath)) return false;
        if (requestPath.length() != mainPath.length() && !mainPath.endsWith("/")) {
            char next = requestPath.charAt(mainPath.length());
            if (next != '/') return false;
        }
        if (main.query != null && !main.query.equalsIgnoreCase(request.query)) return false;
        return main.fragment == null || main.fragment.equalsIgnoreCase(request.fragment);
    }

    private static final class ParsedHttpUrl {
        final String host;
        final int explicitPort;
        final String path;
        final String query;
        final String fragment;

        ParsedHttpUrl(String host, int explicitPort, String path, String query, String fragment) {
            this.host = host;
            this.explicitPort = explicitPort;
            this.path = path;
            this.query = query;
            this.fragment = fragment;
        }
    }

}
