package com.lagradost.cloudstream3;

import com.lagradost.cloudstream3.OrbitCs3FacadeBoundary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Orbit-owned static descriptor facade; required service behavior is unimplemented. */
public class AnimeSearchResponse extends OrbitSearchResponse {
    private AnimeSearchResponse() { super(); }
    private java.lang.Integer year;
    private java.lang.String otherName;
    private Set<DubStatus> dubStatus;
    private Map<DubStatus, Integer> episodes = new LinkedHashMap<>();

    AnimeSearchResponse(java.lang.String name, java.lang.String url, java.lang.String apiName, TvType type) {
        super(name, url, apiName, type);
    }

    public java.lang.String getOtherName() { return otherName; }
    public void setOtherName(java.lang.String p0) {
        otherName = p0;
    }
    public void setPosterUrl(java.lang.String p0) {
        super.setPosterUrl(p0);
    }
    public void setScore(com.lagradost.cloudstream3.Score p0) {
        super.setScore(p0);
    }
    public java.lang.Integer getYear() { return year; }
    public void setYear(java.lang.Integer p0) {
        year = p0;
    }
    public Set<DubStatus> getDubStatus() { return dubStatus; }
    public void setDubStatus(Set<DubStatus> p0) { dubStatus = p0; }
    public Map<DubStatus, Integer> getEpisodes() { return episodes; }
    public void setEpisodes(Map<DubStatus, Integer> p0) {
        if (p0 == null) throw new NullPointerException("episodes");
        episodes = p0;
    }
}
