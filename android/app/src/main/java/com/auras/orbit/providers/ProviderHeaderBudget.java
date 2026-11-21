package com.auras.orbit.providers;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounds provider-supplied HTTP headers before any callback or playback metadata copy. */
public final class ProviderHeaderBudget {
    public static final int MAX_HEADERS = 64;
    public static final int MAX_SCANNED_ENTRIES = 256;
    public static final int MAX_NAME_CHARS = 256;
    public static final int MAX_VALUE_CHARS = 8192;

    private ProviderHeaderBudget() { }

    public static Map<String, String> immutableSnapshot(Map<String, String> supplied) {
        if (supplied == null) return Collections.emptyMap();
        int count = supplied.size();
        if (count < 0 || count > MAX_HEADERS) {
            throw new IllegalArgumentException("Too many provider request headers.");
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        Set<String> normalizedNames = new HashSet<>();
        Iterator<Map.Entry<String, String>> iterator = supplied.entrySet().iterator();
        int scanned = 0;
        while (iterator.hasNext()) {
            if (++scanned > MAX_SCANNED_ENTRIES) {
                throw new IllegalArgumentException("Provider request header iterator exceeded scan budget.");
            }
            Map.Entry<String, String> entry = iterator.next();
            if (entry == null || entry.getKey() == null || entry.getValue() == null) continue;
            String rawName = entry.getKey();
            String rawValue = entry.getValue();
            if (rawName.length() > MAX_NAME_CHARS || rawValue.length() > MAX_VALUE_CHARS) {
                throw new IllegalArgumentException("Oversized provider request header.");
            }
            String name = rawName.trim();
            if (name.isEmpty() || !validName(name) || !validValue(rawValue)
                    || !normalizedNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Malformed or duplicated provider request header.");
            }
            if (copy.size() >= MAX_HEADERS) {
                throw new IllegalArgumentException("Too many provider request headers.");
            }
            copy.put(name, rawValue);
        }
        return copy.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(copy);
    }

    private static boolean validName(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || "!#$%&'*+-.^_`|~".indexOf(c) >= 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\u0000'
                    || (Character.isISOControl(c) && c != '\t')) return false;
        }
        return true;
    }
}
