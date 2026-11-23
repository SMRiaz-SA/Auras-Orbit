package com.auras.orbit.providers;

import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe aggregate metadata reservation for provider links retained during callbacks. */
public final class ProviderLinkMetadataBudget {
    private final long maximumChars;
    private final AtomicLong retainedChars = new AtomicLong();

    public ProviderLinkMetadataBudget(long maximumChars) {
        if (maximumChars <= 0L) throw new IllegalArgumentException("A positive metadata budget is required.");
        this.maximumChars = maximumChars;
    }

    /** Declines only this reservation; later smaller candidates may still fit. */
    public boolean tryReserve(long chars) {
        if (chars <= 0L || chars > maximumChars) return false;
        while (true) {
            long current = retainedChars.get();
            if (current > maximumChars - chars) return false;
            if (retainedChars.compareAndSet(current, current + chars)) return true;
        }
    }

    public long retainedChars() {
        return retainedChars.get();
    }
}
