package io.github.wouthh.tradeassistant;

import static io.github.wouthh.tradeassistant.domain.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.wouthh.tradeassistant.runtime.SafetyGate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SafetyGateTest {
    @Test
    void cancellationCannotReturnBetweenPermitValidationAndTheLocalWrite() throws Exception {
        SafetyGate gate = new SafetyGate();
        long permit = gate.generation();
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        CountDownLatch cancellationStarted = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger writeOrder = new AtomicInteger();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var send =
                    threads.submit(
                            () ->
                                    gate.submit(
                                            permit,
                                            () -> {
                                                validated.countDown();
                                                try {
                                                    assertTrue(
                                                            releaseWrite.await(
                                                                    5, TimeUnit.SECONDS));
                                                } catch (InterruptedException e) {
                                                    throw new AssertionError(e);
                                                }
                                                writeOrder.set(sequence.incrementAndGet());
                                                return Submission.SUBMITTED;
                                            }));
            try {
                assertTrue(validated.await(5, TimeUnit.SECONDS));
                var cancellation =
                        threads.submit(
                                () -> {
                                    cancellationStarted.countDown();
                                    gate.invalidate();
                                    return sequence.incrementAndGet();
                                });
                assertTrue(cancellationStarted.await(5, TimeUnit.SECONDS));
                assertThrows(
                        TimeoutException.class, () -> cancellation.get(100, TimeUnit.MILLISECONDS));
                releaseWrite.countDown();
                assertEquals(Submission.SUBMITTED, send.get(5, TimeUnit.SECONDS));
                assertEquals(1, writeOrder.get());
                assertEquals(2, cancellation.get(5, TimeUnit.SECONDS));
                assertEquals(
                        Submission.CANCELLED,
                        gate.submit(
                                permit,
                                () -> {
                                    fail("A stale write began after cancellation returned");
                                    return Submission.SUBMITTED;
                                }));
            } finally {
                releaseWrite.countDown();
            }
        }
    }

    @Test
    void failedLocalWriteReleasesCancellationBoundary() {
        SafetyGate gate = new SafetyGate();
        long permit = gate.generation();
        assertThrows(
                IllegalStateException.class,
                () ->
                        gate.submit(
                                permit,
                                () -> {
                                    throw new IllegalStateException(
                                            "synthetic local write failure");
                                }));
        assertEquals(permit + 1, gate.invalidate());
        assertEquals(Submission.CANCELLED, gate.submit(permit, () -> Submission.SUBMITTED));
    }
}
