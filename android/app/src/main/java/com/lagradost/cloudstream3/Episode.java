package com.lagradost.cloudstream3;

import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;

/**
 * Orbit-owned source-shaped CS3 episode value.
 *
 * <p>The modern shape mirrors the pinned {@code Episode} data class. A second synthetic
 * constructor preserves the historical Integer-rating/no-runtime descriptor referenced by
 * published plugins. Both shapes normalize the deprecated rating to CS3's {@link Score} model.
 */
public final class Episode {
    private String data;
    private String name;
    private Integer season;
    private Integer episode;
    private String posterUrl;
    private Score score;
    private String description;
    private Long date;
    private Integer runTime;

    /** Modern primary-constructor shape from the pinned CS3 API. */
    public Episode(String data, String name, Integer season, Integer episode, String posterUrl,
                   Score score, String description, Long date, Integer runTime) {
        this.data = required(data, "data");
        this.name = name;
        this.season = season;
        this.episode = episode;
        this.posterUrl = posterUrl;
        this.score = score;
        this.description = description;
        this.date = date;
        this.runTime = runTime;
    }

    /** Kotlin default-argument constructor for the modern primary-constructor shape. */
    public Episode(String data, String name, Integer season, Integer episode, String posterUrl,
                   Score score, String description, Long date, Integer runTime,
                   int mask, DefaultConstructorMarker marker) {
        this(data,
                (mask & (1 << 1)) != 0 ? null : name,
                (mask & (1 << 2)) != 0 ? null : season,
                (mask & (1 << 3)) != 0 ? null : episode,
                (mask & (1 << 4)) != 0 ? null : posterUrl,
                (mask & (1 << 5)) != 0 ? null : score,
                (mask & (1 << 6)) != 0 ? null : description,
                (mask & (1 << 7)) != 0 ? null : date,
                (mask & (1 << 8)) != 0 ? null : runTime);
    }

    /** Historical primary-constructor shape where score was exposed as an Integer rating. */
    public Episode(String data, String name, Integer season, Integer episode, String posterUrl,
                   Integer rating, String description, Long date) {
        this(data, name, season, episode, posterUrl, scoreFromLegacyRating(rating), description,
                date, null);
    }

    /** Kotlin default-argument constructor preserved for historical plugin binaries. */
    public Episode(String data, String name, Integer season, Integer episode, String posterUrl,
                   Integer rating, String description, Long date,
                   int mask, DefaultConstructorMarker marker) {
        this(data,
                (mask & (1 << 1)) != 0 ? null : name,
                (mask & (1 << 2)) != 0 ? null : season,
                (mask & (1 << 3)) != 0 ? null : episode,
                (mask & (1 << 4)) != 0 ? null : posterUrl,
                (mask & (1 << 5)) != 0 ? null : rating,
                (mask & (1 << 6)) != 0 ? null : description,
                (mask & (1 << 7)) != 0 ? null : date);
    }

    private static String required(String value, String parameter) {
        Intrinsics.checkNotNullParameter(value, parameter);
        return value;
    }

    private static Score scoreFromLegacyRating(Integer rating) {
        return Score.Companion.from(rating, 100);
    }

    public String getData() { return data; }
    public void setData(String value) { data = required(value, "data"); }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public Integer getSeason() { return season; }
    public void setSeason(Integer value) { season = value; }
    public Integer getEpisode() { return episode; }
    public void setEpisode(Integer value) { episode = value; }
    public String getPosterUrl() { return posterUrl; }
    public void setPosterUrl(String value) { posterUrl = value; }
    public Score getScore() { return score; }
    public void setScore(Score value) { score = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public Long getDate() { return date; }
    public void setDate(Long value) { date = value; }
    public Integer getRunTime() { return runTime; }
    public void setRunTime(Integer value) { runTime = value; }

    /** Deprecated CS3 0..100 rating property backed by the normalized Score value. */
    public Integer getRating() { return score == null ? null : score.toInt(100); }
    public void setRating(Integer value) { score = scoreFromLegacyRating(value); }

    public String component1() { return data; }
    public String component2() { return name; }
    public Integer component3() { return season; }
    public Integer component4() { return episode; }
    public String component5() { return posterUrl; }
    public Score component6() { return score; }
    public String component7() { return description; }
    public Long component8() { return date; }
    public Integer component9() { return runTime; }

    public Episode copy(String data, String name, Integer season, Integer episode, String posterUrl,
                        Score score, String description, Long date, Integer runTime) {
        return new Episode(data, name, season, episode, posterUrl, score, description, date, runTime);
    }

    public static Episode copy$default(Episode self, String data, String name, Integer season,
                                       Integer episode, String posterUrl, Score score,
                                       String description, Long date, Integer runTime,
                                       int mask, Object marker) {
        if (self == null) throw new NullPointerException("self");
        return self.copy(
                (mask & (1 << 0)) != 0 ? self.data : data,
                (mask & (1 << 1)) != 0 ? self.name : name,
                (mask & (1 << 2)) != 0 ? self.season : season,
                (mask & (1 << 3)) != 0 ? self.episode : episode,
                (mask & (1 << 4)) != 0 ? self.posterUrl : posterUrl,
                (mask & (1 << 5)) != 0 ? self.score : score,
                (mask & (1 << 6)) != 0 ? self.description : description,
                (mask & (1 << 7)) != 0 ? self.date : date,
                (mask & (1 << 8)) != 0 ? self.runTime : runTime);
    }

    /** Historical data-class copy shape, retaining old Integer-rating semantics. */
    public Episode copy(String data, String name, Integer season, Integer episode, String posterUrl,
                        Integer rating, String description, Long date) {
        return new Episode(data, name, season, episode, posterUrl, rating, description, date);
    }

    public static Episode copy$default(Episode self, String data, String name, Integer season,
                                       Integer episode, String posterUrl, Integer rating,
                                       String description, Long date, int mask, Object marker) {
        if (self == null) throw new NullPointerException("self");
        return self.copy(
                (mask & (1 << 0)) != 0 ? self.data : data,
                (mask & (1 << 1)) != 0 ? self.name : name,
                (mask & (1 << 2)) != 0 ? self.season : season,
                (mask & (1 << 3)) != 0 ? self.episode : episode,
                (mask & (1 << 4)) != 0 ? self.posterUrl : posterUrl,
                (mask & (1 << 5)) != 0 ? self.getRating() : rating,
                (mask & (1 << 6)) != 0 ? self.description : description,
                (mask & (1 << 7)) != 0 ? self.date : date);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Episode)) return false;
        Episode value = (Episode) other;
        return Intrinsics.areEqual(data, value.data)
                && Intrinsics.areEqual(name, value.name)
                && Intrinsics.areEqual(season, value.season)
                && Intrinsics.areEqual(episode, value.episode)
                && Intrinsics.areEqual(posterUrl, value.posterUrl)
                && Intrinsics.areEqual(score, value.score)
                && Intrinsics.areEqual(description, value.description)
                && Intrinsics.areEqual(date, value.date)
                && Intrinsics.areEqual(runTime, value.runTime);
    }

    @Override public int hashCode() {
        int result = data.hashCode();
        result = 31 * result + (name == null ? 0 : name.hashCode());
        result = 31 * result + (season == null ? 0 : season.hashCode());
        result = 31 * result + (episode == null ? 0 : episode.hashCode());
        result = 31 * result + (posterUrl == null ? 0 : posterUrl.hashCode());
        result = 31 * result + (score == null ? 0 : score.hashCode());
        result = 31 * result + (description == null ? 0 : description.hashCode());
        result = 31 * result + (date == null ? 0 : date.hashCode());
        result = 31 * result + (runTime == null ? 0 : runTime.hashCode());
        return result;
    }

    @Override public String toString() {
        return "Episode(data=<redacted>, name=" + name + ", season=" + season
                + ", episode=" + episode + ", posterUrl=" + posterUrl + ", score=" + score
                + ", description=" + description + ", date=" + date + ", runTime=" + runTime + ")";
    }
}
