package io.github.wouthh.tradeassistant.runtime;

import io.github.wouthh.tradeassistant.domain.Model.Submission;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Nonblocking invalidation. A write committed before invalidation may finish in transport. */
public final class SafetyGate {
    private final AtomicLong generation = new AtomicLong();

    public long generation() {
        return generation.get();
    }

    public long invalidate() {
        return generation.incrementAndGet();
    }

    public Submission submit(long permit, Supplier<Submission> write) {
        return permit == generation.get() ? write.get() : Submission.UNKNOWN;
    }
}
