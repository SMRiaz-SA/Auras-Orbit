package com.lagradost.cloudstream3.extractors;

import com.lagradost.cloudstream3.utils.ExtractorApi;
import com.lagradost.cloudstream3.utils.OrbitShortLinkResolver;
import kotlin.coroutines.Continuation;

/**
 * Bounded Orbit implementation of CloudStream GMPlayer from commit
 * a65828e2b093f4deca3da7b211254b06c9c382fb (GPL-3.0).
 */
public class GMPlayer extends ExtractorApi {
    public GMPlayer() {
        super();
    }

    @Override
    public String getName() {
        return "GM Player";
    }

    @Override
    public String getMainUrl() {
        return "https://gmplayer.xyz";
    }

    @Override
    public boolean getRequiresReferer() {
        return true;
    }

    /** Preserve the historical three-argument suspend JVM descriptor. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public Object getUrl(String url, String referer, Continuation<?> continuation) {
        return OrbitShortLinkResolver.extractGMPlayerLinks(
                getName(),
                url,
                referer,
                getMainUrl(),
                (Continuation) continuation);
    }
}
