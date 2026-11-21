package com.auras.orbit.providers;

import org.junit.Test;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Regression: timeout/cancel must unlink work even when a plugin ignores interruption. */
public final class ProviderCoordinatorBackpressureTest {
    private static final SearchRequest REQUEST = new SearchRequest("test", SearchType.ALL, 1);

    private static MediaProvider fixture(String id, CountDownLatch started, CountDownLatch release,
                                          AtomicInteger calls) {
        return new MediaProvider() {
            final ProviderDescriptor d = new ProviderDescriptor(id, id, false, true, true, false, false);
            @Override public ProviderDescriptor descriptor() { return d; }
            @Override public List<MediaItem> search(SearchRequest request) throws Exception {
                calls.incrementAndGet();
                if (started != null) started.countDown();
                if (release != null) {
                    while (release.getCount() > 0) {
                        try { release.await(50, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* deliberately uncooperative */ }
                    }
                }
                return Collections.emptyList();
            }
        };
    }

    private static ThreadPoolExecutor pool(ProviderOperationCoordinator coordinator) throws Exception {
        Field field = ProviderOperationCoordinator.class.getDeclaredField("workers");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(coordinator);
    }

    @Test public void thousandsOfCancelledJobsLeaveNoQueuedTasks() throws Exception {
        ProviderOperationCoordinator coordinator = new ProviderOperationCoordinator(1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try {
            coordinator.search(Collections.singletonList(fixture("blocked", started, release, calls)),
                    REQUEST, 10_000, null);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            List<ProviderBatch<?>> batches = new ArrayList<>();
            for (int i = 0; i < 1200; i++) {
                batches.add(coordinator.search(Collections.singletonList(
                        fixture("queued-" + i, null, null, calls)), REQUEST, 30, null));
            }
            Thread.sleep(300);
            for (ProviderBatch<?> batch : batches) {
                assertTrue(batch.isComplete());
                batch.close();
            }
            assertEquals(0, pool(coordinator).getQueue().size());
            assertEquals("queued jobs must not invoke a plugin after cancelling", 1, calls.get());
        } finally {
            release.countDown();
            coordinator.close();
        }
    }

    @Test public void queueOverloadIsBoundedAndRetryable() throws Exception {
        ProviderOperationCoordinator coordinator = new ProviderOperationCoordinator(1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<ProviderBatch<?>> batches = new ArrayList<>();
        try {
            coordinator.search(Collections.singletonList(fixture("blocked", started, release, calls)),
                    REQUEST, 10000, null);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 120; i++) {
                batches.add(coordinator.search(Collections.singletonList(
                        fixture("queued-" + i, null, null, calls)), REQUEST, 10000, null));
            }
            assertTrue(pool(coordinator).getQueue().size() <= 32);
            long busy = batches.stream().filter(batch ->
                    "Provider is busy; retry shortly".equals(batch.snapshot().get(0).message)).count();
            assertTrue("excess jobs should fail with explicit retry message", busy >= 80);
            for (ProviderBatch<?> batch : batches) batch.close();
            assertEquals(0, pool(coordinator).getQueue().size());
        } finally {
            release.countDown();
            coordinator.close();
        }
    }
}
