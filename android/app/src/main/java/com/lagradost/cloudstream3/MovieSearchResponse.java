package com.lagradost.cloudstream3;

/** Movie search model matching the pinned CS3 MovieSearchResponse source shape. */
public final class MovieSearchResponse extends OrbitSearchResponse {
    private Integer year;

    private MovieSearchResponse() { super(); }

    public MovieSearchResponse(String name, String url, String apiName, TvType type,
                               String posterUrl, Integer year, Integer id,
                               SearchQuality quality, java.util.Map<String, String> posterHeaders,
                               Score score) {
        super(name, url, apiName, type);
        setPosterUrl(posterUrl);
        this.year = year;
        setId(id);
        setQuality(quality);
        setPosterHeaders(posterHeaders);
        setScore(score);
    }

    public MovieSearchResponse(String name, String url, String apiName, TvType type,
                               String posterUrl, Integer year, Integer id,
                               SearchQuality quality, java.util.Map<String, String> posterHeaders,
                               Score score, int mask,
                               kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(name, url, apiName, (mask & 8) != 0 ? null : type,
                (mask & 16) != 0 ? null : posterUrl,
                (mask & 32) != 0 ? null : year,
                (mask & 64) != 0 ? null : id,
                (mask & 128) != 0 ? null : quality,
                (mask & 256) != 0 ? null : posterHeaders,
                (mask & 512) != 0 ? null : score);
    }

    public Integer getYear() { return year; }
    public void setYear(Integer value) { year = value; }
    @Override public void setPosterUrl(String value) { super.setPosterUrl(value); }
    @Override public void setScore(Score value) { super.setScore(value); }
}
