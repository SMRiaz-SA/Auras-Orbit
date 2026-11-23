package com.auras.orbit.providers;

import java.util.Locale;
import java.util.regex.Pattern;

/** Maps declared provider media types without inferring or rejecting them from URL suffixes. */
public final class ProviderMediaTypeMapper {
    private static final Pattern MIME_TYPE = Pattern.compile(
            "[A-Za-z0-9!#$&^_.+-]{1,127}/[A-Za-z0-9!#$&^_.+-]{1,127}");

    private ProviderMediaTypeMapper() { }

    public static String mimeType(String declaredType, String providerMimeType) {
        String type = declaredType == null ? "" : declaredType.trim().toUpperCase(Locale.ROOT);
        if ("M3U8".equals(type)) return "application/x-mpegurl";
        if ("DASH".equals(type)) return "application/dash+xml";

        String supplied = safeMimeType(providerMimeType);
        if ("VIDEO".equals(type)) return supplied.isEmpty() ? "video/mp4" : supplied;
        return supplied;
    }

    private static String safeMimeType(String value) {
        if (value == null) return "";
        String result = value.trim().toLowerCase(Locale.ROOT);
        return MIME_TYPE.matcher(result).matches() ? result : "";
    }
}
