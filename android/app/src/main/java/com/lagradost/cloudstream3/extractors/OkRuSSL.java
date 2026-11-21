package com.lagradost.cloudstream3.extractors;

/** HTTPS Odnoklassniki extractor variant with the pinned public JVM constructor. */
public class OkRuSSL extends Odnoklassniki {
    private String name = "OkRuSSL";
    private String mainUrl = "https://ok.ru";

    public OkRuSSL() { super(); }

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
