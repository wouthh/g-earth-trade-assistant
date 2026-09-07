package io.github.wouthh.tradeassistant.runtime;

import io.github.wouthh.tradeassistant.domain.Model.Submission;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Serializes cancellation with the local transport commit. Invalidation may wait for a write
 * already in progress, but no stale write can begin after invalidation returns.
 */
public final class SafetyGate {
    private final AtomicLong generation = new AtomicLong();

    public long generation() {
        return generation.get();
    }

    public synchronized long invalidate() {
        return generation.incrementAndGet();
    }

    public synchronized Submission submit(long permit, Supplier<Submission> write) {
        return permit == generation.get() ? write.get() : Submission.CANCELLED;
    }
}
