package android.net;

import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@android.annotation.Implemented
public class Uri {
    private final String value;
    private final URI uri;

    private Uri(String value) {
        this.value = value;
        URI parsed;
        try {
            parsed = URI.create(value);
        } catch (Exception e) {
            parsed = null;
        }
        this.uri = parsed;
    }

    public static Uri parse(String value) {
        return new Uri(value);
    }

    public String getPath() {
        return uri != null ? uri.getPath() : null;
    }

    public String getHost() {
        return uri != null ? uri.getHost() : null;
    }

    public String getScheme() {
        return uri != null ? uri.getScheme() : null;
    }

    public String getQuery() {
        return uri != null ? uri.getQuery() : null;
    }

    public Set<String> getQueryParameterNames() {
        if (uri != null && uri.isOpaque()) {
            throw new UnsupportedOperationException("This isn't a hierarchical URI.");
        }
        return parseQueryParametersMulti(false).keySet();
    }

    public String getQueryParameter(String key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (uri != null && uri.isOpaque()) {
            throw new UnsupportedOperationException("This isn't a hierarchical URI.");
        }
        String encodedKey = encodeUriComponent(key);
        for (String part : getRawQueryParts()) {
            String[] pair = part.split("=", 2);
            if (!pair[0].equals(encodedKey)) {
                continue;
            }
            return pair.length > 1 ? decodeComponent(pair[1], true) : "";
        }
        return null;
    }

    public List<String> getQueryParameters(String key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (uri != null && uri.isOpaque()) {
            throw new UnsupportedOperationException("This isn't a hierarchical URI.");
        }
        String encodedKey;
        try {
            // Android's plural method uses form-style encoding for its lookup key,
            // even though it percent-decodes returned values without converting '+'.
            encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        List<String> values = new ArrayList<>();
        for (String part : getRawQueryParts()) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals(encodedKey)) {
                values.add(pair.length > 1 ? decodeComponent(pair[1], false) : "");
            }
        }
        return values;
    }

    private Map<String, List<String>> parseQueryParametersMulti(boolean decodePlusAsSpace) {
        // Split the encoded query before decoding. Decoding first would turn an escaped
        // ampersand into a delimiter and decode escaped percent sequences twice.
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (String part : getRawQueryParts()) {
            String[] kv = part.split("=", 2);
            String rawKey = kv[0];
            if (rawKey.isEmpty()) {
                continue;
            }
            String key = decodeComponent(rawKey, false);
            String value = kv.length > 1 ? decodeComponent(kv[1], decodePlusAsSpace) : "";
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return params;
    }

    private List<String> getRawQueryParts() {
        String query = uri != null ? uri.getRawQuery() : null;
        if (query == null || query.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> parts = new ArrayList<>();
        for (String part : query.split("&")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private String encodeUriComponent(String value) {
        try {
            // Android Uri.encode leaves these unreserved characters unchanged and
            // encodes spaces as %20 (unlike URLEncoder, which emits '+').
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20")
                    .replace("%21", "!")
                    .replace("%27", "'")
                    .replace("%28", "(")
                    .replace("%29", ")")
                    .replace("%7E", "~");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String decodeComponent(String value, boolean decodePlusAsSpace) {
        try {
            // Android's singular getQueryParameter converts '+' to a space on Jelly
            // Bean and later, while getQueryParameters and parameter names retain it.
            String encodedValue = decodePlusAsSpace ? value : value.replace("+", "%2B");
            return URLDecoder.decode(encodedValue, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    public String getLastPathSegment() {
        String path = getPath();
        if (path == null || path.isEmpty()) {
            return null;
        }
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    @Override
    public String toString() {
        return value;
    }
}
