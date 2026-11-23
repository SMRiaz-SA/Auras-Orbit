package com.auras.orbit.stremio;

import android.content.Context;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Java-facing Stremio workflow for Orbit Settings and content integrations. All methods perform
 * synchronous network or Keystore work and must be called from a worker thread.
 */
public final class StremioAddonService {
    private final StremioAddonClient client;
    private final StremioAddonRegistry registry;

    public StremioAddonService(Context context) {
        this(new StremioAddonClient(), new StremioAddonRegistry(context));
    }

    public StremioAddonService(StremioAddonClient client, StremioAddonRegistry registry) {
        if (client == null || registry == null) throw new IllegalArgumentException("Stremio services are required.");
        this.client = client;
        this.registry = registry;
    }

    /** Fetch a manifest for review without persisting or enabling it. */
    public Preview previewManifest(String url) throws IOException {
        String canonicalUrl = client.normalizeManifestUrl(url);
        StremioModels.Manifest manifest = client.previewManifest(canonicalUrl);
        return new Preview(canonicalUrl, manifest, safeHost(canonicalUrl));
    }

    /** Fetch a discovered candidate again for review; the embedded catalog snapshot is never installed directly. */
    public Preview previewDiscoveredAddon(StremioModels.DiscoveredAddon candidate) throws IOException {
        if (candidate == null) throw new IOException("Choose a discovered Stremio add-on.");
        return previewManifest(candidate.manifestUrlInternal());
    }

    /** Installs a previously reviewed preview. Calling this method is the explicit install action. */
    public StremioAddonRegistry.InstalledAddon install(Preview preview) throws IOException {
        if (preview == null) throw new IOException("Preview a Stremio manifest before installing it.");
        return registry.install(preview.manifestUrl, preview.manifest);
    }

    public List<StremioAddonRegistry.InstalledAddon> getInstalledAddons() throws IOException {
        return registry.getInstalled();
    }

    public void uninstall(String installationId) throws IOException {
        registry.remove(installationId);
    }

    public StremioModels.Manifest refresh(StremioAddonRegistry.InstalledAddon addon) throws IOException {
        return client.refreshInstalled(addon);
    }

    public StremioModels.CatalogResponse getCatalog(
            StremioAddonRegistry.InstalledAddon addon, StremioModels.Manifest manifest,
            StremioModels.CatalogDefinition catalog, Map<String, String> extraArgs) throws IOException {
        return client.getCatalog(addon, manifest, catalog, extraArgs);
    }

    StremioModels.CatalogResponse getHomeCatalog(
            StremioAddonRegistry.InstalledAddon addon, StremioModels.Manifest manifest,
            StremioModels.CatalogDefinition catalog, Map<String, String> extraArgs) throws IOException {
        return client.getHomeCatalog(addon, manifest, catalog, extraArgs);
    }

    public StremioModels.MetaResponse getMetadata(
            StremioAddonRegistry.InstalledAddon addon, StremioModels.Manifest manifest,
            String type, String id) throws IOException {
        return client.getMetadata(addon, manifest, type, id);
    }

    public StremioModels.StreamResponse getStreams(
            StremioAddonRegistry.InstalledAddon addon, StremioModels.Manifest manifest,
            String type, String videoId) throws IOException {
        return client.getStreams(addon, manifest, type, videoId);
    }

    public StremioModels.SubtitleResponse getSubtitles(
            StremioAddonRegistry.InstalledAddon addon, StremioModels.Manifest manifest,
            String type, String videoId, String videoHash, Long videoSize, String filename)
            throws IOException {
        return client.getSubtitles(addon, manifest, type, videoId, videoHash, videoSize, filename);
    }

    /** Return candidates only. A candidate must be previewed and explicitly installed separately. */
    public StremioModels.AddonCatalogResponse discoverAddons(
            StremioAddonRegistry.InstalledAddon addon, StremioModels.Manifest manifest,
            String type, String catalogId, Map<String, String> extraArgs) throws IOException {
        return client.discoverAddons(addon, manifest, type, catalogId, extraArgs);
    }

    private static String safeHost(String url) throws IOException {
        try {
            String host = new java.net.URI(url).getHost();
            if (host == null) throw new IOException("The manifest host is missing.");
            int port = new java.net.URI(url).getPort();
            return host + (port < 0 ? "" : ":" + port);
        } catch (java.net.URISyntaxException error) {
            throw new IOException("The manifest host is invalid.");
        }
    }

    public static final class Preview {
        public final StremioModels.Manifest manifest;
        /** Host and optional port only. Never display or log the full manifest URL. */
        public final String sourceHost;
        private final String manifestUrl;

        private Preview(String manifestUrl, StremioModels.Manifest manifest, String sourceHost) {
            this.manifestUrl = manifestUrl;
            this.manifest = manifest;
            this.sourceHost = sourceHost;
        }
    }
}
