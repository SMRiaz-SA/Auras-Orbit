package audit;

import com.auras.orbit.navigation.ShellRoute;
import com.auras.orbit.player.PlaybackHandoffExpiry;
import com.auras.orbit.navigation.ShellNavigationRules;
import com.auras.orbit.ui.WeakPendingImageRequests;
import com.auras.orbit.ui.BoundedTaskExecutor;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScreenLifecycleRegression {
    private static long checks;
    private static void verify(boolean value, String explanation) {
        checks++;
        if (!value) throw new AssertionError(explanation + "; check=" + checks);
    }
    public static void main(String[] args) throws Exception {
        for (ShellRoute active : ShellRoute.values()) {
            for (ShellRoute requested : ShellRoute.values()) {
                verify(ShellNavigationRules.shouldCloseProviderDetails(true, active, requested)
                        == (active != requested), "all route details transitions");
                verify(!ShellNavigationRules.shouldCloseProviderDetails(false, active, requested),
                        "closed details don't need cleanup");
            }
        }
        // Player handoffs are one-time capabilities, never allowed to survive an expired launch.
        verify(!PlaybackHandoffExpiry.isExpired(1000, 1000, 600), "fresh handoff");
        verify(!PlaybackHandoffExpiry.isExpired(1000, 1600, 600), "exact expiry boundary");
        verify(PlaybackHandoffExpiry.isExpired(1000, 1601, 600), "expired token");
        verify(PlaybackHandoffExpiry.isExpired(1001, 1000, 600), "invalid clock rollback");
        verify(PlaybackHandoffExpiry.isExpired(-1, 1, 600), "negative timestamp");
        verify(PlaybackHandoffExpiry.isExpired(0, 1, -1), "invalid lifetime");
        Random expiryFuzz = new Random(0x735A1337L);
        for (int trial = 0; trial < 300_000; trial++) {
            long origin = expiryFuzz.nextInt(2_000_000);
            long lifetime = expiryFuzz.nextInt(30_000);
            long elapsed = expiryFuzz.nextInt(2_010_000);
            boolean expected = elapsed < origin || elapsed - origin > lifetime;
            verify(PlaybackHandoffExpiry.isExpired(origin, elapsed, lifetime) == expected,
                    "handoff expiry monotonic fuzz");
        }
        WeakPendingImageRequests<Object> registry = new WeakPendingImageRequests<>(64);
        Object first = new Object();
        Object second = new Object();
        verify(registry.startOrJoin("a",first),"schedule first");
        verify(!registry.startOrJoin("a",first),"duplicate waiter does not reschedule");
        verify(!registry.startOrJoin("a",second),"second waiter shares worker");
        verify(registry.pendingCount()==1 && registry.liveWaiterCount("a")==2,"two waiters");
        List<Object> results=registry.complete("a");
        verify(results.size()==2 && results.get(0)==first && results.get(1)==second,"single completion");
        verify(registry.complete("a").isEmpty(),"duplicate completion no effect");
        verify(registry.startOrJoin("a",second),"completed key can retry");
        registry.cancel("a");
        verify(registry.pendingCount()==0 && registry.startOrJoin("a",first),"cancelled key can retry");
        registry.cancel("a");
        // Verify dead references are not retained: use introspection only to clear weak references
        // without relying on nondeterministic GC timing.
        Object stale=new Object();
        registry.startOrJoin("orphan",stale);
        Field pendingField=WeakPendingImageRequests.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        Map<?,?> internal=(Map<?,?>)pendingField.get(registry);
        @SuppressWarnings("unchecked") List<WeakReference<Object>> refs=(List<WeakReference<Object>>)internal.get("orphan");
        verify(refs.size()==1 && refs.get(0).get()==stale,"registry stores a weak view");
        refs.get(0).clear();
        verify(registry.liveWaiterCount("orphan")==0,"cleared view is invisible");
        verify(!registry.startOrJoin("orphan",first),"stale slot can be reused without reschedule");
        verify(registry.liveWaiterCount("orphan")==1,"old dead reference pruned");
        registry.cancel("orphan");
        for (int i=0;i<100_000;i++) {
            Object object=new Object();
            String key="image-"+(i%96);
            boolean firstSchedule=registry.startOrJoin(key,object);
            verify(firstSchedule==(i<96),"bounded unique work by key");
            verify(registry.pendingCount()<=96,"pending entries bounded by caller queue");
            if (i%91==0) registry.startOrJoin(key,object);
        }
        for (int i=0;i<96;i++) registry.cancel("image-"+i);
        verify(registry.pendingCount()==0,"all keys retired");
        // Saturation must fail rather than allocate a growing queue; shutdown must stop accepting.
        CountDownLatch started=new CountDownLatch(1);
        CountDownLatch release=new CountDownLatch(1);
        AtomicInteger done=new AtomicInteger();
        try(BoundedTaskExecutor pool=new BoundedTaskExecutor("screen-test",1,4)) {
            verify(pool.submit(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}),"blocking worker accepted");
            verify(started.await(2,TimeUnit.SECONDS),"worker started");
            for(int i=0;i<4;i++)verify(pool.submit(done::incrementAndGet),"bounded queue takes allowed work");
            for(int i=0;i<100_000;i++) {
                verify(!pool.submit(done::incrementAndGet),"excess UI requests rejected");
                verify(pool.queuedCount()<=4,"bounded queue budget");
            }
            release.countDown();
        }
        System.out.println("ScreenLifecycleRegression PASS checks="+checks);
    }
}
