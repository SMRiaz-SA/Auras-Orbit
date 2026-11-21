package com.lagradost.cloudstream3;

import java.util.Map;

/** TV-series search model matching the pinned CS3 TvSeriesSearchResponse source shape. */
public final class TvSeriesSearchResponse extends OrbitSearchResponse {
    private Integer year;
    private Integer episodes;

    public TvSeriesSearchResponse(String name, String url, String apiName, TvType type,
                                  String posterUrl, Integer year, Integer episodes, Integer id,
                                  SearchQuality quality, Map<String, String> posterHeaders,
                                  Score score) {
        super(name, url, apiName, type);
        setPosterUrl(posterUrl);
        this.year = year;
        this.episodes = episodes;
        setId(id);
        setQuality(quality);
        setPosterHeaders(posterHeaders);
        setScore(score);
    }

    public TvSeriesSearchResponse(String name, String url, String apiName, TvType type,
                                  String posterUrl, Integer year, Integer episodes, Integer id,
                                  SearchQuality quality, Map<String, String> posterHeaders,
                                  Score score, int mask,
                                  kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(name, url, apiName,
                (mask & 0x8) != 0 ? null : type,
                (mask & 0x10) != 0 ? null : posterUrl,
                (mask & 0x20) != 0 ? null : year,
                (mask & 0x40) != 0 ? null : episodes,
                (mask & 0x80) != 0 ? null : id,
                (mask & 0x100) != 0 ? null : quality,
                (mask & 0x200) != 0 ? null : posterHeaders,
                (mask & 0x400) != 0 ? null : score);
    }

    /** Deprecated source constructor retained with its Kotlin default-argument mask. */
    public TvSeriesSearchResponse(String name, String url, String apiName, TvType type,
                                  String posterUrl, Integer year, Integer episodes, Integer id,
                                  SearchQuality quality, Map<String, String> posterHeaders) {
        this(name, url, apiName, type, posterUrl, year, episodes, id, quality, posterHeaders, null);
    }

    public TvSeriesSearchResponse(String name, String url, String apiName, TvType type,
                                  String posterUrl, Integer year, Integer episodes, Integer id,
                                  SearchQuality quality, Map<String, String> posterHeaders,
                                  int mask, kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(name, url, apiName,
                type,
                posterUrl,
                (mask & 0x20) != 0 ? null : year,
                (mask & 0x40) != 0 ? null : episodes,
                (mask & 0x80) != 0 ? null : id,
                (mask & 0x100) != 0 ? null : quality,
                (mask & 0x200) != 0 ? null : posterHeaders);
    }

    public Integer getYear() { return year; }
    public void setYear(Integer value) { year = value; }
    public Integer getEpisodes() { return episodes; }
    public void setEpisodes(Integer value) { episodes = value; }
    @Override public void setPosterUrl(String value) { super.setPosterUrl(value); }
}
