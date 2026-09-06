package io.github.wouthh.tradeassistant;

import static org.junit.jupiter.api.Assertions.*;

import io.github.wouthh.tradeassistant.runtime.SerialRuntime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeFailureTest {
    @Test
    void overflowCancelsQueuedWorkAndPermanentlyDisablesTheWorker() throws Exception {
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger actions = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        SerialRuntime runtime = new SerialRuntime(cancellations::incrementAndGet);
        runtime.onFailure(failed::countDown);
        try {
            runtime.execute(
                    () -> {
                        entered.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 2100; i++) runtime.execute(actions::incrementAndGet);
            assertEquals(1, cancellations.get());
            release.countDown();
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            runtime.execute(actions::incrementAndGet);
            runtime.later(0, actions::incrementAndGet);
            runtime.finish(() -> {});
            assertEquals(0, actions.get());
        } finally {
            release.countDown();
            runtime.close();
        }
    }

    @Test
    void unexpectedWorkerFailureCancelsAndPublishesFailure() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger cancellations = new AtomicInteger();
        try (SerialRuntime runtime = new SerialRuntime(cancellations::incrementAndGet)) {
            runtime.onFailure(failed::countDown);
            runtime.execute(
                    () -> {
                        throw new IllegalStateException("synthetic fault");
                    });
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertEquals(1, cancellations.get());
        }
    }
}
