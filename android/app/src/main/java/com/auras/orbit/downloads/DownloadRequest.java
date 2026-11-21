package com.auras.orbit.downloads;

import com.auras.orbit.providers.Cs3PlaybackHooks;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A provider-owned direct download that Orbit can save as a file. */
public final class DownloadRequest {
    static final long MAX_DOWNLOAD_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final int MAX_SEASON_LABEL_LENGTH = 64;
    private static final int MAX_EPISODE_LABEL_LENGTH = 128;
    private static final String ARTWORK_REFERENCE_PREFIX = "orbit-artwork:";
    static final int MAX_HEADER_COUNT = 64;
    static final int MAX_HEADER_NAME_LENGTH = 128;
    static final int MAX_HEADER_VALUE_LENGTH = 8_192;
    static final int MAX_HEADER_BYTES = 16 * 1024;

    public final String profileId;
    public final String stableItemId;
    public final String sourceId;
    public final String title;
    public final String fileName;
    public final String mimeType;
    public final String sourceUrl;
    public final boolean sourceSupportsDirectDownload;
    /** Immutable source request headers. These are encrypted before they enter queue storage. */
    public final Map<String, String> headers;
    public final String seasonLabel;
    public final String episodeLabel;
    /** -1 means the provider supplied no trustworthy size estimate. */
    public final long knownSizeBytes;
    /** Orbit-local digest reference only; raw artwork URLs and headers are never queue metadata. */
    public final String artworkReference;
    /** Ephemeral provider hook session; never stored by DownloadQueueStore. */
    public final Cs3PlaybackHooks providerHooks;
    /** Bounded data-only verifier recovery identity; encrypted before queue persistence. */
    public final DownloadProviderHookToken providerHookToken;

    public DownloadRequest(String profileId, String stableItemId, String sourceId,
            String title, String fileName, String mimeType, String sourceUrl,
            boolean sourceSupportsDirectDownload) {
        this(profileId, stableItemId, sourceId, title, fileName, mimeType, sourceUrl,
                sourceSupportsDirectDownload, Collections.emptyMap(), "", "", -1L, "", null);
    }

    public DownloadRequest(String profileId, String stableItemId, String sourceId,
            String title, String fileName, String mimeType, String sourceUrl,
            boolean sourceSupportsDirectDownload, Map<String, String> headers) {
        this(profileId, stableItemId, sourceId, title, fileName, mimeType, sourceUrl,
                sourceSupportsDirectDownload, headers, "", "", -1L, "", null);
    }

    public DownloadRequest(String profileId, String stableItemId, String sourceId,
            String title, String fileName, String mimeType, String sourceUrl,
            boolean sourceSupportsDirectDownload, Map<String, String> headers,
            String seasonLabel, String episodeLabel, long knownSizeBytes, String artworkReference) {
        this(profileId, stableItemId, sourceId, title, fileName, mimeType, sourceUrl,
                sourceSupportsDirectDownload, headers, seasonLabel, episodeLabel, knownSizeBytes,
                artworkReference, null);
    }

    public DownloadRequest(String profileId, String stableItemId, String sourceId,
            String title, String fileName, String mimeType, String sourceUrl,
            boolean sourceSupportsDirectDownload, Map<String, String> headers,
            String seasonLabel, String episodeLabel, long knownSizeBytes, String artworkReference,
            Cs3PlaybackHooks providerHooks) {
        this.profileId = profileId;
        this.stableItemId = stableItemId;
        this.sourceId = sourceId;
        this.title = title;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.sourceUrl = sourceUrl;
        this.sourceSupportsDirectDownload = sourceSupportsDirectDownload;
        this.headers = canonicalizeHeaders(headers);
        this.seasonLabel = boundedDisplayText(seasonLabel, MAX_SEASON_LABEL_LENGTH);
        this.episodeLabel = boundedDisplayText(episodeLabel, MAX_EPISODE_LABEL_LENGTH);
        this.knownSizeBytes = knownSizeBytes > 0L && knownSizeBytes <= MAX_DOWNLOAD_BYTES
                ? knownSizeBytes : -1L;
        this.artworkReference = isLocalArtworkReference(artworkReference)
                ? artworkReference : "";
        this.providerHooks = providerHooks;
        this.providerHookToken = providerHooks == null || sourceUrl == null
                ? null : providerHooks.toDownloadProviderHookToken(
                        sourceId == null ? "" : sourceId.trim(), sourceUrl.trim());
    }

    private static String boundedDisplayText(String value, int limit) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder clean = new StringBuilder(Math.min(limit, value.length()));
        for (int i = 0; i < value.length() && clean.length() < limit; i++) {
            char ch = value.charAt(i);
            if (!Character.isISOControl(ch)) clean.append(ch);
        }
        return clean.toString().trim();
    }

    static boolean isLocalArtworkReference(String value) {
        if (value == null || !value.startsWith(ARTWORK_REFERENCE_PREFIX)) return false;
        String encoded = value.substring(ARTWORK_REFERENCE_PREFIX.length());
        if (encoded.length() != 129 || encoded.charAt(64) != ':') return false;
        return isLowerHex(encoded, 0, 64) && isLowerHex(encoded, 65, 129);
    }

    private static boolean isLowerHex(String value, int start, int end) {
        for (int index = start; index < end; index++) {
            char ch = value.charAt(index);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) return false;
        }
        return true;
    }

    /** Validates and copies headers while normalizing their names to stable title casing. */
    static Map<String, String> canonicalizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return Collections.emptyMap();
        if (headers.size() > MAX_HEADER_COUNT) {
            throw new IllegalArgumentException("The provider returned too many request headers.");
        }

        LinkedHashMap<String, String> canonical = new LinkedHashMap<>();
        LinkedHashMap<String, String> seenNames = new LinkedHashMap<>();
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String inputName = entry.getKey();
            String inputValue = entry.getValue();
            if (inputName == null || inputValue == null) {
                throw new IllegalArgumentException("The provider returned an invalid request header.");
            }
            String name = inputName.trim();
            String value = inputValue.trim();
            if (name.isEmpty() || name.length() > MAX_HEADER_NAME_LENGTH
                    || value.length() > MAX_HEADER_VALUE_LENGTH || !isToken(name)) {
                throw new IllegalArgumentException("The provider returned an invalid request header.");
            }
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (seenNames.put(lowerName, lowerName) != null || isForbiddenRequestHeader(lowerName)) {
                throw new IllegalArgumentException("The provider returned an unsupported request header.");
            }
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if ((ch < 0x20 && ch != '\t') || ch == 0x7f || ch > 0xff) {
                    throw new IllegalArgumentException("The provider returned an invalid request header.");
                }
            }
            int fieldBytes = name.getBytes(StandardCharsets.UTF_8).length
                    + value.getBytes(StandardCharsets.UTF_8).length + 4;
            if (fieldBytes > MAX_HEADER_BYTES - totalBytes) {
                throw new IllegalArgumentException("The provider returned too much request header data.");
            }
            totalBytes += fieldBytes;
            canonical.put(canonicalName(lowerName), value);
        }
        return Collections.unmodifiableMap(canonical);
    }

    /** Returns whether the queue accepts this header set without exposing validation details. */
    public static boolean supportsHeaders(Map<String, String> headers) {
        try {
            canonicalizeHeaders(headers);
            return true;
        } catch (IllegalArgumentException invalidHeaders) {
            return false;
        }
    }

    /**
     * Returns whether this URL can be requested with the supplied bounded headers.
     * Cleartext HTTP is supported only for a headerless request.
     */
    public static boolean supportsHeaders(String sourceUrl, Map<String, String> headers) {
        if (!supportsHeaders(headers) || sourceUrl == null || sourceUrl.length() > 65_536) return false;
        try {
            URI uri = new URI(sourceUrl.trim());
            String scheme = uri.getScheme();
            int port = uri.getPort();
            if (scheme == null || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || port == 0 || port > 65_535) return false;
            if ("https".equalsIgnoreCase(scheme)) return true;
            if ("http".equalsIgnoreCase(scheme)) {
                return uri.getRawQuery() == null && canonicalizeHeaders(headers).isEmpty();
            }
            return false;
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean isToken(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean alphaNumeric = ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'Z'
                    || ch >= 'a' && ch <= 'z';
            boolean punctuation = "!#$%&'*+-.^_`|~".indexOf(ch) >= 0;
            if (!alphaNumeric && !punctuation) return false;
        }
        return true;
    }

    private static String canonicalName(String lowerName) {
        StringBuilder result = new StringBuilder(lowerName.length());
        boolean capitalize = true;
        for (int i = 0; i < lowerName.length(); i++) {
            char ch = lowerName.charAt(i);
            if (capitalize && ch >= 'a' && ch <= 'z') ch = (char) (ch - ('a' - 'A'));
            result.append(ch);
            capitalize = ch == '-';
        }
        return result.toString();
    }

    private static boolean isForbiddenRequestHeader(String lowerName) {
        return "host".equals(lowerName) || "content-length".equals(lowerName)
                || "transfer-encoding".equals(lowerName) || "connection".equals(lowerName)
                || "proxy-connection".equals(lowerName) || "proxy-authorization".equals(lowerName)
                || "keep-alive".equals(lowerName) || "te".equals(lowerName)
                || "trailer".equals(lowerName) || "upgrade".equals(lowerName)
                || "expect".equals(lowerName) || "accept-encoding".equals(lowerName)
                || "range".equals(lowerName) || "if-range".equals(lowerName)
                || "if-match".equals(lowerName) || "if-none-match".equals(lowerName)
                || "if-modified-since".equals(lowerName) || "if-unmodified-since".equals(lowerName)
                || lowerName.startsWith("proxy-");
    }
}
