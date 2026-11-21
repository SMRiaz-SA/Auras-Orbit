package com.auras.orbit.stremio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed, immutable Stremio manifest and resource response models used by Orbit. */
public final class StremioModels {
    private StremioModels() { }

    public enum CapabilityDisposition {
        SUPPORTED,
        NOT_SUPPORTED_EXTERNAL_ACTIVITY,
        NOT_SUPPORTED_UNREVIEWED,
        IGNORED_UNKNOWN
    }

    static CapabilityDisposition dispositionFor(String name) {
        if ("catalog".equals(name) || "meta".equals(name) || "stream".equals(name)
                || "subtitles".equals(name) || "addon_catalog".equals(name)) {
            return CapabilityDisposition.SUPPORTED;
        }
        if ("player".equals(name) || "library".equals(name)) {
            return CapabilityDisposition.NOT_SUPPORTED_EXTERNAL_ACTIVITY;
        }
        return CapabilityDisposition.IGNORED_UNKNOWN;
    }

    public static final class ResourceCapability {
        public final String name;
        public final List<String> types;
        public final List<String> idPrefixes;
        public final CapabilityDisposition disposition;
        public final boolean inheritsManifestFilters;

        ResourceCapability(String name, List<String> types, List<String> idPrefixes,
                           CapabilityDisposition disposition, boolean inheritsManifestFilters) {
            this.name = name;
            this.types = immutable(types);
            this.idPrefixes = immutable(idPrefixes);
            this.disposition = disposition;
            this.inheritsManifestFilters = inheritsManifestFilters;
        }
    }

    public static final class ExtraOption {
        public final String name;
        public final boolean required;
        public final List<String> options;
        public final Integer optionsLimit;

        ExtraOption(String name, boolean required, List<String> options, Integer optionsLimit) {
            this.name = name;
            this.required = required;
            this.options = immutable(options);
            this.optionsLimit = optionsLimit;
        }
    }

    public static final class CatalogDefinition {
        public final String type;
        public final String id;
        public final String name;
        public final List<ExtraOption> extra;

        CatalogDefinition(String type, String id, String name, List<ExtraOption> extra) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.extra = immutable(extra);
        }
    }

    public static final class Manifest {
        public final String id;
        public final String name;
        public final String description;
        public final String version;
        public final List<String> types;
        public final List<String> idPrefixes;
        public final List<ResourceCapability> resources;
        public final List<CatalogDefinition> catalogs;

        Manifest(String id, String name, String description, String version,
                 List<String> types, List<String> idPrefixes,
                 List<ResourceCapability> resources, List<CatalogDefinition> catalogs) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.version = version;
            this.types = immutable(types);
            this.idPrefixes = immutable(idPrefixes);
            this.resources = immutable(resources);
            this.catalogs = immutable(catalogs);
        }

        public boolean declares(String resourceName) {
            if (resourceName == null) return false;
            for (ResourceCapability resource : resources) {
                if (resource.name.equals(resourceName)) return true;
            }
            return false;
        }

        public boolean supports(String resourceName, String type, String id) {
            if (resourceName == null || type == null || id == null) return false;
            for (ResourceCapability resource : resources) {
                if (!resource.name.equals(resourceName)
                        || resource.disposition != CapabilityDisposition.SUPPORTED) continue;
                List<String> resourceTypes = resource.inheritsManifestFilters ? types : resource.types;
                List<String> prefixes = resource.inheritsManifestFilters ? idPrefixes : resource.idPrefixes;
                boolean typeMatches = false;
                for (String supportedType : resourceTypes) {
                    if (supportedType.equalsIgnoreCase(type)) { typeMatches = true; break; }
                }
                if (!typeMatches) continue;
                if ("catalog".equals(resourceName) || "addon_catalog".equals(resourceName)) return true;
                if (prefixes.isEmpty()) return true;
                for (String prefix : prefixes) {
                    if (id.startsWith(prefix)) return true;
                }
            }
            return false;
        }
    }

    public static class MetaPreview {
        public final String id;
        public final String type;
        public final String name;
        public final String poster;
        public final String background;
        public final String logo;
        public final String description;
        public final String releaseInfo;
        public final String imdbRating;
        public final List<String> genres;

        MetaPreview(String id, String type, String name, String poster, String background,
                    String logo, String description, String releaseInfo, String imdbRating,
                    List<String> genres) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.poster = poster;
            this.background = background;
            this.logo = logo;
            this.description = description;
            this.releaseInfo = releaseInfo;
            this.imdbRating = imdbRating;
            this.genres = immutable(genres);
        }
    }

    public static final class Video {
        public final String id;
        public final String title;
        public final Integer season;
        public final Integer episode;
        public final String released;
        public final String overview;
        public final String thumbnail;

        Video(String id, String title, Integer season, Integer episode,
              String released, String overview, String thumbnail) {
            this.id = id;
            this.title = title;
            this.season = season;
            this.episode = episode;
            this.released = released;
            this.overview = overview;
            this.thumbnail = thumbnail;
        }
    }

    public static final class Meta extends MetaPreview {
        public final String runtime;
        public final String released;
        public final List<Video> videos;

        Meta(String id, String type, String name, String poster, String background,
             String logo, String description, String releaseInfo, String imdbRating,
             List<String> genres, String runtime, String released, List<Video> videos) {
            super(id, type, name, poster, background, logo, description, releaseInfo,
                    imdbRating, genres);
            this.runtime = runtime;
            this.released = released;
            this.videos = immutable(videos);
        }
    }

    public static final class CatalogResponse {
        public final List<MetaPreview> metas;
        public final Integer cacheMaxAge;

        CatalogResponse(List<MetaPreview> metas, Integer cacheMaxAge) {
            this.metas = immutable(metas);
            this.cacheMaxAge = cacheMaxAge;
        }
    }

    public static final class MetaResponse {
        public final Meta meta;
        public final Integer cacheMaxAge;

        MetaResponse(Meta meta, Integer cacheMaxAge) {
            this.meta = meta;
            this.cacheMaxAge = cacheMaxAge;
        }
    }

    public static final class BehaviorHints {
        public final String bingeGroup;
        public final String filename;
        public final Boolean notWebReady;
        public final String videoHash;
        public final Long videoSize;
        public final Map<String, String> requestHeaders;

        BehaviorHints(String bingeGroup, String filename, Boolean notWebReady, String videoHash,
                      Long videoSize, Map<String, String> requestHeaders) {
            this.bingeGroup = bingeGroup;
            this.filename = filename;
            this.notWebReady = notWebReady;
            this.videoHash = videoHash;
            this.videoSize = videoSize;
            this.requestHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(requestHeaders));
        }
    }

    public static final class Stream {
        public final String name;
        public final String title;
        public final String description;
        public final String url;
        public final String externalUrl;
        public final String ytId;
        public final String infoHash;
        public final Integer fileIdx;
        public final List<String> sources;
        public final BehaviorHints behaviorHints;
        public final List<Subtitle> subtitles;

        Stream(String name, String title, String description, String url, String externalUrl,
               String ytId, String infoHash, Integer fileIdx, List<String> sources,
               BehaviorHints behaviorHints, List<Subtitle> subtitles) {
            this.name = name;
            this.title = title;
            this.description = description;
            this.url = url;
            this.externalUrl = externalUrl;
            this.ytId = ytId;
            this.infoHash = infoHash;
            this.fileIdx = fileIdx;
            this.sources = immutable(sources);
            this.behaviorHints = behaviorHints;
            this.subtitles = immutable(subtitles);
        }
    }

    public static final class StreamResponse {
        public final List<Stream> streams;
        public final Integer cacheMaxAge;

        StreamResponse(List<Stream> streams, Integer cacheMaxAge) {
            this.streams = immutable(streams);
            this.cacheMaxAge = cacheMaxAge;
        }
    }

    public static final class Subtitle {
        public final String id;
        public final String url;
        public final String lang;
        public final String subEncoding;
        public final String videoId;
        public final String format;
        public final String rating;

        Subtitle(String id, String url, String lang, String subEncoding,
                 String videoId, String format, String rating) {
            this.id = id;
            this.url = url;
            this.lang = lang;
            this.subEncoding = subEncoding;
            this.videoId = videoId;
            this.format = format;
            this.rating = rating;
        }
    }

    public static final class SubtitleResponse {
        public final List<Subtitle> subtitles;
        public final Integer cacheMaxAge;

        SubtitleResponse(List<Subtitle> subtitles, Integer cacheMaxAge) {
            this.subtitles = immutable(subtitles);
            this.cacheMaxAge = cacheMaxAge;
        }
    }

    public static final class DiscoveredAddon {
        public final String transportName;
        public final String sourceHost;
        public final Manifest manifest;
        private final String manifestUrl;

        DiscoveredAddon(String transportName, String manifestUrl, String sourceHost, Manifest manifest) {
            this.transportName = transportName;
            this.manifestUrl = manifestUrl;
            this.sourceHost = sourceHost;
            this.manifest = manifest;
        }

        String manifestUrlInternal() { return manifestUrl; }
    }

    public static final class AddonCatalogResponse {
        public final List<DiscoveredAddon> addons;
        public final Integer cacheMaxAge;

        AddonCatalogResponse(List<DiscoveredAddon> addons, Integer cacheMaxAge) {
            this.addons = immutable(addons);
            this.cacheMaxAge = cacheMaxAge;
        }
    }

    static <T> List<T> immutable(List<T> items) {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }
}
