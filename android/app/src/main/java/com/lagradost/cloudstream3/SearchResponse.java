package com.lagradost.cloudstream3;

import java.util.Map;

/** Orbit-owned interface matching the pinned CS3 SearchResponse contract. */
public interface SearchResponse {
    String getName();
    String getUrl();
    String getApiName();
    TvType getType();
    void setType(TvType value);
    String getPosterUrl();
    void setPosterUrl(String value);
    Map<String, String> getPosterHeaders();
    void setPosterHeaders(Map<String, String> value);
    Integer getId();
    void setId(Integer value);
    SearchQuality getQuality();
    void setQuality(SearchQuality value);
    Score getScore();
    void setScore(Score value);
}

/** Shared Orbit-owned property storage for the currently declared response facades. */
abstract class OrbitSearchResponse implements SearchResponse {
    private final String name;
    private final String url;
    private final String apiName;
    private TvType type;
    private String posterUrl;
    private Map<String, String> posterHeaders;
    private Integer id;
    private SearchQuality quality;
    private Score score;

    protected OrbitSearchResponse() { this("", "", "", null); }

    protected OrbitSearchResponse(String name, String url, String apiName, TvType type) {
        this.name = name;
        this.url = url;
        this.apiName = apiName;
        this.type = type;
    }

    @Override public String getName() { return name; }
    @Override public String getUrl() { return url; }
    @Override public String getApiName() { return apiName; }
    @Override public TvType getType() { return type; }
    @Override public void setType(TvType value) { type = value; }
    @Override public String getPosterUrl() { return posterUrl; }
    @Override public void setPosterUrl(String value) { posterUrl = value; }
    @Override public Map<String, String> getPosterHeaders() { return posterHeaders; }
    @Override public void setPosterHeaders(Map<String, String> value) { posterHeaders = value; }
    @Override public Integer getId() { return id; }
    @Override public void setId(Integer value) { id = value; }
    @Override public SearchQuality getQuality() { return quality; }
    @Override public void setQuality(SearchQuality value) { quality = value; }
    @Override public Score getScore() { return score; }
    @Override public void setScore(Score value) { score = value; }
}
