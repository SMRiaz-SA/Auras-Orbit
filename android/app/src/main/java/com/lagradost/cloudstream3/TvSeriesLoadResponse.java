package com.lagradost.cloudstream3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Source-shaped CS3 TV-series response consumed by Orbit's provider adapter. */
public final class TvSeriesLoadResponse extends OrbitLoadResponse implements EpisodeResponse {
    private List<Episode> episodes = Collections.emptyList();
    private ShowStatus showStatus;
    private NextAiring nextAiring;
    private List<SeasonData> seasonNames;

    public TvSeriesLoadResponse(
            String name, String url, String apiName, TvType type, List<Episode> episodes,
            String posterUrl, Integer year, String plot, ShowStatus showStatus, Score score,
            List<String> tags, Integer duration, List<TrailerData> trailers,
            List<? extends SearchResponse> recommendations, List<ActorData> actors,
            boolean comingSoon, Map<String, String> syncData, Map<String, String> posterHeaders,
            NextAiring nextAiring, List<SeasonData> seasonNames, String backgroundPosterUrl,
            String logoUrl, String contentRating, String uniqueUrl) {
        setName(required(name, "name"));
        setUrl(required(url, "url"));
        setApiName(required(apiName, "apiName"));
        setType(required(type, "type"));
        setEpisodes(required(episodes, "episodes"));
        setPosterUrl(posterUrl);
        setYear(year);
        setPlot(plot);
        this.showStatus = showStatus;
        setScore(score);
        setTags(tags);
        setDuration(duration);
        setTrailers(required(trailers, "trailers"));
        setRecommendations(recommendations);
        setActors(actors);
        setComingSoon(comingSoon);
        setSyncData(required(syncData, "syncData"));
        setPosterHeaders(posterHeaders);
        this.nextAiring = nextAiring;
        setSeasonNames(seasonNames);
        setBackgroundPosterUrl(backgroundPosterUrl);
        setLogoUrl(logoUrl);
        setContentRating(contentRating);
        setUniqueUrl(required(uniqueUrl, "uniqueUrl"));
    }

    /** Kotlin primary-constructor default bridge; optional properties occupy bits 5 through 23. */
    public TvSeriesLoadResponse(
            String name, String url, String apiName, TvType type, List<Episode> episodes,
            String posterUrl, Integer year, String plot, ShowStatus showStatus, Score score,
            List<String> tags, Integer duration, List<TrailerData> trailers,
            List<? extends SearchResponse> recommendations, List<ActorData> actors,
            boolean comingSoon, Map<String, String> syncData, Map<String, String> posterHeaders,
            NextAiring nextAiring, List<SeasonData> seasonNames, String backgroundPosterUrl,
            String logoUrl, String contentRating, String uniqueUrl,
            int mask, kotlin.jvm.internal.DefaultConstructorMarker marker) {
        this(name, url, apiName, type, episodes,
                (mask & (1 << 5)) != 0 ? null : posterUrl,
                (mask & (1 << 6)) != 0 ? null : year,
                (mask & (1 << 7)) != 0 ? null : plot,
                (mask & (1 << 8)) != 0 ? null : showStatus,
                (mask & (1 << 9)) != 0 ? null : score,
                (mask & (1 << 10)) != 0 ? null : tags,
                (mask & (1 << 11)) != 0 ? null : duration,
                (mask & (1 << 12)) != 0 ? new ArrayList<>() : trailers,
                (mask & (1 << 13)) != 0 ? null : recommendations,
                (mask & (1 << 14)) != 0 ? null : actors,
                (mask & (1 << 15)) != 0 ? false : comingSoon,
                (mask & (1 << 16)) != 0 ? new LinkedHashMap<>() : syncData,
                (mask & (1 << 17)) != 0 ? null : posterHeaders,
                (mask & (1 << 18)) != 0 ? null : nextAiring,
                (mask & (1 << 19)) != 0 ? null : seasonNames,
                (mask & (1 << 20)) != 0 ? null : backgroundPosterUrl,
                (mask & (1 << 21)) != 0 ? null : logoUrl,
                (mask & (1 << 22)) != 0 ? null : contentRating,
                (mask & (1 << 23)) != 0 ? url : uniqueUrl);
    }

    public TvSeriesLoadResponse(String name, String url, String apiName, TvType type,
                                List<Episode> episodes) {
        setName(required(name, "name"));
        setUrl(required(url, "url"));
        setApiName(required(apiName, "apiName"));
        setType(required(type, "type"));
        setUniqueUrl(url);
        setEpisodes(episodes);
        setComingSoon(this.episodes.isEmpty());
    }

    public List<Episode> getEpisodes() { return episodes; }
    public void setEpisodes(List<Episode> value) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(value, "episodes");
        episodes = value;
    }

    public String component1() { return getName(); }
    public String component2() { return getUrl(); }
    public String component3() { return getApiName(); }
    public TvType component4() { return getType(); }
    public List<Episode> component5() { return episodes; }
    public String component6() { return getPosterUrl(); }
    public Integer component7() { return getYear(); }
    public String component8() { return getPlot(); }
    public ShowStatus component9() { return showStatus; }
    public Score component10() { return getScore(); }
    public List<String> component11() { return getTags(); }
    public Integer component12() { return getDuration(); }
    public List<TrailerData> component13() { return getTrailers(); }
    public List<SearchResponse> component14() { return getRecommendations(); }
    public List<ActorData> component15() { return getActors(); }
    public boolean component16() { return getComingSoon(); }
    public Map<String, String> component17() { return getSyncData(); }
    public Map<String, String> component18() { return getPosterHeaders(); }
    public NextAiring component19() { return nextAiring; }
    public List<SeasonData> component20() { return seasonNames; }
    public String component21() { return getBackgroundPosterUrl(); }
    public String component22() { return getLogoUrl(); }
    public String component23() { return getContentRating(); }
    public String component24() { return getUniqueUrl(); }

    public TvSeriesLoadResponse copy(
            String name, String url, String apiName, TvType type, List<Episode> episodes,
            String posterUrl, Integer year, String plot, ShowStatus showStatus, Score score,
            List<String> tags, Integer duration, List<TrailerData> trailers,
            List<? extends SearchResponse> recommendations, List<ActorData> actors,
            boolean comingSoon, Map<String, String> syncData, Map<String, String> posterHeaders,
            NextAiring nextAiring, List<SeasonData> seasonNames, String backgroundPosterUrl,
            String logoUrl, String contentRating, String uniqueUrl) {
        return new TvSeriesLoadResponse(name, url, apiName, type, episodes, posterUrl, year,
                plot, showStatus, score, tags, duration, trailers, recommendations, actors,
                comingSoon, syncData, posterHeaders, nextAiring, seasonNames,
                backgroundPosterUrl, logoUrl, contentRating, uniqueUrl);
    }

    public static TvSeriesLoadResponse copy$default(TvSeriesLoadResponse self,
            String name, String url, String apiName, TvType type, List<Episode> episodes,
            String posterUrl, Integer year, String plot, ShowStatus showStatus, Score score,
            List<String> tags, Integer duration, List<TrailerData> trailers,
            List<? extends SearchResponse> recommendations, List<ActorData> actors,
            boolean comingSoon, Map<String, String> syncData, Map<String, String> posterHeaders,
            NextAiring nextAiring, List<SeasonData> seasonNames, String backgroundPosterUrl,
            String logoUrl, String contentRating, String uniqueUrl, int mask, Object marker) {
        if ((mask & (1 << 0)) != 0) name = self.getName();
        if ((mask & (1 << 1)) != 0) url = self.getUrl();
        if ((mask & (1 << 2)) != 0) apiName = self.getApiName();
        if ((mask & (1 << 3)) != 0) type = self.getType();
        if ((mask & (1 << 4)) != 0) episodes = self.getEpisodes();
        if ((mask & (1 << 5)) != 0) posterUrl = self.getPosterUrl();
        if ((mask & (1 << 6)) != 0) year = self.getYear();
        if ((mask & (1 << 7)) != 0) plot = self.getPlot();
        if ((mask & (1 << 8)) != 0) showStatus = self.getShowStatus();
        if ((mask & (1 << 9)) != 0) score = self.getScore();
        if ((mask & (1 << 10)) != 0) tags = self.getTags();
        if ((mask & (1 << 11)) != 0) duration = self.getDuration();
        if ((mask & (1 << 12)) != 0) trailers = self.getTrailers();
        if ((mask & (1 << 13)) != 0) recommendations = self.getRecommendations();
        if ((mask & (1 << 14)) != 0) actors = self.getActors();
        if ((mask & (1 << 15)) != 0) comingSoon = self.getComingSoon();
        if ((mask & (1 << 16)) != 0) syncData = self.getSyncData();
        if ((mask & (1 << 17)) != 0) posterHeaders = self.getPosterHeaders();
        if ((mask & (1 << 18)) != 0) nextAiring = self.getNextAiring();
        if ((mask & (1 << 19)) != 0) seasonNames = self.getSeasonNames();
        if ((mask & (1 << 20)) != 0) backgroundPosterUrl = self.getBackgroundPosterUrl();
        if ((mask & (1 << 21)) != 0) logoUrl = self.getLogoUrl();
        if ((mask & (1 << 22)) != 0) contentRating = self.getContentRating();
        if ((mask & (1 << 23)) != 0) uniqueUrl = self.getUniqueUrl();
        return self.copy(name, url, apiName, type, episodes, posterUrl, year, plot, showStatus,
                score, tags, duration, trailers, recommendations, actors, comingSoon, syncData,
                posterHeaders, nextAiring, seasonNames, backgroundPosterUrl, logoUrl,
                contentRating, uniqueUrl);
    }

    @Override public ShowStatus getShowStatus() { return showStatus; }
    @Override public void setShowStatus(ShowStatus value) { showStatus = value; }
    @Override public NextAiring getNextAiring() { return nextAiring; }
    @Override public void setNextAiring(NextAiring value) { nextAiring = value; }
    @Override public List<SeasonData> getSeasonNames() { return seasonNames; }
    @Override public void setSeasonNames(List<SeasonData> value) {
        seasonNames = value;
    }

    @Override public Map<DubStatus, Integer> getLatestEpisodes() {
        Integer maxSeason = null;
        for (Episode episode : episodes) {
            int season = episode.getSeason() == null ? Integer.MIN_VALUE : episode.getSeason();
            if (maxSeason == null || season > maxSeason) maxSeason = season;
        }
        Integer latest = null;
        for (Episode episode : episodes) {
            int season = episode.getSeason() == null ? Integer.MIN_VALUE : episode.getSeason();
            if (maxSeason != null && season == maxSeason && episode.getEpisode() != null
                    && (latest == null || episode.getEpisode() > latest)) {
                latest = episode.getEpisode();
            }
        }
        LinkedHashMap<DubStatus, Integer> result = new LinkedHashMap<>();
        result.put(DubStatus.None, latest);
        return Collections.unmodifiableMap(result);
    }

    @Override public int getTotalEpisodeIndex(int episode, int season) {
        Map<Integer, Integer> displayMap = new LinkedHashMap<>();
        if (seasonNames != null) {
            for (SeasonData value : seasonNames) {
                if (value.getDisplaySeason() != null) {
                    displayMap.put(value.getSeason(), value.getDisplaySeason());
                }
            }
        }
        int previousCount = 0;
        for (Episode value : episodes) {
            Integer actualSeason = value.getSeason();
            int displaySeason = actualSeason == null ? Integer.MIN_VALUE
                    : displayMap.getOrDefault(actualSeason, actualSeason);
            if (displaySeason >= 1 && displaySeason < season) previousCount++;
        }
        return previousCount + episode;
    }

    @Override public void setActors(List<ActorData> value) { super.setActors(value); }
    @Override public void setBackgroundPosterUrl(String value) { super.setBackgroundPosterUrl(value); }
    @Override public void setContentRating(String value) { super.setContentRating(value); }
    @Override public void setDuration(Integer value) { super.setDuration(value); }
    @Override public void setLogoUrl(String value) { super.setLogoUrl(value); }
    @Override public void setPlot(String value) { super.setPlot(value); }
    @Override public void setPosterUrl(String value) { super.setPosterUrl(value); }
    @Override public void setRecommendations(List<? extends SearchResponse> value) {
        super.setRecommendations(value);
    }
    @Override public void setScore(Score value) { super.setScore(value); }
    /** Deprecated pinned 0..10,000 rating property backed by Score. */
    @Override public Integer getRating() { return getScore() == null ? null : getScore().toOld(); }
    @Override public void setRating(Integer value) { setScore(Score.Companion.fromOld(value)); }
    @Override public void setTags(List<String> value) { super.setTags(value); }
    @Override public void setYear(Integer value) { super.setYear(value); }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TvSeriesLoadResponse)) return false;
        TvSeriesLoadResponse that = (TvSeriesLoadResponse) other;
        return getComingSoon() == that.getComingSoon()
                && java.util.Objects.equals(getName(), that.getName())
                && java.util.Objects.equals(getUrl(), that.getUrl())
                && java.util.Objects.equals(getApiName(), that.getApiName())
                && getType() == that.getType()
                && java.util.Objects.equals(episodes, that.episodes)
                && java.util.Objects.equals(getPosterUrl(), that.getPosterUrl())
                && java.util.Objects.equals(getYear(), that.getYear())
                && java.util.Objects.equals(getPlot(), that.getPlot())
                && showStatus == that.showStatus
                && java.util.Objects.equals(getScore(), that.getScore())
                && java.util.Objects.equals(getTags(), that.getTags())
                && java.util.Objects.equals(getDuration(), that.getDuration())
                && java.util.Objects.equals(getTrailers(), that.getTrailers())
                && java.util.Objects.equals(getRecommendations(), that.getRecommendations())
                && java.util.Objects.equals(getActors(), that.getActors())
                && java.util.Objects.equals(getSyncData(), that.getSyncData())
                && java.util.Objects.equals(getPosterHeaders(), that.getPosterHeaders())
                && java.util.Objects.equals(nextAiring, that.nextAiring)
                && java.util.Objects.equals(seasonNames, that.seasonNames)
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
        result = 31 * result + episodes.hashCode();
        result = 31 * result + componentHash(getPosterUrl());
        result = 31 * result + componentHash(getYear());
        result = 31 * result + componentHash(getPlot());
        result = 31 * result + componentHash(showStatus);
        result = 31 * result + componentHash(getScore());
        result = 31 * result + componentHash(getTags());
        result = 31 * result + componentHash(getDuration());
        result = 31 * result + componentHash(getTrailers());
        result = 31 * result + componentHash(getRecommendations());
        result = 31 * result + componentHash(getActors());
        result = 31 * result + (getComingSoon() ? 1231 : 1237);
        result = 31 * result + componentHash(getSyncData());
        result = 31 * result + componentHash(getPosterHeaders());
        result = 31 * result + componentHash(nextAiring);
        result = 31 * result + componentHash(seasonNames);
        result = 31 * result + componentHash(getBackgroundPosterUrl());
        result = 31 * result + componentHash(getLogoUrl());
        result = 31 * result + componentHash(getContentRating());
        result = 31 * result + getUniqueUrl().hashCode();
        return result;
    }

    @Override public String toString() {
        return "TvSeriesLoadResponse(name=" + getName() + ", url=" + getUrl()
                + ", apiName=" + getApiName() + ", type=" + getType()
                + ", episodes=" + episodes + ", posterUrl=" + getPosterUrl()
                + ", year=" + getYear() + ", plot=" + getPlot() + ", showStatus=" + showStatus
                + ", score=" + getScore() + ", tags=" + getTags() + ", duration=" + getDuration()
                + ", trailers=" + getTrailers() + ", recommendations=" + getRecommendations()
                + ", actors=" + getActors() + ", comingSoon=" + getComingSoon()
                + ", syncData=" + getSyncData() + ", posterHeaders=" + getPosterHeaders()
                + ", nextAiring=" + nextAiring + ", seasonNames=" + seasonNames
                + ", backgroundPosterUrl=" + getBackgroundPosterUrl()
                + ", logoUrl=" + getLogoUrl() + ", contentRating=" + getContentRating()
                + ", uniqueUrl=" + getUniqueUrl() + ")";
    }

    private static int componentHash(Object value) {
        return value == null ? 0 : value.hashCode();
    }

    private static <T> T required(T value, String field) {
        if (value == null) throw new NullPointerException(field + " cannot be null");
        return value;
    }
}
