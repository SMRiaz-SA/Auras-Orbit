package com.auras.orbit.ui;

import java.util.concurrent.atomic.AtomicLong;

/** Local-only performance instrumentation, without metadata, identities or telemetry. */
public final class OrbitShellPerformanceCounters {
    private final AtomicLong fullRootMounts = new AtomicLong();
    private final AtomicLong incrementalDetailsSwaps = new AtomicLong();
    private final AtomicLong detailsNetworkLoads = new AtomicLong();
    private final AtomicLong detailsMemoryHits = new AtomicLong();

    public void rootMounted() { fullRootMounts.incrementAndGet(); }
    public void detailsSwapped() { incrementalDetailsSwaps.incrementAndGet(); }
    public void detailsRequested() { detailsNetworkLoads.incrementAndGet(); }
    public void detailsCacheHit() { detailsMemoryHits.incrementAndGet(); }

    public Snapshot snapshot() {
        return new Snapshot(fullRootMounts.get(), incrementalDetailsSwaps.get(),
                detailsNetworkLoads.get(), detailsMemoryHits.get());
    }

    public static final class Snapshot {
        public final long fullRootMounts;
        public final long incrementalDetailsSwaps;
        public final long detailsNetworkLoads;
        public final long detailsMemoryHits;
        private Snapshot(long fullRootMounts, long incrementalDetailsSwaps,
                         long detailsNetworkLoads, long detailsMemoryHits) {
            this.fullRootMounts = fullRootMounts;
            this.incrementalDetailsSwaps = incrementalDetailsSwaps;
            this.detailsNetworkLoads = detailsNetworkLoads;
            this.detailsMemoryHits = detailsMemoryHits;
        }
    }
}
