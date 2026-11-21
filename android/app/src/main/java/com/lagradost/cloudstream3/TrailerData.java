package com.lagradost.cloudstream3;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;

/** Source-shaped trailer payload used by the pinned CS3 LoadResponse contract. */
public final class TrailerData {
    private final String extractorUrl;
    private final String referer;
    private final boolean raw;
    private final Map<String, String> headers;

    public TrailerData(String extractorUrl, String referer, boolean raw, Map<String, String> headers) {
        Intrinsics.checkNotNullParameter(extractorUrl, "extractorUrl");
        Intrinsics.checkNotNullParameter(headers, "headers");
        this.extractorUrl = extractorUrl;
        this.referer = referer;
        this.raw = raw;
        this.headers = headers;
    }

    public TrailerData(String extractorUrl, String referer, boolean raw) {
        this(extractorUrl, referer, raw, Collections.emptyMap());
    }

    public TrailerData(String extractorUrl, String referer, boolean raw, Map<String, String> headers,
                       int mask, DefaultConstructorMarker marker) {
        this(extractorUrl, referer, raw,
                (mask & 8) != 0 ? Collections.emptyMap() : headers);
    }

    public String getExtractorUrl() { return extractorUrl; }
    public String getReferer() { return referer; }
    public boolean getRaw() { return raw; }
    public boolean isRaw() { return raw; }
    public Map<String, String> getHeaders() { return headers; }

    public String component1() { return extractorUrl; }
    public String component2() { return referer; }
    public boolean component3() { return raw; }
    public Map<String, String> component4() { return headers; }

    public TrailerData copy(String extractorUrl, String referer, boolean raw, Map<String, String> headers) {
        return new TrailerData(extractorUrl, referer, raw, headers);
    }

    public static TrailerData copy$default(TrailerData self, String extractorUrl, String referer,
                                            boolean raw, Map<String, String> headers,
                                            int mask, Object marker) {
        if ((mask & 1) != 0) extractorUrl = self.extractorUrl;
        if ((mask & 2) != 0) referer = self.referer;
        if ((mask & 4) != 0) raw = self.raw;
        if ((mask & 8) != 0) headers = self.headers;
        return self.copy(extractorUrl, referer, raw, headers);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TrailerData)) return false;
        TrailerData value = (TrailerData) other;
        return raw == value.raw && Objects.equals(extractorUrl, value.extractorUrl)
                && Objects.equals(referer, value.referer) && Objects.equals(headers, value.headers);
    }

    @Override public int hashCode() { return Objects.hash(extractorUrl, referer, raw, headers); }

    @Override public String toString() {
        return "TrailerData(extractorUrl=" + extractorUrl + ", referer=" + referer
                + ", raw=" + raw + ", headers=" + headers + ")";
    }
}
