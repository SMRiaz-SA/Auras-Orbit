package com.auras.orbit.navigation;

/** Pure route-transition invariants shared by the phone and television shell. */
public final class ShellNavigationRules {
    private ShellNavigationRules() { }

    /** A provider-details callback cannot remain active on a different top-level page. */
    public static boolean shouldCloseProviderDetails(boolean open, ShellRoute detailsRoute,
                                                     ShellRoute requestedRoute) {
        return open && detailsRoute != requestedRoute;
    }
}
