package com.lagradost.cloudstream3;

import java.util.Map;

/** Pinned minimal contract shared by downloadable media values. */
public interface IDownloadableMinimum {
    String getUrl();
    String getReferer();
    Map<String, String> getHeaders();
}
