package com.lagradost.cloudstream3;

/** CS3 dub grouping and its source-compatible numeric identifier. */
public enum DubStatus {
    None(-1),
    Dubbed(1),
    Subbed(0);

    private final int id;

    DubStatus(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }
}
