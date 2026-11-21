package com.lagradost.cloudstream3;

/** Live search result model matching the pinned CS3 response contract. */
public class LiveSearchResponse extends OrbitSearchResponse {
    private String lang;

    private LiveSearchResponse() { super(); }

    public LiveSearchResponse(String name, String url, String apiName, TvType type,
                              String posterUrl, Integer id, SearchQuality quality,
                              java.util.Map<String, String> posterHeaders, String lang,
                              Score score) {
        super(requireNonNull(name, "name"), requireNonNull(url, "url"),
                requireNonNull(apiName, "apiName"), type);
        setPosterUrl(posterUrl);
        setId(id);
        setQuality(quality);
        setPosterHeaders(posterHeaders);
        this.lang = lang;
        setScore(score);
    }

    public LiveSearchResponse(String name, String url, String apiName, TvType type,
                              String posterUrl, Integer id, SearchQuality quality,
                              java.util.Map<String, String> posterHeaders, String lang,
                              Score score, int mask,
                              kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(name, url, apiName,
                (mask & 8) != 0 ? null : type,
                (mask & 16) != 0 ? null : posterUrl,
                (mask & 32) != 0 ? null : id,
                (mask & 64) != 0 ? null : quality,
                (mask & 128) != 0 ? null : posterHeaders,
                (mask & 256) != 0 ? null : lang,
                (mask & 512) != 0 ? null : score);
    }

    private static String requireNonNull(String value, String parameterName) {
        if (value == null) throw new NullPointerException(parameterName);
        return value;
    }

    public String getLang() { return lang; }
    public void setLang(String value) { lang = value; }

    @Override public void setPosterUrl(String value) { super.setPosterUrl(value); }
}
