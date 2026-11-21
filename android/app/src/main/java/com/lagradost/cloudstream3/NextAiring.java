package com.lagradost.cloudstream3;

import java.util.Objects;
import kotlin.jvm.internal.DefaultConstructorMarker;

/** Source-shaped CS3 next-airing value. */
public final class NextAiring {
    private final int episode;
    private final long unixTime;
    private final Integer season;

    public NextAiring(int episode, long unixTime, Integer season) {
        this.episode = episode;
        this.unixTime = unixTime;
        this.season = season;
    }

    /** Kotlin default-argument constructor; only season is optional. */
    public NextAiring(int episode, long unixTime, Integer season, int mask,
                      DefaultConstructorMarker marker) {
        this(episode, unixTime, (mask & (1 << 2)) != 0 ? null : season);
    }

    public int getEpisode() { return episode; }
    public long getUnixTime() { return unixTime; }
    public Integer getSeason() { return season; }
    public int component1() { return episode; }
    public long component2() { return unixTime; }
    public Integer component3() { return season; }

    public NextAiring copy(int episode, long unixTime, Integer season) {
        return new NextAiring(episode, unixTime, season);
    }

    public static NextAiring copy$default(NextAiring self, int episode, long unixTime,
                                          Integer season, int mask, Object marker) {
        if (self == null) throw new NullPointerException("self");
        return self.copy((mask & 1) != 0 ? self.episode : episode,
                (mask & (1 << 1)) != 0 ? self.unixTime : unixTime,
                (mask & (1 << 2)) != 0 ? self.season : season);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof NextAiring)) return false;
        NextAiring value = (NextAiring) other;
        return episode == value.episode && unixTime == value.unixTime
                && Objects.equals(season, value.season);
    }

    @Override public int hashCode() {
        return 31 * (31 * Integer.hashCode(episode) + Long.hashCode(unixTime))
                + (season == null ? 0 : season.hashCode());
    }

    @Override public String toString() {
        return "NextAiring(episode=" + episode + ", unixTime=" + unixTime
                + ", season=" + season + ")";
    }
}
