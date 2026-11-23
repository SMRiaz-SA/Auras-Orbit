package com.auras.orbit.providers;

import java.util.List;

/** Orbit-facing adapter contract. A CS3 bridge may implement this only after its host gate passes. */
public interface MediaProvider {
    long DEFAULT_LINK_RESOLUTION_TIMEOUT_MS = 120_000L;
    long MIN_LINK_RESOLUTION_TIMEOUT_MS = 5_000L;
    long MAX_LINK_RESOLUTION_TIMEOUT_MS = 8L * 60L * 1_000L;

    interface LinkProgressListener {
        void onProgress(ProviderLinkResult partialResult);
    }
    ProviderDescriptor descriptor();

    /** Optional ownership scope used to cancel stale operations during profile/plugin changes. */
    default String owningPluginId() { return null; }

    /** Optional active-profile scope used to cancel stale operations during profile changes. */
    default String profileScopeId() { return null; }

    /**
     * Opt-in proof that this adapter still belongs to its exact committed registration.
     * Providers that cannot prove ownership of the current registration must keep false.
     */
    default boolean isRegistrationCurrent() { return false; }

    /**
     * Load provider-authored Home sections. An initial request has no section id and returns all
     * sections; a later request identifies exactly one section and passes that section's opaque
     * cursor forward. Providers that cannot page a section should return an empty next cursor.
     */
    default List<HomeSection> loadHome(HomeRequest request) throws Exception {
        throw new UnsupportedOperationException("Home feeds are not supported by this provider");
    }

    /**
     * Load a provider Home batch with an optional provider-level continuation cursor. Existing
     * adapters remain source-compatible; only providers that paginate their section catalog
     * need to override this method. Section item cursors remain on each HomeSection.
     */
    default HomePage loadHomePage(HomeRequest request) throws Exception {
        if (request != null && request.isProviderContinuationRequest()) {
            return new HomePage(java.util.Collections.<HomeSection>emptyList(), "");
        }
        List<HomeSection> sections = loadHome(request);
        if (sections == null) throw new IllegalStateException("Provider returned no Home response");
        return new HomePage(sections, "");
    }

    default List<MediaItem> search(SearchRequest request) throws Exception {
        throw new UnsupportedOperationException("Title search is not supported by this provider");
    }

    /** Home-only short query path; providers without a CS3 quick-search override remain unsupported. */
    default List<MediaItem> quickSearch(SearchRequest request) throws Exception {
        throw new UnsupportedOperationException("Quick search is not supported by this provider");
    }

    /**
     * Opt-in: true only when THIS live adapter still owns every execution-context
     * dependency needed by a previously loaded result. This is NOT a freshness claim.
     * Providers that cannot prove the contract must keep the default false.
     */
    default boolean isLoadedDetailsContextCurrent(MediaItem details) { return false; }

    default MediaItem loadDetails(String providerItemId) throws Exception {
        throw new UnsupportedOperationException("Title details are not supported by this provider");
    }

    /** Resolves a provider item into stream and subtitle candidates without starting playback. */
    default ProviderLinkResult resolveLinks(String providerItemId, boolean isCasting) throws Exception {
        throw new UnsupportedOperationException("Link resolution is not supported by this provider");
    }
    /**
     * Progressive variant for players that can offer early callbacks while resolution continues.
     * Providers without callback support keep their existing terminal-only behavior.
     */
    default ProviderLinkResult resolveLinks(String providerItemId, boolean isCasting,
                                            LinkProgressListener progressListener) throws Exception {
        ProviderLinkResult result = resolveLinks(providerItemId, isCasting);
        if (progressListener != null && result != null) progressListener.onProgress(result);
        return result;
    }

    /** CloudStream-compatible link timeout. Provider adapters may override it. */
    default long linkResolutionTimeoutMs() {
        return DEFAULT_LINK_RESOLUTION_TIMEOUT_MS;
    }
}
