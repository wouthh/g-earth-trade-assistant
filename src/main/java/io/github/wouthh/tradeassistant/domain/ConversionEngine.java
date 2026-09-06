package io.github.wouthh.tradeassistant.domain;

import static io.github.wouthh.tradeassistant.domain.Model.*;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** All mutable state belongs to one executor. Packet callbacks never invoke this directly. */
public final class ConversionEngine {
    private static final class Drop {
        final Handle handle;
        final Target target;
        final boolean controlled;
        boolean removed,
                added,
                placementConfirmed,
                redeemIntent,
                redeemSent,
                acknowledgementAmbiguous,
                completed;
        String type;
        long deadline;

        Drop(Handle handle, Target target, boolean controlled) {
            this.handle = handle;
            this.target = target;
            this.controlled = controlled;
        }
    }

    private final Transport transport;
    private final Scheduler clock;
    private final LongSupplier fence;
    private final Journal journal;
    private final Consumer<Snapshot> updates;
    private final InventoryBook inventory = new InventoryBook();
    private final Map<Handle, Drop> drops = new LinkedHashMap<>();
    private final Set<Integer> seenAdds = new HashSet<>(),
            knownRoom = new HashSet<>(),
            used = new HashSet<>();
    private final Set<Integer> orphanHandles = new HashSet<>();
    private final Set<Handle> selected = new HashSet<>();
    private State state = State.DISARMED;
    private Config config;
    private boolean connected, recovery;
    private int room, observed, placed, redeemed, failed, uncertain;
    private Long balance;
    private Target target;
    private long runFence, roomGeneration, tickVersion, nextSend;
    private Drop current;
    private String message = "Connect through G-Earth, then enter a room. All automation is off.";

    public ConversionEngine(
            Transport transport,
            Scheduler clock,
            LongSupplier fence,
            Journal journal,
            Consumer<Snapshot> updates,
            boolean recovery) {
        this.transport = transport;
        this.clock = clock;
        this.fence = fence;
        this.journal = journal;
        this.updates = updates;
        this.recovery = recovery;
        if (recovery) {
            state = State.UNCERTAIN;
            message = "Previous run journal needs review. No operation will resume.";
        }
    }

    public void connected(boolean compatible) {
        contextLost();
        connected = compatible;
        inventory.clear();
        balance = null;
        message =
                compatible
                        ? "Origins connected. Enter a room before arming."
                        : "Unsupported connection or contradictory packet metadata.";
        publish();
    }

    public void disconnected() {
        contextLost();
        connected = false;
        inventory.clear();
        balance = null;
        message = "Disconnected; no automatic replay.";
        publish();
    }

    private void contextLost() {
        if (outstanding()) {
            uncertain++;
            recovery = true;
        }
        for (Drop d : drops.values()) if (!d.completed) orphanHandles.add(d.handle.value());
        room = 0;
        roomGeneration++;
        tickVersion++;
        target = null;
        current = null;
        drops.clear();
        seenAdds.clear();
        state = recovery ? State.UNCERTAIN : State.DISARMED;
        persist();
    }

    public void start(Config cfg) {
        if (state == State.PAUSED)
            throw new IllegalStateException("Resume or stop the paused run first");
        if (active()) throw new IllegalStateException("A run is already active");
        if (!connected || room <= 0)
            throw new IllegalStateException("Enter a supported Origins room first");
        if (recovery || outstanding() || !knownRoom.isEmpty())
            throw new IllegalStateException(
                    "Review and acknowledge outstanding journal entries first");
        if (cfg.mode() == Mode.INVENTORY && inventory.available().size() < cfg.quantity())
            throw new IllegalArgumentException(
                    "Not enough observed eligible instances; load inventory pages manually");
        if (cfg.mode() == Mode.INVENTORY && !cfg.learnTarget() && target == null)
            throw new IllegalArgumentException(
                    "Learn a destination from your next successful placement");
        config = cfg;
        selected.clear();
        inventory.available().forEach(instance -> selected.add(instance.handle()));
        drops.clear();
        used.clear();
        observed = placed = redeemed = failed = uncertain = 0;
        current = null;
        runFence = fence.getAsLong();
        state = cfg.mode() == Mode.MANUAL_DROPS || cfg.learnTarget() ? State.ARMED : State.RUNNING;
        if (cfg.mode() == Mode.INVENTORY && cfg.learnTarget()) target = null;
        nextSend = clock.now();
        message =
                cfg.mode() == Mode.MANUAL_DROPS
                        ? "Armed for your next bronze drops only."
                        : cfg.learnTarget()
                                ? "Place one selected bronze coin. That seed is included in N."
                                : "Converting observed bronze instances.";
        if (persist()) {
            publish();
            pump();
        }
    }

    public void pause() {
        if (active()) {
            state = State.PAUSED;
            tickVersion++;
            message = "Paused. Already-sent operations remain observed.";
            persist();
            publish();
        }
    }

    public void resume() {
        if (state != State.PAUSED || recovery || !connected || room <= 0)
            throw new IllegalStateException("Cannot resume this run");
        runFence = fence.getAsLong();
        state = State.RUNNING;
        message = "Resumed.";
        publish();
        pump();
    }

    public void stop() {
        tickVersion++;
        state = recovery ? State.UNCERTAIN : State.STOPPED;
        message = "Stopped. Sent operations are still observed; placed items may remain.";
        persist();
        publish();
    }

    public void acknowledge() {
        if (active() || state == State.PAUSED)
            throw new IllegalStateException("Stop before acknowledging the journal");
        // Explicitly dismisses automatic reconciliation, never retries or removes hotel objects.
        drops.clear();
        current = null;
        knownRoom.clear();
        orphanHandles.clear();
        recovery = false;
        uncertain = 0;
        tickVersion++;
        state = State.DISARMED;
        message = "Journal acknowledged. Reconcile any recorded IDs manually; nothing was sent.";
        persist();
        publish();
    }

    public void runtimeFailed() {
        disconnected();
        message =
                "Worker capacity or execution failed. Restart the extension; no automatic replay.";
        publish();
    }

    public void malformed() {
        if (active() || state == State.PAUSED || outstanding())
            halt("Relevant packet was malformed or ambiguous.");
        else {
            message = "Relevant packet could not be decoded; automation is off.";
            publish();
        }
    }

    public void on(Event e) {
        if (e == null) return;
        if (e instanceof ContextLost) {
            contextLost();
            message = "Room or permissions changed. Re-enter the room and arm again.";
            publish();
            return;
        }
        if (e instanceof PermissionsChanged) {
            int remembered = room;
            contextLost();
            room = remembered;
            message =
                    "Permissions changed. Automation disarmed; a new inventory run must learn a successful placement.";
            publish();
            return;
        }
        if (e instanceof RoomReady r) {
            contextLost();
            room = r.id();
            message = "Room observed. Choose a finite run and arm explicitly.";
            publish();
            return;
        }
        if (e instanceof Inventory p) {
            inventory.page(p);
            publish();
            return;
        }
        if (e instanceof Balance b) {
            balance = b.absolute();
            publish();
            return;
        }
        if (e instanceof InventoryRequest) {
            return;
        }
        if (e instanceof Conflict) {
            if (current != null) current.acknowledgementAmbiguous = true;
            if (active() || state == State.PAUSED || outstanding())
                halt(
                        "Competing furniture or purchase action observed; reconcile before restarting.");
            return;
        }
        if (e instanceof ExternalRedemption r) {
            if (current != null) current.acknowledgementAmbiguous = true;
            if (active() || state == State.PAUSED || outstanding())
                halt("Manual redemption overlaps this run.");
            return;
        }
        if (e instanceof Placement p) {
            placement(p);
        } else if (e instanceof StripRemoval r) {
            inventory.removed(r.handle());
            Drop d = drops.get(r.handle());
            if (d != null) {
                d.removed = true;
                match(d);
            }
        } else if (e instanceof Added a) {
            if (!seenAdds.add(a.id().value())) return;
            if (seenAdds.size() > 100_000) {
                halt("Room observation limit reached.");
                return;
            }
            Drop d = drops.get(new Handle(-a.id().value()));
            if (d != null) {
                knownRoom.add(a.id().value());
                if (!a.target().equals(d.target) || !a.type().equals(BRONZE)) {
                    halt("Placement identity/type/target did not match.");
                    return;
                }
                d.added = true;
                d.type = a.type();
                match(d);
            }
        } else if (e instanceof Removed r) {
            Drop d = drops.get(new Handle(-r.id().value()));
            if (d != null && !d.completed) {
                knownRoom.remove(r.id().value());
                if (d == current && d.redeemSent && !d.acknowledgementAmbiguous) {
                    d.completed = true;
                    redeemed++;
                    current = null;
                    message =
                            "Matching object removal observed. Credited proceeds remain unverified.";
                } else {
                    halt("Recorded object disappeared without this worker's pending redemption.");
                    return;
                }
            }
        }
        if (persist()) {
            publish();
            pump();
        }
    }

    private void placement(Placement p) {
        Drop previous = drops.get(p.handle());
        if (previous != null) {
            // Expected injection reflection or duplicate observation. Identity is never queued
            // twice.
            if (!previous.target.equals(p.target()))
                halt("Repeated placement changed destination.");
            return;
        }
        if (!active() || !validFence() || observed >= config.quantity()) return;
        if (seenAdds.contains(p.handle().expectedObject().value())
                || used.contains(p.handle().value())) {
            halt("Object was already present or used before placement.");
            return;
        }
        if (config.mode() == Mode.INVENTORY
                && (!config.learnTarget()
                        || observed > 0
                        || !inventory.contains(p.handle())
                        || !selected.contains(p.handle()))) {
            halt("Unexpected manual placement during inventory conversion.");
            return;
        }
        if (drops.values().stream().filter(d -> !d.completed).count() >= 128) {
            halt("Pending placement queue limit reached.");
            return;
        }
        Drop d = new Drop(p.handle(), p.target(), false);
        drops.put(p.handle(), d);
        used.add(p.handle().value());
        observed++;
        deadline(d);
        message = "Observed your placement; awaiting matching removal and room addition.";
    }

    private void match(Drop d) {
        if (!d.added || !d.removed || d.completed || d.placementConfirmed) return;
        d.placementConfirmed = true;
        d.deadline = -1;
        placed++;
        if (config != null
                && config.mode() == Mode.INVENTORY
                && config.learnTarget()
                && target == null) target = d.target;
    }

    private boolean active() {
        return state == State.ARMED || state == State.RUNNING;
    }

    private boolean validFence() {
        return runFence == fence.getAsLong();
    }

    private boolean outstanding() {
        return drops.values().stream().anyMatch(d -> !d.completed);
    }

    private void deadline(Drop d) {
        long context = roomGeneration;
        d.deadline = clock.now() + config.timeoutMillis();
        long expectedDeadline = d.deadline;
        clock.later(
                config.timeoutMillis(),
                () -> {
                    if (context != roomGeneration || d.completed || drops.get(d.handle) != d)
                        return;
                    if (d.deadline == expectedDeadline && clock.now() >= d.deadline)
                        halt("Operation timed out. Outcome unknown; it will not be retried.");
                });
    }

    private void pump() {
        if (!active() || !validFence() || recovery) return;
        if (redeemed == config.quantity()) {
            state = State.COMPLETE;
            message =
                    "Conversion count complete; matching object removals observed. Proceeds unverified.";
            persist();
            publish();
            return;
        }
        if (current != null && current.redeemSent) return;
        if (current == null)
            current =
                    drops.values().stream()
                            .filter(d -> d.added && d.removed && !d.completed)
                            .findFirst()
                            .orElse(null);
        if (current != null) {
            if (!current.added || !current.removed) return;
            scheduleSend(() -> sendRedemption(current));
            return;
        }
        if (config.mode() == Mode.INVENTORY && target != null && observed < config.quantity()) {
            List<InventoryBook.Instance> candidates =
                    inventory.available().stream()
                            .filter(
                                    i ->
                                            selected.contains(i.handle())
                                                    && !used.contains(i.handle().value()))
                            .toList();
            if (candidates.isEmpty()) {
                failed++;
                state = State.STOPPED;
                message = "Observed inventory exhausted; partial completion retained.";
                persist();
                publish();
                return;
            }
            scheduleSend(() -> sendPlacement(candidates.getFirst().handle()));
        }
    }

    private void scheduleSend(Runnable action) {
        long tick = ++tickVersion, context = roomGeneration;
        clock.later(
                Math.max(0, nextSend - clock.now()),
                () -> {
                    if (tick == tickVersion
                            && context == roomGeneration
                            && active()
                            && validFence()
                            && !recovery) action.run();
                });
    }

    private void sendPlacement(Handle handle) {
        if (current != null || !inventory.contains(handle) || used.contains(handle.value())) {
            pump();
            return;
        }
        Drop d = new Drop(handle, target, true);
        current = d;
        drops.put(handle, d);
        used.add(handle.value());
        observed++;
        inventory.removed(handle);
        message = "Placement intent recorded.";
        if (!persist() || !validFence()) return;
        if (transport.place(handle, target, runFence) != Submission.SUBMITTED) {
            halt("Placement submission uncertain; no retry.");
            return;
        }
        nextSend = clock.now() + config.pacingMillis();
        deadline(d);
        publish();
    }

    private void sendRedemption(Drop d) {
        if (d == null || d.redeemSent || d.completed || !d.added || !d.removed) return;
        d.redeemIntent = true;
        message = "Redemption intent recorded; awaiting exact object removal.";
        if (!persist() || !validFence()) return;
        if (transport.redeem(d.handle.expectedObject(), runFence) != Submission.SUBMITTED) {
            halt("Redemption submission uncertain; no retry.");
            return;
        }
        d.redeemSent = true;
        nextSend = clock.now() + config.pacingMillis();
        deadline(d);
        publish();
    }

    private void halt(String reason) {
        state = State.UNCERTAIN;
        recovery = true;
        uncertain++;
        tickVersion++;
        message = reason;
        persist();
        publish();
    }

    private boolean persist() {
        try {
            journal.save(snapshot());
            return true;
        } catch (IOException | RuntimeException e) {
            state = State.UNCERTAIN;
            recovery = true;
            tickVersion++;
            message = "Local journal unavailable. Automation stopped before further sends.";
            publish();
            return false;
        }
    }

    public Snapshot snapshot() {
        Set<Integer> pending = new HashSet<>(orphanHandles);
        Map<Integer, String> operations = new TreeMap<>();
        orphanHandles.forEach(h -> operations.put(h, "UNKNOWN_LOST_CONTEXT"));
        for (Drop d : drops.values())
            operations.put(
                    d.handle.value(),
                    d.completed
                            ? "REMOVAL_CONFIRMED"
                            : d.redeemIntent
                                    ? "REDEMPTION_INTENT"
                                    : d.placementConfirmed
                                            ? "PLACEMENT_CONFIRMED"
                                            : "PLACEMENT_OBSERVED_OR_INTENDED");
        for (Drop d : drops.values()) if (!d.completed) pending.add(d.handle.value());
        return new Snapshot(
                state,
                connected,
                room,
                inventory.available().size(),
                observed,
                (int) drops.values().stream().filter(d -> !d.completed && !d.redeemSent).count(),
                placed,
                redeemed,
                failed,
                uncertain,
                balance,
                target,
                knownRoom,
                pending,
                operations,
                message);
    }

    private void publish() {
        updates.accept(snapshot());
    }
}
