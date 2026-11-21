package com.lagradost.cloudstream3;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Internal property storage used by Orbit's source-shaped LoadResponse implementation classes. */
abstract class OrbitLoadResponse implements LoadResponse {
    private String name = "";
    private String url = "";
    private String apiName = "";
    private TvType type;
    private String posterUrl;
    private Integer year;
    private String plot;
    private Score score;
    private List<String> tags;
    private Integer duration;
    // CloudStream's LoadResponse contract exposes mutable, non-null collections for
    // trailers and sync data. Provider helpers append to these while building responses.
    private List<TrailerData> trailers = new ArrayList<>();
    private List<SearchResponse> recommendations;
    private List<ActorData> actors;
    private boolean comingSoon;
    private Map<String, String> syncData = new LinkedHashMap<>();
    private Map<String, String> posterHeaders;
    private String backgroundPosterUrl;
    private String logoUrl;
    private String contentRating;
    private String uniqueUrl = "";

    protected OrbitLoadResponse() { }
    @Override public String getName() { return name; }
    @Override public void setName(String value) { name = value; }
    @Override public String getUrl() { return url; }
    @Override public void setUrl(String value) { url = value; }
    @Override public String getApiName() { return apiName; }
    @Override public void setApiName(String value) { apiName = value; }
    @Override public TvType getType() { return type; }
    @Override public void setType(TvType value) { type = value; }
    @Override public String getPosterUrl() { return posterUrl; }
    @Override public void setPosterUrl(String value) { posterUrl = value; }
    @Override public Integer getYear() { return year; }
    @Override public void setYear(Integer value) { year = value; }
    @Override public String getPlot() { return plot; }
    @Override public void setPlot(String value) { plot = value; }
    @Override public Score getScore() { return score; }
    @Override public void setScore(Score value) { score = value; }
    @Override public List<String> getTags() { return tags; }
    @Override public void setTags(List<String> value) { tags = value; }
    @Override public Integer getDuration() { return duration; }
    @Override public void setDuration(Integer value) { duration = value; }
    @Override public List<TrailerData> getTrailers() { return trailers; }
    @Override public void setTrailers(List<TrailerData> value) {
        if (value == null) throw new NullPointerException("trailers");
        trailers = value;
    }
    @Override public List<SearchResponse> getRecommendations() { return recommendations; }
    @SuppressWarnings("unchecked")
    @Override public void setRecommendations(List<? extends SearchResponse> value) {
        recommendations = (List<SearchResponse>) value;
    }
    @Override public List<ActorData> getActors() { return actors; }
    @Override public void setActors(List<ActorData> value) { actors = value; }
    @Override public boolean getComingSoon() { return comingSoon; }
    @Override public void setComingSoon(boolean value) { comingSoon = value; }
    @Override public Map<String, String> getSyncData() { return syncData; }
    @Override public void setSyncData(Map<String, String> value) { syncData = value; }
    @Override public Map<String, String> getPosterHeaders() { return posterHeaders; }
    @Override public void setPosterHeaders(Map<String, String> value) { posterHeaders = value; }
    @Override public String getBackgroundPosterUrl() { return backgroundPosterUrl; }
    @Override public void setBackgroundPosterUrl(String value) { backgroundPosterUrl = value; }
    @Override public String getLogoUrl() { return logoUrl; }
    @Override public void setLogoUrl(String value) { logoUrl = value; }
    @Override public String getContentRating() { return contentRating; }
    @Override public void setContentRating(String value) { contentRating = value; }
    @Override public String getUniqueUrl() { return uniqueUrl; }
    @Override public void setUniqueUrl(String value) { uniqueUrl = value; }
}
