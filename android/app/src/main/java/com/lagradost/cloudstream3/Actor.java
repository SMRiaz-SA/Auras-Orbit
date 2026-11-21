package com.lagradost.cloudstream3;

import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;

/** Orbit-owned data facade matching the pinned CS3 Actor JVM shape. */
public final class Actor {
    private final String name;
    private final String image;

    public Actor(String name, String image) {
        Intrinsics.checkNotNullParameter(name, "name");
        this.name = name;
        this.image = image;
    }

    /** Kotlin default-argument constructor: image defaults to null (mask bit 1). */
    public Actor(String name, String image, int mask, DefaultConstructorMarker marker) {
        this(name, (mask & 2) != 0 ? null : image);
    }

    public final String getName() { return name; }
    public final String getImage() { return image; }
    public final String component1() { return name; }
    public final String component2() { return image; }

    public final Actor copy(String name, String image) {
        return new Actor(name, image);
    }

    public static Actor copy$default(Actor self, String name, String image, int mask, Object marker) {
        if ((mask & 1) != 0) name = self.name;
        if ((mask & 2) != 0) image = self.image;
        return self.copy(name, image);
    }

    @Override public String toString() {
        return "Actor(name=" + name + ", image=" + image + ")";
    }

    @Override public int hashCode() {
        return 31 * name.hashCode() + (image == null ? 0 : image.hashCode());
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Actor)) return false;
        Actor actor = (Actor) other;
        return Intrinsics.areEqual(name, actor.name) && Intrinsics.areEqual(image, actor.image);
    }
}
