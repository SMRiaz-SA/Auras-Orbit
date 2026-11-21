package com.lagradost.cloudstream3.extractors;

/** HTTP Odnoklassniki extractor variant with the pinned public JVM constructor. */
public class OkRuHTTP extends Odnoklassniki {
    private String name = "OkRuHTTP";
    private String mainUrl = "http://ok.ru";

    public OkRuHTTP() { super(); }

    @Override public String getName() { return name; }
    public void setName(String value) {
        if (value == null) throw new NullPointerException("name");
        name = value;
    }
    @Override public String getMainUrl() { return mainUrl; }
    public void setMainUrl(String value) {
        if (value == null) throw new NullPointerException("mainUrl");
        mainUrl = value;
    }
}
