package com.auras.orbit.stremio;

import android.util.Base64;

import com.auras.orbit.player.PlaybackRequest;
import com.auras.orbit.player.SubtitleFormat;
import com.auras.orbit.providers.HomeRequest;
import com.auras.orbit.providers.HomePage;
import com.auras.orbit.providers.HomeSection;
import com.auras.orbit.providers.MediaItem;
import com.auras.orbit.providers.MediaKind;
import com.auras.orbit.providers.MediaProvider;
import com.auras.orbit.providers.ProviderDescriptor;
import com.auras.orbit.providers.ProviderLinkCandidate;
import com.auras.orbit.providers.ProviderLinkResult;
import com.auras.orbit.providers.ProviderSubtitleCandidate;
import com.auras.orbit.providers.SearchRequest;
import com.auras.orbit.providers.SearchType;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter for one explicitly installed, declarative Stremio add-on. Methods make synchronous
 * service calls and must only run on the provider worker context used by ProviderOperationCoordinator.
 * Add-on results stay in memory; this provider does not cache or persist catalogs, metadata, or links.
 */
public final class StremioMediaProvider implements MediaProvider {
    private static final int MAX_ITEMS_PER_SECTION = 100;
    private static final int MAX_SEARCH_RESULTS = 100;
    // Provider admission and playback retain the same explicit source ceiling.
    private static final int MAX_EPISODES = MediaItem.MAX_EPISODES;
    private static final int MAX_STREMIO_ID_CHARS = 512;
    private static final int MAX_TYPE_CHARS = 128;
    private static final int MAX_SUBTITLES = 30;
    private static final int MAX_CATALOG_CALLS_PER_OPERATION = 8;
    private static final long MAX_CATALOG_FANOUT_MS = StremioAddonClient.CALL_TIMEOUT_MS + 5_000L;
    private static final String PARTIAL_HOME_TITLE_SUFFIX = " · partial results";
    private static final String PARTIAL_HOME_FAILURE_MESSAGE =
            "Could not load a Stremio catalog. Retry to continue.";
    private static final String PARTIAL_SEARCH_ID_PREFIX = "stremio-partial-search-";
    public static final String PARTIAL_SEARCH_MESSAGE =
            "Partial Stremio results: Orbit reached its catalog request budget or 100-result limit; more catalogs or matches may be available.";
    private static final Pattern QUALITY_PATTERN = Pattern.compile("(?:^|\\D)(2160|1440|1080|720|576|480|360)p(?:\\D|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RUNTIME_PATTERN = Pattern.compile("^(\\d{1,4})(?::(\\d{1,2}))?(?:\\s*min(?:utes?)?)?$",
            Pattern.CASE_INSENSITIVE);
    private static final String TOKEN_PREFIX = "stremio1";

    private final StremioAddonService service;
    private final StremioAddonRegistry.InstalledAddon addon;
    private final StremioModels.Manifest manifest;
    private final ProviderDescriptor descriptor;

    /**
     * Bind the adapter to a registry-issued installation and the manifest refreshed for it.
     * Callers should obtain the manifest with StremioAddonService.refresh on a worker thread.
     */
    public StremioMediaProvider(StremioAddonService service,
                                StremioAddonRegistry.InstalledAddon addon,
                                StremioModels.Manifest manifest) {
        if (service == null || addon == null || manifest == null) {
            throw new IllegalArgumentException("An installed Stremio add-on and manifest are required.");
        }
        if (!addon.manifestId.equals(manifest.id)) {
            throw new IllegalArgumentException("The manifest does not match this installed Stremio add-on.");
        }
        this.service = service;
        this.addon = addon;
        this.manifest = manifest;
        boolean hasHome = hasHomeCatalog();
        boolean hasSearch = hasSearchCatalog();
        boolean hasDetails = hasSupportedType("meta", "movie") || hasSupportedType("meta", "series");
        boolean hasStreams = hasSupportedType("stream", "movie") || hasSupportedType("stream", "series");
        this.descriptor = new ProviderDescriptor("stremio-" + addon.installationId,
                safeDisplay(addon.name, "Stremio add-on", 160), hasHome, hasSearch,
                hasSearch, hasDetails, hasStreams, hasStreams, false);
    }

    @Override public ProviderDescriptor descriptor() { return descriptor; }

    @Override public List<HomeSection> loadHome(HomeRequest request) throws IOException {
        return loadHomePage(request).sections;
    }

    @Override public HomePage loadHomePage(HomeRequest request) throws IOException {
        if (request == null) throw new IllegalArgumentException("A Home request is required.");
        if (!descriptor.homeFeed) return new HomePage(Collections.emptyList(), "");
        long skip = resolveHomeSkip(request);

        ArrayList<StremioModels.CatalogDefinition> eligibleCatalogs = new ArrayList<>();
        boolean matchedRequestedSection = false;
        for (StremioModels.CatalogDefinition catalog : manifest.catalogs) {
            if (!homeRequestTargetsCatalog(request, catalog)) continue;
            if (request.isSectionRequest()) matchedRequestedSection = true;
            MediaKind kind = mediaKind(catalog.type);
            if (kind == MediaKind.UNKNOWN || !requestedKind(request, kind)
                    || !manifest.supports("catalog", catalog.type, catalog.id)
                    || catalogArguments(catalog, null, skip, false) == null) continue;
            eligibleCatalogs.add(catalog);
        }
        if (request.isSectionRequest() && !matchedRequestedSection) {
            throw new IOException("The requested Stremio catalog section is no longer available.");
        }
        ArrayList<HomeSection> sections = new ArrayList<>();
        CatalogCallBudget budget = new CatalogCallBudget();
        StremioHomeCatalogCursor cursorState = request.isProviderContinuationRequest()
                ? StremioHomeCatalogCursor.parse(request.providerCursor, eligibleCatalogs.size())
                : StremioHomeCatalogCursor.parse("", eligibleCatalogs.size());
        int nextFreshIndex = cursorState.nextFreshIndex;
        ArrayList<Integer> failedCatalogIndices = new ArrayList<>(cursorState.failedCatalogIndices);
        boolean retryOnly = !request.isSectionRequest()
                && cursorState.isRetryOnly(eligibleCatalogs.size());

        if (retryOnly) {
            ArrayList<Integer> attempted = new ArrayList<>();
            ArrayList<Integer> stillFailed = new ArrayList<>();
            for (Integer failedIndex : cursorState.failedCatalogIndices) {
                if (!budget.tryStartCall()) break;
                attempted.add(failedIndex);
                try {
                    sections.add(loadHomeSection(eligibleCatalogs.get(failedIndex), skip));
                } catch (IOException error) {
                    stillFailed.add(failedIndex);
                }
            }
            failedCatalogIndices = new ArrayList<>(StremioHomeCatalogCursor.remainingFailures(
                    cursorState.failedCatalogIndices, attempted, stillFailed));
        } else {
            while (nextFreshIndex < eligibleCatalogs.size()) {
                int catalogIndex = nextFreshIndex;
                StremioModels.CatalogDefinition catalog = eligibleCatalogs.get(catalogIndex);
                if (!budget.tryStartCall()) break;
                boolean failed = false;
                try {
                    sections.add(loadHomeSection(catalog, skip));
                } catch (IOException error) {
                    if (request.isSectionRequest()) throw error;
                    failed = true;
                }
                cursorState = cursorState.afterFreshAttempt(
                        catalogIndex, failed, eligibleCatalogs.size());
                nextFreshIndex = cursorState.nextFreshIndex;
                failedCatalogIndices = new ArrayList<>(cursorState.failedCatalogIndices);
            }
        }
        boolean hasMoreProviderSections = !request.isSectionRequest()
                && nextFreshIndex < eligibleCatalogs.size();
        boolean retryAvailable = !request.isSectionRequest() && !hasMoreProviderSections
                && !failedCatalogIndices.isEmpty();
        String nextProviderCursor = request.isSectionRequest() ? ""
                : StremioHomeCatalogCursor.create(nextFreshIndex, failedCatalogIndices,
                        eligibleCatalogs.size()).encode(eligibleCatalogs.size());
        if (!nextProviderCursor.isEmpty()) {
            for (int index = 0; index < sections.size(); index++) {
                HomeSection section = sections.get(index);
                sections.set(index, new HomeSection(section.id,
                        section.title + PARTIAL_HOME_TITLE_SUFFIX, section.items, section.nextCursor));
            }
        }
        String continuationError = failedCatalogIndices.isEmpty() ? ""
                : PARTIAL_HOME_FAILURE_MESSAGE;
        return new HomePage(sections, nextProviderCursor, continuationError,
                hasMoreProviderSections, retryAvailable);
    }

    private HomeSection loadHomeSection(StremioModels.CatalogDefinition catalog, long skip)
            throws IOException {
        Map<String, String> args = catalogArguments(catalog, null, skip, false);
        if (args == null) throw new IOException("The Stremio catalog is not available for Home.");
        StremioModels.CatalogResponse response = service.getHomeCatalog(addon, manifest, catalog, args);
        List<MediaItem> items = mapPreviews(response.metas, MAX_ITEMS_PER_SECTION);
        MediaKind kind = mediaKind(catalog.type);
        String title = safeDisplay(catalog.name, fallbackTypeTitle(kind), 160);
        return new HomeSection(catalogSectionId(catalog), title, items,
                nextHomeCursor(catalog, response, skip));
    }

    @Override public List<MediaItem> search(SearchRequest request) throws IOException {
        if (request == null) throw new IllegalArgumentException("A Stremio search request is required.");
        if (!descriptor.titleSearch) return Collections.emptyList();
        int skip = pageSkip(request.page);
        if (skip < 0) throw new IOException("The requested Stremio search page is outside the supported range.");

        LinkedHashMap<String, MediaItem> results = new LinkedHashMap<>();
        IOException lastFailure = null;
        boolean queriedAnyCatalog = false;
        boolean completedAnyCatalog = false;
        boolean omittedCatalogsByBudget = false;
        boolean reachedSearchResultLimit = false;
        CatalogCallBudget budget = new CatalogCallBudget();
        for (StremioModels.CatalogDefinition catalog : manifest.catalogs) {
            MediaKind kind = mediaKind(catalog.type);
            if (!searchTypeMatches(request.type, kind)
                    || !manifest.supports("catalog", catalog.type, catalog.id)
                    || !declaresExtra(catalog, "search")) continue;
            Map<String, String> args = catalogArguments(catalog, request.query, skip, true);
            if (args == null) continue;
            if (!budget.tryStartCall()) {
                omittedCatalogsByBudget = true;
                break;
            }
            queriedAnyCatalog = true;
            try {
                StremioModels.CatalogResponse response = service.getCatalog(addon, manifest, catalog, args);
                completedAnyCatalog = true;
                for (MediaItem item : mapPreviews(response.metas, MAX_SEARCH_RESULTS)) {
                    results.putIfAbsent(item.stableId, item);
                    if (results.size() >= MAX_SEARCH_RESULTS) {
                        reachedSearchResultLimit = true;
                        break;
                    }
                }
                if (reachedSearchResultLimit) break;
            } catch (IOException error) {
                lastFailure = error;
            }
        }
        if (results.isEmpty() && queriedAnyCatalog && !completedAnyCatalog && lastFailure != null) {
            throw lastFailure;
        }
        ArrayList<MediaItem> items = new ArrayList<>(results.values());
        if (omittedCatalogsByBudget || reachedSearchResultLimit) {
            items.add(partialSearchMarker(request.type));
        }
        return Collections.unmodifiableList(items);
    }

    @Override public List<MediaItem> quickSearch(SearchRequest request) throws IOException {
        if (request != null && request.page != 1) {
            throw new IOException("Stremio quick search supports only the first page.");
        }
        return search(request);
    }

    /** Identifies the in-memory status item appended when a bounded search may omit output. */
    public static boolean isPartialSearchResult(MediaItem item) {
        return item != null && item.stableId.startsWith(PARTIAL_SEARCH_ID_PREFIX);
    }

    private MediaItem partialSearchMarker(SearchType searchType) {
        MediaKind matchingKind = searchType == SearchType.SERIES ? MediaKind.SERIES : MediaKind.MOVIE;
        return new MediaItem(PARTIAL_SEARCH_ID_PREFIX + digest(addon.installationId),
                "Partial results", matchingKind, "", "", "", PARTIAL_SEARCH_MESSAGE);
    }

    @Override public MediaItem loadDetails(String providerItemId) throws IOException {
        ItemToken token = parseToken(providerItemId);
        if (!"meta".equals(token.role)) throw new IOException("This Stremio item is not a metadata identity.");
        if (!manifest.supports("meta", token.type, token.id)) {
            throw new IOException("This Stremio add-on does not declare metadata for this item.");
        }
        StremioModels.MetaResponse response = service.getMetadata(addon, manifest, token.type, token.id);
        StremioModels.Meta meta = response.meta;
        if (!token.type.equalsIgnoreCase(meta.type) || !token.id.equals(meta.id)) {
            throw new IOException("The Stremio add-on returned metadata for a different item.");
        }
        return mapMeta(meta);
    }

    @Override public ProviderLinkResult resolveLinks(String providerItemId, boolean isCasting)
            throws IOException {
        // Stremio manifest resources do not provide a separate Orbit casting resolution contract.
        ItemToken token = parseToken(providerItemId);
        if ((("meta".equals(token.role) || "stream".equals(token.role))
                && !"movie".equalsIgnoreCase(token.type))
                || ("video".equals(token.role) && !"series".equalsIgnoreCase(token.type))) {
            throw new IOException("Resolve Stremio playback from a movie or series episode.");
        }
        if (!manifest.supports("stream", token.type, token.id)) {
            throw new IOException("This Stremio add-on does not declare streams for this item.");
        }
        StremioModels.StreamResponse response = service.getStreams(addon, manifest, token.type, token.id);
        ArrayList<ProviderLinkCandidate> links = new ArrayList<>();
        ArrayList<ProviderSubtitleCandidate> subtitles = new ArrayList<>();
        Set<String> subtitleIds = new HashSet<>();
        StremioModels.Stream subtitleContext = null;
        boolean inlineSubtitleMetadataIncomplete = false;
        int streamIndex = 0;
        for (StremioModels.Stream stream : response.streams) {
            int currentIndex = streamIndex++;
            inlineSubtitleMetadataIncomplete |= stream.inlineSubtitleMetadataIncomplete;
            if (stream.url == null) {
                // Keep provider metadata visible even when Orbit cannot hand the result to Media3.
                links.add(unavailableResultCandidate(addon.name, currentIndex, stream));
                continue;
            }
            try {
                PlaybackRequest.Source source = StremioPlaybackMapper.toDirectSource(addon, stream);
                if (source == null) {
                    links.add(new ProviderLinkCandidate("stremio-result-" + currentIndex,
                            safeDisplay(firstNonBlank(stream.title, stream.name, stream.description),
                                    "Provider stream", 180), addon.name, "", "", 0,
                            "UNSUPPORTED", "", Collections.emptyMap(), Collections.emptyList(), null, ""));
                    continue;
                }
                String directType = providerLinkType(source.mimeType, source.uri);
                String linkName = firstNonBlank(stream.title, stream.name, stream.description);
                links.add(new ProviderLinkCandidate(source.id,
                        safeDisplay(linkName, "Direct stream", 180), addon.name, source.uri,
                        "", quality(stream), directType, "", source.headers,
                        Collections.emptyList(), null, source.mimeType));
                appendSubtitles(subtitles, subtitleIds, stream.subtitles, token.id);
                if (subtitleContext == null || (!hasSubtitleHints(subtitleContext)
                        && hasSubtitleHints(stream))) subtitleContext = stream;
            } catch (IOException | IllegalArgumentException ignored) {
                String linkName = firstNonBlank(stream.title, stream.name, stream.description);
                links.add(new ProviderLinkCandidate("stremio-result-" + currentIndex,
                        safeDisplay(linkName, "Provider stream", 180), addon.name,
                        stream.url == null ? "" : stream.url, "", quality(stream),
                        "UNSUPPORTED", "", Collections.emptyMap(), Collections.emptyList(), null, "",
                        "The provider result could not be represented safely for playback."));
            }
        }

        if (!links.isEmpty() && manifest.supports("subtitles", token.type, token.id)) {
            StremioModels.BehaviorHints hints = subtitleContext == null
                    ? null : subtitleContext.behaviorHints;
            Long videoSize = subtitleContext != null && subtitleContext.behaviorHints != null
                    ? hints.videoSize : null;
            String filename = hints == null ? null : hints.filename;
            String videoHash = hints == null ? null : hints.videoHash;
            try {
                StremioModels.SubtitleResponse subtitleResponse = service.getSubtitles(addon, manifest,
                        token.type, token.id, videoHash, videoSize, filename);
                appendSubtitles(subtitles, subtitleIds, subtitleResponse.subtitles, token.id);
            } catch (IOException ignored) {
                // Subtitle lookup is optional; valid direct links remain available if it fails.
            }
        }

        List<ProviderSubtitleCandidate> subtitleResult = subtitles.isEmpty()
                ? Collections.emptyList() : Collections.unmodifiableList(subtitles);
        String diagnostic = links.isEmpty()
                ? "This Stremio add-on returned no direct HTTP(S) stream Orbit can play."
                : inlineSubtitleMetadataIncomplete
                        ? ProviderLinkResult.STREMIO_SUBTITLE_METADATA_DIAGNOSTIC : "";
        return new ProviderLinkResult(!links.isEmpty(), links, subtitleResult,
                diagnostic);
    }

    static ProviderLinkCandidate unavailableResultCandidate(String addonName, int index,
            StremioModels.Stream stream) {
        String externalUri = stream.externalUrl == null ? "" : stream.externalUrl;
        return new ProviderLinkCandidate("stremio-result-" + index,
                safeDisplay(firstNonBlank(stream.title, stream.name, stream.description),
                        "Provider stream", 180), addonName, externalUri, "", 0,
                stream.externalUrl == null ? "UNSUPPORTED" : "EXTERNAL", "",
                Collections.emptyMap(), Collections.emptyList(), null, "",
                stream.unavailableReason.isEmpty()
                        ? "Orbit Player can open direct streams only; this result is unavailable."
                        : stream.unavailableReason);
    }

    private MediaItem mapMeta(StremioModels.Meta meta) throws IOException {
        MediaKind kind = mediaKind(meta.type);
        if (kind == MediaKind.UNKNOWN) throw new IOException("Orbit supports Stremio movie and series metadata only.");
        ArrayList<MediaItem.EpisodeItem> episodes = new ArrayList<>();
        if (kind == MediaKind.SERIES && meta.videos != null) {
            for (StremioModels.Video video : meta.videos) {
                if (episodes.size() >= MAX_EPISODES) break;
                String videoToken = manifest.supports("stream", meta.type, video.id)
                        ? makeToken("video", meta.type, video.id) : "";
                String videoStableId = stableItemId(meta.type, video.id);
                episodes.add(new MediaItem.EpisodeItem(videoStableId,
                        safeDisplay(video.title, "Episode", 240),
                        boundedEpisodeNumber(video.season, 999),
                        boundedEpisodeNumber(video.episode, 9_999),
                        video.thumbnail, video.overview, 0, "", 0L, "", videoToken));
            }
        }
        String providerData = kind == MediaKind.MOVIE && manifest.supports("stream", meta.type, meta.id)
                ? makeToken("stream", meta.type, meta.id) : "";
        return new MediaItem(stableItemId(meta.type, meta.id),
                safeDisplay(meta.name, "Untitled", 512), kind, makeToken("meta", meta.type, meta.id),
                meta.poster, meta.releaseInfo, meta.description, providerData,
                meta.genres, parseRuntimeMinutes(meta.runtime), "", meta.background, meta.logo,
                Collections.emptyMap(), meta.imdbRating, Collections.emptyList(),
                Collections.emptyList(), episodes);
    }

    private List<MediaItem> mapPreviews(List<StremioModels.MetaPreview> previews, int maximum) {
        if (previews == null || previews.isEmpty()) return Collections.emptyList();
        LinkedHashMap<String, MediaItem> result = new LinkedHashMap<>();
        for (StremioModels.MetaPreview preview : previews) {
            if (result.size() >= maximum) break;
            MediaKind kind = mediaKind(preview.type);
            if (kind == MediaKind.UNKNOWN) continue;
            String providerData = kind == MediaKind.MOVIE
                    && manifest.supports("stream", preview.type, preview.id)
                    ? makeToken("stream", preview.type, preview.id) : "";
            String detailToken = manifest.supports("meta", preview.type, preview.id)
                    ? makeToken("meta", preview.type, preview.id) : "";
            MediaItem item = new MediaItem(stableItemId(preview.type, preview.id),
                    safeDisplay(preview.name, "Untitled", 512), kind, detailToken,
                    preview.poster, preview.releaseInfo, preview.description, providerData,
                    preview.genres, 0, "", preview.background, preview.logo,
                    Collections.emptyMap(), preview.imdbRating, Collections.emptyList());
            result.putIfAbsent(item.stableId, item);
        }
        return Collections.unmodifiableList(new ArrayList<>(result.values()));
    }

    private void appendSubtitles(List<ProviderSubtitleCandidate> target, Set<String> seen,
                                 List<StremioModels.Subtitle> values, String requestedVideoId) {
        if (target.size() >= MAX_SUBTITLES || values == null || values.isEmpty()) return;
        for (StremioModels.Subtitle subtitle : values) {
            if (target.size() >= MAX_SUBTITLES) break;
            if (subtitle == null) continue;
            if (subtitle.videoId != null && !subtitle.videoId.isEmpty()
                    && !requestedVideoId.equals(subtitle.videoId)) continue;
            String extension = supportedSubtitleExtension(subtitle.url);
            String declaredMime = mimeForSubtitleFormat(subtitle.format);
            String mimeType = SubtitleFormat.mimeType(subtitle.url, declaredMime);
            if (mimeType == null || extension != null
                    && !subtitleFormatMatches(extension, subtitle.format)) continue;
            String id = "stremio-subtitle-" + digest(addon.installationId + "\n"
                    + (subtitle.id == null ? "" : subtitle.id) + "\n" + subtitle.url);
            if (!seen.add(id)) continue;
            try {
                target.add(new ProviderSubtitleCandidate(id, safeDisplay(subtitle.lang, "", 64),
                        subtitle.url, Collections.emptyMap(), mimeType));
            } catch (IllegalArgumentException ignored) {
                // Invalid optional subtitle metadata does not invalidate playable stream links.
            }
        }
    }

    private static boolean hasSubtitleHints(StremioModels.Stream stream) {
        if (stream == null || stream.behaviorHints == null) return false;
        StremioModels.BehaviorHints hints = stream.behaviorHints;
        return hints.videoHash != null || hints.videoSize != null
                || (hints.filename != null && !hints.filename.trim().isEmpty());
    }

    private Map<String, String> catalogArguments(StremioModels.CatalogDefinition catalog,
                                                  String search, long skip, boolean searchRequest) {
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        boolean searchDeclared = false;
        boolean skipDeclared = false;
        for (StremioModels.ExtraOption option : catalog.extra) {
            if ("search".equals(option.name)) {
                searchDeclared = true;
                if (searchRequest) args.put("search", search);
                else if (option.required) return null;
            } else if ("skip".equals(option.name)) {
                skipDeclared = true;
                String requestedSkip = Long.toString(skip);
                if (skip > 0 && !option.options.isEmpty()
                        && !option.options.contains(requestedSkip)) return null;
                if (option.required && skip == 0 && !option.options.isEmpty()
                        && !option.options.contains(requestedSkip)) return null;
                if (skip > 0 || option.required) args.put("skip", Long.toString(skip));
            } else if (option.required) {
                if (option.options.size() == 1) {
                    args.put(option.name, option.options.get(0));
                } else {
                    return null;
                }
            }
        }
        if (searchRequest && !searchDeclared) return null;
        if (skip > 0 && !skipDeclared) return null;
        return Collections.unmodifiableMap(args);
    }

    private boolean hasHomeCatalog() {
        for (StremioModels.CatalogDefinition catalog : manifest.catalogs) {
            MediaKind kind = mediaKind(catalog.type);
            if (kind != MediaKind.UNKNOWN && manifest.supports("catalog", catalog.type, catalog.id)
                    && catalogArguments(catalog, null, 0, false) != null) return true;
        }
        return false;
    }

    private boolean hasSearchCatalog() {
        for (StremioModels.CatalogDefinition catalog : manifest.catalogs) {
            if (mediaKind(catalog.type) != MediaKind.UNKNOWN
                    && manifest.supports("catalog", catalog.type, catalog.id)
                    && declaresExtra(catalog, "search")
                    && catalogArguments(catalog, "query", 0, true) != null) return true;
        }
        return false;
    }

    private boolean hasSupportedType(String resourceName, String type) {
        for (StremioModels.ResourceCapability capability : manifest.resources) {
            if (!resourceName.equals(capability.name)
                    || capability.disposition != StremioModels.CapabilityDisposition.SUPPORTED) continue;
            List<String> types = capability.inheritsManifestFilters ? manifest.types : capability.types;
            for (String supportedType : types) if (supportedType.equalsIgnoreCase(type)) return true;
        }
        return false;
    }

    private static boolean declaresExtra(StremioModels.CatalogDefinition catalog, String name) {
        for (StremioModels.ExtraOption option : catalog.extra) if (name.equals(option.name)) return true;
        return false;
    }

    private static boolean requestedKind(HomeRequest request, MediaKind kind) {
        if (request.mediaKinds.isEmpty()) return true;
        for (MediaKind requested : request.mediaKinds) if (requested == kind) return true;
        return false;
    }

    private static boolean searchTypeMatches(SearchType requested, MediaKind kind) {
        if (kind == MediaKind.UNKNOWN) return false;
        if (requested == SearchType.ALL) return true;
        return requested == SearchType.MOVIES && kind == MediaKind.MOVIE
                || requested == SearchType.SERIES && kind == MediaKind.SERIES;
    }

    static long resolveHomeSkip(HomeRequest request) throws IOException {
        if (request == null) throw new IOException("A Stremio Home request is required.");
        long pageSkip = homeSkipForPage(request.page);
        String cursor = request.cursor == null ? "" : request.cursor;
        if (cursor.isEmpty()) return pageSkip;
        if (cursor.matches("stremio-skip:[0-9]+")) {
            try {
                long cursorSkip = Long.parseLong(cursor.substring("stremio-skip:".length()));
                if (cursorSkip != pageSkip) {
                    throw new IOException("The Stremio Home skip cursor does not match its page.");
                }
                return cursorSkip;
            } catch (NumberFormatException error) {
                throw new IOException("This Stremio Home skip cursor is outside the supported numeric range.");
            }
        }
        // Accept cursors produced by an earlier in-memory adapter while preserving page checks.
        if (cursor.matches("stremio-page:[1-9][0-9]*")) {
            try {
                int cursorPage = Integer.parseInt(cursor.substring("stremio-page:".length()));
                if (cursorPage != request.page) {
                    throw new IOException("The Stremio catalog cursor does not match its page.");
                }
                return pageSkip;
            } catch (NumberFormatException error) {
                throw new IOException("This Stremio catalog cursor is invalid.");
            }
        }
        throw new IOException("This Stremio Home cursor is invalid.");
    }

    static int resolveHomeCatalogStartIndex(HomeRequest request, int eligibleCatalogCount)
            throws IOException {
        if (request == null || eligibleCatalogCount < 0) {
            throw new IOException("The Stremio Home continuation request is invalid.");
        }
        if (!request.isProviderContinuationRequest()) return 0;
        if (request.isSectionRequest()) {
            throw new IOException("This Stremio Home catalog cursor is invalid.");
        }
        return StremioHomeCatalogCursor.parse(request.providerCursor,
                eligibleCatalogCount).nextFreshIndex;
    }

    static String nextHomeCatalogCursor(int nextEligibleIndex, int eligibleCatalogCount) {
        try {
            return StremioHomeCatalogCursor.create(nextEligibleIndex,
                    Collections.<Integer>emptyList(), eligibleCatalogCount)
                    .encode(eligibleCatalogCount);
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    private static long homeSkipForPage(int page) throws IOException {
        if (page < 1) throw new IOException("The requested Stremio Home page is invalid.");
        try {
            return Math.multiplyExact((long) page - 1L, (long) MAX_ITEMS_PER_SECTION);
        } catch (ArithmeticException error) {
            throw new IOException("The requested Stremio Home skip is outside the supported numeric range.");
        }
    }

    private static int pageSkip(int page) {
        if (page < 1) return -1;
        long skip = (long) (page - 1) * MAX_ITEMS_PER_SECTION;
        return skip > 100_000L ? -1 : (int) skip;
    }

    private static MediaKind mediaKind(String type) {
        if ("movie".equalsIgnoreCase(type)) return MediaKind.MOVIE;
        if ("series".equalsIgnoreCase(type)) return MediaKind.SERIES;
        return MediaKind.UNKNOWN;
    }

    private static String fallbackTypeTitle(MediaKind kind) {
        return kind == MediaKind.MOVIE ? "Movies" : "Series";
    }

    private static int boundedEpisodeNumber(Integer number, int maximum) {
        return number == null || number < 0 || number > maximum ? -1 : number;
    }

    static boolean homeRequestTargetsCatalog(HomeRequest request,
            StremioModels.CatalogDefinition catalog) {
        return request != null && catalog != null
                && (!request.isSectionRequest()
                || request.sectionId.equals(catalogSectionId(catalog)));
    }

    static String nextHomeCursor(StremioModels.CatalogDefinition catalog,
            StremioModels.CatalogResponse response, long skip) {
        if (catalog == null || response == null || skip < 0
                || !declaresExtra(catalog, "skip")
                || response.metas.size() < MAX_ITEMS_PER_SECTION) return "";
        try {
            return "stremio-skip:" + Math.addExact(skip, (long) MAX_ITEMS_PER_SECTION);
        } catch (ArithmeticException error) {
            return "";
        }
    }

    static String catalogSectionId(StremioModels.CatalogDefinition catalog) {
        return "stremio-catalog-" + digest(catalog.type + "\n" + catalog.id);
    }

    private static String providerLinkType(String mimeType, String rawUrl) {
        String mime = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        if ("application/vnd.apple.mpegurl".equals(mime)
                || "application/x-mpegurl".equals(mime)) return "M3U8";
        if ("application/dash+xml".equals(mime)) return "DASH";
        if (mime.startsWith("video/")) return "VIDEO";
        String path = httpPath(rawUrl);
        if (path == null) return "UNKNOWN";
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".m3u8")) return "M3U8";
        if (lower.endsWith(".mpd")) return "DASH";
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".ts")
                || lower.endsWith(".mpg") || lower.endsWith(".mpeg")) return "VIDEO";
        return "UNKNOWN";
    }

    private static String supportedSubtitleExtension(String rawUrl) {
        String path = httpPath(rawUrl);
        if (path == null) return null;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".vtt")) return "vtt";
        if (lower.endsWith(".srt")) return "srt";
        if (lower.endsWith(".ttml") || lower.endsWith(".xml")) return "ttml";
        if (lower.endsWith(".ssa")) return "ssa";
        if (lower.endsWith(".ass")) return "ass";
        return null;
    }

    private static String mimeForSubtitleFormat(String format) {
        if (format == null) return null;
        String normalized = format.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        if ("vtt".equals(normalized) || "webvtt".equals(normalized)
                || "text/vtt".equals(normalized)) return "text/vtt";
        if ("srt".equals(normalized) || "subrip".equals(normalized)
                || "application/x-subrip".equals(normalized)) return "application/x-subrip";
        if ("ttml".equals(normalized) || "xml".equals(normalized)
                || "application/ttml+xml".equals(normalized)) return "application/ttml+xml";
        if ("ssa".equals(normalized) || "ass".equals(normalized)
                || "text/x-ssa".equals(normalized)) return "text/x-ssa";
        return null;
    }

    private static boolean subtitleFormatMatches(String extension, String format) {
        if (format == null || format.trim().isEmpty()) return true;
        String normalized = format.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        if ("vtt".equals(extension)) return "vtt".equals(normalized) || "webvtt".equals(normalized);
        if ("srt".equals(extension)) return "srt".equals(normalized) || "subrip".equals(normalized);
        if ("ssa".equals(extension)) return "ssa".equals(normalized) || "text/x-ssa".equals(normalized);
        if ("ass".equals(extension)) return "ass".equals(normalized) || "ssa".equals(normalized)
                || "text/x-ssa".equals(normalized);
        return "ttml".equals(normalized) || "xml".equals(normalized)
                || "application/ttml+xml".equals(normalized);
    }

    private static String httpPath(String rawUrl) {
        if (rawUrl == null || rawUrl.length() > StremioAddonClient.MAX_STREAM_URL_CHARS) return null;
        try {
            URI uri = new URI(rawUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65_535) return null;
            return uri.getPath() == null ? "" : uri.getPath();
        } catch (URISyntaxException error) {
            return null;
        }
    }

    private static int quality(StremioModels.Stream stream) {
        String candidate = firstNonBlank(stream.title, stream.description, stream.name);
        if (candidate == null) return 0;
        Matcher matcher = QUALITY_PATTERN.matcher(candidate);
        if (!matcher.find()) return 0;
        try { return Integer.parseInt(matcher.group(1)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static int parseRuntimeMinutes(String runtime) {
        if (runtime == null || runtime.trim().isEmpty()) return 0;
        Matcher matcher = RUNTIME_PATTERN.matcher(runtime.trim());
        if (!matcher.matches()) return 0;
        try {
            int first = Integer.parseInt(matcher.group(1));
            int minutes = matcher.group(2) == null ? first : first * 60 + Integer.parseInt(matcher.group(2));
            return minutes > 0 && minutes <= 1_000 ? minutes : 0;
        } catch (NumberFormatException ignored) { return 0; }
    }

    private String makeToken(String role, String type, String id) {
        validateIdentity(type, MAX_TYPE_CHARS, "Stremio type");
        validateIdentity(id, MAX_STREMIO_ID_CHARS, "Stremio item ID");
        return TOKEN_PREFIX + "." + addon.installationId + "." + role + "."
                + encodeToken(type) + "." + encodeToken(id);
    }

    private ItemToken parseToken(String value) throws IOException {
        if (value == null || value.length() > 1_200) throw new IOException("This Stremio item identity is invalid.");
        String[] fields = value.split("\\.", -1);
        if (fields.length != 5 || !TOKEN_PREFIX.equals(fields[0])
                || !addon.installationId.equals(fields[1])
                || !("meta".equals(fields[2]) || "video".equals(fields[2])
                || "stream".equals(fields[2]))) {
            throw new IOException("This Stremio item belongs to a different or unsupported provider.");
        }
        try {
            String type = decodeToken(fields[3], MAX_TYPE_CHARS);
            String id = decodeToken(fields[4], MAX_STREMIO_ID_CHARS);
            boolean supported = "meta".equals(fields[2])
                    ? manifest.supports("meta", type, id)
                    : manifest.supports("stream", type, id);
            if (!supported || ("video".equals(fields[2]) && !"series".equalsIgnoreCase(type))
                    || ("stream".equals(fields[2]) && !"movie".equalsIgnoreCase(type))) {
                throw new IOException("This Stremio item is outside the installed manifest capabilities.");
            }
            return new ItemToken(fields[2], type, id);
        } catch (IllegalArgumentException error) {
            throw new IOException("This Stremio item identity is invalid.");
        }
    }

    private String stableItemId(String type, String id) {
        return "stremio-item-" + digest(addon.installationId + "\n" + type.toLowerCase(Locale.ROOT) + "\n" + id);
    }

    private static String encodeToken(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String decodeToken(String value, int maximum) throws IOException {
        if (value == null || value.isEmpty() || value.length() > maximum * 2
                || !value.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid encoded Stremio identity.");
        byte[] bytes;
        try {
            bytes = Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid encoded Stremio identity.");
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            validateIdentity(decoded, maximum, "Stremio identity");
            if (!encodeToken(decoded).equals(value)) throw new IOException("Non-canonical Stremio identity.");
            return decoded;
        } catch (CharacterCodingException error) {
            throw new IOException("Invalid encoded Stremio identity.");
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void validateIdentity(String value, int maximum, String label) {
        if (value == null || value.trim().isEmpty() || value.length() > maximum) {
            throw new IllegalArgumentException(label + " is invalid.");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) throw new IllegalArgumentException(label + " is invalid.");
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (int index = 0; index < 16; index++) result.append(String.format(Locale.ROOT, "%02x", bytes[index] & 0xff));
            java.util.Arrays.fill(bytes, (byte) 0);
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
    }

    private static String safeDisplay(String value, String fallback, int maximum) {
        if (value == null) return fallback;
        StringBuilder clean = new StringBuilder(Math.min(value.length(), maximum));
        for (int index = 0; index < value.length() && clean.length() < maximum; index++) {
            char character = value.charAt(index);
            if (!Character.isISOControl(character)) clean.append(character);
        }
        String result = clean.toString().trim();
        return result.isEmpty() ? fallback : result;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return null;
    }

    private static final class ItemToken {
        final String role;
        final String type;
        final String id;
        ItemToken(String role, String type, String id) {
            this.role = role;
            this.type = type;
            this.id = id;
        }
    }

    private static final class CatalogCallBudget {
        private static final long CALL_TIMEOUT_NANOS = StremioAddonClient.CALL_TIMEOUT_MS * 1_000_000L;
        private final long deadlineNanos = System.nanoTime()
                + MAX_CATALOG_FANOUT_MS * 1_000_000L;
        private int callsStarted;

        boolean tryStartCall() {
            if (callsStarted >= MAX_CATALOG_CALLS_PER_OPERATION
                    || deadlineNanos - System.nanoTime() < CALL_TIMEOUT_NANOS) return false;
            callsStarted++;
            return true;
        }
    }
}
