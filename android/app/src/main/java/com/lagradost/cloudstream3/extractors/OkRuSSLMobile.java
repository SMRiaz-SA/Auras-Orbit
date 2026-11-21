package com.lagradost.cloudstream3.extractors;

/** Mobile HTTPS Odnoklassniki extractor with the pinned public JVM constructor. */
public final class OkRuSSLMobile extends OkRuSSL {
    private String mainUrl = "https://m.ok.ru";

    public OkRuSSLMobile() { super(); }

    @Override public String getMainUrl() { return mainUrl; }
    @Override public void setMainUrl(String value) {
        if (value == null) throw new NullPointerException("mainUrl");
        mainUrl = value;
    }
}
