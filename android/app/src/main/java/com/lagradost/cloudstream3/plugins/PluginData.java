package com.lagradost.cloudstream3.plugins;

/** Orbit-owned installed plugin metadata view; this value never activates a package. */
public final class PluginData {
    private final String internalName;
    private final String url;
    private final boolean online;
    private final String filePath;
    private final int version;

    public PluginData(String internalName, String url, boolean online, String filePath, int version) {
        this.internalName = internalName;
        this.url = url;
        this.online = online;
        this.filePath = filePath;
        this.version = version;
    }

    public String getInternalName() { return internalName; }
    public String getUrl() { return url; }
    public boolean isOnline() { return online; }
    public String getFilePath() { return filePath; }
    public int getVersion() { return version; }
}
