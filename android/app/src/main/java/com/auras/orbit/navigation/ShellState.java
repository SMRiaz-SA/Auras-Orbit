package com.auras.orbit.navigation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Activity-owned navigation state. A process-wide singleton cannot correctly represent
 * two Android task windows, two independent Back stacks, or two television focus graphs.
 * All transitions produce an immutable snapshot so asynchronous work can reject an old epoch.
 * Provider actions, credentials, Activity references and Views must never be stored here.
 */
public final class ShellState {
    private ShellRoute route = ShellRoute.HOME;
    private long revision = 1L;
    private String profileScope = "";
    private final EnumMap<ShellRoute, String> focusByRoute = new EnumMap<>(ShellRoute.class);
    private final EnumMap<ShellRoute, Integer> scrollByRoute = new EnumMap<>(ShellRoute.class);

    public static final class Snapshot {
        public final ShellRoute route;
        public final long revision;
        public final String profileScope;
        public final Map<ShellRoute, String> focusByRoute;
        public final Map<ShellRoute, Integer> scrollByRoute;

        private Snapshot(ShellRoute route, long revision, String profileScope,
                         Map<ShellRoute, String> focus, Map<ShellRoute, Integer> scroll) {
            this.route = route;
            this.revision = revision;
            this.profileScope = profileScope;
            this.focusByRoute = Collections.unmodifiableMap(new EnumMap<>(focus));
            this.scrollByRoute = Collections.unmodifiableMap(new EnumMap<>(scroll));
        }
    }

    public synchronized ShellRoute getRoute() { return route; }
    public synchronized long getRevision() { return revision; }
    public synchronized String getProfileScope() { return profileScope; }

    /** A route transition is idempotent. Never advance an epoch on an identical route. */
    public synchronized Snapshot navigateTo(ShellRoute destination) {
        ShellRoute safe = destination == null ? ShellRoute.HOME : destination;
        if (route != safe) {
            route = safe;
            revision++;
        }
        return snapshot();
    }

    /** The accepted shell's top-level Back rule is Home, not an invented history stack. */
    public synchronized Snapshot backToHome() { return navigateTo(ShellRoute.HOME); }

    /** Clear route-local presentation details when the user switches accounts/profiles. */
    public synchronized Snapshot setProfileScope(String scope) {
        String normalized = scope == null ? "" : scope;
        if (normalized.length() > 256) throw new IllegalArgumentException("profile scope too long");
        if (!profileScope.equals(normalized)) {
            profileScope = normalized;
            route = ShellRoute.HOME;
            focusByRoute.clear();
            scrollByRoute.clear();
            revision++;
        }
        return snapshot();
    }

    public synchronized void rememberFocus(ShellRoute destination, String focusTag) {
        Objects.requireNonNull(destination, "route");
        if (focusTag == null || focusTag.isEmpty()) {
            focusByRoute.remove(destination);
        } else if (focusTag.length() <= 256 && focusTag.matches("[A-Za-z0-9_:.\\-]{1,256}")) {
            focusByRoute.put(destination, focusTag);
        } else {
            throw new IllegalArgumentException("Unsafe focus tag");
        }
    }

    public synchronized String focusFor(ShellRoute destination) {
        return focusByRoute.get(destination);
    }

    public synchronized void rememberScroll(ShellRoute destination, int scrollY) {
        Objects.requireNonNull(destination, "route");
        scrollByRoute.put(destination, Math.max(0, Math.min(scrollY, 1_000_000)));
    }

    public synchronized int scrollFor(ShellRoute destination) {
        Integer saved = scrollByRoute.get(destination);
        return saved == null ? 0 : saved;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(route, revision, profileScope, focusByRoute, scrollByRoute);
    }

    /** Restore presentation only, never authorizations or request generations. */
    public synchronized Snapshot restore(Snapshot old, String actualProfileScope) {
        Objects.requireNonNull(old, "snapshot");
        String actual = actualProfileScope == null ? "" : actualProfileScope;
        if (!old.profileScope.equals(actual)) return setProfileScope(actual);
        route = old.route;
        profileScope = actual;
        focusByRoute.clear();
        focusByRoute.putAll(old.focusByRoute);
        scrollByRoute.clear();
        scrollByRoute.putAll(old.scrollByRoute);
        revision = Math.max(revision, old.revision) + 1; // stale callbacks stay invalid
        return snapshot();
    }
}
