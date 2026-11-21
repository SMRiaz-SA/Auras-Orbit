package audit;

import com.auras.orbit.navigation.ShellRoute;
import com.auras.orbit.navigation.ShellState;

/** No Android instrumentation required for route/epoch/focus invariants. */
public final class ShellNavigationStateRegression {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        ShellState phone = new ShellState();
        ShellState tv = new ShellState();
        long original = phone.getRevision();
        phone.navigateTo(ShellRoute.SEARCH);
        check(phone.getRoute() == ShellRoute.SEARCH, "phone navigation");
        check(tv.getRoute() == ShellRoute.HOME, "separate Android task route");
        check(phone.getRevision() > original, "navigation epoch");
        long sameEpoch = phone.getRevision();
        phone.navigateTo(ShellRoute.SEARCH);
        check(sameEpoch == phone.getRevision(), "idempotent route");
        phone.rememberFocus(ShellRoute.SEARCH, "search_query");
        phone.rememberScroll(ShellRoute.SEARCH, 1040);
        ShellState.Snapshot saved = phone.snapshot();
        phone.navigateTo(ShellRoute.LIBRARY);
        check(saved.route == ShellRoute.SEARCH, "immutable snapshot");
        check("search_query".equals(saved.focusByRoute.get(ShellRoute.SEARCH)), "snapshot focus");
        ShellState restored = new ShellState();
        restored.setProfileScope("p1");
        phone.setProfileScope("p1");
        phone.rememberFocus(ShellRoute.SEARCH, "search_query");
        phone.rememberScroll(ShellRoute.SEARCH, 1040);
        phone.navigateTo(ShellRoute.SEARCH);
        saved = phone.snapshot();
        restored.restore(saved, "p1");
        check(restored.getRoute() == ShellRoute.SEARCH, "same-profile route restore");
        check(restored.scrollFor(ShellRoute.SEARCH) == 1040, "scroll restore");
        check("search_query".equals(restored.focusFor(ShellRoute.SEARCH)), "focus restore");
        long restoredEpoch = restored.getRevision();
        restored.setProfileScope("p2");
        check(restored.getRoute() == ShellRoute.HOME, "profile switch route reset");
        check(restored.focusFor(ShellRoute.SEARCH) == null, "profile switch drops focus");
        check(restored.getRevision() > restoredEpoch, "profile switch invalidates callbacks");
        restored.restore(saved, "p2");
        check(restored.getRoute() == ShellRoute.HOME, "cross-profile restore forbidden");
        restored.navigateTo(ShellRoute.DOWNLOADS);
        restored.backToHome();
        check(restored.getRoute() == ShellRoute.HOME, "accepted top level Back contract");
        try { restored.rememberFocus(ShellRoute.HOME, " bad\n tag"); throw new AssertionError("invalid focus"); }
        catch (IllegalArgumentException expected) { checks++; }
        check(restored.snapshot().focusByRoute.isEmpty(), "no invalid tag persisted");
        // Portable state-machine soak only. Real 500 D-pad / 200 navigation journeys
        // still require physical/emulated Android TV and screenshot qualification.
        ShellState manyRoutes = new ShellState();
        manyRoutes.setProfileScope("tv-profile");
        ShellState otherWindow = new ShellState();
        otherWindow.setProfileScope("tv-profile");
        ShellRoute[] destinations = ShellRoute.values();
        for (int i = 0; i < 500; i++) {
            ShellRoute destination = destinations[i % destinations.length];
            manyRoutes.navigateTo(destination);
            manyRoutes.rememberFocus(destination, "dpad_" + i);
            manyRoutes.rememberScroll(destination, i * 37);
            check(manyRoutes.getRoute() == destination, "soak route " + i);
            check(("dpad_" + i).equals(manyRoutes.focusFor(destination)), "soak focus " + i);
            check(manyRoutes.scrollFor(destination) == i * 37, "soak scroll " + i);
            if (i % 5 == 0) {
                ShellState recovered = new ShellState();
                recovered.restore(manyRoutes.snapshot(), "tv-profile");
                check(recovered.getRoute() == destination, "same-window restore " + i);
            }
        }
        check(otherWindow.getRoute() == ShellRoute.HOME, "independent window navigation");
        check(otherWindow.snapshot().focusByRoute.isEmpty(), "independent window focus");
        System.out.println("SHELL_NAVIGATION_WINDOW_STATE_PASS checks=" + checks
                + " portable_focus_actions=500 route_transitions=500");
    }
}
