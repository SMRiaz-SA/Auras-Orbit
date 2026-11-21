package com.auras.orbit.intent;

import android.content.Intent;
import android.util.Base64;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Conservative, non-executing classification of untrusted Android handoff values. */
public final class RequestValidation {
    public static final int MAX_VALUE_LENGTH = 4096;
    private static final int MAX_ACTION_VALUE_LENGTH = 2048;
    private static final int MAX_DISPLAY_NAME_LENGTH = 256;
    static final String TRUNCATED_VALUE = "orbit-internal://request-value-truncated";

    public enum Kind {
        WEB_LINK,
        REPOSITORY_LINK,
        PLAYER_LINK,
        SEARCH_QUERY,
        SHARED_PROVIDER_LINK,
        SHARED_TEXT,
        SHARED_CONTENT_URI,
        KNOWN_UNSUPPORTED_SCHEME,
        INVALID
    }

    public enum Disposition {
        REQUIRES_CONFIRMATION,
        INFORMATIONAL,
        REJECTED
    }

    private final List<Item> items;

    private RequestValidation(List<Item> items) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    public static RequestValidation parse(String action, String mimeType, List<String> rawValues) {
        ArrayList<Item> parsed = new ArrayList<>();
        boolean viewAction = Intent.ACTION_VIEW.equals(action);
        boolean shareAction = Intent.ACTION_SEND.equals(action)
                || Intent.ACTION_SEND_MULTIPLE.equals(action);

        if (!viewAction && !shareAction) {
            parsed.add(new Item(0, Kind.INVALID, Disposition.REJECTED,
                    "Unsupported request action. Nothing was opened or processed."));
            return new RequestValidation(parsed);
        }
        if (rawValues == null || rawValues.isEmpty()) {
            parsed.add(new Item(0, Kind.INVALID, Disposition.REJECTED,
                    "The request contains no usable content."));
            return new RequestValidation(parsed);
        }

        int limit = Math.min(rawValues.size(), 8);
        for (int index = 0; index < limit; index++) {
            parsed.add(parseValue(action, rawValues.get(index), index));
        }
        if (rawValues.size() > limit) {
            parsed.add(new Item(limit, Kind.INVALID, Disposition.REJECTED,
                    "Additional shared items were omitted because the request exceeds the safe item limit."));
        }
        return new RequestValidation(parsed);
    }

    private static Item parseValue(String action, String rawValue, int index) {
        boolean shareAction = Intent.ACTION_SEND.equals(action)
                || Intent.ACTION_SEND_MULTIPLE.equals(action);
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return rejected(index, "An empty request item was ignored.");
        }
        if (TRUNCATED_VALUE.equals(rawValue)) {
            return rejected(index, "A request item was too large or the request contained too many items.");
        }
        if (rawValue.length() > MAX_VALUE_LENGTH || containsControlCharacters(rawValue)) {
            return rejected(index, "A malformed or oversized request item was rejected.");
        }

        String value = rawValue.trim();
        if (value.length() == 0 || value.length() > MAX_VALUE_LENGTH) {
            return rejected(index, "A malformed or oversized request item was rejected.");
        }

        if (startsWithIgnoreCase(value, "cloudstreamrepo://")) {
            if (!Intent.ACTION_VIEW.equals(action)) return rejected(index, "Repository links must be opened as a confirmed link request.");
            return parseRepositoryLink(value.substring("cloudstreamrepo://".length()).trim(), index);
        }
        if (startsWithIgnoreCase(value, "cloudstreamplayer://")) {
            if (!Intent.ACTION_VIEW.equals(action)) return rejected(index, "Player links must be opened as a confirmed link request.");
            return parsePlayerLink(value.substring("cloudstreamplayer://".length()), index);
        }
        if (startsWithIgnoreCase(value, "cloudstreamsearch://")) {
            if (!Intent.ACTION_VIEW.equals(action)) return rejected(index, "Search links must be opened as a confirmed link request.");
            return parseSearchRequest(value.substring("cloudstreamsearch://".length()), index);
        }
        if (startsWithIgnoreCase(value, "csshare:")) {
            if (!Intent.ACTION_VIEW.equals(action)) return rejected(index, "Provider links must be opened as a confirmed link request.");
            return parseSharedProviderLink(value.substring("csshare:".length()), index);
        }
        if (startsWithIgnoreCase(value, "cloudstreamcontinuewatching://")) {
            String id = value.substring("cloudstreamcontinuewatching://".length());
            if (id.matches("[0-9]{1,20}")) {
                return rejected(index, "This continue-watching ID belongs to the legacy app and cannot be mapped to Orbit data.");
            }
            return rejected(index, "The continue-watching request is malformed and cannot be mapped to Orbit data.");
        }
        if (startsWithIgnoreCase(value, "cloudstreamapp:")) {
            return rejected(index, "CloudStream authentication redirects are not supported. Authentication values were hidden.");
        }

        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            if (shareAction && !looksLikeScheme(value)) {
                return new Item(index, Kind.SHARED_TEXT, Disposition.REQUIRES_CONFIRMATION,
                        "Shared text (content hidden); confirmation is required before using it.");
            }
            return rejected(index, "The request contains a malformed link.");
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            if (!shareAction) return rejected(index, "A link request must include a supported scheme.");
            return new Item(index, Kind.SHARED_TEXT, Disposition.REQUIRES_CONFIRMATION,
                    "Shared text (content hidden); confirmation is required before using it.");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);

        if ("http".equals(scheme) || "https".equals(scheme)) {
            return parseWebLink(value, index, isRepositoryShortcut(uri));
        }
        if ("content".equals(scheme) && shareAction) {
            if (uri.getRawAuthority() == null || uri.getRawAuthority().isEmpty()) {
                return rejected(index, "The shared content URI is malformed.");
            }
            return new Item(index, Kind.SHARED_CONTENT_URI, Disposition.REQUIRES_CONFIRMATION,
                    "Shared content URI (details hidden); confirmation and an available permission are required.");
        }

        if (isKnownCloudStreamScheme(scheme)) {
            return new Item(index, Kind.KNOWN_UNSUPPORTED_SCHEME, Disposition.REJECTED,
                    "A CloudStream link was malformed or uses an unsupported request type. Nothing was opened or executed.");
        }
        return rejected(index, "The request uses an unsupported link scheme. Nothing was opened or executed.");
    }

    private static Item parseRepositoryLink(String candidate, int index) {
        if (candidate.isEmpty()) return rejected(index, "The repository request does not contain a URL or shortcut.");
        if (candidate.matches("[A-Za-z0-9!_-]{1,128}")) {
            return new Item(index, Kind.REPOSITORY_LINK, Disposition.REQUIRES_CONFIRMATION,
                    "Repository shortcut " + candidate + " (not resolved or fetched)",
                    null, candidate, null, null, null);
        }
        if (!hasHttpScheme(candidate)) {
            if (containsSchemeSeparator(candidate)) {
                return rejected(index, "The repository request uses an unsupported nested scheme.");
            }
            candidate = "https://" + candidate;
        }
        return parseHttpTarget(candidate, index, Kind.REPOSITORY_LINK,
                "Repository link to ", true, null, null, null);
    }

    private static Item parsePlayerLink(String encodedPayload, int index) {
        int queryStart = encodedPayload.indexOf('?');
        String encodedUrl = queryStart < 0 ? encodedPayload : encodedPayload.substring(0, queryStart);
        String rawQuery = queryStart < 0 ? null : encodedPayload.substring(queryStart + 1);
        if (encodedUrl.isEmpty() || encodedPayload.indexOf('#') >= 0) {
            return rejected(index, "The player link is malformed.");
        }

        String decodedUrl = decodeFormValue(encodedUrl);
        if (decodedUrl == null || decodedUrl.length() > MAX_ACTION_VALUE_LENGTH
                || containsControlCharacters(decodedUrl)) {
            return rejected(index, "The player link URL is malformed or oversized.");
        }

        String playerName = null;
        if (rawQuery != null && !rawQuery.isEmpty()) {
            QueryValue nameValue = readQueryParameter(rawQuery, "name");
            if (!nameValue.valid) return rejected(index, "The player link title is malformed.");
            playerName = nameValue.value;
            if (playerName != null && (playerName.length() > MAX_DISPLAY_NAME_LENGTH
                    || containsControlCharacters(playerName))) {
                return rejected(index, "The player link title is oversized or malformed.");
            }
        }
        return parseHttpTarget(decodedUrl, index, Kind.PLAYER_LINK,
                "Player link to ", false, playerName, null, null);
    }

    private static Item parseSearchRequest(String encodedQuery, int index) {
        if (encodedQuery.isEmpty() || encodedQuery.length() > MAX_ACTION_VALUE_LENGTH) {
            return rejected(index, "The search request is empty or oversized.");
        }
        String query = decodeFormValue(encodedQuery);
        if (query == null || query.trim().isEmpty() || query.length() > MAX_ACTION_VALUE_LENGTH
                || containsControlCharacters(query)) {
            return rejected(index, "The search request is malformed.");
        }
        return new Item(index, Kind.SEARCH_QUERY, Disposition.REQUIRES_CONFIRMATION,
                "Search query received (query text hidden)", null, null, null, query, null);
    }

    private static Item parseSharedProviderLink(String encodedPayload, int index) {
        int separator = encodedPayload.indexOf('?');
        if (separator <= 0 || separator == encodedPayload.length() - 1
                || encodedPayload.indexOf('?', separator + 1) >= 0) {
            return rejected(index, "The shared provider link is malformed.");
        }

        String providerName = decodeBase64Utf8(encodedPayload.substring(0, separator));
        String detailUrl = decodeBase64Utf8(encodedPayload.substring(separator + 1));
        if (providerName == null || providerName.trim().isEmpty()
                || providerName.length() > MAX_DISPLAY_NAME_LENGTH
                || containsControlCharacters(providerName)) {
            return rejected(index, "The shared provider name is malformed.");
        }
        if (detailUrl == null || detailUrl.length() > MAX_ACTION_VALUE_LENGTH
                || containsControlCharacters(detailUrl)) {
            return rejected(index, "The shared provider detail link is malformed or oversized.");
        }
        return parseHttpTarget(detailUrl, index, Kind.SHARED_PROVIDER_LINK,
                "Shared provider link to ", false, null, null, providerName);
    }

    private static Item parseWebLink(String value, int index, boolean repository) {
        Kind kind = repository ? Kind.REPOSITORY_LINK : Kind.WEB_LINK;
        return parseHttpTarget(value, index, kind,
                repository ? "Repository link to " : "Web link to ", repository,
                null, null, null);
    }

    private static Item parseHttpTarget(String value, int index, Kind kind, String displayLabel,
            boolean repository, String playerName, String searchQuery, String providerName) {
        if (value == null || value.isEmpty() || value.length() > MAX_ACTION_VALUE_LENGTH
                || containsControlCharacters(value)) {
            return rejected(index, "The link is malformed or oversized.");
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            return rejected(index, "The link is malformed.");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return rejected(index, "Only HTTP(S) links are accepted for explicit review.");
        }
        if (uri.isOpaque() || uri.getHost() == null || uri.getHost().isEmpty()
                || uri.getPort() > 65535 || uri.getPort() < -1) {
            return rejected(index, "The link has no valid web host.");
        }
        if (uri.getRawUserInfo() != null) {
            return rejected(index, "Links containing embedded credentials are not accepted.");
        }

        String host = uri.getHost();
        int port = uri.getPort();
        String safeHost = host.toLowerCase(Locale.ROOT) + (port < 0 ? "" : ":" + port);
        String display = displayLabel + safeHost
                + " (path, query, and fragment hidden)";
        String target = uri.toString();
        return new Item(index, kind, Disposition.REQUIRES_CONFIRMATION, display,
                target, repository ? target : null, playerName, searchQuery, providerName);
    }

    private static String decodeFormValue(String value) {
        if (value == null || value.length() > MAX_VALUE_LENGTH) return null;
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException malformed) {
            return null;
        }
    }

    private static QueryValue readQueryParameter(String rawQuery, String expectedName) {
        String found = null;
        boolean foundParameter = false;
        String[] parameters = rawQuery.split("&", -1);
        if (parameters.length > 8) return QueryValue.invalid();
        for (String parameter : parameters) {
            int equals = parameter.indexOf('=');
            String key = decodeFormValue(equals < 0 ? parameter : parameter.substring(0, equals));
            if (key == null) return QueryValue.invalid();
            if (!expectedName.equals(key)) return QueryValue.invalid();
            if (foundParameter) return QueryValue.invalid();
            foundParameter = true;
            found = decodeFormValue(equals < 0 ? "" : parameter.substring(equals + 1));
            if (found == null) return QueryValue.invalid();
        }
        return new QueryValue(true, foundParameter ? found : null);
    }

    private static String decodeBase64Utf8(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_VALUE_LENGTH
                || !encoded.matches("[A-Za-z0-9+/]+={0,2}")) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            if (bytes.length > MAX_ACTION_VALUE_LENGTH) return null;
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException malformed) {
            return null;
        }
    }

    private static boolean isRepositoryShortcut(URI uri) {
        String host = uri.getHost();
        return host != null && "cs.repo".equalsIgnoreCase(host);
    }

    private static boolean isKnownCloudStreamScheme(String scheme) {
        return "cloudstreamplayer".equals(scheme)
                || "cloudstreamapp".equals(scheme)
                || "cloudstreamrepo".equals(scheme)
                || "csshare".equals(scheme)
                || "cloudstreamsearch".equals(scheme)
                || "cloudstreamcontinuewatching".equals(scheme);
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean hasHttpScheme(String value) {
        return startsWithIgnoreCase(value, "http://") || startsWithIgnoreCase(value, "https://");
    }

    private static boolean containsSchemeSeparator(String value) {
        int separator = value.indexOf("://");
        return separator > 0;
    }

    private static boolean looksLikeScheme(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) return false;
        for (int index = 0; index < colon; index++) {
            char character = value.charAt(index);
            if (!(Character.isLetterOrDigit(character) || character == '+' || character == '-'
                    || character == '.')) return false;
        }
        return Character.isLetter(value.charAt(0));
    }

    private static boolean containsControlCharacters(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)) return true;
        }
        return false;
    }

    private static Item rejected(int index, String message) {
        return new Item(index, Kind.INVALID, Disposition.REJECTED, message);
    }

    private static String boundedActionValue(String value, int maximumLength) {
        if (value == null || value.isEmpty() || value.length() > maximumLength
                || containsControlCharacters(value)) return null;
        return value;
    }

    private static final class QueryValue {
        final boolean valid;
        final String value;

        QueryValue(boolean valid, String value) {
            this.valid = valid;
            this.value = value;
        }

        static QueryValue invalid() {
            return new QueryValue(false, null);
        }
    }

    public List<Item> getItems() {
        return items;
    }

    public boolean hasRejectedItems() {
        for (Item item : items) if (item.disposition == Disposition.REJECTED) return true;
        return false;
    }

    public boolean requiresConfirmation() {
        for (Item item : items) if (item.disposition == Disposition.REQUIRES_CONFIRMATION) return true;
        return false;
    }

    /** Safe for the current shell's visible request card; never includes raw URI or shared text. */
    public String getSafeDisplayText() {
        if (items.isEmpty()) return "No request content was accepted.";
        StringBuilder output = new StringBuilder();
        for (Item item : items) {
            if (output.length() > 0) output.append('\n');
            output.append(item.displayText);
        }
        return output.toString();
    }

    public static final class Item {
        public final int index;
        public final Kind kind;
        public final Disposition disposition;
        private final String displayText;
        private final String httpUrl;
        private final String repositoryInput;
        private final String playerName;
        private final String searchQuery;
        private final String providerName;

        private Item(int index, Kind kind, Disposition disposition, String displayText) {
            this(index, kind, disposition, displayText, null, null, null, null, null);
        }

        private Item(int index, Kind kind, Disposition disposition, String displayText,
                String httpUrl, String repositoryInput, String playerName,
                String searchQuery, String providerName) {
            this.index = index;
            this.kind = kind;
            this.disposition = disposition;
            this.displayText = displayText;
            this.httpUrl = httpUrl;
            this.repositoryInput = repositoryInput;
            this.playerName = playerName;
            this.searchQuery = searchQuery;
            this.providerName = providerName;
        }

        public boolean requiresConfirmation() {
            return disposition == Disposition.REQUIRES_CONFIRMATION;
        }

        public boolean isRejected() {
            return disposition == Disposition.REJECTED;
        }

        public String getSafeDisplayText() {
            return displayText;
        }

        /**
         * Returns the parsed HTTP(S) target for a caller that has already obtained explicit user
         * confirmation. Never log this value; it may contain private query parameters.
         */
        public String getHttpUrlForConfirmedAction() {
            if (disposition != Disposition.REQUIRES_CONFIRMATION
                    || (kind != Kind.WEB_LINK && kind != Kind.REPOSITORY_LINK
                    && kind != Kind.SHARED_PROVIDER_LINK)) {
                return null;
            }
            return boundedActionValue(httpUrl, MAX_ACTION_VALUE_LENGTH);
        }

        /** Return the repository URL or shortcode only after explicit user confirmation. */
        public String getRepositoryInputForConfirmedAction() {
            if (disposition != Disposition.REQUIRES_CONFIRMATION || kind != Kind.REPOSITORY_LINK) {
                return null;
            }
            return boundedActionValue(repositoryInput, MAX_ACTION_VALUE_LENGTH);
        }

        /** Return a player target only after explicit user confirmation; never log it. */
        public String getPlayerUrlForConfirmedAction() {
            if (disposition != Disposition.REQUIRES_CONFIRMATION || kind != Kind.PLAYER_LINK) return null;
            return boundedActionValue(httpUrl, MAX_ACTION_VALUE_LENGTH);
        }

        /** Player title metadata is hidden in display text and available only after confirmation. */
        public String getPlayerNameForConfirmedAction() {
            if (disposition != Disposition.REQUIRES_CONFIRMATION || kind != Kind.PLAYER_LINK) return null;
            return boundedActionValue(playerName, MAX_DISPLAY_NAME_LENGTH);
        }

        /** Return the search query only after explicit user confirmation. */
        public String getSearchQueryForConfirmedAction() {
            if (disposition != Disposition.REQUIRES_CONFIRMATION || kind != Kind.SEARCH_QUERY) return null;
            return boundedActionValue(searchQuery, MAX_ACTION_VALUE_LENGTH);
        }

        /** Return the shared provider name only after explicit user confirmation. */
        public String getProviderNameForConfirmedAction() {
            if (disposition != Disposition.REQUIRES_CONFIRMATION
                    || kind != Kind.SHARED_PROVIDER_LINK) return null;
            return boundedActionValue(providerName, MAX_DISPLAY_NAME_LENGTH);
        }
    }
}
