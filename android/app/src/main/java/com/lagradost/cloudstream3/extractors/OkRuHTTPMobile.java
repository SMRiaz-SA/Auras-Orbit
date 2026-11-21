package com.lagradost.cloudstream3.extractors;

/** Mobile HTTP Odnoklassniki extractor with the pinned public JVM constructor. */
public final class OkRuHTTPMobile extends OkRuHTTP {
    private String mainUrl = "http://m.ok.ru";

    public OkRuHTTPMobile() { super(); }

    @Override public String getMainUrl() { return mainUrl; }
    @Override public void setMainUrl(String value) {
        if (value == null) throw new NullPointerException("mainUrl");
        mainUrl = value;
    }
}
