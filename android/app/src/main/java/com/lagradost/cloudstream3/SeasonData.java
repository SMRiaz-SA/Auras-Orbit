package com.lagradost.cloudstream3;

import java.util.Objects;
import kotlin.jvm.internal.DefaultConstructorMarker;

/** Source-shaped season label; actual season identity is kept separate from its display label. */
public final class SeasonData {
    private final int season;
    private final String name;
    private final Integer displaySeason;

    public SeasonData(int season, String name, Integer displaySeason) {
        this.season = season;
        this.name = name;
        this.displaySeason = displaySeason;
    }

    /** Kotlin default-argument constructor; name and displaySeason are optional. */
    public SeasonData(int season, String name, Integer displaySeason, int mask,
                      DefaultConstructorMarker marker) {
        this(season, (mask & (1 << 1)) != 0 ? null : name,
                (mask & (1 << 2)) != 0 ? null : displaySeason);
    }

    public int getSeason() { return season; }
    public String getName() { return name; }
    public Integer getDisplaySeason() { return displaySeason; }
    public int component1() { return season; }
    public String component2() { return name; }
    public Integer component3() { return displaySeason; }

    public SeasonData copy(int season, String name, Integer displaySeason) {
        return new SeasonData(season, name, displaySeason);
    }

    public static SeasonData copy$default(SeasonData self, int season, String name,
                                          Integer displaySeason, int mask, Object marker) {
        if (self == null) throw new NullPointerException("self");
        return self.copy((mask & 1) != 0 ? self.season : season,
                (mask & (1 << 1)) != 0 ? self.name : name,
                (mask & (1 << 2)) != 0 ? self.displaySeason : displaySeason);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SeasonData)) return false;
        SeasonData value = (SeasonData) other;
        return season == value.season && Objects.equals(name, value.name)
                && Objects.equals(displaySeason, value.displaySeason);
    }

    @Override public int hashCode() {
        return 31 * (31 * Integer.hashCode(season) + (name == null ? 0 : name.hashCode()))
                + (displaySeason == null ? 0 : displaySeason.hashCode());
    }

    @Override public String toString() {
        return "SeasonData(season=" + season + ", name=" + name
                + ", displaySeason=" + displaySeason + ")";
    }
}
