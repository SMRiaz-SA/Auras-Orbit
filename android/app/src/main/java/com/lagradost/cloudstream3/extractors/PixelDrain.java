package com.lagradost.cloudstream3.extractors;

import com.lagradost.cloudstream3.SubtitleFile;
import com.lagradost.cloudstream3.utils.ExtractorApi;
import com.lagradost.cloudstream3.utils.ExtractorApiKt;
import com.lagradost.cloudstream3.utils.ExtractorLink;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;
import okhttp3.HttpUrl;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Bounded Orbit implementation of CloudStream PixelDrain from stable v4.8.0
 * commit 3796406e636356f556c16e04e1da4e37d6a19038 and prerelease commit
 * 5024ace2922a148d38df2a816b483b529789cf58 (GPL-3.0).
 */
public class PixelDrain extends ExtractorApi {
    private static final int MAX_URL_CHARS = 4_096;
    private static final int MAX_ID_CHARS = 256;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,256}");

    public PixelDrain() { super(); }

    @Override public String getName() { return "PixelDrain"; }
    @Override public String getMainUrl() { return "https://pixeldrain.com"; }
    @Override public boolean getRequiresReferer() { return true; }

    /** Preserve the pinned callback suspend descriptor and emit at most one validated link. */
    @SuppressWarnings("unchecked")
    @Override
    public Object getUrl(String url, String referer,
                         Function1<? super SubtitleFile, Unit> subtitleCallback,
                         Function1<? super ExtractorLink, Unit> callback,
                         Continuation<?> continuation) {
        if (url == null) throw new NullPointerException("url");
        if (subtitleCallback == null) throw new NullPointerException("subtitleCallback");
        if (callback == null) throw new NullPointerException("callback");
        if (continuation == null) throw new NullPointerException("continuation");

        if (!ExtractorApiKt.isBoundedExtractorHttpUrl(url)) return Unit.INSTANCE;
        String destination = normalizeDestination(url);
        if (destination == null) return Unit.INSTANCE;

        return ExtractorApiKt.emitValidatedExtractorLink(
                getName(), getName(), destination, url, (Function1) callback, continuation);
    }

    private String normalizeDestination(String input) {
        if (input.length() < 1 || input.length() > MAX_URL_CHARS) return null;
        try {
            URI inputUri = new URI(input);
            String path = inputUri.getRawPath();
            int routeStart = path == null ? -1 : path.indexOf("/u/");
            if (routeStart < 0) return input;

            String idAndSuffix = path.substring(routeStart + 3);
            int nextSlash = idAndSuffix.indexOf('/');
            String id = nextSlash < 0 ? idAndSuffix : idAndSuffix.substring(0, nextSlash);
            if (id.isEmpty()) return input;
            if (id.length() > MAX_ID_CHARS || !SAFE_ID.matcher(id).matches()) return null;

            String mainUrl = getMainUrl();
            if (!ExtractorApiKt.isBoundedExtractorHttpUrl(mainUrl)) return null;
            URI mainUri = new URI(mainUrl);
            String mainPath = mainUri.getRawPath();
            if (mainUri.getRawUserInfo() != null || mainUri.getRawQuery() != null ||
                    mainUri.getRawFragment() != null ||
                    (mainPath != null && !mainPath.isEmpty() && !"/".equals(mainPath))) {
                return null;
            }

            HttpUrl base = HttpUrl.get(mainUrl);
            if (!("http".equals(base.scheme()) || "https".equals(base.scheme()))) return null;
            return base.newBuilder()
                    .encodedPath("/api/file/")
                    .addPathSegment(id)
                    .encodedQuery("download")
                    .build()
                    .toString();
        } catch (Exception invalidInput) {
            return null;
        }
    }
}
