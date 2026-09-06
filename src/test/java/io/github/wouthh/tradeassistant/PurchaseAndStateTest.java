package io.github.wouthh.tradeassistant;

import static io.github.wouthh.tradeassistant.domain.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.wouthh.tradeassistant.domain.*;
import io.github.wouthh.tradeassistant.runtime.*;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PurchaseAndStateTest {
    @Test
    void acknowledgementArchivesTheOriginalPendingIdentities() throws Exception {
        Harness h = new Harness();
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        h.drop(80);
        try (LocalState store = new LocalState(temp)) {
            store.save(h.engine.snapshot());
            h.engine.stop();
            h.engine.acknowledge();
            store.save(h.engine.snapshot());
            try (var files = Files.list(temp)) {
                Path archive =
                        files.filter(p -> p.getFileName().toString().startsWith("previous-"))
                                .findFirst()
                                .orElseThrow();
                String json = Files.readString(archive);
                assertTrue(json.contains("-80"));
                assertTrue(json.contains("PLACEMENT_CONFIRMED"));
                assertTrue(json.contains("runRoom"));
            }
        }
    }

    @TempDir Path temp;

    @Test
    void throwingPurchaseContractBecomesUnknownAndNeverRetries() {
        PurchasePhase p =
                new PurchasePhase(
                        product -> {
                            throw new IllegalStateException("synthetic fault");
                        });
        p.start(new PurchasePhase.Budget(2, 50, 100, 100, 100, false), true);
        p.next();
        p.next();
        assertTrue(p.unknown());
        assertEquals(1, p.sent());
        assertEquals(0, p.received());
    }

    @Test
    void unavailableContractNeverSends() {
        PurchasePhase p = new PurchasePhase(null);
        assertThrows(
                IllegalStateException.class,
                () -> p.start(new PurchasePhase.Budget(1, 50, 100, 50, 50, false), true));
        p.next();
        assertEquals(0, p.sent());
    }

    @Test
    void integerBudgetsRejectInsufficientFundsAndOverflow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PurchasePhase.Budget(2, 50, 100, 50, 100, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PurchasePhase.Budget(2, 50, 99, 100, 100, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PurchasePhase.Budget(2, 50, 100, 100, 99, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PurchasePhase.Budget(0, 50, 100, 100, 100, true));
        assertThrows(
                ArithmeticException.class,
                () ->
                        new PurchasePhase.Budget(
                                1000,
                                Long.MAX_VALUE,
                                Long.MAX_VALUE,
                                Long.MAX_VALUE,
                                Long.MAX_VALUE,
                                true));
    }

    @Test
    void fakePurchaseContractSerializesAndDeduplicates() {
        PurchasePhase p = new PurchasePhase(product -> true);
        p.start(new PurchasePhase.Budget(2, 50, 200, 100, 100, false), true);
        p.next();
        p.next();
        assertEquals(1, p.sent());
        p.delivered("receipt-a", PurchasePhase.PRODUCT, 1, 150);
        p.delivered("receipt-a", PurchasePhase.PRODUCT, 1, 150);
        assertEquals(1, p.received());
        p.next();
        assertEquals(2, p.sent());
        p.delivered("receipt-b", PurchasePhase.PRODUCT, 1, 100);
        p.next();
        assertEquals(2, p.received());
        assertEquals(100, p.spend());
        assertEquals(2, p.sent());
    }

    @Test
    void unknownPurchaseNeverRetriesOrCreatesConversionInput() {
        PurchasePhase p = new PurchasePhase(product -> true);
        p.start(new PurchasePhase.Budget(2, 50, 100, 100, 100, false), true);
        p.next();
        p.delivered("wrong", PurchasePhase.PRODUCT, 2, 0);
        p.next();
        assertTrue(p.unknown());
        assertEquals(1, p.sent());
        assertEquals(0, p.received());
        assertEquals(0, p.spend());
    }

    @Test
    void stopStillAllowsReconciliationWithoutAnotherPurchase() {
        PurchasePhase p = new PurchasePhase(product -> true);
        p.start(new PurchasePhase.Budget(2, 50, 100, 100, 100, false), true);
        p.next();
        p.stop();
        p.delivered("one", PurchasePhase.PRODUCT, 1, 50);
        p.next();
        assertEquals(1, p.sent());
        assertEquals(1, p.received());
    }

    @Test
    void journalRecoversWithoutResumeAndLocksStateDirectory() throws Exception {
        Harness h = new Harness();
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        h.drop(80);
        h.advance(0);
        try (LocalState s = new LocalState(temp)) {
            s.save(h.engine.snapshot());
            assertThrows(IOException.class, () -> new LocalState(temp));
            s.preferences(2, 2000, 15000);
            assertEquals("2", s.preferences().getProperty("quantity"));
            assertFalse(s.preferences().containsKey("armed"));
        }
        try (LocalState s = new LocalState(temp)) {
            assertTrue(s.needsReview());
            assertTrue(s.recoverySummary().contains("80"));
            ConversionEngine e =
                    new ConversionEngine(h, h, () -> 0, s, snapshot -> {}, s.needsReview());
            assertEquals(State.UNCERTAIN, e.snapshot().state());
            e.connected(true);
            e.on(new RoomReady(42));
            assertThrows(
                    IllegalStateException.class,
                    () -> e.start(Config.defaults(Mode.MANUAL_DROPS, 1, false)));
            e.acknowledge();
            assertEquals(State.DISARMED, e.snapshot().state());
        }
        assertEquals(1, h.sent.size());
    }

    @Test
    void malformedOrOversizedJournalFailsClosed() throws Exception {
        Files.writeString(temp.resolve("journal.json"), "not json");
        try (LocalState s = new LocalState(temp)) {
            assertTrue(s.needsReview());
        }
    }

    @Test
    void safetyGateChecksAtTheWriteBoundary() {
        SafetyGate gate = new SafetyGate();
        long permit = gate.generation();
        gate.invalidate();
        assertEquals(
                Submission.UNKNOWN,
                gate.submit(
                        permit,
                        () -> {
                            fail("Stale write executed");
                            return Submission.SUBMITTED;
                        }));
    }
}
