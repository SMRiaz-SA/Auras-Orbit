package com.auras.orbit.ui;

import android.widget.ImageView;

import com.auras.orbit.providers.MediaItem;
import com.auras.orbit.providers.MediaProvider;
import com.auras.orbit.providers.ProviderBatch;
import com.auras.orbit.providers.ProviderDetailsKindCompatibility;

import java.util.Collections;
import java.util.Map;

/** Selected Home hero details request state. It retains no Activity-owned view after invalidation. */
final class HomeFeaturedHeroDetailsState {
    final String profileId;
    final MediaProvider provider;
    final String stableId;
    final String detailUrl;
    final String selectionKey;
    final int selectedIndex;
    final int selectionGeneration;
    final int uiGeneration;

    boolean detailsAttempted;
    ProviderBatch<MediaItem> detailsBatch;
    ImageView currentOverlay;
    String backgroundPosterUrl = "";
    String backgroundPosterHeaderOriginUrl = "";
    Map<String, String> backgroundPosterHeaders = Collections.emptyMap();

    HomeFeaturedHeroDetailsState(String profileId, MediaProvider provider, MediaItem item,
            String selectionKey, int selectedIndex, int selectionGeneration, int uiGeneration) {
        this.profileId = profileId == null ? "" : profileId;
        this.provider = provider;
        this.stableId = item == null ? "" : item.stableId;
        this.detailUrl = item == null ? "" : item.detailUrl;
        this.selectionKey = selectionKey == null ? "" : selectionKey;
        this.selectedIndex = selectedIndex;
        this.selectionGeneration = selectionGeneration;
        this.uiGeneration = uiGeneration;
    }

    boolean matches(MediaProvider candidateProvider, String candidateProfileId,
            MediaItem item, String candidateSelectionKey, int candidateIndex,
            int candidateSelectionGeneration, int candidateUiGeneration) {
        return matchesProvider(candidateProvider)
                && equals(profileId, candidateProfileId)
                && item != null
                && equals(stableId, item.stableId)
                && equals(detailUrl, item.detailUrl)
                && equals(selectionKey, candidateSelectionKey)
                && selectedIndex == candidateIndex
                && selectionGeneration == candidateSelectionGeneration
                && uiGeneration == candidateUiGeneration;
    }

    boolean matchesProvider(MediaProvider candidate) {
        if (provider == candidate) return provider != null;
        if (provider == null || candidate == null
                || provider.descriptor() == null || candidate.descriptor() == null) return false;
        return equals(provider.descriptor().id, candidate.descriptor().id)
                && equals(provider.owningPluginId(), candidate.owningPluginId())
                && equals(provider.profileScopeId(), candidate.profileScopeId())
                && provider.isRegistrationCurrent()
                && candidate.isRegistrationCurrent();
    }

    boolean markDetailsAttempted() {
        if (detailsAttempted) return false;
        detailsAttempted = true;
        return true;
    }

    boolean applyDetails(MediaItem seed, MediaItem details, boolean selectionCurrent,
            boolean backgroundUsable) {
        if (!selectionCurrent || !backgroundUsable
                || !matchesDetailsIdentity(seed, details)
                || details.backgroundPosterUrl == null
                || details.backgroundPosterUrl.trim().isEmpty()) return false;
        backgroundPosterUrl = details.backgroundPosterUrl;
        backgroundPosterHeaderOriginUrl = details.posterUrl == null || details.posterUrl.trim().isEmpty()
                ? seed.posterUrl : details.posterUrl;
        backgroundPosterHeaders = details.posterHeaders;
        return true;
    }

    boolean matchesDetailsIdentity(MediaItem seed, MediaItem details) {
        return seed != null && details != null
                && equals(stableId, seed.stableId)
                && equals(detailUrl, seed.detailUrl)
                && equals(seed.stableId, details.stableId)
                && ProviderDetailsKindCompatibility.isCompatible(seed.kind, details.kind);
    }

    void updateOverlay(ImageView overlay) {
        currentOverlay = overlay;
    }

    void clearViewReferences() {
        currentOverlay = null;
    }

    private static boolean equals(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }
}
