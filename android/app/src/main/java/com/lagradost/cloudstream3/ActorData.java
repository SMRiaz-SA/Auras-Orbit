package com.lagradost.cloudstream3;

import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;

/** Orbit-owned data facade matching the pinned CS3 ActorData JVM shape. */
public final class ActorData {
    private final Actor actor;
    private final ActorRole role;
    private final String roleString;
    private final Actor voiceActor;

    public ActorData(Actor actor, ActorRole role, String roleString, Actor voiceActor) {
        Intrinsics.checkNotNullParameter(actor, "actor");
        this.actor = actor;
        this.role = role;
        this.roleString = roleString;
        this.voiceActor = voiceActor;
    }

    /** Kotlin default-argument constructor; nullable values default at mask bits 1, 2, and 3. */
    public ActorData(Actor actor, ActorRole role, String roleString, Actor voiceActor,
                     int mask, DefaultConstructorMarker marker) {
        this(actor, (mask & 2) != 0 ? null : role,
                (mask & 4) != 0 ? null : roleString,
                (mask & 8) != 0 ? null : voiceActor);
    }

    public final Actor getActor() { return actor; }
    public final ActorRole getRole() { return role; }
    public final String getRoleString() { return roleString; }
    public final Actor getVoiceActor() { return voiceActor; }
    public final Actor component1() { return actor; }
    public final ActorRole component2() { return role; }
    public final String component3() { return roleString; }
    public final Actor component4() { return voiceActor; }

    public final ActorData copy(Actor actor, ActorRole role, String roleString, Actor voiceActor) {
        return new ActorData(actor, role, roleString, voiceActor);
    }

    public static ActorData copy$default(ActorData self, Actor actor, ActorRole role,
            String roleString, Actor voiceActor, int mask, Object marker) {
        if ((mask & 1) != 0) actor = self.actor;
        if ((mask & 2) != 0) role = self.role;
        if ((mask & 4) != 0) roleString = self.roleString;
        if ((mask & 8) != 0) voiceActor = self.voiceActor;
        return self.copy(actor, role, roleString, voiceActor);
    }

    @Override public String toString() {
        return "ActorData(actor=" + actor + ", role=" + role
                + ", roleString=" + roleString + ", voiceActor=" + voiceActor + ")";
    }

    @Override public int hashCode() {
        int result = actor.hashCode();
        result = 31 * result + (role == null ? 0 : role.hashCode());
        result = 31 * result + (roleString == null ? 0 : roleString.hashCode());
        result = 31 * result + (voiceActor == null ? 0 : voiceActor.hashCode());
        return result;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ActorData)) return false;
        ActorData data = (ActorData) other;
        return Intrinsics.areEqual(actor, data.actor)
                && Intrinsics.areEqual(role, data.role)
                && Intrinsics.areEqual(roleString, data.roleString)
                && Intrinsics.areEqual(voiceActor, data.voiceActor);
    }
}
