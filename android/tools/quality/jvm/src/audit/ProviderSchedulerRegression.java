package audit;

import com.auras.orbit.providers.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deterministic scheduler stress checks; compiles the REAL provider Java sources. */
public final class ProviderSchedulerRegression {
    private static int checks = 0;
    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
    private static final class Provider implements MediaProvider {
        final ProviderDescriptor descriptor;
        final CountDownLatch started;
        final CountDownLatch release;
        final AtomicInteger calls;
        Provider(String id, CountDownLatch started, CountDownLatch release, AtomicInteger calls) {
            descriptor = new ProviderDescriptor(id, id, false, true, true, false, false);
            this.started = started; this.release = release; this.calls = calls;
        }
        @Override public ProviderDescriptor descriptor() { return descriptor; }
        @Override public List<MediaItem> search(SearchRequest request) throws Exception {
            calls.incrementAndGet();
            if (started != null) started.countDown();
            if (release != null) {
                while (release.getCount() > 0) {
                    try { release.await(30, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* deliberately uncooperative plugin */ }
                }
            }
            return Collections.emptyList();
        }
    }
    private static final SearchRequest REQUEST = new SearchRequest("test", SearchType.ALL, 1);
    private static Provider provider(String id, CountDownLatch started, CountDownLatch release,
                                     AtomicInteger calls) {
        return new Provider(id, started, release, calls);
    }
    private static ThreadPoolExecutor pool(ProviderOperationCoordinator coordinator) throws Exception {
        Field f = ProviderOperationCoordinator.class.getDeclaredField("workers");
        f.setAccessible(true);
        return (ThreadPoolExecutor)f.get(coordinator);
    }
    private static ScheduledThreadPoolExecutor clock(ProviderOperationCoordinator coordinator) throws Exception {
        Field f = ProviderOperationCoordinator.class.getDeclaredField("timer");
        f.setAccessible(true);
        return (ScheduledThreadPoolExecutor)f.get(coordinator);
    }
    private static void waitUntilComplete(ProviderBatch<?> batch, long maxMs) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMs);
        while (!batch.isComplete() && System.nanoTime() < end) Thread.sleep(1);
        check(batch.isComplete(), "batch terminal before deadline");
    }

    private static void busyQueueHasHardCapAndBackpressure() throws Exception {
        ProviderOperationCoordinator c = new ProviderOperationCoordinator(1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<ProviderBatch<?>> all = new ArrayList<>();
        try {
            all.add(c.search(List.of(provider("stalled", started, release, calls)), REQUEST, 10000, null));
            check(started.await(2, TimeUnit.SECONDS), "blocked worker entered");
            for (int i = 0; i < 400; i++) {
                all.add(c.search(List.of(provider("queued-" + i, null, null, calls)), REQUEST, 10000, null));
            }
            check(pool(c).getQueue().size() <= 32, "bounded queue never exceeds 32 for one worker");
            int rejected = 0;
            for (int i = 1; i < all.size(); i++) {
                ProviderOutcome<?> state = all.get(i).snapshot().get(0);
                if (state.status == ProviderOutcome.Status.FAILURE) {
                    check("Provider is busy; retry shortly".equals(state.message),
                            "backpressure has safe stable diagnostic");
                    rejected++;
                }
            }
            check(rejected >= 350, "overload rejected immediately instead of reserving memory");
            for (ProviderBatch<?> batch : all) batch.close();
            check(pool(c).getQueue().isEmpty(), "close removes all cancelled queued work");
            check(clock(c).getQueue().isEmpty(), "close removes all cancelled timer tasks");
        } finally {
            release.countDown();
            c.close();
        }
    }

    private static void earlyTimeoutDoesNotResurrectCancelledTask() throws Exception {
        ProviderOperationCoordinator c = new ProviderOperationCoordinator(1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try {
            ProviderBatch<?> blocker = c.search(List.of(provider("blocker", started, release, calls)),
                    REQUEST, 10000, null);
            check(started.await(2, TimeUnit.SECONDS), "timeout fixture worker entered");
            for (int i = 0; i < 600; i++) {
                ProviderBatch<?> b = c.search(List.of(provider("race-" + i, null, null, calls)),
                        REQUEST, 1, null);
                waitUntilComplete(b, 500);
                b.close();
            }
            Thread.sleep(20);
            check(pool(c).getQueue().isEmpty(), "race cancelled before / after execute stays unlinked");
            check(calls.get() == 1, "no timed-out queued plugin ran");
            blocker.close();
            check(clock(c).getQueue().isEmpty(), "cancel timers removed immediately");
        } finally {
            release.countDown();
            c.close();
        }
    }

    private static void callbackCanCloseDuringRegistration() throws Exception {
        ProviderOperationCoordinator c = new ProviderOperationCoordinator(1);
        AtomicInteger pluginCalls = new AtomicInteger(), callbacks = new AtomicInteger();
        try {
            ProviderBatch<?> batch = c.search(List.of(provider("test", null, null, pluginCalls)),
                    REQUEST, 1000, snapshot -> { callbacks.incrementAndGet(); c.close(); });
            check(callbacks.get() >= 1, "initial event observed");
            check(batch.isComplete(), "synchronous coordinator close cancels registered batch");
            check(pluginCalls.get() == 0, "closed batch cannot start plugin call");
        } finally { c.close(); }
    }

    private static void providerCallLifecycleAndRetry() throws Exception {
        ProviderOperationCoordinator c = new ProviderOperationCoordinator(1);
        AtomicInteger calls = new AtomicInteger();
        Provider p = provider("normal", null, null, calls);
        try {
            for (int j = 0; j < 250; j++) {
                ProviderBatch<?> batch = c.search(List.of(p), REQUEST, 2000, null);
                waitUntilComplete(batch, 2000);
                check(batch.snapshot().get(0).status == ProviderOutcome.Status.SUCCESS, "successful result stable");
                check(!batch.retry("normal"), "retry rejected for successful attempt");
                batch.close();
            }
            check(calls.get() == 250, "exact invocation count");
        } finally { c.close(); }
    }

    private static void multipleWorkersRemainBounded() throws Exception {
        for (int workers : new int[] {1, 2, 4, 8}) {
            ProviderOperationCoordinator c = new ProviderOperationCoordinator(workers);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            List<ProviderBatch<?>> all = new ArrayList<>();
            try {
                for (int i = 0; i < workers; i++) {
                    all.add(c.search(List.of(provider("worker-" + i, null, release, calls)),
                            REQUEST, 5000, null));
                }
                for (int i = 0; i < 500; i++) {
                    all.add(c.search(List.of(provider("waiting-" + i, null, null, calls)),
                            REQUEST, 5000, null));
                }
                check(pool(c).getQueue().size() <= Math.max(32, workers * 32),
                        "bounded capacity at concurrency " + workers);
                for (ProviderBatch<?> b : all) b.close();
                check(pool(c).getQueue().size() == 0, "capacity reclaimed after cancel");
            } finally { release.countDown(); c.close(); }
        }
    }

    /** An extension's revoke hook must not abort cleanup of unrelated resources. */
    private static void throwingRevocationDoesNotBreakShutdown() throws Exception {
        ProviderOperationCoordinator c = new ProviderOperationCoordinator(1);
        AtomicInteger notified = new AtomicInteger();
        // Register both before closing: one callback can be hostile or simply buggy.
        c.registerProviderResource("hostile", "profile", () -> {
            notified.incrementAndGet();
            throw new IllegalStateException("plugin cleanup crashed");
        });
        c.registerProviderResource("healthy", "profile", notified::incrementAndGet);
        c.close();
        check(notified.get() == 2, "all revocation callbacks invoked despite a throw");
        check(pool(c).isShutdown(), "worker executor shut down after a bad callback");
        check(clock(c).isShutdown(), "timer executor shut down after a bad callback");
    }

    public static void main(String[] args) throws Exception {
        throwingRevocationDoesNotBreakShutdown();
        busyQueueHasHardCapAndBackpressure();
        earlyTimeoutDoesNotResurrectCancelledTask();
        callbackCanCloseDuringRegistration();
        providerCallLifecycleAndRetry();
        multipleWorkersRemainBounded();
        System.out.println("PROVIDER_SCHEDULER_REGRESSION checks=" + checks + " failures=0");
    }
}
