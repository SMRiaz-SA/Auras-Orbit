package com.lagradost.cloudstream3;

import com.lagradost.cloudstream3.OrbitCs3FacadeBoundary;

/** Orbit-owned per-provider setting snapshot. */
public class SettingsJson {
    private volatile boolean enableAdult;
    public SettingsJson() { super(); }
    public boolean getEnableAdult() {
        return enableAdult;
    }
    public void setEnableAdult(boolean value) { enableAdult = value; }
}
