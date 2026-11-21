package audit;

import com.auras.orbit.providers.DetailsRequestSingleFlight;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class DetailsSingleFlightRegression {
    private static void assertTrue(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        DetailsRequestSingleFlight<String> flights = new DetailsRequestSingleFlight<>();
        Object firstPluginInstance = new Object();
        Object secondPluginInstance = new Object();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch providerRelease = new CountDownLatch(1);
        AtomicReference<String> ownerResult = new AtomicReference<>();
        AtomicReference<String> followerResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            try { ownerResult.set(flights.execute(firstPluginInstance, "profile1", "/title1", () -> {
                calls.incrementAndGet(); providerStarted.countDown();
                if (!providerRelease.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                return "full episodes";
            })); } catch (Throwable ex) { failure.set(ex); }
        });
        owner.start();
        assertTrue(providerStarted.await(3, TimeUnit.SECONDS), "provider did not start");
        Thread follower = new Thread(() -> {
            try { followerResult.set(flights.execute(firstPluginInstance, "profile1", "/title1", () -> {
                calls.incrementAndGet(); return "should not execute";
            })); } catch (Throwable ex) { failure.set(ex); }
        });
        follower.start();
        // Both subscribers observe one logical operation while the owner is active.
        Thread.sleep(40);
        assertTrue(calls.get() == 1, "duplicate provider execution");
        providerRelease.countDown();
        owner.join(3000); follower.join(3000);
        assertTrue(failure.get() == null, "subscriber failed: " + failure.get());
        assertTrue("full episodes".equals(ownerResult.get()) && "full episodes".equals(followerResult.get()), "different responses");
        assertTrue(flights.activeCount() == 0, "completion leaked provider reference");
        assertTrue("different generation".equals(flights.execute(secondPluginInstance, "profile1", "/title1",
                () -> "different generation")), "plugin generation reused result");
        assertTrue("different profile".equals(flights.execute(firstPluginInstance, "profile2", "/title1",
                () -> "different profile")), "profile reused result");
        try {
            flights.execute(firstPluginInstance, "profile1", "/error", () -> { throw new IllegalStateException("retry"); });
            throw new AssertionError("failure suppressed");
        } catch (IllegalStateException expected) { assertTrue("retry".equals(expected.getMessage()), "wrong failure"); }
        assertTrue("success".equals(flights.execute(firstPluginInstance, "profile1", "/error",
                () -> "success")), "failure must not poison later calls");
        // One impatient subscriber must time out independently, without poisoning the
        // shared owner or forcing other subscribers to lose the in-flight response.
        CountDownLatch blockedStarted = new CountDownLatch(1);
        CountDownLatch blockedRelease = new CountDownLatch(1);
        AtomicReference<String> eventual = new AtomicReference<>();
        Thread slowOwner = new Thread(() -> {
            try {
                eventual.set(flights.execute(firstPluginInstance, "profile1", "/slow", () -> {
                    blockedStarted.countDown();
                    if (!blockedRelease.await(3, TimeUnit.SECONDS)) throw new AssertionError("owner stuck");
                    return "owner complete";
                }, 2000L));
            } catch (Exception ex) { failure.set(ex); }
        });
        slowOwner.start();
        assertTrue(blockedStarted.await(3, TimeUnit.SECONDS), "slow provider did not start");
        try {
            flights.execute(firstPluginInstance, "profile1", "/slow", () -> {
                throw new AssertionError("follower started second provider call");
            }, 25L);
            throw new AssertionError("subscriber timeout was ignored");
        } catch (TimeoutException expected) {
            assertTrue(flights.activeCount() == 1, "timeout cancelled another subscriber");
        } finally { blockedRelease.countDown(); }
        slowOwner.join(3000);
        assertTrue(failure.get() == null && "owner complete".equals(eventual.get()),
                "owner not recoverable after follower timeout: " + failure.get());
        assertTrue(flights.activeCount() == 0, "slow owner leaked");
        try {
            flights.execute(firstPluginInstance, "profile1", "/negative", () -> "bad", -1L);
            throw new AssertionError("negative timeout accepted");
        } catch (IllegalArgumentException expected) { /* time budgets must be explicit */ }
        System.out.println("DETAILS_SINGLE_FLIGHT_PASS");
    }
}
