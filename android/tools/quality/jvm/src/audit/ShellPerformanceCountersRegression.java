package audit;

import com.auras.orbit.ui.OrbitShellPerformanceCounters;
import java.util.concurrent.CountDownLatch;

public final class ShellPerformanceCountersRegression {
    public static void main(String[] args) throws Exception {
        OrbitShellPerformanceCounters counters = new OrbitShellPerformanceCounters();
        Thread[] threads=new Thread[6];
        CountDownLatch start=new CountDownLatch(1);
        for(int i=0;i<threads.length;i++) {
            threads[i]=new Thread(()->{
                try {start.await();} catch(InterruptedException failure){Thread.currentThread().interrupt();return;}
                for(int j=0;j<10_000;j++) {
                    counters.rootMounted();counters.detailsSwapped();
                    counters.detailsRequested();counters.detailsCacheHit();
                }
            });threads[i].start();
        }
        start.countDown();
        for(Thread t:threads)t.join(15_000L);
        for(Thread t:threads) if(t.isAlive()) throw new AssertionError("counter thread stuck");
        OrbitShellPerformanceCounters.Snapshot result=counters.snapshot();
        if(result.fullRootMounts!=60_000L || result.incrementalDetailsSwaps!=60_000L
                || result.detailsNetworkLoads!=60_000L || result.detailsMemoryHits!=60_000L) {
            throw new AssertionError("lost counters");
        }
        System.out.println("SHELL_PERFORMANCE_COUNTERS_PASS assertions=4 concurrent_increments=240000");
    }
}
