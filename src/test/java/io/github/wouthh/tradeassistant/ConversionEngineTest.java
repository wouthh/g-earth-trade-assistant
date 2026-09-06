package io.github.wouthh.tradeassistant;

import static io.github.wouthh.tradeassistant.domain.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.wouthh.tradeassistant.domain.*;
import org.junit.jupiter.api.Test;

class ConversionEngineTest {
    @Test
    void conflictAfterRedemptionPreventsAttributingTheRemoval() {
        Harness h = new Harness();
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        h.drop(80);
        h.advance(0);
        assertEquals(1, h.sent.size());
        h.engine.on(new Conflict());
        h.engine.on(new Removed(new ObjectId(80)));
        assertEquals(0, h.engine.snapshot().redeemed());
        assertEquals(State.UNCERTAIN, h.engine.snapshot().state());
    }

    @Test
    void cancellationWhileJournalingIntentCannotCreateARedemptionAcknowledgement() {
        Harness h = new Harness();
        ConversionEngine e =
                new ConversionEngine(
                        h,
                        h,
                        h.gate::generation,
                        snapshot -> {
                            if (snapshot.operations().containsValue("REDEMPTION_INTENT"))
                                h.gate.invalidate();
                        },
                        snapshot -> {},
                        false);
        e.connected(true);
        e.on(new RoomReady(42));
        e.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        e.on(new Placement(new Handle(-80), Harness.TARGET));
        e.on(new StripRemoval(new Handle(-80)));
        e.on(new Added(new ObjectId(80), BRONZE, Harness.TARGET));
        h.advance(0);
        assertTrue(h.sent.isEmpty());
        e.on(new Removed(new ObjectId(80)));
        assertEquals(0, e.snapshot().redeemed());
        assertEquals(State.UNCERTAIN, e.snapshot().state());
    }

    @Test
    void previousRunTimeoutCannotExpireANewExplicitManualPlacement() {
        Harness h = new Harness();
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        h.engine.on(new Placement(new Handle(-80), Harness.TARGET));
        h.advance(1000);
        h.engine.stop();
        h.engine.acknowledge();
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        h.engine.on(new Placement(new Handle(-80), Harness.TARGET));
        h.advance(14000);
        assertNotEquals(State.UNCERTAIN, h.engine.snapshot().state());
        assertTrue(h.sent.isEmpty());
        h.advance(1000);
        assertEquals(State.UNCERTAIN, h.engine.snapshot().state());
    }

    @Test
    void newInventoryArrivalsCannotFeedTheCurrentRun() {
        Harness h = new Harness();
        h.engine.on(Harness.inventory(80, 81));
        h.engine.start(Config.defaults(Mode.INVENTORY, 2, true));
        h.drop(80);
        h.advance(0);
        h.engine.on(Harness.inventory(80, 999));
        h.confirm(h.sent.getFirst());
        h.advance(2000);
        assertEquals(State.STOPPED, h.engine.snapshot().state());
        assertEquals(1, h.sent.size());
    }

    @Test
    void manualModeNeedsNoInventoryAndBalanceCannotAcknowledge() {
        Harness h = new Harness();
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        h.drop(120045);
        h.advance(0);
        assertEquals(1, h.sent.size());
        assertEquals("redeem", h.sent.getFirst().kind());
        h.engine.on(new Balance(50));
        h.engine.on(new Removed(new ObjectId(999)));
        assertEquals(0, h.engine.snapshot().redeemed());
        h.engine.on(new Removed(new ObjectId(120045)));
        assertEquals(State.COMPLETE, h.engine.snapshot().state());
        h.advance(20000);
        assertEquals(1, h.engine.snapshot().redeemed());
        assertEquals(1, h.sent.size());
    }

    @Test
    void foreignAndPreexistingAdditionsAreNeverSelected() {
        Harness h = new Harness();
        h.engine.on(new Added(new ObjectId(71), BRONZE, Harness.TARGET));
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 2, false));
        h.engine.on(new Added(new ObjectId(72), BRONZE, Harness.TARGET));
        h.advance(0);
        assertTrue(h.sent.isEmpty());
        h.drop(71);
        h.advance(0);
        assertTrue(h.sent.isEmpty());
        assertEquals(State.UNCERTAIN, h.engine.snapshot().state());
    }

    @Test
    void wrongTypeOrDestinationFailsClosed() {
        for (boolean wrongType : new boolean[] {true, false}) {
            Harness h = new Harness();
            h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
            Handle id = new Handle(-44);
            h.engine.on(new Placement(id, Harness.TARGET));
            h.engine.on(new StripRemoval(id));
            h.engine.on(
                    new Added(
                            id.expectedObject(),
                            wrongType ? "CF_20_moneybag" : BRONZE,
                            wrongType ? Harness.TARGET : new Target(8, 8, 0)));
            h.advance(0);
            assertTrue(h.sent.isEmpty());
            assertEquals(State.UNCERTAIN, h.engine.snapshot().state());
        }
    }

    @Test
    void responseReorderingDuplicatesAndRepeatedStart() {
        Harness h = new Harness();
        Config cfg = Config.defaults(Mode.MANUAL_DROPS, 1, false);
        h.engine.start(cfg);
        assertThrows(IllegalStateException.class, () -> h.engine.start(cfg));
        Handle id = new Handle(-77);
        h.engine.on(new Placement(id, Harness.TARGET));
        h.engine.on(new Added(id.expectedObject(), BRONZE, Harness.TARGET));
        h.advance(0);
        assertTrue(h.sent.isEmpty());
        h.engine.on(new StripRemoval(id));
        h.advance(0);
        h.engine.on(new StripRemoval(id));
        h.engine.on(new Added(id.expectedObject(), BRONZE, Harness.TARGET));
        h.advance(0);
        assertEquals(1, h.sent.size());
        assertEquals(1, h.engine.snapshot().placed());
        h.engine.on(new Removed(id.expectedObject()));
        h.engine.on(new Removed(id.expectedObject()));
        assertEquals(1, h.engine.snapshot().redeemed());
    }

    @Test
    void manualQuantityLimitAndBoundedPendingQueue() {
        Harness h = new Harness();
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        h.drop(80);
        h.drop(81);
        h.advance(0);
        h.confirm(h.sent.getFirst());
        assertEquals(1, h.sent.size());
        assertEquals(1, h.engine.snapshot().observed());
        Harness full = new Harness();
        full.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1000, false));
        for (int i = 1; i <= 129; i++)
            full.engine.on(new Placement(new Handle(-i), Harness.TARGET));
        full.advance(0);
        assertEquals(State.UNCERTAIN, full.engine.snapshot().state());
        assertTrue(full.sent.isEmpty());
        assertEquals(128, full.engine.snapshot().pendingHandles().size());
    }

    @Test
    void inventorySeedIncludedAndEveryControlledHandleIsDistinct() {
        Harness h = new Harness();
        h.reflect = true;
        h.engine.on(Harness.inventory(80, 92, 77));
        h.engine.start(Config.defaults(Mode.INVENTORY, 3, true));
        h.drop(80);
        h.advance(0);
        assertEquals("redeem", h.sent.getFirst().kind());
        h.confirm(h.sent.getFirst());
        for (int i = 1; i < 5; i++) {
            h.advance(1500);
            assertEquals(i + 1, h.sent.size());
            h.confirm(h.sent.get(i));
        }
        assertEquals(State.COMPLETE, h.engine.snapshot().state());
        assertEquals(3, h.engine.snapshot().redeemed());
        assertEquals(2, h.sent.stream().filter(s -> s.kind().equals("place")).count());
        assertEquals(
                2,
                h.sent.stream()
                        .filter(s -> s.kind().equals("place"))
                        .map(s -> s.handle().value())
                        .distinct()
                        .count());
        assertTrue(
                h.sent.stream()
                        .filter(s -> s.kind().equals("place"))
                        .noneMatch(s -> s.handle().value() == -80));
    }

    @Test
    void nEqualsOneIsJustTheLearningSeed() {
        Harness h = new Harness();
        h.engine.on(Harness.inventory(80));
        h.engine.start(Config.defaults(Mode.INVENTORY, 1, true));
        h.drop(80);
        h.advance(0);
        h.confirm(h.sent.getFirst());
        h.advance(20000);
        assertEquals(1, h.sent.size());
        assertEquals(State.COMPLETE, h.engine.snapshot().state());
    }

    @Test
    void removalDuringRunProducesHonestExhaustion() {
        Harness h = new Harness();
        h.engine.on(Harness.inventory(80, 81));
        h.engine.start(Config.defaults(Mode.INVENTORY, 2, true));
        h.drop(80);
        h.advance(0);
        h.engine.on(new StripRemoval(new Handle(-81)));
        h.confirm(h.sent.getFirst());
        h.advance(2000);
        assertEquals(State.STOPPED, h.engine.snapshot().state());
        assertEquals(1, h.engine.snapshot().redeemed());
        assertEquals(1, h.sent.size());
    }

    @Test
    void stopInvalidatesAlreadyScheduledSendsButReconcilesPlacedObjects() {
        Harness h = new Harness();
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 2, false));
        h.drop(80);
        h.drop(81);
        h.advance(0);
        h.confirm(h.sent.getFirst());
        h.gate.invalidate();
        h.engine.stop();
        h.advance(60000);
        assertEquals(1, h.sent.size());
        assertTrue(h.engine.snapshot().unredeemed().contains(81));
    }

    @Test
    void pauseResumesOnlyWithExplicitDecision() {
        Harness h = new Harness();
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        h.drop(80);
        h.gate.invalidate();
        h.engine.pause();
        h.advance(2000);
        assertTrue(h.sent.isEmpty());
        h.engine.resume();
        h.advance(0);
        assertEquals(1, h.sent.size());
        h.engine.stop();
        h.confirm(h.sent.getFirst());
        assertEquals(1, h.engine.snapshot().redeemed());
    }

    @Test
    void timeoutAndLostContextNeverRetryAndRetainIdentifiers() {
        Harness h = new Harness();
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        h.drop(80);
        h.advance(15000);
        assertEquals(State.UNCERTAIN, h.engine.snapshot().state());
        assertEquals(1, h.sent.size());
        h.gate.invalidate();
        h.engine.disconnected();
        assertTrue(h.engine.snapshot().pendingHandles().contains(-80));
        assertTrue(h.engine.snapshot().unredeemed().contains(80));
        h.engine.connected(true);
        h.engine.on(new RoomReady(99));
        h.engine.on(new Removed(new ObjectId(80)));
        h.advance(60000);
        assertEquals(1, h.sent.size());
        assertThrows(
                IllegalStateException.class,
                () -> h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false)));
    }

    @Test
    void stopBeforeCallbacksAndTransportRejectionDoNotReplay() {
        Harness h = new Harness();
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        h.drop(80);
        h.gate.invalidate();
        h.engine.stop();
        h.advance(60000);
        assertTrue(h.sent.isEmpty());
        Harness r = new Harness();
        r.reject = true;
        r.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        r.drop(81);
        r.advance(60000);
        assertEquals(1, r.sent.size());
        assertEquals(State.UNCERTAIN, r.engine.snapshot().state());
    }

    @Test
    void conflictingClientActionOrRoomPermissionsDisarms() {
        Harness h = new Harness();
        h.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        h.drop(80);
        h.engine.on(new ExternalRedemption(new ObjectId(80)));
        h.advance(0);
        assertTrue(h.sent.isEmpty());
        Harness p = new Harness();
        p.engine.start(Config.defaults(Mode.MANUAL_DROPS, 1, false));
        p.gate.invalidate();
        p.engine.on(new PermissionsChanged());
        p.drop(81);
        p.advance(0);
        assertTrue(p.sent.isEmpty());
        assertEquals(State.DISARMED, p.engine.snapshot().state());
    }

    @Test
    void journalFailureBlocksSendsAndConfigurationIsBounded() {
        Harness h = new Harness();
        ConversionEngine e =
                new ConversionEngine(
                        h,
                        h,
                        () -> 0,
                        s -> {
                            throw new java.io.IOException();
                        },
                        s -> {},
                        false);
        e.connected(true);
        e.on(new RoomReady(42));
        assertThrows(
                IllegalStateException.class,
                () -> e.start(Config.defaults(Mode.MANUAL_DROPS, 1, false)));
        assertTrue(h.sent.isEmpty());
        assertThrows(
                IllegalArgumentException.class, () -> Config.defaults(Mode.MANUAL_DROPS, 0, false));
        assertThrows(
                IllegalArgumentException.class, () -> Config.defaults(Mode.INVENTORY, 1001, true));
    }
}
