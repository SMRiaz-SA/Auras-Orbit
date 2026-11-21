package com.lagradost.cloudstream3;

import java.util.List;
import java.util.Map;

/** Source-shaped CS3 live-stream response backed by Orbit's shared load-response storage. */
public final class LiveStreamLoadResponse extends OrbitLoadResponse {
    private String dataUrl;
    private List<? extends SearchResponse> recommendations;

    public LiveStreamLoadResponse(
            String name, String url, String apiName, String dataUrl,
            String posterUrl, Integer year, String plot, TvType type, Score score,
            List<String> tags, Integer duration, List<TrailerData> trailers,
            List<? extends SearchResponse> recommendations, List<ActorData> actors,
            boolean comingSoon, Map<String, String> syncData,
            Map<String, String> posterHeaders, String backgroundPosterUrl,
            String logoUrl, String contentRating, String uniqueUrl) {
        super();
        setName(required(name, "name"));
        setUrl(required(url, "url"));
        setApiName(required(apiName, "apiName"));
        this.dataUrl = required(dataUrl, "dataUrl");
        setPosterUrl(posterUrl);
        setYear(year);
        setPlot(plot);
        setType(required(type, "type"));
        setScore(score);
        setTags(tags);
        setDuration(duration);
        setTrailers(required(trailers, "trailers"));
        setRecommendations(recommendations);
        setActors(actors);
        setComingSoon(comingSoon);
        setSyncData(required(syncData, "syncData"));
        setPosterHeaders(posterHeaders);
        setBackgroundPosterUrl(backgroundPosterUrl);
        setLogoUrl(logoUrl);
        setContentRating(contentRating);
        setUniqueUrl(required(uniqueUrl, "uniqueUrl"));
    }

    /** Kotlin default-argument constructor; optional values use the source constructor mask. */
    public LiveStreamLoadResponse(
            String name, String url, String apiName, String dataUrl,
            String posterUrl, Integer year, String plot, TvType type, Score score,
            List<String> tags, Integer duration, List<TrailerData> trailers,
            List<? extends SearchResponse> recommendations, List<ActorData> actors,
            boolean comingSoon, Map<String, String> syncData, Map<String, String> posterHeaders,
            String backgroundPosterUrl, String logoUrl, String contentRating,
            String uniqueUrl, int mask, kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(name, url, apiName, dataUrl,
                (mask & (1 << 4)) != 0 ? null : posterUrl,
                (mask & (1 << 5)) != 0 ? null : year,
                (mask & (1 << 6)) != 0 ? null : plot,
                (mask & (1 << 7)) != 0 ? TvType.Live : type,
                (mask & (1 << 8)) != 0 ? null : score,
                (mask & (1 << 9)) != 0 ? null : tags,
                (mask & (1 << 10)) != 0 ? null : duration,
                (mask & (1 << 11)) != 0 ? new java.util.ArrayList<>() : trailers,
                (mask & (1 << 12)) != 0 ? null : recommendations,
                (mask & (1 << 13)) != 0 ? null : actors,
                (mask & (1 << 14)) != 0 ? false : comingSoon,
                (mask & (1 << 15)) != 0 ? new java.util.LinkedHashMap<>() : syncData,
                (mask & (1 << 16)) != 0 ? null : posterHeaders,
                (mask & (1 << 17)) != 0 ? null : backgroundPosterUrl,
                (mask & (1 << 18)) != 0 ? null : logoUrl,
                (mask & (1 << 19)) != 0 ? null : contentRating,
                (mask & (1 << 20)) != 0 ? url : uniqueUrl);
    }

    public String getDataUrl() { return dataUrl; }
    public void setDataUrl(String value) { dataUrl = required(value, "dataUrl"); }

    @Override public void setName(String value) { super.setName(required(value, "name")); }
    @Override public void setUrl(String value) { super.setUrl(required(value, "url")); }
    @Override public void setApiName(String value) { super.setApiName(required(value, "apiName")); }
    @Override public void setType(TvType value) { super.setType(required(value, "type")); }
    @Override public void setTrailers(List<TrailerData> value) { super.setTrailers(required(value, "trailers")); }
    @Override public void setSyncData(Map<String, String> value) { super.setSyncData(required(value, "syncData")); }
    @Override public void setUniqueUrl(String value) { super.setUniqueUrl(required(value, "uniqueUrl")); }

    @SuppressWarnings("unchecked")
    @Override public List<SearchResponse> getRecommendations() {
        return (List<SearchResponse>) recommendations;
    }
    @Override public void setRecommendations(List<? extends SearchResponse> value) {
        recommendations = value;
    }

    @Override public void setBackgroundPosterUrl(String value) { super.setBackgroundPosterUrl(value); }
    @Override public void setPlot(String value) { super.setPlot(value); }
    @Override public void setPosterUrl(String value) { super.setPosterUrl(value); }
    @Override public void setTags(List<String> value) { super.setTags(value); }

    private static <T> T required(T value, String field) {
        if (value == null) throw new NullPointerException(field + " cannot be null");
        return value;
    }
}
