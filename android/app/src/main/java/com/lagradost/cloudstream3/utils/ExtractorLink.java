package com.lagradost.cloudstream3.utils;

import android.net.Uri;
import com.lagradost.cloudstream3.AudioFile;
import com.lagradost.cloudstream3.IDownloadableMinimum;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Orbit-owned source-shaped ExtractorLink value used to capture CS3 callback results. */
public class ExtractorLink implements IDownloadableMinimum {
    private final String source;
    private final String name;
    private final String url;
    private String referer;
    private int quality;
    private Map<String, String> headers;
    private String extractorData;
    private ExtractorLinkType type;
    private List<AudioFile> audioTracks;
    private Long videoSize;

    public ExtractorLink(String source, String name, String url, String referer, int quality,
                         Map<String, String> headers, String extractorData,
                         ExtractorLinkType type, List<AudioFile> audioTracks) {
        this.source = source;
        this.name = name;
        this.url = url;
        this.referer = referer == null ? "" : referer;
        this.quality = quality;
        // The pinned Kotlin property keeps and returns the caller's map reference.
        this.headers = headers == null ? Collections.emptyMap() : headers;
        this.extractorData = extractorData;
        this.type = type == null ? inferType(url) : type;
        this.audioTracks = audioTracks == null ? Collections.emptyList() : audioTracks;
    }

    public ExtractorLink(String source, String name, String url, String referer, int quality,
                         Map<String, String> headers, String extractorData,
                         ExtractorLinkType type, List<AudioFile> audioTracks, int mask,
                         kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(source, name, url, referer, quality,
                (mask & (1 << 5)) != 0 ? Collections.<String, String>emptyMap() : headers,
                (mask & (1 << 6)) != 0 ? null : extractorData,
                type,
                (mask & (1 << 8)) != 0 ? Collections.<AudioFile>emptyList() : audioTracks);
    }

    public ExtractorLink(String source, String name, String url, String referer, int quality,
                         ExtractorLinkType type, Map<String, String> headers, String extractorData) {
        this(source, name, url, referer, quality, headers, extractorData, type, Collections.emptyList());
    }

    /** Kotlin default-argument constructor for the deprecated type/headers/data overload. */
    public ExtractorLink(String source, String name, String url, String referer, int quality,
                         ExtractorLinkType type, Map<String, String> headers, String extractorData,
                         int mask, kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(source, name, url, referer, quality, type,
                (mask & (1 << 6)) != 0 ? Collections.<String, String>emptyMap() : headers,
                (mask & (1 << 7)) != 0 ? null : extractorData);
    }

    public ExtractorLink(String source, String name, String url, String referer, Integer quality,
                         ExtractorLinkType type, Map<String, String> headers, String extractorData) {
        this(source, name, url, referer,
                quality == null ? Qualities.Unknown.getValue() : quality.intValue(),
                type, headers, extractorData);
    }

    /** Kotlin default bridge for the nullable-quality deprecated constructor. */
    public ExtractorLink(String source, String name, String url, String referer, Integer quality,
                         ExtractorLinkType type, Map<String, String> headers, String extractorData,
                         int mask, kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(source, name, url,
                (mask & (1 << 3)) != 0 ? null : referer,
                (mask & (1 << 4)) != 0 ? null : quality,
                (mask & (1 << 5)) != 0 ? null : type,
                (mask & (1 << 6)) != 0 ? Collections.<String, String>emptyMap() : headers,
                (mask & (1 << 7)) != 0 ? null : extractorData);
    }

    public ExtractorLink(String source, String name, String url, String referer, int quality,
                         boolean isM3u8, Map<String, String> headers, String extractorData) {
        this(source, name, url, referer, quality, headers, extractorData,
                isM3u8 ? ExtractorLinkType.M3U8 : ExtractorLinkType.VIDEO, Collections.emptyList());
    }

    public ExtractorLink(String source, String name, String url, String referer, int quality,
                         boolean isM3u8, Map<String, String> headers, String extractorData,
                         boolean isDash) {
        this(source, name, url, referer, quality, headers, extractorData,
                isDash ? ExtractorLinkType.DASH : isM3u8 ? ExtractorLinkType.M3U8 : ExtractorLinkType.VIDEO,
                Collections.emptyList());
    }

    public ExtractorLink(String source, String name, String url, String referer, int quality,
                         boolean isM3u8, Map<String, String> headers, String extractorData,
                         int mask, kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(source, name, url, referer, quality,
                (mask & (1 << 5)) != 0 ? false : isM3u8,
                (mask & (1 << 6)) != 0 ? Collections.<String, String>emptyMap() : headers,
                (mask & (1 << 7)) != 0 ? null : extractorData);
    }

    public ExtractorLink(String source, String name, String url, String referer, int quality,
                         boolean isM3u8, Map<String, String> headers, String extractorData,
                         boolean isDash, int mask,
                         kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(source, name, url, referer, quality,
                (mask & (1 << 5)) != 0 ? false : isM3u8,
                (mask & (1 << 6)) != 0 ? Collections.<String, String>emptyMap() : headers,
                (mask & (1 << 7)) != 0 ? null : extractorData, isDash);
    }

    public String getSource() { return source; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getReferer() { return referer; }
    public void setReferer(String value) { referer = java.util.Objects.requireNonNull(value, "referer"); }
    public int getQuality() { return quality; }
    public void setQuality(int value) { quality = value; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> value) {
        headers = java.util.Objects.requireNonNull(value, "headers");
    }
    public String getExtractorData() { return extractorData; }
    public void setExtractorData(String value) { extractorData = value; }
    public ExtractorLinkType getType() { return type; }
    public void setType(ExtractorLinkType value) { type = java.util.Objects.requireNonNull(value, "type"); }
    public List<AudioFile> getAudioTracks() { return audioTracks; }
    public void setAudioTracks(List<AudioFile> value) {
        audioTracks = value == null ? Collections.<AudioFile>emptyList() : value;
    }
    public boolean isM3u8() { return type == ExtractorLinkType.M3U8; }
    public boolean isDash() { return type == ExtractorLinkType.DASH; }

    /** Stable source's cached HEAD lookup for VIDEO links. */
    public final Object getVideoSize(long timeoutSeconds,
                               final kotlin.coroutines.Continuation<? super Long> continuation) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(continuation, "continuation");
        if (type != ExtractorLinkType.VIDEO) return null;
        if (videoSize != null) return videoSize;

        try {
            kotlin.coroutines.Continuation<Long> requestContinuation =
                    new kotlin.coroutines.Continuation<Long>() {
                @Override public kotlin.coroutines.CoroutineContext getContext() {
                    return continuation.getContext();
                }
                @Override public void resumeWith(Object outcome) {
                    Long size = parseVideoSize(outcome);
                    videoSize = size;
                    continuation.resumeWith(size);
                }
            };
            Object result = ExtractorLinkCompat.getOrbitVideoSize(
                    this, timeoutSeconds, requestContinuation);
            if (result == kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
                return result;
            }
            videoSize = parseVideoSize(result);
            return videoSize;
        } catch (Throwable ignored) {
            videoSize = null;
            return null;
        }
    }

    public static Object getVideoSize$default(ExtractorLink self, long timeoutSeconds,
            kotlin.coroutines.Continuation<? super Long> continuation, int mask, Object marker) {
        if ((mask & 1) != 0) timeoutSeconds = 3L;
        return self.getVideoSize(timeoutSeconds, continuation);
    }

    private static Long parseVideoSize(Object outcome) {
        try {
            kotlin.ResultKt.throwOnFailure(outcome);
            return (Long) outcome;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public Map<String, String> getAllHeaders() {
        if (referer == null || referer.trim().isEmpty()) return headers;
        for (String key : headers.keySet()) if ("referer".equalsIgnoreCase(key)) return headers;
        LinkedHashMap<String, String> result = new LinkedHashMap<>(headers);
        result.put("referer", referer);
        return Collections.unmodifiableMap(result);
    }

    private static ExtractorLinkType inferType(String url) {
        String path = null;
        try {
            if (url != null) path = Uri.parse(url).getPath();
        } catch (Throwable ignored) { /* Match the pinned fallback for malformed URLs. */ }
        if (path != null && path.endsWith(".m3u8")) return ExtractorLinkType.M3U8;
        if (path != null && path.endsWith(".mpd")) return ExtractorLinkType.DASH;
        if (path != null && path.endsWith(".torrent")) return ExtractorLinkType.TORRENT;
        if (url != null && url.startsWith("magnet:")) return ExtractorLinkType.MAGNET;
        return ExtractorLinkType.VIDEO;
    }
}
