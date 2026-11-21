package com.lagradost.cloudstream3;

import java.util.List;
import java.util.Map;

/** Source-shaped CS3 movie details value. Episode and other response variants remain unsupported. */
public final class MovieLoadResponse extends OrbitLoadResponse {
    private String dataUrl;

    public MovieLoadResponse(
            String name, String url, String apiName, TvType type, String dataUrl,
            String posterUrl, Integer year, String plot, Score score, List<String> tags,
            Integer duration, List<TrailerData> trailers,
            List<? extends SearchResponse> recommendations, List<ActorData> actors,
            boolean comingSoon, Map<String, String> syncData,
            Map<String, String> posterHeaders, String backgroundPosterUrl,
            String logoUrl, String contentRating, String uniqueUrl) {
        super();
        setName(required(name, "name"));
        setUrl(required(url, "url"));
        setApiName(required(apiName, "apiName"));
        setType(required(type, "type"));
        this.dataUrl = required(dataUrl, "dataUrl");
        setPosterUrl(posterUrl);
        setYear(year);
        setPlot(plot);
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

    public MovieLoadResponse(
            String name, String url, String apiName, TvType type, String dataUrl,
            String posterUrl, Integer year, String plot, Score score, List<String> tags,
            Integer duration, List<TrailerData> trailers,
            List<? extends SearchResponse> recommendations, List<ActorData> actors,
            boolean comingSoon, Map<String, String> syncData, Map<String, String> posterHeaders,
            String backgroundPosterUrl, String logoUrl, String contentRating,
            String uniqueUrl, int mask, kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(name, url, apiName, type, dataUrl,
                (mask & (1 << 5)) != 0 ? null : posterUrl,
                (mask & (1 << 6)) != 0 ? null : year,
                (mask & (1 << 7)) != 0 ? null : plot,
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

    /** Deprecated pinned 0..10,000 rating property backed by Score. */
    @Override public Integer getRating() { return getScore() == null ? null : getScore().toOld(); }
    @Override public void setRating(Integer value) { setScore(Score.Companion.fromOld(value)); }

    public String component1() { return getName(); }
    public String component2() { return getUrl(); }
    public String component3() { return getApiName(); }
    public TvType component4() { return getType(); }
    public String component5() { return dataUrl; }
    public String component6() { return getPosterUrl(); }
    public Integer component7() { return getYear(); }
    public String component8() { return getPlot(); }
    public Score component9() { return getScore(); }
    public List<String> component10() { return getTags(); }
    public Integer component11() { return getDuration(); }
    public List<TrailerData> component12() { return getTrailers(); }
    public List<SearchResponse> component13() { return getRecommendations(); }
    public List<ActorData> component14() { return getActors(); }
    public boolean component15() { return getComingSoon(); }
    public Map<String, String> component16() { return getSyncData(); }
    public Map<String, String> component17() { return getPosterHeaders(); }
    public String component18() { return getBackgroundPosterUrl(); }
    public String component19() { return getLogoUrl(); }
    public String component20() { return getContentRating(); }
    public String component21() { return getUniqueUrl(); }

    public MovieLoadResponse copy(
            String name, String url, String apiName, TvType type, String dataUrl,
            String posterUrl, Integer year, String plot, Score score, List<String> tags,
            Integer duration, List<TrailerData> trailers, List<? extends SearchResponse> recommendations,
            List<ActorData> actors, boolean comingSoon, Map<String, String> syncData,
            Map<String, String> posterHeaders, String backgroundPosterUrl, String logoUrl,
            String contentRating, String uniqueUrl) {
        return new MovieLoadResponse(name, url, apiName, type, dataUrl, posterUrl, year, plot,
                score, tags, duration, trailers, recommendations, actors, comingSoon, syncData,
                posterHeaders, backgroundPosterUrl, logoUrl, contentRating, uniqueUrl);
    }

    public static MovieLoadResponse copy$default(MovieLoadResponse self,
            String name, String url, String apiName, TvType type, String dataUrl,
            String posterUrl, Integer year, String plot, Score score, List<String> tags,
            Integer duration, List<TrailerData> trailers, List<? extends SearchResponse> recommendations,
            List<ActorData> actors, boolean comingSoon, Map<String, String> syncData,
            Map<String, String> posterHeaders, String backgroundPosterUrl, String logoUrl,
            String contentRating, String uniqueUrl, int mask, Object marker) {
        if ((mask & (1 << 0)) != 0) name = self.getName();
        if ((mask & (1 << 1)) != 0) url = self.getUrl();
        if ((mask & (1 << 2)) != 0) apiName = self.getApiName();
        if ((mask & (1 << 3)) != 0) type = self.getType();
        if ((mask & (1 << 4)) != 0) dataUrl = self.getDataUrl();
        if ((mask & (1 << 5)) != 0) posterUrl = self.getPosterUrl();
        if ((mask & (1 << 6)) != 0) year = self.getYear();
        if ((mask & (1 << 7)) != 0) plot = self.getPlot();
        if ((mask & (1 << 8)) != 0) score = self.getScore();
        if ((mask & (1 << 9)) != 0) tags = self.getTags();
        if ((mask & (1 << 10)) != 0) duration = self.getDuration();
        if ((mask & (1 << 11)) != 0) trailers = self.getTrailers();
        if ((mask & (1 << 12)) != 0) recommendations = self.getRecommendations();
        if ((mask & (1 << 13)) != 0) actors = self.getActors();
        if ((mask & (1 << 14)) != 0) comingSoon = self.getComingSoon();
        if ((mask & (1 << 15)) != 0) syncData = self.getSyncData();
        if ((mask & (1 << 16)) != 0) posterHeaders = self.getPosterHeaders();
        if ((mask & (1 << 17)) != 0) backgroundPosterUrl = self.getBackgroundPosterUrl();
        if ((mask & (1 << 18)) != 0) logoUrl = self.getLogoUrl();
        if ((mask & (1 << 19)) != 0) contentRating = self.getContentRating();
        if ((mask & (1 << 20)) != 0) uniqueUrl = self.getUniqueUrl();
        return self.copy(name, url, apiName, type, dataUrl, posterUrl, year, plot, score,
                tags, duration, trailers, recommendations, actors, comingSoon, syncData,
                posterHeaders, backgroundPosterUrl, logoUrl, contentRating, uniqueUrl);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MovieLoadResponse)) return false;
        MovieLoadResponse that = (MovieLoadResponse) other;
        return getComingSoon() == that.getComingSoon()
                && java.util.Objects.equals(getName(), that.getName())
                && java.util.Objects.equals(getUrl(), that.getUrl())
                && java.util.Objects.equals(getApiName(), that.getApiName())
                && getType() == that.getType()
                && java.util.Objects.equals(dataUrl, that.dataUrl)
                && java.util.Objects.equals(getPosterUrl(), that.getPosterUrl())
                && java.util.Objects.equals(getYear(), that.getYear())
                && java.util.Objects.equals(getPlot(), that.getPlot())
                && java.util.Objects.equals(getScore(), that.getScore())
                && java.util.Objects.equals(getTags(), that.getTags())
                && java.util.Objects.equals(getDuration(), that.getDuration())
                && java.util.Objects.equals(getTrailers(), that.getTrailers())
                && java.util.Objects.equals(getRecommendations(), that.getRecommendations())
                && java.util.Objects.equals(getActors(), that.getActors())
                && java.util.Objects.equals(getSyncData(), that.getSyncData())
                && java.util.Objects.equals(getPosterHeaders(), that.getPosterHeaders())
                && java.util.Objects.equals(getBackgroundPosterUrl(), that.getBackgroundPosterUrl())
                && java.util.Objects.equals(getLogoUrl(), that.getLogoUrl())
                && java.util.Objects.equals(getContentRating(), that.getContentRating())
                && java.util.Objects.equals(getUniqueUrl(), that.getUniqueUrl());
    }

    @Override public int hashCode() {
        int result = getName().hashCode();
        result = 31 * result + getUrl().hashCode();
        result = 31 * result + getApiName().hashCode();
        result = 31 * result + getType().hashCode();
        result = 31 * result + dataUrl.hashCode();
        result = 31 * result + componentHash(getPosterUrl());
        result = 31 * result + componentHash(getYear());
        result = 31 * result + componentHash(getPlot());
        result = 31 * result + componentHash(getScore());
        result = 31 * result + componentHash(getTags());
        result = 31 * result + componentHash(getDuration());
        result = 31 * result + componentHash(getTrailers());
        result = 31 * result + componentHash(getRecommendations());
        result = 31 * result + componentHash(getActors());
        result = 31 * result + (getComingSoon() ? 1231 : 1237);
        result = 31 * result + componentHash(getSyncData());
        result = 31 * result + componentHash(getPosterHeaders());
        result = 31 * result + componentHash(getBackgroundPosterUrl());
        result = 31 * result + componentHash(getLogoUrl());
        result = 31 * result + componentHash(getContentRating());
        result = 31 * result + getUniqueUrl().hashCode();
        return result;
    }

    @Override public String toString() {
        return "MovieLoadResponse(name=" + getName() + ", url=" + getUrl()
                + ", apiName=" + getApiName() + ", type=" + getType()
                + ", dataUrl=" + dataUrl + ", posterUrl=" + getPosterUrl()
                + ", year=" + getYear() + ", plot=" + getPlot() + ", score=" + getScore()
                + ", tags=" + getTags() + ", duration=" + getDuration()
                + ", trailers=" + getTrailers() + ", recommendations=" + getRecommendations()
                + ", actors=" + getActors() + ", comingSoon=" + getComingSoon()
                + ", syncData=" + getSyncData() + ", posterHeaders=" + getPosterHeaders()
                + ", backgroundPosterUrl=" + getBackgroundPosterUrl()
                + ", logoUrl=" + getLogoUrl() + ", contentRating=" + getContentRating()
                + ", uniqueUrl=" + getUniqueUrl() + ")";
    }

    private static int componentHash(Object value) {
        return value == null ? 0 : value.hashCode();
    }

    private static String required(String value, String field) {
        if (value == null) throw new NullPointerException(field + " cannot be null");
        return value;
    }

    private static <T> T required(T value, String field) {
        if (value == null) throw new NullPointerException(field + " cannot be null");
        return value;
    }
}
