package com.lagradost.cloudstream3;

import java.util.Map;

/** Orbit-owned subtitle value matching the pinned CS3 lang/url/headers shape. */
public final class SubtitleFile {
    private String lang;
    private String url;
    private Map<String, String> headers;
    private String mimeType;

    private SubtitleFile(String lang, String url, Map<String, String> headers) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(lang, "lang");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(url, "url");
        this.lang = lang;
        this.url = url;
        this.headers = headers;
        this.mimeType = "";
    }

    public SubtitleFile(String lang, String url) { this(lang, url, null); }

    public String getLang() { return lang; }
    public void setLang(String value) { lang = value; }
    public String getUrl() { return url; }
    public void setUrl(String value) { url = value; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> value) { headers = value; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String value) { mimeType = value == null ? "" : value; }
}
