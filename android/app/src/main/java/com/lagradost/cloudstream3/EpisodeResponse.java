package com.lagradost.cloudstream3;

import java.util.List;
import java.util.Map;

/** Shared series metadata contract used by CS3 TV-series and anime responses. */
public interface EpisodeResponse {
    ShowStatus getShowStatus();
    void setShowStatus(ShowStatus value);
    NextAiring getNextAiring();
    void setNextAiring(NextAiring value);
    List<SeasonData> getSeasonNames();
    void setSeasonNames(List<SeasonData> value);
    Map<DubStatus, Integer> getLatestEpisodes();
    int getTotalEpisodeIndex(int episode, int season);
}
