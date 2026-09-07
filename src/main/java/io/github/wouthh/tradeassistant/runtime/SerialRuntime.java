package io.github.wouthh.tradeassistant.runtime;

import io.github.wouthh.tradeassistant.domain.Model.Scheduler;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SerialRuntime implements Scheduler, AutoCloseable {
    private final ScheduledThreadPoolExecutor executor =
            new ScheduledThreadPoolExecutor(
                    1,
                    r -> {
                        Thread t = new Thread(r, "trade-assistant-worker");
                        t.setDaemon(true);
                        return t;
                    });
    private final AtomicInteger queued = new AtomicInteger();
    private final Runnable overflow;
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile Runnable failureHandler = () -> {};

    public void onFailure(Runnable handler) {
        failureHandler = handler;
    }

    private void fail() {
        if (!failed.compareAndSet(false, true)) return;
        overflow.run();
        try {
            executor.execute(() -> failureHandler.run());
        } catch (RejectedExecutionException ignored) {
            // Shutdown already prevents further execution.
        }
    }

    private void guarded(Runnable action) {
        if (failed.get()) return;
        try {
            action.run();
        } catch (RuntimeException e) {
            fail();
        }
    }

    public SerialRuntime(Runnable overflow) {
        this.overflow = overflow;
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public void execute(Runnable action) {
        if (failed.get()) return;
        if (queued.incrementAndGet() > 2048) {
            queued.decrementAndGet();
            fail();
            return;
        }
        try {
            executor.execute(
                    () -> {
                        try {
                            guarded(action);
                        } finally {
                            queued.decrementAndGet();
                        }
                    });
        } catch (RejectedExecutionException e) {
            queued.decrementAndGet();
        }
    }

    @Override
    public long now() {
        return System.nanoTime() / 1_000_000;
    }

    @Override
    public void later(long delay, Runnable action) {
        if (failed.get()) return;
        if (executor.getQueue().size() >= 4096) {
            fail();
            return;
        }
        try {
            if (!executor.isShutdown())
                executor.schedule(() -> guarded(action), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Concurrent shutdown cannot schedule another send.
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    public void finish(Runnable cleanup) {
        try {
            executor.execute(cleanup);
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        } catch (RejectedExecutionException ignored) {
            executor.shutdownNow();
        }
    }
}
