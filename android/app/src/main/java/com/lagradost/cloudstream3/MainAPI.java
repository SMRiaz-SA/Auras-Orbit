package com.lagradost.cloudstream3;

import java.util.Collections;
import java.util.Arrays;
import java.util.Set;

import com.lagradost.cloudstream3.syncproviders.SyncIdName;

/**
 * Orbit-owned MainAPI compatibility facade. Provider metadata and registration
 * are implemented locally. Base provider operations fail closed when a plugin
 * does not override them; Orbit invokes plugin implementations only behind its
 * compatibility and runtime gates.
 */
public abstract class MainAPI {
    public static final Companion Companion = new Companion();

    private volatile String name = "NONE";
    private volatile String mainUrl = "NONE";
    private volatile String storedCredentials;
    private volatile boolean canBeOverridden = true;
    private volatile boolean sequentialMainPage;
    private volatile long sequentialMainPageDelay;
    private volatile long sequentialMainPageScrollDelay;
    private volatile String lang = "en";
    private volatile String sourcePlugin;
    private volatile long lastHomepageRequest;

    public MainAPI() { }

    /** Applies CloudStream's process-local provider override before provider use. */
    public final void init() {
        java.util.HashMap<String, ProvidersInfoJson> overrides = Companion.getOverrideData();
        ProvidersInfoJson data = overrides == null ? null : overrides.get(getClass().getSimpleName());
        if (data != null) overrideWithNewData(data);
    }

    /** Applies the pinned CloudStream metadata override when this provider permits it. */
    public final void overrideWithNewData(ProvidersInfoJson data) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(data, "data");
        if (!canBeOverridden) return;
        setName(data.getName());
        String overrideUrl = data.getUrl();
        if (isNotBlank(overrideUrl) && !"NONE".equals(overrideUrl)) {
            setMainUrl(overrideUrl);
        }
        setStoredCredentials(data.getCredentials());
    }

    private static boolean isNotBlank(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return true;
        }
        return false;
    }

    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getMainUrl() { return mainUrl; }
    public void setMainUrl(String value) { mainUrl = value; }
    public String getStoredCredentials() { return storedCredentials; }
    public void setStoredCredentials(String value) { storedCredentials = value; }
    public boolean getCanBeOverridden() { return canBeOverridden; }
    public boolean isCanBeOverridden() { return canBeOverridden; }
    public void setCanBeOverridden(boolean value) { canBeOverridden = value; }
    public boolean getSequentialMainPage() { return sequentialMainPage; }
    public void setSequentialMainPage(boolean value) { sequentialMainPage = value; }
    public long getSequentialMainPageDelay() { return sequentialMainPageDelay; }
    public void setSequentialMainPageDelay(long value) { sequentialMainPageDelay = value; }
    public long getSequentialMainPageScrollDelay() { return sequentialMainPageScrollDelay; }
    public void setSequentialMainPageScrollDelay(long value) { sequentialMainPageScrollDelay = value; }
    public String getLang() { return lang; }
    public void setLang(String value) { lang = value; }
    public String getSourcePlugin() { return sourcePlugin; }
    public void setSourcePlugin(String value) { sourcePlugin = value; }
    public long getLastHomepageRequest() { return lastHomepageRequest; }
    public void setLastHomepageRequest(long value) { lastHomepageRequest = value; }

    public boolean getInstantLinkLoading() { return false; }
    public boolean getHasChromecastSupport() { return true; }
    public boolean getHasDownloadSupport() { return true; }
    public boolean getUsesWebView() { return false; }
    public boolean getHasMainPage() { return false; }
    public boolean getHasQuickSearch() { return false; }
    public Long getLoadLinksTimeoutMs() { return null; }
    public Long getGetMainPageTimeoutMs() { return null; }
    public Long getSearchTimeoutMs() { return null; }
    public Long getQuickSearchTimeoutMs() { return null; }
    public Long getLoadTimeoutMs() { return null; }
    public Set<SyncIdName> getSupportedSyncNames() { return Collections.emptySet(); }
    public Set<TvType> getSupportedTypes() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(Arrays.asList(
                TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Anime, TvType.OVA)));
    }
    public VPNStatus getVpnStatus() { return VPNStatus.None; }
    public ProviderType getProviderType() { return ProviderType.DirectProvider; }
    public java.util.List<?> getMainPage() {
        return Collections.singletonList(new MainPageData("", "", false));
    }

    public Object getMainPage(int page, MainPageRequest request,
                              kotlin.coroutines.Continuation<? super HomePageResponse> continuation) {
        throw new kotlin.NotImplementedError();
    }
    public Object search(String query, int page,
                         kotlin.coroutines.Continuation<? super SearchResponseList> continuation) {
        return OrbitMainApiDefaults.searchPage(this, query, continuation);
    }
    public Object search(String query,
                         kotlin.coroutines.Continuation<? super java.util.List<SearchResponse>> continuation) {
        throw new kotlin.NotImplementedError();
    }
    public Object quickSearch(String query,
                              kotlin.coroutines.Continuation<? super java.util.List<SearchResponse>> continuation) {
        throw new kotlin.NotImplementedError();
    }
    public Object load(String url, kotlin.coroutines.Continuation<? super LoadResponse> continuation) {
        throw new kotlin.NotImplementedError();
    }
    public Object extractorVerifierJob(String data, kotlin.coroutines.Continuation<?> continuation) {
        throw new kotlin.NotImplementedError();
    }
    public Object loadLinks(String data, boolean isCasting,
                            kotlin.jvm.functions.Function1<? super SubtitleFile, kotlin.Unit> subtitleCallback,
                            kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> callback,
                            kotlin.coroutines.Continuation<? super Boolean> continuation) {
        throw new kotlin.NotImplementedError();
    }
    public okhttp3.Interceptor getVideoInterceptor(com.lagradost.cloudstream3.utils.ExtractorLink link) {
        return null;
    }
    public Object getLoadUrl(SyncIdName name, String id, kotlin.coroutines.Continuation<?> continuation) {
        return null;
    }

    public static class Companion {
        private volatile java.util.HashMap<String, ProvidersInfoJson> overrideData;
        private volatile SettingsJson settingsForProvider = new SettingsJson();
        private Companion() { }
        public java.util.HashMap<String, ProvidersInfoJson> getOverrideData() { return overrideData; }
        public void setOverrideData(java.util.HashMap<String, ProvidersInfoJson> value) { overrideData = value; }
        public SettingsJson getSettingsForProvider() {
            return settingsForProvider;
        }
        public void setSettingsForProvider(SettingsJson value) { settingsForProvider = value; }
    }
}
