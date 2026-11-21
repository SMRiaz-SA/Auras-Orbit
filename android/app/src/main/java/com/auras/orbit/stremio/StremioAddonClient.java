package com.auras.orbit.stremio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.auras.orbit.providers.ProviderLinkResult;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Bounded Stremio manifest transport. It fetches declarative JSON resources only; it never loads
 * add-on code. Call from a worker thread because requests are synchronous.
 */
public final class StremioAddonClient {
    public static final long CONNECT_TIMEOUT_MS = 5_000L;
    public static final long READ_TIMEOUT_MS = 8_000L;
    public static final long CALL_TIMEOUT_MS = 15_000L;
    public static final int MAX_RESPONSE_BYTES = 1_048_576;
    public static final int MAX_MANIFEST_URL_CHARS = 4_096;
    private static final int MAX_LIST_ITEMS = 1_000;
    private static final int MAX_STREAM_ITEMS = ProviderLinkResult.MAX_LINKS;
    private static final int MAX_EXTRA_ARGS = 20;
    private static final int MAX_TEXT_CHARS = 8_192;
    private static final int MAX_PROXY_HEADERS = 64;
    private static final int MAX_PROXY_HEADER_BYTES = 16 * 1024;
    private static final int MAX_JSON_NODES = 100_000;
    private static final int MAX_JSON_DEPTH = 64;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();

    /** Resolve a host or Stremio URI to a canonical manifest URL without making a request. */
    public String normalizeManifestUrl(String rawInput) throws IOException {
        if (rawInput == null) throw new IOException("Enter a Stremio manifest URL.");
        String input = rawInput.trim();
        if (input.isEmpty() || input.length() > MAX_MANIFEST_URL_CHARS) {
            throw new IOException("Enter a valid Stremio manifest URL.");
        }
        if (input.regionMatches(true, 0, "stremio://", 0, "stremio://".length())) {
            input = "https://" + input.substring("stremio://".length());
        } else if (!input.matches("(?i)^https?://.*")) {
            input = "https://" + input;
        }
        URI parsed = parseUri(input);
        validateHttpUri(parsed);
        if (parsed.getRawFragment() != null) throw new IOException("Manifest URLs cannot contain a fragment.");
        String path = parsed.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        path = path.replaceFirst("/+$", "");
        if (!path.toLowerCase(Locale.ROOT).endsWith("/manifest.json")) {
            path = path + "/manifest.json";
        }
        String authority = formatAuthority(parsed);
        String normalized = parsed.getScheme().toLowerCase(Locale.ROOT) + "://" + authority + path
                + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery());
        if (normalized.length() > MAX_MANIFEST_URL_CHARS) {
            throw new IOException("The Stremio manifest URL is too long.");
        }
        return normalized;
    }

    /** Fetch and validate one manifest for review; this call does not install it. */
    public StremioModels.Manifest previewManifest(String rawInput) throws IOException {
        String url = normalizeManifestUrl(rawInput);
        return parseManifest(requestJson(url));
    }

    /** Re-fetch a previously installed add-on and fail closed if its manifest identity changed. */
    public StremioModels.Manifest refreshInstalled(StremioAddonRegistry.InstalledAddon addon)
            throws IOException {
        if (addon == null) throw new IOException("Choose an installed Stremio add-on.");
        StremioModels.Manifest refreshed = parseManifest(requestJson(addon.manifestUrlInternal()));
        if (!refreshed.id.equals(addon.manifestId)) {
            throw new IOException("This manifest now identifies a different add-on. Preview and install it again.");
        }
        return refreshed;
    }

    public StremioModels.CatalogResponse getCatalog(
            StremioAddonRegistry.InstalledAddon addon,
            StremioModels.Manifest manifest,
            StremioModels.CatalogDefinition catalog,
            Map<String, String> extraArgs) throws IOException {
        requireInstalledResource(addon, manifest, "catalog");
        if (catalog == null || !manifest.catalogs.contains(catalog)) {
            throw new IOException("Choose a catalog declared by this manifest.");
        }
        if (!manifest.supports("catalog", catalog.type, catalog.id)) {
            throw new IOException("This add-on does not support the selected catalog type.");
        }
        Map<String, String> cleanArgs = validateExtraArgs(catalog, extraArgs);
        String url = buildResourceUrl(addon.manifestUrlInternal(), "catalog", catalog.type, catalog.id,
                cleanArgs);
        JsonObject root = requireObject(requestJson(url), "catalog response");
        List<StremioModels.MetaPreview> metas = new ArrayList<>();
        JsonArray items = requiredArray(root, "metas");
        if (items.size() > MAX_LIST_ITEMS) throw new IOException("The catalog response contains too many entries.");
        for (JsonElement element : items) metas.add(parseMetaPreview(requireObject(element, "catalog item")));
        return new StremioModels.CatalogResponse(metas, optionalInt(root, "cacheMaxAge", 0, 31_536_000));
    }

    public StremioModels.MetaResponse getMetadata(
            StremioAddonRegistry.InstalledAddon addon, StremioModels.Manifest manifest,
            String type, String id) throws IOException {
        requireSupportedRequest(addon, manifest, "meta", type, id);
        JsonObject root = requireObject(requestJson(buildResourceUrl(addon.manifestUrlInternal(),
                "meta", type, id, Collections.emptyMap())), "metadata response");
        return new StremioModels.MetaResponse(parseMeta(requiredObject(root, "meta")),
                optionalInt(root, "cacheMaxAge", 0, 31_536_000));
    }

    public StremioModels.StreamResponse getStreams(
            StremioAddonRegistry.InstalledAddon addon, StremioModels.Manifest manifest,
            String type, String videoId) throws IOException {
        requireSupportedRequest(addon, manifest, "stream", type, videoId);
        JsonObject root = requireObject(requestJson(buildResourceUrl(addon.manifestUrlInternal(),
                "stream", type, videoId, Collections.emptyMap())), "stream response");
        JsonArray items = requiredArray(root, "streams");
        if (items.size() > MAX_STREAM_ITEMS) throw new IOException("The stream response contains too many entries.");
        List<StremioModels.Stream> streams = new ArrayList<>();
        for (JsonElement element : items) {
            StremioModels.Stream stream = parseStream(requireObject(element, "stream"));
            if (stream != null) streams.add(stream);
        }
        return new StremioModels.StreamResponse(streams,
                optionalInt(root, "cacheMaxAge", 0, 31_536_000));
    }

    /**
     * Query the Stremio subtitle resource using the SDK handler contract: video ID is the route
     * ID, while file hash, size, and filename are optional extra arguments.
     */
    public StremioModels.SubtitleResponse getSubtitles(
            StremioAddonRegistry.InstalledAddon addon, StremioModels.Manifest manifest,
            String type, String videoId, String videoHash, Long videoSize, String filename)
            throws IOException {
        requireSupportedRequest(addon, manifest, "subtitles", type, videoId);
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        if (videoHash != null && !videoHash.trim().isEmpty()) args.put("videoHash", boundedValue(videoHash, 512));
        if (videoSize != null) {
            if (videoSize < 0 || videoSize > 8L * 1024L * 1024L * 1024L) {
                throw new IOException("Video size is outside the supported range.");
            }
            args.put("videoSize", Long.toString(videoSize));
        }
        if (filename != null && !filename.trim().isEmpty()) args.put("filename", boundedValue(filename, 512));
        JsonObject root = requireObject(requestJson(buildResourceUrl(addon.manifestUrlInternal(),
                "subtitles", type, videoId, args)), "subtitle response");
        JsonArray items = requiredArray(root, "subtitles");
        if (items.size() > MAX_LIST_ITEMS) throw new IOException("The subtitle response contains too many entries.");
        List<StremioModels.Subtitle> subtitles = new ArrayList<>();
        for (JsonElement element : items) {
            StremioModels.Subtitle subtitle = parseSubtitle(requireObject(element, "subtitle"));
            if (subtitle != null) subtitles.add(subtitle);
        }
        return new StremioModels.SubtitleResponse(subtitles,
                optionalInt(root, "cacheMaxAge", 0, 31_536_000));
    }

    /**
     * Return manifest previews from a declared addon_catalog resource. Entries are never installed
     * by this method; callers must explicitly preview and install a selected manifest URL.
     */
    public StremioModels.AddonCatalogResponse discoverAddons(
            StremioAddonRegistry.InstalledAddon addon, StremioModels.Manifest manifest,
            String type, String catalogId, Map<String, String> extraArgs) throws IOException {
        requireInstalledResource(addon, manifest, "addon_catalog");
        requireSupportedRequest(addon, manifest, "addon_catalog", type, catalogId);
        StremioModels.CatalogDefinition catalog = findCatalogDefinition(manifest, type, catalogId);
        if (catalog == null) throw new IOException("Choose a discovery catalog declared by this manifest.");
        Map<String, String> cleanArgs = validateExtraArgs(catalog, extraArgs);
        JsonObject root = requireObject(requestJson(buildResourceUrl(addon.manifestUrlInternal(),
                "addon_catalog", type, catalogId, cleanArgs)), "add-on catalog response");
        JsonArray items = requiredArray(root, "addons");
        if (items.size() > MAX_LIST_ITEMS) throw new IOException("The add-on catalog contains too many entries.");
        List<StremioModels.DiscoveredAddon> discovered = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject item = requireObject(element, "discovered add-on");
            String transportName = optionalString(item, "transportName", 32);
            if (!"http".equalsIgnoreCase(transportName)) continue;
            String manifestUrl = normalizeManifestUrl(requiredString(item, "transportUrl", 1, MAX_MANIFEST_URL_CHARS));
            StremioModels.Manifest embedded = parseManifest(requiredObject(item, "manifest"));
            discovered.add(new StremioModels.DiscoveredAddon("http", manifestUrl,
                    safeHost(manifestUrl), embedded));
        }
        return new StremioModels.AddonCatalogResponse(discovered,
                optionalInt(root, "cacheMaxAge", 0, 31_536_000));
    }

    private void requireInstalledResource(StremioAddonRegistry.InstalledAddon addon,
                                          StremioModels.Manifest manifest, String resource)
            throws IOException {
        if (addon == null || manifest == null || !addon.manifestId.equals(manifest.id)) {
            throw new IOException("Refresh this installed Stremio add-on before using it.");
        }
        if (!manifest.declares(resource)) throw new IOException("This add-on does not declare that resource.");
    }

    private void requireSupportedRequest(StremioAddonRegistry.InstalledAddon addon,
                                        StremioModels.Manifest manifest,
                                        String resource, String type, String id) throws IOException {
        requireInstalledResource(addon, manifest, resource);
        validateType(type);
        validateId(id);
        if (!manifest.supports(resource, type, id)) {
            throw new IOException("This add-on does not support the requested resource, type, and ID.");
        }
    }

    private Map<String, String> validateExtraArgs(StremioModels.CatalogDefinition catalog,
                                                   Map<String, String> args) throws IOException {
        Map<String, String> requested = args == null ? Collections.emptyMap() : args;
        if (requested.size() > MAX_EXTRA_ARGS) throw new IOException("Too many catalog options were requested.");
        LinkedHashMap<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String key = boundedValue(entry.getKey(), 64);
            String value = boundedValue(entry.getValue(), 512);
            if ("search".equals(key)) {
                clean.put(key, value);
                continue;
            }
            if ("skip".equals(key)) {
                try {
                    int skip = Integer.parseInt(value);
                    if (skip < 0 || skip > 100_000) throw new NumberFormatException();
                } catch (NumberFormatException error) {
                    throw new IOException("Catalog pagination must be a non-negative bounded integer.");
                }
                clean.put(key, value);
                continue;
            }
            StremioModels.ExtraOption declaration = null;
            for (StremioModels.ExtraOption extra : catalog.extra) {
                if (extra.name.equals(key)) { declaration = extra; break; }
            }
            if (declaration == null) throw new IOException("The catalog did not declare that option.");
            if (!declaration.options.isEmpty()) {
                String[] selected = declaration.optionsLimit != null && declaration.optionsLimit > 1
                        ? value.split(",", -1) : new String[] { value };
                int maximumSelections = declaration.optionsLimit == null ? 1 : declaration.optionsLimit;
                if (selected.length > maximumSelections) {
                    throw new IOException("Too many values were selected for this catalog option.");
                }
                for (String selectedValue : selected) {
                    if (!declaration.options.contains(selectedValue)) {
                        throw new IOException("The selected catalog option is not declared by the manifest.");
                    }
                }
            }
            clean.put(key, value);
        }
        for (StremioModels.ExtraOption extra : catalog.extra) {
            if (extra.required && !clean.containsKey(extra.name)) {
                throw new IOException("A required catalog option is missing.");
            }
        }
        return Collections.unmodifiableMap(clean);
    }

    private static StremioModels.CatalogDefinition findCatalogDefinition(
            StremioModels.Manifest manifest, String type, String id) {
        for (StremioModels.CatalogDefinition catalog : manifest.catalogs) {
            if (catalog.type.equalsIgnoreCase(type) && catalog.id.equals(id)) return catalog;
        }
        return null;
    }

    private String buildResourceUrl(String manifestUrl, String resource, String type, String id,
                                    Map<String, String> extraArgs)
            throws IOException {
        validateType(type);
        validateId(id);
        URI manifest = parseUri(normalizeManifestUrl(manifestUrl));
        String rawPath = manifest.getRawPath();
        String basePath = rawPath.substring(0, rawPath.length() - "/manifest.json".length());
        StringBuilder path = new StringBuilder(basePath).append('/')
                .append(resource).append('/').append(encode(type)).append('/').append(encode(id));
        if (extraArgs != null && !extraArgs.isEmpty()) {
            StringBuilder extras = new StringBuilder();
            for (Map.Entry<String, String> extra : extraArgs.entrySet()) {
                if (extras.length() > 0) extras.append('&');
                extras.append(encode(extra.getKey())).append('=').append(encode(extra.getValue()));
            }
            path.append('/').append(extras);
        }
        path.append(".json");
        String url = manifest.getScheme() + "://" + formatAuthority(manifest) + path
                + (manifest.getRawQuery() == null ? "" : "?" + manifest.getRawQuery());
        if (url.length() > MAX_MANIFEST_URL_CHARS + MAX_TEXT_CHARS) {
            throw new IOException("The Stremio resource URL is too long.");
        }
        validateHttpUri(parseUri(url));
        return url;
    }

    private JsonElement requestJson(String rawUrl) throws IOException {
        URI uri = parseUri(rawUrl);
        validateHttpUri(uri);
        Request request = new Request.Builder().url(uri.toASCIIString())
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", "AurasOrbit/1.0")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            int status = response.code();
            if (status < 200 || status >= 300) {
                throw new IOException("The Stremio add-on returned HTTP " + status + ".");
            }
            String contentType = response.header("Content-Type");
            if (contentType != null && !contentType.trim().isEmpty()
                    && !contentType.toLowerCase(Locale.ROOT).contains("json")) {
                throw new IOException("The Stremio add-on returned a non-JSON response.");
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) throw new IOException("The Stremio add-on returned an empty response.");
            long declaredLength = responseBody.contentLength();
            if (declaredLength > MAX_RESPONSE_BYTES) throw new IOException("The Stremio response is too large.");
            try (InputStream body = responseBody.byteStream()) {
                byte[] bytes = readBounded(body, MAX_RESPONSE_BYTES);
                java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                try (JsonReader reader = new JsonReader(new InputStreamReader(
                        new ByteArrayInputStream(bytes), decoder))) {
                    reader.setStrictness(Strictness.STRICT);
                    int[] remainingNodes = new int[] { MAX_JSON_NODES };
                    JsonElement parsed = parseStrictValue(reader, remainingNodes, 0);
                    if (parsed == null || parsed instanceof JsonNull || reader.peek() != JsonToken.END_DOCUMENT) {
                        throw new IOException("The Stremio add-on returned invalid JSON.");
                    }
                    return parsed;
                }
            } catch (IllegalStateException | NumberFormatException error) {
                throw new IOException("The Stremio add-on returned invalid JSON.");
            }
        } catch (IOException error) {
            String message = error.getMessage();
            if (message != null && (message.startsWith("The Stremio") || message.startsWith("A Stremio"))) {
                throw error;
            }
            throw new IOException("Could not reach the Stremio add-on. Check its URL and connection.");
        } catch (RuntimeException error) {
            throw new IOException("Could not read the Stremio response.");
        }
    }

    private static JsonElement parseStrictValue(JsonReader reader, int[] remainingNodes, int depth)
            throws IOException {
        if (depth > MAX_JSON_DEPTH || --remainingNodes[0] < 0) {
            throw new IOException("The Stremio response exceeds JSON complexity limits.");
        }
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT: {
                JsonObject object = new JsonObject();
                Set<String> names = new HashSet<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) throw new IOException("The Stremio add-on returned duplicate JSON fields.");
                    object.add(name, parseStrictValue(reader, remainingNodes, depth + 1));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY: {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(parseStrictValue(reader, remainingNodes, depth + 1));
                reader.endArray();
                return array;
            }
            case STRING:
                return new JsonPrimitive(reader.nextString());
            case NUMBER:
                return new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN:
                return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JsonNull.INSTANCE;
            default:
                throw new IOException("The Stremio add-on returned invalid JSON.");
        }
    }

    private StremioModels.Manifest parseManifest(JsonElement element) throws IOException {
        JsonObject root = requireObject(element, "manifest");
        String id = requiredString(root, "id", 1, 256);
        String name = requiredString(root, "name", 1, 256);
        String description = requiredString(root, "description", 1, 4_096);
        String version = requiredString(root, "version", 1, 128);
        List<String> types = stringArray(root, "types", true, 128, 128);
        if (types.isEmpty()) throw new IOException("The manifest must declare at least one content type.");
        List<String> prefixes = stringArray(root, "idPrefixes", false, 128, 256);
        JsonArray resourceArray = requiredArray(root, "resources");
        if (resourceArray.size() == 0 || resourceArray.size() > 64) {
            throw new IOException("The manifest must declare a bounded set of resources.");
        }
        List<StremioModels.ResourceCapability> resources = new ArrayList<>();
        Set<String> resourceNames = new HashSet<>();
        for (JsonElement item : resourceArray) {
            String resourceName;
            List<String> resourceTypes = Collections.emptyList();
            List<String> resourcePrefixes = Collections.emptyList();
            boolean inheritsManifestFilters;
            if (item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                resourceName = boundedValue(item.getAsString(), 64);
                inheritsManifestFilters = true;
            } else {
                JsonObject resource = requireObject(item, "manifest resource");
                resourceName = requiredString(resource, "name", 1, 64);
                resourceTypes = stringArray(resource, "types", true, 64, 128);
                if (resourceTypes.isEmpty()) throw new IOException("Object resources must declare at least one type.");
                resourcePrefixes = stringArray(resource, "idPrefixes", false, 64, 256);
                inheritsManifestFilters = false;
            }
            if (!resourceNames.add(resourceName)) throw new IOException("The manifest repeats a resource declaration.");
            StremioModels.CapabilityDisposition disposition = StremioModels.dispositionFor(resourceName);
            resources.add(new StremioModels.ResourceCapability(resourceName, resourceTypes,
                    resourcePrefixes, disposition, inheritsManifestFilters));
        }
        List<StremioModels.CatalogDefinition> catalogs = parseCatalogDefinitions(root);
        return new StremioModels.Manifest(id, name, description, version, types, prefixes,
                resources, catalogs);
    }

    private List<StremioModels.CatalogDefinition> parseCatalogDefinitions(JsonObject root)
            throws IOException {
        JsonElement value = root.get("catalogs");
        if (value == null || value instanceof JsonNull) return Collections.emptyList();
        if (!value.isJsonArray() || value.getAsJsonArray().size() > 256) {
            throw new IOException("The manifest catalog list is invalid.");
        }
        List<StremioModels.CatalogDefinition> result = new ArrayList<>();
        Set<String> catalogKeys = new HashSet<>();
        for (JsonElement element : value.getAsJsonArray()) {
            JsonObject catalog = requireObject(element, "manifest catalog");
            String type = requiredString(catalog, "type", 1, 128);
            validateType(type);
            String id = requiredString(catalog, "id", 1, 512);
            if (!catalogKeys.add(type.toLowerCase(Locale.ROOT) + "\n" + id)) {
                throw new IOException("The manifest repeats a catalog definition.");
            }
            String name = optionalString(catalog, "name", 256);
            List<StremioModels.ExtraOption> extras = parseExtra(catalog);
            result.add(new StremioModels.CatalogDefinition(type, id, name, extras));
        }
        return result;
    }

    private List<StremioModels.ExtraOption> parseExtra(JsonObject catalog) throws IOException {
        JsonElement value = catalog.get("extra");
        if (value == null || value instanceof JsonNull) return Collections.emptyList();
        if (!value.isJsonArray() || value.getAsJsonArray().size() > MAX_EXTRA_ARGS) {
            throw new IOException("The manifest catalog options are invalid.");
        }
        List<StremioModels.ExtraOption> result = new ArrayList<>();
        Set<String> optionNames = new HashSet<>();
        for (JsonElement element : value.getAsJsonArray()) {
            JsonObject extra = requireObject(element, "catalog option");
            String name = requiredString(extra, "name", 1, 64);
            if (!optionNames.add(name)) throw new IOException("The manifest repeats a catalog option.");
            boolean required = optionalBoolean(extra, "isRequired", false);
            List<String> options = stringArray(extra, "options", false, 128, 512);
            Integer optionsLimit = optionalInt(extra, "optionsLimit", 1, 1_000);
            result.add(new StremioModels.ExtraOption(name, required, options, optionsLimit));
        }
        return result;
    }

    private StremioModels.MetaPreview parseMetaPreview(JsonObject object) throws IOException {
        String id = requiredString(object, "id", 1, 512);
        String type = requiredString(object, "type", 1, 128);
        validateType(type);
        String name = requiredString(object, "name", 1, 512);
        return new StremioModels.MetaPreview(id, type, name, safeOptionalUrl(object, "poster"),
                safeOptionalUrl(object, "background"), safeOptionalUrl(object, "logo"),
                optionalString(object, "description", 8_192), optionalString(object, "releaseInfo", 256),
                scalarString(object, "imdbRating", 64), stringArray(object, "genres", false, 128, 128));
    }

    private StremioModels.Meta parseMeta(JsonObject object) throws IOException {
        StremioModels.MetaPreview preview = parseMetaPreview(object);
        List<StremioModels.Video> videos = new ArrayList<>();
        JsonElement value = object.get("videos");
        if (value != null && !(value instanceof JsonNull)) {
            if (!value.isJsonArray() || value.getAsJsonArray().size() > MAX_LIST_ITEMS) {
                throw new IOException("The metadata video list is invalid.");
            }
            for (JsonElement element : value.getAsJsonArray()) {
                JsonObject video = requireObject(element, "metadata video");
                String id = requiredString(video, "id", 1, 512);
                videos.add(new StremioModels.Video(id, optionalString(video, "title", 512),
                        optionalInt(video, "season", 0, 10_000), optionalInt(video, "episode", 0, 100_000),
                        optionalString(video, "released", 128), optionalString(video, "overview", 8_192),
                        safeOptionalUrl(video, "thumbnail")));
            }
        }
        return new StremioModels.Meta(preview.id, preview.type, preview.name, preview.poster,
                preview.background, preview.logo, preview.description, preview.releaseInfo,
                preview.imdbRating, preview.genres, optionalString(object, "runtime", 128),
                optionalString(object, "released", 128), videos);
    }

    private StremioModels.Stream parseStream(JsonObject object) throws IOException {
        String name = optionalString(object, "name", 512);
        String title = optionalString(object, "title", 2_048);
        String description = optionalString(object, "description", 4_096);
        String url = safeOptionalUrl(object, "url");
        String externalUrl = safeOptionalUrl(object, "externalUrl");
        String ytId = optionalString(object, "ytId", 256);
        String infoHash = optionalString(object, "infoHash", 128);
        Integer fileIdx = optionalInt(object, "fileIdx", 0, 100_000);
        List<String> sources = stringArray(object, "sources", false, 64, 512);
        List<StremioModels.Subtitle> subtitles = parseInlineSubtitles(object);
        StremioModels.BehaviorHints hints = parseBehaviorHints(object);
        if (hints != null && !hints.requestHeaders.isEmpty() && !Boolean.TRUE.equals(hints.notWebReady)) {
            throw new IOException("The manifest supplied proxy headers without the required notWebReady hint.");
        }
        if (url == null && externalUrl == null && ytId == null && infoHash == null) return null;
        return new StremioModels.Stream(name, title, description, url, externalUrl, ytId,
                infoHash, fileIdx, sources, hints, subtitles);
    }

    private StremioModels.BehaviorHints parseBehaviorHints(JsonObject object) throws IOException {
        JsonElement value = object.get("behaviorHints");
        if (value == null || value instanceof JsonNull) return null;
        JsonObject hints = requireObject(value, "stream behavior hints");
        return new StremioModels.BehaviorHints(optionalString(hints, "bingeGroup", 512),
                optionalString(hints, "filename", 1_024), optionalBooleanNullable(hints, "notWebReady"),
                optionalString(hints, "videoHash", 512),
                optionalLong(hints, "videoSize", 0, 8L * 1024L * 1024L * 1024L),
                parseProxyRequestHeaders(hints));
    }

    private List<StremioModels.Subtitle> parseInlineSubtitles(JsonObject stream) throws IOException {
        JsonElement value = stream.get("subtitles");
        if (value == null || value instanceof JsonNull) return Collections.emptyList();
        if (!value.isJsonArray() || value.getAsJsonArray().size() > MAX_LIST_ITEMS) {
            throw new IOException("The inline stream subtitle list is invalid.");
        }
        ArrayList<StremioModels.Subtitle> subtitles = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            StremioModels.Subtitle subtitle = parseSubtitle(requireObject(element, "inline stream subtitle"));
            if (subtitle != null) subtitles.add(subtitle);
        }
        return subtitles;
    }

    private Map<String, String> parseProxyRequestHeaders(JsonObject behaviorHints) throws IOException {
        JsonElement value = behaviorHints.get("proxyHeaders");
        if (value == null || value instanceof JsonNull) return Collections.emptyMap();
        JsonObject proxyHeaders = requireObject(value, "stream proxy headers");
        JsonElement requestValue = proxyHeaders.get("request");
        if (requestValue == null || requestValue instanceof JsonNull) return Collections.emptyMap();
        JsonObject requestHeaders = requireObject(requestValue, "stream request headers");
        if (requestHeaders.size() > MAX_PROXY_HEADERS) throw new IOException("The stream declares too many request headers.");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        int totalBytes = 0;
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry : requestHeaders.entrySet()) {
            String name = entry.getKey();
            if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) {
                throw new IOException("The stream declares an invalid request header.");
            }
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!names.add(normalized) || isForbiddenStreamHeader(normalized)) {
                throw new IOException("The stream declares a duplicate or restricted request header.");
            }
            JsonElement headerValue = entry.getValue();
            if (headerValue == null || !headerValue.isJsonPrimitive()
                    || !headerValue.getAsJsonPrimitive().isString()) {
                throw new IOException("Stream request header values must be strings.");
            }
            String header = boundedValue(headerValue.getAsString(), 8_192);
            totalBytes += name.length() + header.length();
            if (totalBytes > MAX_PROXY_HEADER_BYTES) throw new IOException("The stream request headers are too large.");
            result.put(name, header);
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean isForbiddenStreamHeader(String lowerName) {
        return "host".equals(lowerName) || "content-length".equals(lowerName)
                || "connection".equals(lowerName) || "transfer-encoding".equals(lowerName)
                || "proxy-authorization".equals(lowerName) || "proxy-connection".equals(lowerName)
                || "upgrade".equals(lowerName);
    }

    private StremioModels.Subtitle parseSubtitle(JsonObject object) throws IOException {
        String url = safeOptionalUrl(object, "url");
        if (url == null) return null;
        return new StremioModels.Subtitle(optionalString(object, "id", 512), url,
                optionalString(object, "lang", 64), optionalString(object, "subEncoding", 128),
                optionalString(object, "videoId", 512), optionalString(object, "format", 32),
                scalarString(object, "rating", 64));
    }

    private static JsonObject requireObject(JsonElement element, String label) throws IOException {
        if (element == null || element instanceof JsonNull || !element.isJsonObject()) {
            throw new IOException("The Stremio " + label + " must be a JSON object.");
        }
        return element.getAsJsonObject();
    }

    private static JsonObject requiredObject(JsonObject object, String key) throws IOException {
        return requireObject(object.get(key), key);
    }

    private static JsonArray requiredArray(JsonObject object, String key) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) throw new IOException("The Stremio " + key + " field must be an array.");
        return element.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String key, int minimum, int maximum)
            throws IOException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("The Stremio " + key + " field must be a string.");
        }
        String value = boundedValue(element.getAsString(), maximum);
        if (value.trim().length() < minimum) throw new IOException("The Stremio " + key + " field is empty.");
        return value;
    }

    private static String optionalString(JsonObject object, String key, int maximum) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element instanceof JsonNull) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("The Stremio " + key + " field must be a string.");
        }
        return boundedValue(element.getAsString(), maximum);
    }

    private static String scalarString(JsonObject object, String key, int maximum) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element instanceof JsonNull) return null;
        if (!element.isJsonPrimitive() || element.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("The Stremio " + key + " field must be text or a number.");
        }
        return boundedValue(element.getAsString(), maximum);
    }

    private static List<String> stringArray(JsonObject object, String key, boolean required,
                                            int maximumCount, int maximumString) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element instanceof JsonNull) {
            if (required) throw new IOException("The Stremio " + key + " field is required.");
            return Collections.emptyList();
        }
        if (!element.isJsonArray() || element.getAsJsonArray().size() > maximumCount) {
            throw new IOException("The Stremio " + key + " field must be a bounded array.");
        }
        List<String> result = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IOException("The Stremio " + key + " field must contain strings.");
            }
            String value = boundedValue(item.getAsString(), maximumString);
            if (value.trim().isEmpty()) throw new IOException("The Stremio " + key + " field contains an empty value.");
            result.add(value);
        }
        return result;
    }

    private static Integer optionalInt(JsonObject object, String key, int minimum, int maximum)
            throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element instanceof JsonNull) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException("The Stremio " + key + " field must be an integer.");
        }
        try {
            String raw = element.getAsString();
            if (!raw.matches("-?[0-9]{1,12}")) throw new NumberFormatException();
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IOException("The Stremio " + key + " field is outside its allowed range.");
        }
    }

    private static Long optionalLong(JsonObject object, String key, long minimum, long maximum)
            throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element instanceof JsonNull) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException("The Stremio " + key + " field must be an integer.");
        }
        try {
            String raw = element.getAsString();
            if (!raw.matches("-?[0-9]{1,19}")) throw new NumberFormatException();
            long value = Long.parseLong(raw);
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IOException("The Stremio " + key + " field is outside its allowed range.");
        }
    }

    private static boolean optionalBoolean(JsonObject object, String key, boolean defaultValue)
            throws IOException {
        Boolean value = optionalBooleanNullable(object, key);
        return value == null ? defaultValue : value;
    }

    private static Boolean optionalBooleanNullable(JsonObject object, String key) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element instanceof JsonNull) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("The Stremio " + key + " field must be a boolean.");
        }
        return element.getAsBoolean();
    }

    private static String safeOptionalUrl(JsonObject object, String key) throws IOException {
        String raw = optionalString(object, key, MAX_MANIFEST_URL_CHARS);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            URI uri = parseUri(raw);
            validateHttpUri(uri);
            if (uri.getRawFragment() != null || uri.toASCIIString().length() > MAX_MANIFEST_URL_CHARS) return null;
            return uri.toASCIIString();
        } catch (IOException error) {
            // Unsafe media and artwork links are omitted from the typed response.
            return null;
        }
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 16 * 1024));
        byte[] buffer = new byte[8 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) throw new IOException("Input stream made no progress.");
            if (count > maximum - total) throw new IOException("The Stremio response is too large.");
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private static String boundedValue(String value, int maximum) throws IOException {
        if (value == null || value.length() > maximum) throw new IOException("A Stremio field exceeds its size limit.");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\0' || character == '\r' || character == '\n'
                    || character == 0x7f || (character < 0x20 && character != '\t')) {
                throw new IOException("A Stremio field contains invalid control characters.");
            }
        }
        return value;
    }

    private static void validateType(String type) throws IOException {
        String safe = boundedValue(type, 128);
        if (!safe.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IOException("Invalid Stremio content type.");
    }

    private static void validateId(String id) throws IOException {
        String safe = boundedValue(id, 512);
        if (safe.trim().isEmpty()) throw new IOException("A Stremio content ID is required.");
    }

    private static URI parseUri(String value) throws IOException {
        try {
            URI parsed = new URI(value).normalize();
            if (parsed.isOpaque() || parsed.getHost() == null || parsed.getRawUserInfo() != null) {
                throw new IOException("Enter a valid Stremio HTTP(S) URL without embedded credentials.");
            }
            return parsed;
        } catch (URISyntaxException error) {
            throw new IOException("Enter a valid Stremio HTTP(S) URL.");
        }
    }

    private static void validateHttpUri(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (("https".equals(scheme) || "http".equals(scheme)) && uri.getHost() != null) {
            if (uri.getPort() == 0 || uri.getPort() > 65_535) throw new IOException("The Stremio URL port is invalid.");
            return;
        }
        throw new IOException("Stremio add-on URLs must use HTTP or HTTPS.");
    }

    private static String formatAuthority(URI uri) throws IOException {
        String host = uri.getHost();
        if (host == null) throw new IOException("The Stremio URL host is missing.");
        String safeHost = host;
        if (safeHost.startsWith("[") && safeHost.endsWith("]")) {
            safeHost = safeHost.substring(1, safeHost.length() - 1);
        }
        if (safeHost.indexOf(':') >= 0) safeHost = "[" + safeHost + "]";
        else {
            try { safeHost = IDN.toASCII(safeHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT); }
            catch (IllegalArgumentException error) { throw new IOException("The Stremio URL host is invalid."); }
        }
        int port = uri.getPort();
        if (port < -1 || port == 0 || port > 65_535) throw new IOException("The Stremio URL port is invalid.");
        if (port != -1) safeHost += ":" + port;
        return safeHost;
    }

    private static String safeHost(String url) throws IOException {
        URI uri = parseUri(url);
        String host = uri.getHost();
        if (host == null) throw new IOException("The discovered manifest host is missing.");
        return host + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }

    private static String encode(String value) throws IOException {
        boundedValue(value, MAX_TEXT_CHARS);
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (Exception error) { throw new IOException("Could not encode a Stremio request value."); }
    }
}
