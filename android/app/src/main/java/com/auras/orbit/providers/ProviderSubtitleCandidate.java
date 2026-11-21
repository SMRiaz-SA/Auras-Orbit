package com.auras.orbit.providers;

import com.auras.orbit.player.SubtitleFormat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable subtitle metadata reported by a provider during link resolution. */
public final class ProviderSubtitleCandidate {
    public final String id;
    public final String language;
    public final String uri;
    public final String mimeType;
    public final Map<String, String> headers;

    public ProviderSubtitleCandidate(String id, String language, String uri, Map<String, String> headers) {
        this(id, language, uri, headers, null);
    }

    public ProviderSubtitleCandidate(String id, String language, String uri,
            Map<String, String> headers, String mimeType) {
        this.id = required(id, "subtitle id", 160);
        this.language = language == null ? "" : (language.length() > 32
                ? language.substring(0, 32) : language).trim();
        this.uri = required(uri, "subtitle URI", 4096);
        this.mimeType = SubtitleFormat.allowedMime(mimeType) == null
                ? "" : SubtitleFormat.allowedMime(mimeType);
        this.headers = immutableHeaders(headers);
    }

    private static String required(String value, String field, int maximum) {
        if (value == null || value.length() > maximum || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is missing or too long");
        }
        return value.trim();
    }

    private static Map<String, String> immutableHeaders(Map<String, String> value) {
        return ProviderHeaderBudget.immutableSnapshot(value);
    }
}
