package audit;

import com.auras.orbit.ui.BoundedTaskExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests Orbit's actual image scheduler with 10,000 adversarial tasks. */
public final class ArtworkSchedulerRegression {
    public static void main(String[] args) throws Exception {
        int checks = 0;
        try (BoundedTaskExecutor executor = new BoundedTaskExecutor("test-art", 3, 96)) {
            CountDownLatch entered = new CountDownLatch(3), release = new CountDownLatch(1);
            AtomicInteger completed = new AtomicInteger();
            for (int i = 0; i < 3; i++) {
                if (!executor.submit(() -> {
                    entered.countDown();
                    try { release.await(15, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    completed.incrementAndGet();
                })) throw new AssertionError("Initial worker task rejected");
            }
            if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("Worker fixture did not start");
            int accepted = 0, rejected = 0;
            for (int i = 0; i < 10000; i++) {
                if (executor.submit(completed::incrementAndGet)) accepted++;
                else rejected++;
                if (executor.queuedCount() > 96) throw new AssertionError("Unbounded poster backlog");
                checks++;
            }
            if (accepted != 96 || rejected != 9904) {
                throw new AssertionError("Unexpected poster admission accepted="+accepted+" rejected="+rejected);
            }
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (completed.get() < 99 && System.nanoTime() < deadline) Thread.sleep(1);
            if (completed.get() != 99) throw new AssertionError("Accepted work not executed");
            checks += 3;
        }
        System.out.println("ARTWORK_SCHEDULER_REGRESSION checks=" + checks + " failures=0");
    }
}
