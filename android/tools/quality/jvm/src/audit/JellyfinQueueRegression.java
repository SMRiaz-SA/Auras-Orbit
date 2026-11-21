package audit;

import com.auras.orbit.jellyfin.BoundedFutureExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class JellyfinQueueRegression {
    private static int checks;
    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        BoundedFutureExecutor pool = new BoundedFutureExecutor("jellyfin-probe", 3, 64);
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<String>> running = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                running.add(pool.submit(() -> {
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return "done";
                }));
            }
            require(started.await(5, TimeUnit.SECONDS), "workers did not start");
            List<Future<String>> queued = new ArrayList<>();
            for (int i = 0; i < 64; i++) queued.add(pool.submit(() -> "queued"));
            require(pool.queuedCount() == 64, "backlog cap incorrect");
            for (int i = 0; i < 20000; i++) {
                Future<String> rejected = pool.submit(() -> "never accepted");
                require(rejected.isDone(), "overload must return immediate Future");
                try {
                    rejected.get();
                    throw new AssertionError("overloaded call ran");
                } catch (ExecutionException expected) {
                    require(expected.getCause() instanceof java.io.IOException,
                            "overload did not fail as retryable IOException");
                }
            }
            for (Future<String> task : queued) require(task.cancel(true), "queued task cancellation failed");
            require(pool.queuedCount() == 0, "cancelled tasks retained by queue");
            release.countDown();
            for (Future<String> task : running) require("done".equals(task.get(5, TimeUnit.SECONDS)),
                    "worker was lost");
            require("accepted".equals(pool.submit(() -> "accepted").get(5, TimeUnit.SECONDS)),
                    "pool did not recover");
            pool.shutdownNow();
            Future<String> afterClose = pool.submit(() -> "impossible");
            require(afterClose.isDone(), "closed pool does not fail safely");
            try { afterClose.get(); throw new AssertionError("closed pool accepted work"); }
            catch (ExecutionException expected) { require(true, "closed pool failure"); }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        // A shutdown must also settle futures that were accepted but never started.
        BoundedFutureExecutor interrupted = new BoundedFutureExecutor("jellyfin-close-probe", 1, 8);
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        Future<String> blocked = interrupted.submit(() -> {
            busy.countDown();
            unblock.await(5, TimeUnit.SECONDS);
            return "finished";
        });
        try {
            require(busy.await(5, TimeUnit.SECONDS), "close probe worker did not start");
            List<Future<String>> pending = new ArrayList<>();
            for (int i = 0; i < 8; i++) pending.add(interrupted.submit(() -> "waiting"));
            require(interrupted.queuedCount() == 8, "expected queued work before close");
            interrupted.shutdownNow();
            for (Future<String> item : pending) {
                require(item.isDone(), "shutdown orphaned queued Future (get would hang)");
            }
        } finally {
            unblock.countDown();
            blocked.cancel(true);
            interrupted.shutdownNow();
        }
        System.out.println("JELLYFIN_QUEUE_REGRESSION checks=" + checks + " failures=0");
    }
}
